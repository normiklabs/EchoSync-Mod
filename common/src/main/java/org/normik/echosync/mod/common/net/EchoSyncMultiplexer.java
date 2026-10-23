/*
 * Copyright 2026 The normik Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.normik.echosync.mod.common.net;

import java.io.ByteArrayOutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.normik.echosync.api.ConnectionState;
import org.normik.echosync.api.EchoPacket;
import org.normik.echosync.api.EchoSyncChannel;
import org.normik.echosync.api.Subscription;
import org.normik.echosync.mod.common.auth.MojangSessionAuthenticator;
import org.normik.echosync.mod.common.auth.MojangSessionAuthenticator.PlayerIdentity;
import org.normik.echosync.mod.common.discovery.RelayDiscoveryClient;
import org.normik.echosync.mod.common.discovery.RelayDiscoveryClient.RelayEndpoint;
import org.normik.echosync.mod.common.protocol.BinaryFrameCodec;
import org.normik.echosync.mod.common.protocol.BinaryFrameCodec.DecodedRelayFrame;
import org.normik.echosync.mod.common.protocol.ControlFrame;

/**
 * Maintains a single physical WebSocket connection across all installed mods,
 * multiplexing channels via 1-byte appSlot (1..255), serializing concurrent WebSocket
 * writes to prevent {@link IllegalStateException}, and automatically failing over
 * across backup relays with jittered exponential backoff.
 */
public final class EchoSyncMultiplexer implements AutoCloseable {

    private static final Logger LOGGER = System.getLogger(EchoSyncMultiplexer.class.getName());

    private volatile RelayDiscoveryClient discoveryClient;
    private volatile MojangSessionAuthenticator authenticator;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    private final Map<String, ModChannel> channelsByKey = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, ModChannel> pendingBinds = new ConcurrentHashMap<>();
    private final Map<Integer, ModChannel> boundSlots = new ConcurrentHashMap<>();
    private final Set<String> rejectedAuthKeys = ConcurrentHashMap.newKeySet();
    private final AtomicInteger reqIdCounter = new AtomicInteger(1);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final Object bindLock = new Object();

    private CompletableFuture<WebSocket> writeChain = CompletableFuture.completedFuture(null);
    private volatile WebSocket activeSocket;
    private volatile PlayerIdentity lastAuthedIdentity;
    private volatile String currentAuthCandidateKey;
    private boolean readyToBind = false;
    private volatile List<RelayEndpoint> relayCandidates = List.of();
    private volatile int candidateIndex = 0;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    public EchoSyncMultiplexer(
            RelayDiscoveryClient discoveryClient,
            MojangSessionAuthenticator authenticator
    ) {
        this(discoveryClient, authenticator, 1000L, 30_000L);
    }

