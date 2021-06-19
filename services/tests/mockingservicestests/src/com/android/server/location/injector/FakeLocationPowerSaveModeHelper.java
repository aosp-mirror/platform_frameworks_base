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

import android.os.IPowerManager;
import android.os.PowerManager.LocationPowerSaveMode;

/**
 * Version of LocationPowerSaveModeHelper for testing. Power save mode is initialized as "no
 * change".
 */
public class FakeLocationPowerSaveModeHelper extends LocationPowerSaveModeHelper {

    @LocationPowerSaveMode
    private int mLocationPowerSaveMode;

    public FakeLocationPowerSaveModeHelper() {
        mLocationPowerSaveMode = IPowerManager.LOCATION_MODE_NO_CHANGE;
    }

    public void setLocationPowerSaveMode(int locationPowerSaveMode) {
        if (locationPowerSaveMode == mLocationPowerSaveMode) {
            return;
        }

        mLocationPowerSaveMode = locationPowerSaveMode;
        notifyLocationPowerSaveModeChanged(locationPowerSaveMode);
    }

    @LocationPowerSaveMode
    @Override
    public int getLocationPowerSaveMode() {
        return mLocationPowerSaveMode;
    }
}
