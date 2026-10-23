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

package org.normik.echosync.mod.common.discovery;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public final class RelayDiscoveryClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final String masterBaseUrl;
    private final String region;
    private final HttpClient httpClient;
    private final boolean ownsHttpClient;

    public RelayDiscoveryClient(String masterBaseUrl, String region) {
        this(
                masterBaseUrl,
                region,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .build(),
                true
        );
    }

    @SuppressWarnings("unused")
    public RelayDiscoveryClient(String masterBaseUrl, String region, HttpClient httpClient) {
        this(masterBaseUrl, region, httpClient, false);
    }

    private RelayDiscoveryClient(String masterBaseUrl, String region, HttpClient httpClient, boolean ownsHttpClient) {
        this.masterBaseUrl = masterBaseUrl.endsWith("/")
                ? masterBaseUrl.substring(0, masterBaseUrl.length() - 1)
                : masterBaseUrl;
        this.region = region;
        this.httpClient = httpClient;
        this.ownsHttpClient = ownsHttpClient;
    }

    public String masterBaseUrl() {
        return masterBaseUrl;
    }

    public String region() {
        return region;
    }

    @Override
    public void close() {
        if (ownsHttpClient) {
            httpClient.close();
        }
    }

    public CompletableFuture<List<RelayEndpoint>> discoverRelays() {
        String url = masterBaseUrl + "/api/public/relays/discover";
        if (region != null && !region.isBlank()) {
            url += "?region=" + URLEncoder.encode(region, StandardCharsets.UTF_8);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new IllegalStateException("Relay discovery failed with status " + resp.statusCode());
                    }
                    JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                    JsonObject data = root.getAsJsonObject("data");
                    List<RelayEndpoint> endpoints = new ArrayList<>();
                    if (data != null && data.has("recommended") && !data.get("recommended").isJsonNull()) {
                        endpoints.add(GSON.fromJson(data.get("recommended"), RelayEndpoint.class));
                    }
                    if (data != null && data.has("fallbacks") && data.get("fallbacks").isJsonArray()) {
                        for (JsonElement el : data.getAsJsonArray("fallbacks")) {
                            endpoints.add(GSON.fromJson(el, RelayEndpoint.class));
                        }
                    }
                    if (endpoints.isEmpty()) {
                        throw new IllegalStateException("No online relays returned by master");
                    }
                    return List.copyOf(endpoints);
                });
    }

    public record RelayEndpoint(
            String id,
            String nodeCode,
            String region,
            String wsUrl,
            String status
    ) {}
}
