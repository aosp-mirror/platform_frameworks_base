/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.annotation.Nullable;
import android.mtp.MtpConstants;

class MtpDeviceRecord {
    public final int deviceId;
    public final String name;
    public final @Nullable String deviceKey;
    public final boolean opened;
    public final MtpRoot[] roots;
    public final @Nullable int[] operationsSupported;
    public final @Nullable int[] eventsSupported;

    MtpDeviceRecord(int deviceId, String name, @Nullable String deviceKey, boolean opened,
                    MtpRoot[] roots, @Nullable int[] operationsSupported,
                    @Nullable int[] eventsSupported) {
        this.deviceId = deviceId;
        this.name = name;
        this.opened = opened;
        this.roots = roots;
        this.deviceKey = deviceKey;
        this.operationsSupported = operationsSupported;
        this.eventsSupported = eventsSupported;
    }

    /**
     * Helper method to check operations/events are supported by the device or not.
     */
    static boolean isSupported(@Nullable int[] supportedList, int code) {
        if (supportedList == null) {
            return false;
        }
        for (int i = 0; i < supportedList.length; i++) {
            if (supportedList[i] == code) {
                return true;
            }
        }
        return false;
    }

    static boolean isPartialReadSupported(@Nullable int[] supportedList, long fileSize) {
        if (isSupported(supportedList, MtpConstants.OPERATION_GET_PARTIAL_OBJECT_64)) {
            return true;
        }
        if (0 <= fileSize &&
                fileSize <= 0xffffffffL &&
                isSupported(supportedList, MtpConstants.OPERATION_GET_PARTIAL_OBJECT)) {
            return true;
        }
        return false;
    }

    static boolean isWritingSupported(@Nullable int[] supportedList) {
        return isSupported(supportedList, MtpConstants.OPERATION_SEND_OBJECT_INFO) &&
                isSupported(supportedList, MtpConstants.OPERATION_SEND_OBJECT);
    }
}
