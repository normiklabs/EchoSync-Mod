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

package org.normik.echosync.mod.common;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.normik.echosync.api.ConnectionState;
import org.normik.echosync.api.EchoPacket;
import org.normik.echosync.api.EchoSync;
import org.normik.echosync.api.EchoSyncChannel;
import org.normik.echosync.api.EchoSyncProvider;
import org.normik.echosync.api.Subscription;
import org.normik.echosync.mod.common.auth.MojangSessionAuthenticator.PlayerIdentity;
import org.normik.echosync.mod.common.discovery.RelayDiscoveryClient;
import org.normik.echosync.mod.common.net.EchoSyncMultiplexer;
import org.normik.echosync.mod.common.protocol.BinaryFrameCodec;
import org.normik.echosync.mod.common.protocol.BinaryFrameCodec.DecodedRelayFrame;
import org.normik.echosync.mod.common.protocol.ControlFrame;
import org.normik.echosync.mod.common.spi.ModEchoSyncServiceProvider;

import static org.junit.jupiter.api.Assertions.*;

class EchoSyncModTest {

    @Test
    void binaryFrameCodecShouldEncodeAndDecodeBigEndianFrames() {
        assertTrue(BinaryFrameCodec.isValidTopic("pub:chunk_0_0"));
        byte[] payload = new byte[] {10, 20, 30, 40};
        byte[] clientFrame = BinaryFrameCodec.encodeClientFrame(2, "pub:chunk_0_0", payload);
        assertEquals(BinaryFrameCodec.PROTOCOL_VERSION, clientFrame[0]);
        assertEquals(2, Byte.toUnsignedInt(clientFrame[1]));

        UUID sender = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        long ts = 1792324800000L;
        byte[] topicBytes = "pub:chunk_0_0".getBytes(StandardCharsets.UTF_8);

        ByteBuffer relayBuf = ByteBuffer.allocate(27 + topicBytes.length + payload.length);
        relayBuf.put(BinaryFrameCodec.PROTOCOL_VERSION);
        relayBuf.put((byte) 2);
        relayBuf.putLong(sender.getMostSignificantBits());
        relayBuf.putLong(sender.getLeastSignificantBits());
        relayBuf.putLong(ts);
        relayBuf.put((byte) topicBytes.length);
        relayBuf.put(topicBytes);
        relayBuf.put(payload);

        DecodedRelayFrame decoded = BinaryFrameCodec.decodeRelayFrame(relayBuf.array());
        assertEquals(2, decoded.appSlot());
        assertEquals("pub:chunk_0_0", decoded.packet().topic());
        assertEquals(sender, decoded.packet().senderUuid());
        assertEquals(ts, decoded.packet().timestampMs());
        assertArrayEquals(payload, decoded.packet().payload());

        // Reject > 64KB frame
        assertThrows(
                IllegalArgumentException.class,
                () -> BinaryFrameCodec.encodeClientFrame(1, "pub:oversized", new byte[65536])
        );
    }

    @Test
    void serviceLoaderShouldDiscoverModEchoSyncServiceProvider() {
        assertInstanceOf(ModEchoSyncServiceProvider.class, EchoSyncProvider.load());
    }

    @Test
    void mojangAuthenticatorShouldBindServerIdToRelayHostAndNonce() {
        String nonce = "0123456789abcdef0123456789abcdef";
        String expectedEu = org.normik.echosync.mod.common.auth.MojangSessionAuthenticator.deriveExpectedServerId(
                "d9sp09-eu-relay-echosync.normik.org:443",
                nonce
        );
        String expectedSameHostNoPort = org.normik.echosync.mod.common.auth.MojangSessionAuthenticator.deriveExpectedServerId(
                "d9sp09-eu-relay-echosync.normik.org",
                nonce
        );
        String expectedOtherHost = org.normik.echosync.mod.common.auth.MojangSessionAuthenticator.deriveExpectedServerId(
                "untrusted-relay.example.org",
                nonce
        );
        assertEquals(40, expectedEu.length());
        assertEquals(expectedEu, expectedSameHostNoPort);

        var yggAuth = org.normik.echosync.mod.common.auth.MojangSessionAuthenticator.yggdrasilClient(
                () -> Optional.of(new org.normik.echosync.mod.common.auth.MojangSessionAuthenticator.MinecraftClientSession(
                        "Player1",
                        UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                        "valid_access_token"
                ))
        );
        // Forwarding expectedEu serverId over untrusted-relay.example.org must fail before touching Mojang
        var mitmFuture = yggAuth.authenticate("untrusted-relay.example.org", nonce, expectedEu);
        assertTrue(mitmFuture.isCompletedExceptionally());
        assertNotEquals(expectedEu, expectedOtherHost);
        yggAuth.close();
    }

