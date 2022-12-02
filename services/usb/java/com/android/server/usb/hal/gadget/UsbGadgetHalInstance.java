/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.usb.hal.gadget;

import static com.android.server.usb.UsbPortManager.logAndPrint;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.usb.hal.gadget.UsbGadgetHidl;
import com.android.server.usb.hal.gadget.UsbGadgetAidl;
import com.android.server.usb.UsbDeviceManager;

import android.util.Log;
/**
 * Helper class that queries the underlying hal layer to populate UsbPortHal instance.
 */
public final class UsbGadgetHalInstance {

    public static UsbGadgetHal getInstance(UsbDeviceManager deviceManager,
            IndentingPrintWriter pw) {

        logAndPrint(Log.DEBUG, pw, "Querying USB Gadget HAL version");
        if (UsbGadgetAidl.isServicePresent(null)) {
            logAndPrint(Log.INFO, pw, "USB Gadget HAL AIDL present");
            return new UsbGadgetAidl(deviceManager, pw);
        }
        if (UsbGadgetHidl.isServicePresent(null)) {
            logAndPrint(Log.INFO, pw, "USB Gadget HAL HIDL present");
            return new UsbGadgetHidl(deviceManager, pw);
        }

        logAndPrint(Log.ERROR, pw, "USB Gadget HAL AIDL/HIDL not present");
        return null;
    }
}

