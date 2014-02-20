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

package com.android.server.dreams;

import android.service.dreams.DozeHardware;

/**
 * Provides access to the low-level microcontroller hardware abstraction layer.
 */
final class McuHal {
    private final long mPtr;

    private static native long nativeOpen();
    private static native byte[] nativeSendMessage(long ptr, String msg, byte[] arg);

    private McuHal(long ptr) {
        mPtr = ptr;
    }

    public static McuHal open() {
        long ptr = nativeOpen();
        return ptr != 0 ? new McuHal(ptr) : null;
    }

    public void reset() {
        sendMessage(DozeHardware.MSG_ENABLE_MCU, DozeHardware.VALUE_OFF);
    }

    public byte[] sendMessage(String msg, byte[] arg) {
        return nativeSendMessage(mPtr, msg, arg);
    }
}