    public EchoSyncMultiplexer(
            RelayDiscoveryClient discoveryClient,
            MojangSessionAuthenticator authenticator,
            long baseBackoffMs,
            long maxBackoffMs
    ) {
        this.discoveryClient = Objects.requireNonNull(discoveryClient);
        this.authenticator = Objects.requireNonNull(authenticator);
        this.baseBackoffMs = Math.max(10L, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EchoSync-Reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    public RelayDiscoveryClient discoveryClient() {
        return discoveryClient;
    }

    public MojangSessionAuthenticator authenticator() {
        return authenticator;
    }

    /**
     * Reconfigures the discovery client and Mojang authenticator in-place without
     * orphaning any {@link EchoSyncChannel} instances opened early during mod loading.
     */
    public synchronized void reconfigure(
            RelayDiscoveryClient newDiscoveryClient,
            MojangSessionAuthenticator newAuthenticator
    ) {
        RelayDiscoveryClient oldDiscovery = this.discoveryClient;
        MojangSessionAuthenticator oldAuth = this.authenticator;
        this.discoveryClient = Objects.requireNonNull(newDiscoveryClient);
        this.authenticator = Objects.requireNonNull(newAuthenticator);
        if (oldDiscovery != null && oldDiscovery != newDiscoveryClient) {
            oldDiscovery.close();
        }
        if (oldAuth != null && oldAuth != newAuthenticator) {
            oldAuth.close();
        }
        this.relayCandidates = List.of();
        this.candidateIndex = 0;
        this.reconnectAttempt.set(0);
        this.rejectedAuthKeys.clear();
        this.lastAuthedIdentity = null;
        this.currentAuthCandidateKey = null;

        WebSocket ws;
        synchronized (bindLock) {
            ws = this.activeSocket;
            this.activeSocket = null;
            this.readyToBind = false;
            this.boundSlots.clear();
            this.pendingBinds.clear();
        }
        if (ws != null) {
            ws.abort();
        }
        if (!channelsByKey.isEmpty()) {
            connecting.set(false);
            triggerConnect(false);
        }
    }

    public EchoSyncChannel openChannel(String modNamespace, String apiKey) {
        Objects.requireNonNull(modNamespace, "modNamespace must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        String compositeKey = modNamespace + ":" + apiKey;
        ModChannel channel;
        synchronized (bindLock) {
            channel = channelsByKey.computeIfAbsent(
                    compositeKey,
                    _ -> new ModChannel(modNamespace, apiKey)
            );
            ensureConnectedAndBindLocked(channel);
        }
        return channel;
    }

    private void enqueueTextSend(WebSocket ws, String text) {
        synchronized (writeLock) {
            writeChain = writeChain
                    .handle((_, _) -> ws)
                    .thenCompose(socket -> {
                        if (socket == null || socket.isOutputClosed()) {
                            return CompletableFuture.completedFuture(socket);
                        }
                        return socket.sendText(text, true);
                    });
        }
    }

    private CompletableFuture<Void> enqueueBinarySend(WebSocket ws, byte[] encodedFrame) {
        synchronized (writeLock) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            writeChain = writeChain
                    .handle((_, _) -> ws)
                    .thenCompose(socket -> {
                        if (socket == null || socket.isOutputClosed()) {
                            result.completeExceptionally(new IllegalStateException("WebSocket closed"));
                            return CompletableFuture.completedFuture(socket);
                        }
                        return socket.sendBinary(ByteBuffer.wrap(encodedFrame), true).whenComplete((_, sendErr) -> {
                            if (sendErr != null) {
                                result.completeExceptionally(sendErr);
                            } else {
                                result.complete(null);
                            }
                        });
                    });
            return result;
        }
    }

    private void ensureConnectedAndBindLocked(ModChannel channel) {
        if (closed.get()) {
            return;
        }
        WebSocket ws = activeSocket;
        if (ws != null && !ws.isOutputClosed()) {
            if (readyToBind && channel.appSlot == 0 && !pendingBinds.containsValue(channel)) {
                sendBindMod(ws, channel);
            }
            return;
        }
        triggerConnect(false);
    }

    private void triggerConnect(boolean isReconnect) {
        if (closed.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        ConnectionState targetState = isReconnect ? ConnectionState.RECONNECTING : ConnectionState.DISCOVERING_RELAY;
        broadcastStateToActiveChannels(targetState);

        CompletableFuture<List<RelayEndpoint>> endpointsFuture;
        if (!relayCandidates.isEmpty() && candidateIndex < relayCandidates.size()) {
            endpointsFuture = CompletableFuture.completedFuture(relayCandidates);
        } else {
            endpointsFuture = discoveryClient.discoverRelays().thenApply(list -> {
                relayCandidates = list;
                candidateIndex = 0;
                return list;
            });
        }

        endpointsFuture
                .thenCompose(list -> {
                    RelayEndpoint endpoint = list.get(candidateIndex % list.size());
                    URI wsUri = URI.create(endpoint.wsUrl());
                    if (discoveryClient.masterBaseUrl().startsWith("https://")
                            && !"wss".equalsIgnoreCase(wsUri.getScheme())) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("Refusing plaintext ws:// relay when Master uses https://")
                        );
                    }
                    String relayHost = wsUri.getHost() != null ? wsUri.getHost() : "127.0.0.1";
                    broadcastStateToActiveChannels(ConnectionState.CONNECTING);
                    return httpClient.newWebSocketBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .buildAsync(wsUri, new RelaySocketListener(relayHost));
                })
                .whenComplete((ws, err) -> {
                    connecting.set(false);
                    if (err != null) {
                        scheduleFailoverReconnect();
                    } else {
                        synchronized (writeLock) {
                            writeChain = CompletableFuture.completedFuture(ws);
                        }
                        synchronized (bindLock) {
                            activeSocket = ws;
                        }
                        reconnectAttempt.set(0);
                    }
                });
    }

    private void handleControlMessage(WebSocket ws, RelaySocketListener listener, ControlFrame frame) {
        if (frame == null || frame.op() == null) {
            return;
        }
        switch (frame.op()) {
            case "AUTH_CHALLENGE" -> {
                if (!listener.challengeHandled.compareAndSet(false, true)) {
                    ws.abort();
                    if (ws == activeSocket) {
                        scheduleFailoverReconnect();
                    }
                    return;
                }
                broadcastStateToActiveChannels(ConnectionState.AUTHENTICATING);
                authenticator.authenticate(listener.relayHost, frame.nonce(), frame.serverId())
                        .whenComplete((optIdentity, err) -> {
                            if (err != null) {
                                ws.abort();
                                if (ws == activeSocket) {
                                    scheduleFailoverReconnect();
                                }
                                return;
                            }
                            if (optIdentity.isPresent()) {
                                PlayerIdentity id = optIdentity.get();
                                lastAuthedIdentity = id;
                                sendNextAuthPlayerCandidate(ws, id);
                            } else {
                                synchronized (bindLock) {
                                    if (ws == activeSocket) {
                                        readyToBind = true;
                                        bindAllChannelsLocked(ws);
                                    }
                                }
                            }
                        });
            }
            case "AUTH_OK" -> {
                synchronized (bindLock) {
                    if (ws == activeSocket) {
                        readyToBind = true;
                        bindAllChannelsLocked(ws);
                    }
                }
            }
            case "AUTH_ERR" -> {
                String rejectedKey = currentAuthCandidateKey;
                if (rejectedKey != null) {
                    rejectedAuthKeys.add(rejectedKey);
                    for (ModChannel ch : snapshotChannels()) {
                        if (rejectedKey.equals(ch.apiKey)) {
                            if ("QUOTA_EXCEEDED".equals(frame.code())) {
                                ch.updateState(ConnectionState.QUOTA_EXCEEDED);
                            } else {
                                ch.updateState(ConnectionState.DISCONNECTED);
                            }
                        }
                    }
                }
                PlayerIdentity id = lastAuthedIdentity;
                if (id != null && ws == activeSocket) {
                    sendNextAuthPlayerCandidate(ws, id);
                }
            }
            case "BIND_MOD_OK" -> {
                ModChannel ch = pendingBinds.remove(frame.reqId());
                if (ch != null && frame.appSlot() != null) {
                    int slot = frame.appSlot();
                    ch.appSlot = slot;
                    boundSlots.put(slot, ch);
                    ch.updateState(ConnectionState.CONNECTED);
                    for (String topic : ch.subscribedTopics()) {
                        enqueueTextSend(ws, ControlFrame.sub(slot, topic).toJson());
                    }
                }
            }
            case "BIND_MOD_ERR" -> {
                ModChannel ch = pendingBinds.remove(frame.reqId());
                if (ch != null) {
                    if (ch.apiKey != null && ch.apiKey.startsWith("epk_") && !"AUTH_REQUIRED".equals(frame.code())) {
                        rejectedAuthKeys.add(ch.apiKey);
                    }
                    if ("QUOTA_EXCEEDED".equals(frame.code())) {
                        ch.updateState(ConnectionState.QUOTA_EXCEEDED);
                    } else {
                        ch.updateState(ConnectionState.DISCONNECTED);
                    }
                }
            }
            case "SLOT_REVOKED" -> {
                if (frame.appSlot() != null) {
                    ModChannel ch = boundSlots.remove(frame.appSlot());
                    if (ch != null) {
                        ch.appSlot = 0;
                        if ("QUOTA_EXCEEDED".equals(frame.reason())) {
                            ch.updateState(ConnectionState.QUOTA_EXCEEDED);
                        } else {
                            ch.updateState(ConnectionState.DISCONNECTED);
                        }
                    }
                }
            }
            default -> {}
        }
    }

    private void handleBinaryMessage(byte[] raw) {
        try {
            DecodedRelayFrame decoded = BinaryFrameCodec.decodeRelayFrame(raw);
            ModChannel channel = boundSlots.get(decoded.appSlot());
            if (channel != null) {
                channel.dispatchPacket(decoded.packet());
            }
        } catch (Exception ignored) {
        }
    }

    private List<ModChannel> snapshotChannels() {
        synchronized (channelsByKey) {
            return List.copyOf(channelsByKey.values());
        }
    }

    private void sendNextAuthPlayerCandidate(WebSocket ws, PlayerIdentity id) {
        String authKey = snapshotChannels().stream()
                .map(ch -> ch.apiKey)
                .filter(k -> k != null && k.startsWith("epk_") && !rejectedAuthKeys.contains(k))
                .findFirst()
                .orElse(null);
        if (authKey != null) {
            currentAuthCandidateKey = authKey;
            ControlFrame authFrame = ControlFrame.authPlayer(id.username(), id.uuid().toString(), authKey);
            enqueueTextSend(ws, authFrame.toJson());
        } else {
            // No valid epk_ keys remain (e.g., only esk_ server channels or all epk_ keys were rejected)
            currentAuthCandidateKey = null;
            synchronized (bindLock) {
                if (ws == activeSocket) {
                    readyToBind = true;
                    bindAllChannelsLocked(ws);
                }
            }
        }
    }

    private void bindAllChannelsLocked(WebSocket ws) {
        for (ModChannel ch : snapshotChannels()) {
            if (ch.apiKey != null && rejectedAuthKeys.contains(ch.apiKey)) {
                continue;
            }
            if (ch.state() != ConnectionState.QUOTA_EXCEEDED && ch.appSlot == 0 && !pendingBinds.containsValue(ch)) {
                sendBindMod(ws, ch);
            }
        }
    }

    private void sendBindMod(WebSocket ws, ModChannel channel) {
        String reqId = Integer.toString(reqIdCounter.getAndIncrement());
        pendingBinds.put(reqId, channel);
        ControlFrame bind = ControlFrame.bindMod(reqId, channel.modNamespace, channel.apiKey);
        enqueueTextSend(ws, bind.toJson());
    }

    private void scheduleFailoverReconnect() {
        if (closed.get()) {
            return;
        }
        synchronized (bindLock) {
            activeSocket = null;
            readyToBind = false;
            boundSlots.clear();
            pendingBinds.clear();
        }
        for (ModChannel ch : snapshotChannels()) {
            ch.appSlot = 0;
            if (ch.state() != ConnectionState.QUOTA_EXCEEDED) {
                ch.updateState(ConnectionState.RECONNECTING);
            }
        }

        if (!relayCandidates.isEmpty()) {
            int nextIdx = candidateIndex + 1;
            if (nextIdx >= relayCandidates.size()) {
                relayCandidates = List.of();
                candidateIndex = 0;
            } else {
                candidateIndex = nextIdx;
            }
        }

        int attempt = reconnectAttempt.getAndIncrement();
        long expDelay = Math.min(maxBackoffMs, baseBackoffMs * (1L << Math.min(attempt, 5)));
        long jitter = ThreadLocalRandom.current().nextLong(0L, Math.max(1L, expDelay / 4));
        long delayMs = Math.min(maxBackoffMs, expDelay + jitter);

        scheduler.schedule(() -> triggerConnect(true), delayMs, TimeUnit.MILLISECONDS);
    }

    private void broadcastStateToActiveChannels(ConnectionState newState) {
        for (ModChannel ch : snapshotChannels()) {
            if (ch.state() != ConnectionState.QUOTA_EXCEEDED
                    && (ch.apiKey == null || !rejectedAuthKeys.contains(ch.apiKey))) {
                ch.updateState(newState);
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            WebSocket ws = activeSocket;
            if (ws != null) {
                ws.abort();
            }
            RelayDiscoveryClient disc = discoveryClient;
            if (disc != null) {
                disc.close();
            }
            MojangSessionAuthenticator auth = authenticator;
            if (auth != null) {
                auth.close();
            }
            httpClient.close();
        }
    }

    private final class RelaySocketListener implements WebSocket.Listener {

        private final String relayHost;
        private final AtomicBoolean challengeHandled = new AtomicBoolean(false);
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

        private RelaySocketListener(String relayHost) {
            this.relayHost = relayHost;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (bindLock) {
                activeSocket = webSocket;
                readyToBind = false;
            }
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (textBuffer.length() + data.length() > BinaryFrameCodec.MAX_FRAME_SIZE) {
                textBuffer.setLength(0);
                webSocket.abort();
                if (webSocket == activeSocket) {
                    scheduleFailoverReconnect();
                }
                return null;
            }
            textBuffer.append(data);
            if (last) {
                String json = textBuffer.toString();
                textBuffer.setLength(0);
                handleControlMessage(webSocket, this, ControlFrame.fromJson(json));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            int rem = data.remaining();
            if (binaryBuffer.size() + rem > BinaryFrameCodec.MAX_FRAME_SIZE) {
                binaryBuffer.reset();
                webSocket.abort();
                if (webSocket == activeSocket) {
                    scheduleFailoverReconnect();
                }
                return null;
            }
            byte[] chunk = new byte[rem];
            data.get(chunk);
            binaryBuffer.write(chunk, 0, chunk.length);
            if (last) {
                byte[] full = binaryBuffer.toByteArray();
                binaryBuffer.reset();
                handleBinaryMessage(full);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (webSocket == activeSocket) {
                scheduleFailoverReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (webSocket == activeSocket) {
                scheduleFailoverReconnect();
            }
        }
    }

    private final class ModChannel implements EchoSyncChannel {

        private final String modNamespace;
        private final String apiKey;
        private final Object subLock = new Object();
        private final Map<String, List<Consumer<EchoPacket>>> topicListeners = new ConcurrentHashMap<>();
        private final List<Consumer<ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();

        private volatile int appSlot = 0;
        private volatile ConnectionState currentState = ConnectionState.DISCONNECTED;

        private ModChannel(String modNamespace, String apiKey) {
            this.modNamespace = modNamespace;
            this.apiKey = apiKey;
        }

        @Override
        public CompletableFuture<Void> send(String topic, byte[] payload) {
            if (!BinaryFrameCodec.isValidTopic(topic)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid topic: " + topic));
            }
            int slot = this.appSlot;
            WebSocket ws = activeSocket;
            if (slot == 0 || ws == null || currentState != ConnectionState.CONNECTED) {
                return CompletableFuture.failedFuture(new IllegalStateException("Channel not connected"));
            }
            try {
                byte[] encoded = BinaryFrameCodec.encodeClientFrame(slot, topic, payload);
                return enqueueBinarySend(ws, encoded);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public Subscription subscribe(String topic, Consumer<EchoPacket> listener) {
            if (!BinaryFrameCodec.isValidTopic(topic)) {
                throw new IllegalArgumentException("Invalid topic: " + topic);
            }
            Objects.requireNonNull(listener, "listener must not be null");

            synchronized (subLock) {
                List<Consumer<EchoPacket>> listeners = topicListeners.computeIfAbsent(
                        topic,
                        _ -> new CopyOnWriteArrayList<>()
                );
                boolean firstForTopic = listeners.isEmpty();
                listeners.add(listener);

                int slot = this.appSlot;
                WebSocket ws = activeSocket;
                if (firstForTopic && slot != 0 && ws != null && currentState == ConnectionState.CONNECTED) {
                    enqueueTextSend(ws, ControlFrame.sub(slot, topic).toJson());
                }
            }

            AtomicBoolean unsubscribed = new AtomicBoolean(false);
            return () -> {
                if (!unsubscribed.compareAndSet(false, true)) {
                    return;
                }
                synchronized (subLock) {
                    List<Consumer<EchoPacket>> current = topicListeners.get(topic);
                    if (current != null) {
                        current.remove(listener);
                        if (current.isEmpty()) {
                            topicListeners.remove(topic);
                            int activeSlot = this.appSlot;
                            WebSocket activeWs = activeSocket;
                            if (activeSlot != 0 && activeWs != null && currentState == ConnectionState.CONNECTED) {
                                enqueueTextSend(activeWs, ControlFrame.unsub(activeSlot, topic).toJson());
                            }
                        }
                    }
                }
            };
        }

        @Override
        public ConnectionState state() {
            return currentState;
        }

        @Override
        public Subscription onStateChange(Consumer<ConnectionState> listener) {
            Objects.requireNonNull(listener, "listener must not be null");
            stateListeners.add(listener);
            return () -> stateListeners.remove(listener);
        }

        Set<String> subscribedTopics() {
            synchronized (subLock) {
                return Set.copyOf(topicListeners.keySet());
            }
        }

        void dispatchPacket(EchoPacket packet) {
            List<Consumer<EchoPacket>> listeners = topicListeners.get(packet.topic());
            if (listeners == null) {
                return;
            }
            for (Consumer<EchoPacket> listener : listeners) {
                try {
                    listener.accept(packet);
                } catch (Throwable t) {
                    LOGGER.log(Level.ERROR, "Unhandled exception in EchoSync packet listener for mod " + modNamespace, t);
                }
            }
        }

        void updateState(ConnectionState next) {
            if (this.currentState == next) {
                return;
            }
            this.currentState = next;
            for (Consumer<ConnectionState> listener : new ArrayList<>(stateListeners)) {
                try {
                    listener.accept(next);
                } catch (Throwable t) {
                    LOGGER.log(Level.ERROR, "Unhandled exception in EchoSync state listener for mod " + modNamespace, t);
                }
            }
        }
    }
}
