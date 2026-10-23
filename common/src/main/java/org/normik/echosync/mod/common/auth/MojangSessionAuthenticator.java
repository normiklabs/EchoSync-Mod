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

package org.normik.echosync.mod.common.auth;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Executes the client-side Mojang Yggdrasil joinServer challenge without ever exposing
 * the player's Minecraft accessToken to EchoSync relays or Master.
 * Dedicated servers return Optional.empty().
 */
@FunctionalInterface
public interface MojangSessionAuthenticator extends AutoCloseable {

    String DEFAULT_MOJANG_JOIN_URL = "https://sessionserver.mojang.com/session/minecraft/join";

    CompletableFuture<Optional<PlayerIdentity>> authenticate(String nonce, String serverId);

    default CompletableFuture<Optional<PlayerIdentity>> authenticate(String relayHost, String nonce, String serverId) {
        return authenticate(nonce, serverId);
    }

    @Override
    default void close() {}

    static String extractCanonicalHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return "127.0.0.1";
        }
        String trimmed = rawHost.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        int colonIdx = trimmed.lastIndexOf(':');
        if (colonIdx > 0 && trimmed.indexOf(':') == colonIdx) {
            return trimmed.substring(0, colonIdx);
        }
        return trimmed;
    }

    static String deriveExpectedServerId(String relayHost, String nonce) {
        String host = extractCanonicalHost(relayHost);
        String cleanNonce = nonce == null ? "" : nonce.trim().toLowerCase(Locale.ROOT);
        String input = "echosync-yggdrasil-v1:" + host + ":" + cleanNonce;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sum, 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static MojangSessionAuthenticator dedicatedServer() {
        return (_, _) -> CompletableFuture.completedFuture(Optional.empty());
    }

    static MojangSessionAuthenticator reflectiveMinecraftClient() {
        return yggdrasilClient(MojangSessionAuthenticator::resolveLiveMinecraftClientSession);
    }

    static MojangSessionAuthenticator yggdrasilClient(
            Supplier<Optional<MinecraftClientSession>> sessionSupplier
    ) {
        String joinUrl = System.getenv().getOrDefault("ECHOSYNC_MOJANG_JOIN_URL", DEFAULT_MOJANG_JOIN_URL);
        @SuppressWarnings("resource")
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        return new MojangSessionAuthenticator() {
            @Override
            public CompletableFuture<Optional<PlayerIdentity>> authenticate(String nonce, String serverId) {
                return authenticate("127.0.0.1", nonce, serverId);
            }

            @Override
            public CompletableFuture<Optional<PlayerIdentity>> authenticate(
                    String relayHost,
                    String nonce,
                    String serverId
            ) {
                if (nonce == null || nonce.isBlank() || serverId == null || serverId.isBlank()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                String expectedServerId = deriveExpectedServerId(relayHost, nonce);
                if (!expectedServerId.equalsIgnoreCase(serverId.trim())) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Relay challenge serverId mismatch for host " + relayHost)
                    );
                }
                Optional<MinecraftClientSession> sessionOpt = sessionSupplier.get();
                if (sessionOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                MinecraftClientSession session = sessionOpt.get();
                JsonObject body = new JsonObject();
                body.addProperty("accessToken", session.accessToken());
                body.addProperty("selectedProfile", session.uuid().toString().replace("-", ""));
                body.addProperty("serverId", expectedServerId);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(joinUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();

                return httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                        .thenApply(resp -> {
                            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                                throw new IllegalStateException("Mojang session join rejected with HTTP " + resp.statusCode());
                            }
                            return Optional.of(new PlayerIdentity(session.username(), session.uuid()));
                        });
            }

            @Override
            public void close() {
                httpClient.close();
            }
        };
    }

    static Optional<MinecraftClientSession> resolveLiveMinecraftClientSession() {
        String envUser = System.getenv("ECHOSYNC_MC_USERNAME");
        String envUuid = System.getenv("ECHOSYNC_MC_UUID");
        String envToken = System.getenv("ECHOSYNC_MC_ACCESS_TOKEN");
        if (envUser != null && envUuid != null && envToken != null) {
            return Optional.of(new MinecraftClientSession(envUser, UUID.fromString(envUuid), envToken));
        }
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            if (mc == null) {
                return Optional.empty();
            }
            Object user = mcClass.getMethod("getUser").invoke(mc);
            if (user == null) {
                return Optional.empty();
            }
            String name = (String) user.getClass().getMethod("getName").invoke(user);
            UUID uuid = (UUID) user.getClass().getMethod("getProfileId").invoke(user);
            String accessToken = (String) user.getClass().getMethod("getAccessToken").invoke(user);
            if (accessToken != null
                    && !accessToken.isBlank()
                    && !"0".equals(accessToken)
                    && !"FabricMC".equalsIgnoreCase(accessToken)) {
                return Optional.of(new MinecraftClientSession(name, uuid, accessToken));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    record PlayerIdentity(String username, UUID uuid) {}

    record MinecraftClientSession(String username, UUID uuid, String accessToken) {}
}
