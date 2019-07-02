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
 * Wrapper around ITestDevice exposing useful device functions.
 */
class Device {

    private ITestDevice mDevice;

    Device(ITestDevice device) {
        mDevice = device;
    }

    /**
     * Execute a shell command and return the output as a string.
     */
    public String executeShellCommand(String command) throws TestException {
        try {
            return mDevice.executeShellCommand(command);
        } catch (DeviceNotAvailableException e) {
            throw new TestException(e);
        }
    }

    /**
     * Enable adb root
     */
    public void enableAdbRoot() throws TestException {
        try {
            mDevice.enableAdbRoot();
        } catch (DeviceNotAvailableException e) {
            throw new TestException(e);
        }
    }

    /**
     * Returns the pid for the process with the given name.
     */
    public int getProcessPid(String name) throws TestException {
        try {
            String pid = mDevice.getProcessPid(name);
            if (pid == null) {
                throw new TestException("failed to get pid for " + name);
            }
            return Integer.parseInt(pid);
        } catch (DeviceNotAvailableException e) {
            throw new TestException(e);
        }
    }
}
