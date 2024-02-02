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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.hardware.soundtrigger.V2_0.ISoundTriggerHw;
import android.os.HwBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * This is the basic implementation of HalFactory, which uses either the default STHAL or a mock.
 *
 * The choice of which HAL to use is as follows:
 * - Get the (int) value of "debug.soundtrigger_middleware.use_mock_hal" sysprop, if it doesn't
 *   exist, assume 0.
 * - If the value is 0, use the default HAL on the device. Connect to the latest-version "default"
 *   instance declared in the device manifest (either AIDL or HIDL).
 * - If the value is 2, connect to a "mock" instance of the latest v2.x (HIDL).
 * - If the value is 3, connect to a "mock" instance of soundtrigger3 (AIDL).
 * - Otherwise, throw.
 */
class DefaultHalFactory implements HalFactory {
    private static final String TAG = "SoundTriggerMiddlewareDefaultHalFactory";

    private static final @NonNull ICaptureStateNotifier mCaptureStateNotifier =
            new ExternalCaptureStateTracker();

    private static final int USE_DEFAULT_HAL = 0;
    private static final int USE_MOCK_HAL_V2 = 2;
    private static final int USE_MOCK_HAL_V3 = 3;

    @Override
    public ISoundTriggerHal create() {
        try {
            int mockHal = SystemProperties.getInt("debug.soundtrigger_middleware.use_mock_hal",
                    USE_DEFAULT_HAL);
            if (mockHal == USE_DEFAULT_HAL) {
                // Use production HAL.

                // Try soundtrigger3 (AIDL) first.
                final String aidlServiceName =
                        android.hardware.soundtrigger3.ISoundTriggerHw.class.getCanonicalName()
                                + "/default";
                if (ServiceManager.isDeclared(aidlServiceName)) {
                    Slog.i(TAG, "Connecting to default soundtrigger3.ISoundTriggerHw");
                    return new SoundTriggerHw3Compat(ServiceManager.waitForService(aidlServiceName),
                            () -> {
                                // This property needs to be defined in an init.rc script and
                                // trigger a HAL reboot.
                                SystemProperties.set("sys.audio.restart.hal", "1");
                            });
                }

                // Fallback to soundtrigger-V2.x (HIDL).
                Slog.i(TAG, "Connecting to default soundtrigger-V2.x.ISoundTriggerHw");
                ISoundTriggerHw driver = ISoundTriggerHw.getService(true);
                return SoundTriggerHw2Compat.create(driver, () -> {
                    // This property needs to be defined in an init.rc script and
                    // trigger a HAL reboot.
                    SystemProperties.set("sys.audio.restart.hal", "1");
                }, mCaptureStateNotifier);
            } else if (mockHal == USE_MOCK_HAL_V2) {
                // Use V2 mock.
                Slog.i(TAG, "Connecting to mock soundtrigger-V2.x.ISoundTriggerHw");
                HwBinder.setTrebleTestingOverride(true);
                try {
                    ISoundTriggerHw driver = ISoundTriggerHw.getService("mock", true);
                    return SoundTriggerHw2Compat.create(driver, () -> {
                        try {
                            driver.debug(null, new ArrayList<>(Arrays.asList("reboot")));
                        } catch (Exception e) {
                            Slog.e(TAG, "Failed to reboot mock HAL", e);
                        }
                    }, mCaptureStateNotifier);
                } finally {
                    HwBinder.setTrebleTestingOverride(false);
                }
            } else if (mockHal == USE_MOCK_HAL_V3) {
                // Use V3 mock.
                final String aidlServiceName =
                        android.hardware.soundtrigger3.ISoundTriggerHw.class.getCanonicalName()
                                + "/mock";
                Slog.i(TAG, "Connecting to mock soundtrigger3.ISoundTriggerHw");
                return new SoundTriggerHw3Compat(ServiceManager.waitForService(aidlServiceName),
                        () -> {
                            try {
                                ServiceManager.waitForService(aidlServiceName).shellCommand(null,
                                        null, null, new String[]{"reboot"}, null, null);
                            } catch (Exception e) {
                                Slog.e(TAG, "Failed to reboot mock HAL", e);
                            }
                        });
            } else {
                throw new RuntimeException("Unknown HAL mock version: " + mockHal);
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }
}
