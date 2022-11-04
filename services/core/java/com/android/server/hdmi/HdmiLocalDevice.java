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

/**
 * Class that models an HDMI device hosted in this system.
 * Can be used to share methods between CEC and eARC local devices.
 * Currently just a placeholder.
 */
abstract class HdmiLocalDevice {
    private static final String TAG = "HdmiLocalDevice";

    protected final HdmiControlService mService;
    protected final int mDeviceType;

    protected final Object mLock;

    protected HdmiLocalDevice(HdmiControlService service, int deviceType) {
        mService = service;
        mDeviceType = deviceType;
        mLock = service.getServiceLock();
    }
}
