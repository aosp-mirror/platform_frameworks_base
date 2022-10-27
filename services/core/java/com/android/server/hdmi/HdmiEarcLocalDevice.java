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

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiDeviceInfo;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;

/**
 * Class that models a local eARC device hosted in this system.
 * The class contains methods that are common between eARC TX and eARC RX devices.
 */
abstract class HdmiEarcLocalDevice extends HdmiLocalDevice {
    private static final String TAG = "HdmiEarcLocalDevice";

    // The current status of the eARC connection, as reported by the HAL
    @GuardedBy("mLock")
    @Constants.EarcStatus
    protected int mEarcStatus;

    protected HdmiEarcLocalDevice(HdmiControlService service, int deviceType) {
        super(service, deviceType);
    }

    // Factory method that returns HdmiCecLocalDevice of corresponding type.
    static HdmiEarcLocalDevice create(HdmiControlService service, int deviceType) {
        switch (deviceType) {
            case HdmiDeviceInfo.DEVICE_TV:
                return new HdmiEarcLocalDeviceTx(service);
            default:
                return null;
        }
    }

    protected abstract void handleEarcStateChange(@Constants.EarcStatus int status);

    protected abstract void handleEarcCapabilitiesReported(byte[] rawCapabilities);

    protected void disableDevice() {
    }

    /** Dump internal status of HdmiEarcLocalDevice object */
    protected void dump(final IndentingPrintWriter pw) {
        // Should be overridden in the more specific classes
    }
}
