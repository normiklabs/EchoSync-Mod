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

import org.normik.echosync.api.EchoSyncChannel;
import org.normik.echosync.mod.common.auth.MojangSessionAuthenticator;
import org.normik.echosync.mod.common.discovery.RelayDiscoveryClient;
import org.normik.echosync.mod.common.net.EchoSyncMultiplexer;

public final class EchoSyncRuntime {

    public static final String DEFAULT_MASTER_URL = "https://master-echosync.normik.org";
    public static final String DEFAULT_REGION = "EU";

    private static volatile EchoSyncMultiplexer multiplexer;
    private static volatile String configuredMasterUrl;
    private static volatile String configuredRegion;

    private EchoSyncRuntime() {}

    public static synchronized void initialize(
            String masterUrl,
            String preferredRegion,
            MojangSessionAuthenticator authenticator
    ) {
        String cleanMasterUrl = masterUrl != null && masterUrl.endsWith("/")
                ? masterUrl.substring(0, masterUrl.length() - 1)
                : masterUrl;
        if (multiplexer != null
                && java.util.Objects.equals(configuredMasterUrl, cleanMasterUrl)
                && java.util.Objects.equals(configuredRegion, preferredRegion)
                && multiplexer.authenticator() == authenticator) {
            return;
        }
        configuredMasterUrl = cleanMasterUrl;
        configuredRegion = preferredRegion;
        RelayDiscoveryClient discovery = new RelayDiscoveryClient(masterUrl, preferredRegion);
        if (multiplexer != null) {
            multiplexer.reconfigure(discovery, authenticator);
        } else {
            multiplexer = new EchoSyncMultiplexer(discovery, authenticator);
        }
    }

    public static synchronized void setMultiplexer(EchoSyncMultiplexer customMultiplexer) {
        if (multiplexer != null && multiplexer != customMultiplexer) {
            multiplexer.close();
        }
        multiplexer = customMultiplexer;
        configuredMasterUrl = customMultiplexer != null ? customMultiplexer.discoveryClient().masterBaseUrl() : null;
        configuredRegion = customMultiplexer != null ? customMultiplexer.discoveryClient().region() : null;
    }

    @SuppressWarnings("resource")
    public static EchoSyncChannel openChannel(String modNamespace, String apiKey) {
        return currentMultiplexer().openChannel(modNamespace, apiKey);
    }

    public static EchoSyncMultiplexer currentMultiplexer() {
        EchoSyncMultiplexer current = multiplexer;
        if (current != null) {
            return current;
        }
        synchronized (EchoSyncRuntime.class) {
            if (multiplexer == null) {
                String masterUrl = System.getenv().getOrDefault("ECHOSYNC_MASTER_URL", DEFAULT_MASTER_URL);
                String region = System.getenv().getOrDefault("ECHOSYNC_REGION", DEFAULT_REGION);
                initialize(masterUrl, region, MojangSessionAuthenticator.reflectiveMinecraftClient());
            }
            return multiplexer;
        }
    }
}
