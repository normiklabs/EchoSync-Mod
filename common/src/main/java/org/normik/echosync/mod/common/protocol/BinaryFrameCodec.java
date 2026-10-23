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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.normik.echosync.api.EchoPacket;

public final class BinaryFrameCodec {

    public static final byte PROTOCOL_VERSION = 0x01;
    public static final int MAX_FRAME_SIZE = 65536;
    private static final Pattern TOPIC_PATTERN = Pattern.compile("^(pub|srv|priv):[a-z0-9:_-]{1,60}$");

    private BinaryFrameCodec() {}

    public static boolean isValidTopic(String topic) {
        return topic != null && topic.length() <= 64 && TOPIC_PATTERN.matcher(topic).matches();
    }

    /**
     * Encodes Client -> Relay binary frame:
     * [Version: 1B (0x01)] [AppSlot: 1B (uint8)] [TopicLen: 1B (uint8)] [Topic: UTF-8] [Payload: byte[]]
     */
    public static byte[] encodeClientFrame(int appSlot, String topic, byte[] payload) {
        if (appSlot < 1 || appSlot > 255) {
            throw new IllegalArgumentException("appSlot must be in 1..255");
        }
        if (!isValidTopic(topic)) {
            throw new IllegalArgumentException("Invalid topic: " + topic);
        }
        Objects.requireNonNull(payload, "payload must not be null");

        byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
        int totalSize = 3 + topicBytes.length + payload.length;
        if (totalSize > MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("Binary frame exceeds 64 KB limit (" + totalSize + " bytes)");
        }

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.put(PROTOCOL_VERSION);
        buf.put((byte) (appSlot & 0xFF));
        buf.put((byte) (topicBytes.length & 0xFF));
        buf.put(topicBytes);
        buf.put(payload);
        return buf.array();
    }

    /**
     * Decodes Relay -> Client binary frame:
     * [Version: 1B (0x01)] [AppSlot: 1B (uint8)] [SenderUUID: 16B] [TimestampMs: 8B] [TopicLen: 1B (uint8)] [Topic: UTF-8] [Payload: byte[]]
     */
    public static DecodedRelayFrame decodeRelayFrame(byte[] raw) {
        Objects.requireNonNull(raw, "raw frame must not be null");
        if (raw.length < 28 || raw.length > MAX_FRAME_SIZE) {
            throw new IllegalArgumentException("Invalid relay frame length: " + raw.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        byte version = buf.get();
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported frame version: " + version);
        }
        int appSlot = Byte.toUnsignedInt(buf.get());
        if (appSlot == 0) {
            throw new IllegalArgumentException("Invalid appSlot 0");
        }
        long mostSigBits = buf.getLong();
        long leastSigBits = buf.getLong();
        UUID senderUuid = new UUID(mostSigBits, leastSigBits);
        long timestampMs = buf.getLong();
        int topicLen = Byte.toUnsignedInt(buf.get());
        if (topicLen == 0 || buf.remaining() < topicLen) {
            throw new IllegalArgumentException("Invalid topic length in relay frame");
        }

        byte[] topicBytes = new byte[topicLen];
        buf.get(topicBytes);
        String topic = new String(topicBytes, StandardCharsets.UTF_8);

        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);

        return new DecodedRelayFrame(appSlot, new EchoPacket(topic, senderUuid, payload, timestampMs));
    }

    public record DecodedRelayFrame(int appSlot, EchoPacket packet) {}
}
