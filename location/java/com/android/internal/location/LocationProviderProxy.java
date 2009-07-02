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
import android.location.ILocationProvider;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * A class for proxying remote ILocationProvider implementations.
 *
 * {@hide}
 */
public class LocationProviderProxy implements IBinder.DeathRecipient {

    private static final String TAG = "LocationProviderProxy";

    private final String mName;
    private final ILocationProvider mProvider;
    private boolean mLocationTracking = false;
    private long mMinTime = 0;
    private boolean mDead;

    public LocationProviderProxy(String name, ILocationProvider provider) {
        mName = name;
        mProvider = provider;
        try {
            provider.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "linkToDeath failed", e);
            mDead = true;
        }
    }

    public void unlinkProvider() {
        if (mProvider != null) {
            mProvider.asBinder().unlinkToDeath(this, 0);
        }
    }

    public String getName() {
        return mName;
    }

    public boolean isDead() {
        return mDead;
    }

    public boolean requiresNetwork() {
        try {
            return mProvider.requiresNetwork();
        } catch (RemoteException e) {
            Log.e(TAG, "requiresNetwork failed", e);
            return false;
        }
    }

    public boolean requiresSatellite() {
        try {
            return mProvider.requiresSatellite();
        } catch (RemoteException e) {
            Log.e(TAG, "requiresSatellite failed", e);
            return false;
        }
    }

    public boolean requiresCell() {
        try {
            return mProvider.requiresCell();
        } catch (RemoteException e) {
            Log.e(TAG, "requiresCell failed", e);
            return false;
        }
    }

    public boolean hasMonetaryCost() {
        try {
            return mProvider.hasMonetaryCost();
        } catch (RemoteException e) {
            Log.e(TAG, "hasMonetaryCost failed", e);
            return false;
        }
    }

    public boolean supportsAltitude() {
        try {
            return mProvider.supportsAltitude();
        } catch (RemoteException e) {
            Log.e(TAG, "supportsAltitude failed", e);
            return false;
        }
    }

    public boolean supportsSpeed() {
        try {
            return mProvider.supportsSpeed();
        } catch (RemoteException e) {
            Log.e(TAG, "supportsSpeed failed", e);
            return false;
        }
    }

     public boolean supportsBearing() {
        try {
            return mProvider.supportsBearing();
        } catch (RemoteException e) {
            Log.e(TAG, "supportsBearing failed", e);
            return false;
        }
    }

    public int getPowerRequirement() {
        try {
            return mProvider.getPowerRequirement();
        } catch (RemoteException e) {
            Log.e(TAG, "getPowerRequirement failed", e);
            return 0;
        }
    }

    public int getAccuracy() {
        try {
            return mProvider.getAccuracy();
        } catch (RemoteException e) {
            Log.e(TAG, "getAccuracy failed", e);
            return 0;
        }
    }

    public void enable() {
        try {
            mProvider.enable();
        } catch (RemoteException e) {
            Log.e(TAG, "enable failed", e);
        }
    }

    public void disable() {
        try {
            mProvider.disable();
        } catch (RemoteException e) {
            Log.e(TAG, "disable failed", e);
        }
    }

    public boolean isEnabled() {
        try {
            return mProvider.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "isEnabled failed", e);
            return false;
        }
    }

    public int getStatus(Bundle extras) {
        try {
            return mProvider.getStatus(extras);
        } catch (RemoteException e) {
            Log.e(TAG, "getStatus failed", e);
            return 0;
        }
    }

    public long getStatusUpdateTime() {
        try {
            return mProvider.getStatusUpdateTime();
        } catch (RemoteException e) {
            Log.e(TAG, "getStatusUpdateTime failed", e);
            return 0;
        }
    }

    public boolean isLocationTracking() {
        return mLocationTracking;
    }

    public void enableLocationTracking(boolean enable) {
        mLocationTracking = enable;
        try {
            mProvider.enableLocationTracking(enable);
        } catch (RemoteException e) {
            Log.e(TAG, "enableLocationTracking failed", e);
        }
    }

    public long getMinTime() {
        return mMinTime;
    }

    public void setMinTime(long minTime) {
        mMinTime = minTime;
        try {
            mProvider.setMinTime(minTime);
        } catch (RemoteException e) {
            Log.e(TAG, "setMinTime failed", e);
        }
    }

    public void updateNetworkState(int state) {
        try {
            mProvider.updateNetworkState(state);
        } catch (RemoteException e) {
            Log.e(TAG, "updateNetworkState failed", e);
        }
    }

    public void updateLocation(Location location) {
        try {
            mProvider.updateLocation(location);
        } catch (RemoteException e) {
            Log.e(TAG, "updateLocation failed", e);
        }
    }

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

    public void binderDied() {
        Log.w(TAG, "Location Provider " + mName + " died");
        mDead = true;
        mProvider.asBinder().unlinkToDeath(this, 0);
    }
}
