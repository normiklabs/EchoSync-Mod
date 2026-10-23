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

package org.normik.echosync.mod.common.protocol;

import com.google.gson.Gson;

public record ControlFrame(
        int v,
        String op,
        String nonce,
        String serverId,
        String username,
        String uuid,
        String reqId,
        String modNamespace,
        String apiKey,
        Integer appSlot,
        String topic,
        String code,
        String message,
        String reason
) {
    private static final Gson GSON = new Gson();

    public static ControlFrame authPlayer(String username, String uuid, String apiKey) {
        return new ControlFrame(1, "AUTH_PLAYER", null, null, username, uuid, null, null, apiKey, null, null, null, null, null);
    }

    public static ControlFrame bindMod(String reqId, String modNamespace, String apiKey) {
        return new ControlFrame(1, "BIND_MOD", null, null, null, null, reqId, modNamespace, apiKey, null, null, null, null, null);
    }

    public static ControlFrame sub(int appSlot, String topic) {
        return new ControlFrame(1, "SUB", null, null, null, null, null, null, null, appSlot, topic, null, null, null);
    }

    public static ControlFrame unsub(int appSlot, String topic) {
        return new ControlFrame(1, "UNSUB", null, null, null, null, null, null, null, appSlot, topic, null, null, null);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static ControlFrame fromJson(String json) {
        return GSON.fromJson(json, ControlFrame.class);
    }
}
