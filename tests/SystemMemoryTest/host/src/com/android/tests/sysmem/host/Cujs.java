/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tests.sysmem.host;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;

/**
 * Critical user journeys with which to exercise the system, driven from the
 * host.
 */
public class Cujs {
    private ITestDevice mDevice;

    public Cujs(ITestDevice device) {
        this.mDevice = device;
    }

    /**
     * Runs the critical user journeys.
     */
    public void run() throws DeviceNotAvailableException {
        // Invoke the Device Cujs instrumentation to run the cujs.
        // TODO: Consider exercising the system in other interesting ways as
        // well.
        String command = "am instrument -w com.android.tests.sysmem.device/.Cujs";
        mDevice.executeShellCommand(command);
    }
}
