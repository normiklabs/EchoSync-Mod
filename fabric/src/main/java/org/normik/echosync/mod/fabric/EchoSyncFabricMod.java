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

package org.normik.echosync.mod.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import org.normik.echosync.mod.common.EchoSyncRuntime;
import org.normik.echosync.mod.common.auth.MojangSessionAuthenticator;

@SuppressWarnings("unused")
public final class EchoSyncFabricMod implements ClientModInitializer, DedicatedServerModInitializer {

    @Override
    public void onInitializeClient() {
        String masterUrl = System.getenv().getOrDefault("ECHOSYNC_MASTER_URL", EchoSyncRuntime.DEFAULT_MASTER_URL);
        String region = System.getenv().getOrDefault("ECHOSYNC_REGION", EchoSyncRuntime.DEFAULT_REGION);
        EchoSyncRuntime.initialize(
                masterUrl,
                region,
                MojangSessionAuthenticator.reflectiveMinecraftClient()
        );
    }

    @Override
    public void onInitializeServer() {
        String masterUrl = System.getenv().getOrDefault("ECHOSYNC_MASTER_URL", EchoSyncRuntime.DEFAULT_MASTER_URL);
        String region = System.getenv().getOrDefault("ECHOSYNC_REGION", EchoSyncRuntime.DEFAULT_REGION);
        EchoSyncRuntime.initialize(masterUrl, region, MojangSessionAuthenticator.dedicatedServer());
    }
}
