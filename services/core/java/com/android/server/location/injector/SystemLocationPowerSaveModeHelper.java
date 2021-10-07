/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.LocationPowerSaveMode;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;

import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Provides accessors and listeners for location power save mode.
 */
public class SystemLocationPowerSaveModeHelper extends LocationPowerSaveModeHelper implements
        Consumer<PowerSaveState> {

    private final Context mContext;
    private boolean mReady;

    @LocationPowerSaveMode
    private volatile int mLocationPowerSaveMode;

    public SystemLocationPowerSaveModeHelper(Context context) {
        mContext = context;
    }

    /** Called when system is ready. */
    public void onSystemReady() {
        if (mReady) {
            return;
        }

        LocalServices.getService(PowerManagerInternal.class).registerLowPowerModeObserver(
                PowerManager.ServiceType.LOCATION, this);
        mLocationPowerSaveMode = Objects.requireNonNull(
                mContext.getSystemService(PowerManager.class)).getLocationPowerSaveMode();

        mReady = true;
    }

    @Override
    public void accept(PowerSaveState powerSaveState) {
        int locationPowerSaveMode;
        if (!powerSaveState.batterySaverEnabled) {
            locationPowerSaveMode = PowerManager.LOCATION_MODE_NO_CHANGE;
        } else {
            locationPowerSaveMode = powerSaveState.locationMode;
        }

        if (locationPowerSaveMode == mLocationPowerSaveMode) {
            return;
        }

        mLocationPowerSaveMode = locationPowerSaveMode;

        // invoked on ui thread, move to fg thread so we don't block the ui thread
        FgThread.getHandler().post(() -> notifyLocationPowerSaveModeChanged(locationPowerSaveMode));
    }

    @LocationPowerSaveMode
    @Override
    public int getLocationPowerSaveMode() {
        Preconditions.checkState(mReady);
        return mLocationPowerSaveMode;
    }
}
