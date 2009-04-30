/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.location;

import android.location.Address;
import android.location.ILocationManager;
import android.location.ILocationProvider;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * A class for proxying remote ILocationProvider implementations.
 *
 * {@hide}
 */
public class LocationProviderProxy extends LocationProviderImpl {

    private static final String TAG = "LocationProviderProxy";

    private final ILocationProvider mProvider;

    public LocationProviderProxy(String name, ILocationManager locationManager,
            ILocationProvider provider) {
        super(name, locationManager);
        mProvider = provider;
    }

    @Override
    public boolean requiresNetwork() {
        try {
            return mProvider.requiresNetwork();
        } catch (RemoteException e) {
            Log.e(TAG, "requiresNetwork failed", e);
            return false;
        }
    }

    @Override
    public boolean requiresSatellite() {
        try {
            return mProvider.requiresSatellite();
        } catch (RemoteException e) {
            Log.e(TAG, "requiresSatellite failed", e);
            return false;
        }
    }

    @Override
    public boolean requiresCell() {
        try {
            return mProvider.requiresCell();
        } catch (RemoteException e) {
            Log.e(TAG, "requiresCell failed", e);
            return false;
        }
    }

    @Override
    public boolean hasMonetaryCost() {
        try {
            return mProvider.hasMonetaryCost();
        } catch (RemoteException e) {
            Log.e(TAG, "hasMonetaryCost failed", e);
            return false;
        }
    }

    @Override
    public boolean supportsAltitude() {
        try {
            return mProvider.supportsAltitude();
        } catch (RemoteException e) {
            Log.e(TAG, "supportsAltitude failed", e);
            return false;
        }
    }

    @Override
    public boolean supportsSpeed() {
        try {
            return mProvider.supportsSpeed();
        } catch (RemoteException e) {
            Log.e(TAG, "supportsSpeed failed", e);
            return false;
        }
    }

     @Override
    public boolean supportsBearing() {
        try {
            return mProvider.supportsBearing();
        } catch (RemoteException e) {
            Log.e(TAG, "supportsBearing failed", e);
            return false;
        }
    }

    @Override
    public int getPowerRequirement() {
        try {
            return mProvider.getPowerRequirement();
        } catch (RemoteException e) {
            Log.e(TAG, "getPowerRequirement failed", e);
            return 0;
        }
    }

    @Override
    public int getAccuracy() {
        try {
            return mProvider.getAccuracy();
        } catch (RemoteException e) {
            Log.e(TAG, "getAccuracy failed", e);
            return 0;
        }
    }

    @Override
    public void enable() {
        try {
            mProvider.enable();
        } catch (RemoteException e) {
            Log.e(TAG, "enable failed", e);
        }
    }

    @Override
    public void disable() {
        try {
            mProvider.disable();
        } catch (RemoteException e) {
            Log.e(TAG, "disable failed", e);
        }
    }

    @Override
    public boolean isEnabled() {
        try {
            return mProvider.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "isEnabled failed", e);
            return false;
        }
    }

    @Override
    public int getStatus(Bundle extras) {
        try {
            return mProvider.getStatus(extras);
        } catch (RemoteException e) {
            Log.e(TAG, "getStatus failed", e);
            return 0;
        }
    }

    @Override
    public long getStatusUpdateTime() {
        try {
            return mProvider.getStatusUpdateTime();
        } catch (RemoteException e) {
            Log.e(TAG, "getStatusUpdateTime failed", e);
            return 0;
        }
    }

    @Override
    public void enableLocationTracking(boolean enable) {
        try {
            super.enableLocationTracking(enable);
            mProvider.enableLocationTracking(enable);
        } catch (RemoteException e) {
            Log.e(TAG, "enableLocationTracking failed", e);
        }
    }

    @Override
    public void setMinTime(long minTime) {
        try {
            super.setMinTime(minTime);
            mProvider.setMinTime(minTime);
        } catch (RemoteException e) {
            Log.e(TAG, "setMinTime failed", e);
        }
    }

    @Override
    public void updateNetworkState(int state) {
        try {
            mProvider.updateNetworkState(state);
        } catch (RemoteException e) {
            Log.e(TAG, "updateNetworkState failed", e);
        }
    }

    @Override
    public boolean sendExtraCommand(String command, Bundle extras) {
        try {
            return mProvider.sendExtraCommand(command, extras);
        } catch (RemoteException e) {
            Log.e(TAG, "sendExtraCommand failed", e);
            return false;
        }
    }

    public void addListener(int uid) {
        try {
            mProvider.addListener(uid);
        } catch (RemoteException e) {
            Log.e(TAG, "addListener failed", e);
        }
    }

    public void removeListener(int uid) {
        try {
            mProvider.removeListener(uid);
        } catch (RemoteException e) {
            Log.e(TAG, "removeListener failed", e);
        }
    }

    public void wakeLockAcquired() {
        try {
            mProvider.wakeLockAcquired();
        } catch (RemoteException e) {
            Log.e(TAG, "wakeLockAcquired failed", e);
        }
    }

    public void wakeLockReleased() {
        try {
            mProvider.wakeLockReleased();
        } catch (RemoteException e) {
            Log.e(TAG, "wakeLockReleased failed", e);
        }
    }
}
