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

package org.normik.echosync.mod.common.spi;

import org.normik.echosync.api.EchoSyncChannel;
import org.normik.echosync.api.EchoSyncServiceProvider;
import org.normik.echosync.mod.common.EchoSyncRuntime;

public final class ModEchoSyncServiceProvider implements EchoSyncServiceProvider {

    @Override
    public EchoSyncChannel openChannel(String modNamespace, String apiKey) {
        return EchoSyncRuntime.openChannel(modNamespace, apiKey);
    }
}
