/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.wrapper;

import android.location.LocationManager;
import android.os.UserHandle;

/**
 * This class replicates some methods of android.location.LocationManager that are new and not
 * yet available in our current version of Robolectric. It provides a thin wrapper to call the real
 * methods in production and a mock in tests.
 */
public class LocationManagerWrapper {

    private LocationManager mLocationManager;

    public LocationManagerWrapper(LocationManager locationManager) {
        mLocationManager = locationManager;
    }

    /** Returns the real {@code LocationManager} object */
    public LocationManager getLocationManager() {
        return mLocationManager;
    }

    /** Wraps {@code LocationManager.isProviderEnabled} method */
    public boolean isProviderEnabled(String provider) {
        return mLocationManager.isProviderEnabled(provider);
    }

    /** Wraps {@code LocationManager.setProviderEnabledForUser} method */
    public void setProviderEnabledForUser(String provider, boolean enabled, UserHandle userHandle) {
        mLocationManager.setProviderEnabledForUser(provider, enabled, userHandle);
    }

    /** Wraps {@code LocationManager.isLocationEnabled} method */
    public boolean isLocationEnabled() {
        return mLocationManager.isLocationEnabled();
    }

    /** Wraps {@code LocationManager.isLocationEnabledForUser} method */
    public boolean isLocationEnabledForUser(UserHandle userHandle) {
        return mLocationManager.isLocationEnabledForUser(userHandle);
    }

    /** Wraps {@code LocationManager.setLocationEnabledForUser} method */
    public void setLocationEnabledForUser(boolean enabled, UserHandle userHandle) {
        mLocationManager.setLocationEnabledForUser(enabled, userHandle);
    }
}
