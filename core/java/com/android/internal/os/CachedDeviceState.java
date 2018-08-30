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

package com.android.internal.os;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Stores the device state (e.g. charging/on battery, screen on/off) to be shared with
 * the System Server telemetry services.
 *
 * @hide
 */
public class CachedDeviceState {
    private volatile boolean mScreenInteractive;
    private volatile boolean mCharging;

    public CachedDeviceState() {
        mCharging = true;
        mScreenInteractive = false;
    }

    @VisibleForTesting
    public CachedDeviceState(boolean isCharging, boolean isScreenInteractive) {
        mCharging = isCharging;
        mScreenInteractive = isScreenInteractive;
    }

    public void setScreenInteractive(boolean screenInteractive) {
        mScreenInteractive = screenInteractive;
    }

    public void setCharging(boolean charging) {
        mCharging = charging;
    }

    public Readonly getReadonlyClient() {
        return new CachedDeviceState.Readonly();
    }

    /**
     * Allows for only a readonly access to the device state.
     */
    public class Readonly {
        public boolean isCharging() {
            return mCharging;
        }

        public boolean isScreenInteractive() {
            return mScreenInteractive;
        }
    }
}
