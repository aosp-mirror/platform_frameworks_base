/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.usb.hal.port;

import static com.android.server.usb.UsbPortManager.logAndPrint;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.usb.hal.port.UsbPortHidl;
import com.android.server.usb.hal.port.UsbPortAidl;
import com.android.server.usb.UsbPortManager;

import android.util.Log;
/**
 * Helper class that queries the underlying hal layer to populate UsbPortHal instance.
 */
public final class UsbPortHalInstance {

    public static UsbPortHal getInstance(UsbPortManager portManager, IndentingPrintWriter pw) {

        logAndPrint(Log.DEBUG, null, "Querying USB HAL version");
        if (UsbPortAidl.isServicePresent(null)) {
            logAndPrint(Log.INFO, null, "USB HAL AIDL present");
            return new UsbPortAidl(portManager, pw);
        }
        if (UsbPortHidl.isServicePresent(null)) {
            logAndPrint(Log.INFO, null, "USB HAL HIDL present");
            return new UsbPortHidl(portManager, pw);
        }
        return null;
    }
}