    @Test
    void multiplexerShouldShareSingleSocketAndFailoverToBackupRelay() throws Exception {
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        CountDownLatch primaryBothBound = new CountDownLatch(2);
        CountDownLatch fallbackPacketReceived = new CountDownLatch(1);
        AtomicReference<EchoPacket> receivedOnFallback = new AtomicReference<>();

        MockRelayServer primaryRelay = new MockRelayServer(playerUuid, primaryBothBound, false);
        primaryRelay.startServer();

        MockRelayServer fallbackRelay = new MockRelayServer(playerUuid, new CountDownLatch(2), true);
        fallbackRelay.startServer();

        HttpServer masterHttp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String primaryWsUrl = "ws://127.0.0.1:" + primaryRelay.getPort() + "/v1/ws";
        String fallbackWsUrl = "ws://127.0.0.1:" + fallbackRelay.getPort() + "/v1/ws";

        masterHttp.createContext("/api/public/relays/discover", exchange -> {
            String json = """
                    {
                      "data": {
                        "recommended": {
                          "id": "11111111-1111-1111-1111-111111111111",
                          "nodeCode": "eu-1",
                          "region": "eu-central",
                          "wsUrl": "%s",
                          "status": "ONLINE"
                        },
                        "fallbacks": [
                          {
                            "id": "22222222-2222-2222-2222-222222222222",
                            "nodeCode": "eu-2",
                            "region": "eu-central",
                            "wsUrl": "%s",
                            "status": "ONLINE"
                          }
                        ]
                      },
                      "error": null,
                      "timestamp": "2026-10-18T12:00:00Z"
                    }
                    """.formatted(primaryWsUrl, fallbackWsUrl);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        masterHttp.start();

        String masterUrl = "http://127.0.0.1:" + masterHttp.getAddress().getPort();
        RelayDiscoveryClient discoveryClient = new RelayDiscoveryClient(masterUrl, "eu-central");

        try (EchoSyncMultiplexer mux = new EchoSyncMultiplexer(
                discoveryClient,
                (_, _) -> CompletableFuture.completedFuture(Optional.of(new PlayerIdentity("Player1", playerUuid))),
                30L,
                150L
        )) {
            EchoSyncRuntime.setMultiplexer(mux);

            EchoSyncChannel voiceChannel = EchoSync.channel("proximity_voice", "epk_live_00000000000000000000000000000001");
            EchoSyncChannel minimapChannel = EchoSync.channel("team_minimap", "epk_live_00000000000000000000000000000002");

            try (Subscription ignored = voiceChannel.subscribe("pub:chunk_0_0", pkt -> {
                receivedOnFallback.set(pkt);
                fallbackPacketReceived.countDown();
            })) {
                assertTrue(primaryBothBound.await(5, TimeUnit.SECONDS), "Both mods should bind on primary relay");
                long stateDeadline = System.currentTimeMillis() + 2000L;
                while (System.currentTimeMillis() < stateDeadline
                        && (voiceChannel.state() != ConnectionState.CONNECTED
                                || minimapChannel.state() != ConnectionState.CONNECTED)) {
                    Thread.onSpinWait();
                }
                // Only 1 physical WebSocket connection opened to primary relay for both mods
                assertEquals(1, primaryRelay.totalConnections.get());
                assertEquals(ConnectionState.CONNECTED, voiceChannel.state());
                assertEquals(ConnectionState.CONNECTED, minimapChannel.state());

                // Simulate primary relay dropping connection -> triggers automatic failover to fallbackRelay
                primaryRelay.stop(100);

                assertTrue(fallbackPacketReceived.await(5, TimeUnit.SECONDS), "Should fail over to backup relay and replay SUB");
                assertEquals(1, fallbackRelay.totalConnections.get());
                assertArrayEquals(new byte[] {0x42, 0x43}, receivedOnFallback.get().payload());
            }
        } finally {
            masterHttp.stop(0);
            primaryRelay.stop(100);
            fallbackRelay.stop(100);
        }
    }

    @Test
    void multiplexerShouldFallbackToValidEpkKeyWhenFirstModKeyRejectedDuringAuthPlayer() throws Exception {
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        CountDownLatch goodModBound = new CountDownLatch(1);
        MockRelayServer relay = new MockRelayServer(playerUuid, goodModBound, false);
        relay.startServer();

        HttpServer masterHttp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String wsUrl = "ws://127.0.0.1:" + relay.getPort() + "/v1/ws";
        masterHttp.createContext("/api/public/relays/discover", exchange -> {
            String json = """
                    {
                      "data": {
                        "recommended": {
                          "id": "11111111-1111-1111-1111-111111111111",
                          "nodeCode": "eu-1",
                          "region": "EU",
                          "wsUrl": "%s",
                          "status": "ONLINE"
                        },
                        "fallbacks": []
                      },
                      "error": null,
                      "timestamp": "2026-10-21T12:00:00Z"
                    }
                    """.formatted(wsUrl);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        masterHttp.start();

        String masterUrl = "http://127.0.0.1:" + masterHttp.getAddress().getPort();
        try (EchoSyncMultiplexer mux = new EchoSyncMultiplexer(
                new RelayDiscoveryClient(masterUrl, "EU"),
                (_, _) -> CompletableFuture.completedFuture(Optional.of(new PlayerIdentity("Player1", playerUuid))),
                30L,
                150L
        )) {
            EchoSyncRuntime.setMultiplexer(mux);

            EchoSyncChannel brokenMod = EchoSync.channel("broken_mod", "epk_live_00000000000000000000000000000bad");
            EchoSyncChannel workingMod = EchoSync.channel("working_mod", "epk_live_00000000000000000000000000000002");

            assertTrue(goodModBound.await(5, TimeUnit.SECONDS), "Working mod should still bind after AUTH_ERR fallback");
            long deadline = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < deadline && workingMod.state() != ConnectionState.CONNECTED) {
                Thread.onSpinWait();
            }
            assertEquals(ConnectionState.CONNECTED, workingMod.state());
            assertEquals(ConnectionState.DISCONNECTED, brokenMod.state());
        } finally {
            masterHttp.stop(0);
            relay.stop(100);
        }
    }

    private static final class MockRelayServer extends WebSocketServer {

        private final UUID expectedUuid;
        private final CountDownLatch bindLatch;
        private final boolean pushPacketOnSub;
        private final AtomicInteger slotSeq = new AtomicInteger(1);
        final AtomicInteger totalConnections = new AtomicInteger(0);
        private final CountDownLatch startedLatch = new CountDownLatch(1);

        MockRelayServer(UUID expectedUuid, CountDownLatch bindLatch, boolean pushPacketOnSub) {
            super(new InetSocketAddress("127.0.0.1", 0));
            this.expectedUuid = expectedUuid;
            this.bindLatch = bindLatch;
            this.pushPacketOnSub = pushPacketOnSub;
        }

        void startServer() throws InterruptedException {
            start();
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            totalConnections.incrementAndGet();
            conn.send("""
                    {"v":1,"op":"AUTH_CHALLENGE","nonce":"0123456789abcdef0123456789abcdef","serverId":"srv1"}
                    """);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            ControlFrame frame = ControlFrame.fromJson(message);
            switch (frame.op()) {
                case "AUTH_PLAYER" -> {
                    if (frame.apiKey() != null && frame.apiKey().endsWith("bad")) {
                        conn.send("""
                                {"v":1,"op":"AUTH_ERR","code":"INVALID_KEY","message":"Invalid API key"}
                                """);
                        return;
                    }
                    conn.send("""
                            {"v":1,"op":"AUTH_OK","uuid":"%s"}
                            """.formatted(expectedUuid));
                }
                case "BIND_MOD" -> {
                    int assignedSlot = slotSeq.getAndIncrement();
                    conn.send("""
                            {"v":1,"op":"BIND_MOD_OK","reqId":"%s","appSlot":%d}
                            """.formatted(frame.reqId(), assignedSlot));
                    bindLatch.countDown();
                }
                case "SUB" -> {
                    if (pushPacketOnSub && frame.appSlot() != null) {
                        byte[] topicBytes = frame.topic().getBytes(StandardCharsets.UTF_8);
                        byte[] payload = new byte[] {0x42, 0x43};
                        ByteBuffer buf = ByteBuffer.allocate(27 + topicBytes.length + payload.length);
                        buf.put(BinaryFrameCodec.PROTOCOL_VERSION);
                        buf.put((byte) frame.appSlot().intValue());
                        buf.putLong(expectedUuid.getMostSignificantBits());
                        buf.putLong(expectedUuid.getLeastSignificantBits());
                        buf.putLong(System.currentTimeMillis());
                        buf.put((byte) topicBytes.length);
                        buf.put(topicBytes);
                        buf.put(payload);
                        conn.send(buf.array());
                    }
                }
                default -> {}
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {}

        @Override
        public void onError(WebSocket conn, Exception ex) {}

        @Override
        public void onStart() {
            startedLatch.countDown();
        }
    }
}
