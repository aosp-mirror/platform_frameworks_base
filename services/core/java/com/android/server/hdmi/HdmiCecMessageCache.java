/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.hdmi;

import android.util.FastImmutableArraySet;
import android.util.SparseArray;

/**
 * Cache for incoming message. It caches {@link HdmiCecMessage} with source address and opcode
 * as a key.
 *
 * <p>Note that whenever a device is removed it should call {@link #flushMessagesFrom(int)}
 * to clean up messages come from the device.
 */
final class HdmiCecMessageCache {
    private static final FastImmutableArraySet<Integer> CACHEABLE_OPCODES =
            new FastImmutableArraySet<>(new Integer[] {
                    Constants.MESSAGE_SET_OSD_NAME,
                    Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS,
                    Constants.MESSAGE_DEVICE_VENDOR_ID,
                    Constants.MESSAGE_CEC_VERSION,
            });

    // It's like [Source Logical Address, [Opcode, HdmiCecMessage]].
    private final SparseArray<SparseArray<HdmiCecMessage>> mCache = new SparseArray<>();

    HdmiCecMessageCache() {
    }

    /**
     * Return a {@link HdmiCecMessage} corresponding to the given {@code address} and
     * {@code opcode}.
     *
     * @param address a logical address of source device
     * @param opcode opcode of message
     * @return null if has no {@link HdmiCecMessage} matched to the given {@code address} and {code
     *         opcode}
     */
    public HdmiCecMessage getMessage(int address, int opcode) {
        SparseArray<HdmiCecMessage> messages = mCache.get(address);
        if (messages == null) {
            return null;
        }

        return messages.get(opcode);
    }

    /**
     * Flush all {@link HdmiCecMessage}s sent from the given {@code address}.
     *
     * @param address a logical address of source device
     */
    public void flushMessagesFrom(int address) {
        mCache.remove(address);
    }

    /**
     * Flush all cached {@link HdmiCecMessage}s.
     */
    public void flushAll() {
        mCache.clear();
    }

    /**
     * Cache incoming {@link HdmiCecMessage}. If opcode of message is not listed on
     * cacheable opcodes list, just ignore it.
     *
     * @param message a {@link HdmiCecMessage} to be cached
     */
    public void cacheMessage(HdmiCecMessage message) {
        int opcode = message.getOpcode();
        if (!isCacheable(opcode)) {
            return;
        }

        int source = message.getSource();
        SparseArray<HdmiCecMessage> messages = mCache.get(source);
        if (messages == null) {
            messages = new SparseArray<>();
            mCache.put(source, messages);
        }
        messages.put(opcode, message);
    }

    private boolean isCacheable(int opcode) {
        return CACHEABLE_OPCODES.contains(opcode);
    }
}
