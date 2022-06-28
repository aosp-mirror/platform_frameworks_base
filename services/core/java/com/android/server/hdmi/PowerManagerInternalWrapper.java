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

package com.android.server.hdmi;

import android.os.PowerManagerInternal;

import com.android.server.LocalServices;

/**
 * Abstraction around {@link PowerManagerInternal} to allow faking it in tests.
 */
public class PowerManagerInternalWrapper {
    private static final String TAG = "PowerManagerInternalWrapper";

    private PowerManagerInternal mPowerManagerInternal;

    public PowerManagerInternalWrapper() {
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
    }

    /**
     * Wraps {@link PowerManagerInternal#wasDeviceIdleFor(long)}
     */
    public boolean wasDeviceIdleFor(long ms) {
        return mPowerManagerInternal.wasDeviceIdleFor(ms);
    }
}
