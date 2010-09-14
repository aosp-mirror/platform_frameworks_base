/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.location;

import android.location.Criteria;
import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;

/**
 * A passive location provider reports locations received from other providers
 * for clients that want to listen passively without actually triggering
 * location updates.
 *
 * {@hide}
 */
public class PassiveProvider implements LocationProviderInterface {

    private static final String TAG = "PassiveProvider";

    private final ILocationManager mLocationManager;
    private boolean mTracking;

    public PassiveProvider(ILocationManager locationManager) {
        mLocationManager = locationManager;
    }

    public String getName() {
        return LocationManager.PASSIVE_PROVIDER;
    }

    public boolean requiresNetwork() {
        return false;
    }

    public boolean requiresSatellite() {
        return false;
    }

    public boolean requiresCell() {
        return false;
    }

    public boolean hasMonetaryCost() {
        return false;
    }

    public boolean supportsAltitude() {
        return false;
    }

    public boolean supportsSpeed() {
        return false;
    }

    public boolean supportsBearing() {
        return false;
    }

    public int getPowerRequirement() {
        return -1;
    }

    public boolean meetsCriteria(Criteria criteria) {
        // We do not want to match the special passive provider based on criteria.
        return false;
    }

    public int getAccuracy() {
        return -1;
    }

    public boolean isEnabled() {
        return true;
    }

    public void enable() {
    }

    public void disable() {
    }

    public int getStatus(Bundle extras) {
        if (mTracking) {
            return LocationProvider.AVAILABLE;
        } else {
            return LocationProvider.TEMPORARILY_UNAVAILABLE;
        }
    }

    public long getStatusUpdateTime() {
        return -1;
    }

    public String getInternalState() {
        return null;
    }

    public void enableLocationTracking(boolean enable) {
        mTracking = enable;
    }

    public boolean requestSingleShotFix() {
        return false;
    }

    public void setMinTime(long minTime, WorkSource ws) {
    }

    public void updateNetworkState(int state, NetworkInfo info) {
    }

    public void updateLocation(Location location) {
        if (mTracking) {
            try {
                // pass the location back to the location manager
                mLocationManager.reportLocation(location, true);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling reportLocation");
            }
        }
    }

    public boolean sendExtraCommand(String command, Bundle extras) {
        return false;
    }

    public void addListener(int uid) {
    }

    public void removeListener(int uid) {
    }
}
