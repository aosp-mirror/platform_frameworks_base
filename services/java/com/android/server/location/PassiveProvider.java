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

import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import android.location.Criteria;
import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
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

    private static final ProviderProperties PROPERTIES = new ProviderProperties(
            false, false, false, false, false, false, false,
            Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);

    private final ILocationManager mLocationManager;
    private boolean mReportLocation;

    public PassiveProvider(ILocationManager locationManager) {
        mLocationManager = locationManager;
    }

    @Override
    public String getName() {
        return LocationManager.PASSIVE_PROVIDER;
    }

    @Override
    public ProviderProperties getProperties() {
        return PROPERTIES;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void enable() {
    }

    @Override
    public void disable() {
    }

    @Override
    public int getStatus(Bundle extras) {
        if (mReportLocation) {
            return LocationProvider.AVAILABLE;
        } else {
            return LocationProvider.TEMPORARILY_UNAVAILABLE;
        }
    }

    @Override
    public long getStatusUpdateTime() {
        return -1;
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) {
        mReportLocation = request.reportLocation;
    }

    @Override
    public void switchUser(int userId) {
        // nothing to do here
    }

    public void updateLocation(Location location) {
        if (mReportLocation) {
            try {
                // pass the location back to the location manager
                mLocationManager.reportLocation(location, true);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling reportLocation");
            }
        }
    }

    @Override
    public boolean sendExtraCommand(String command, Bundle extras) {
        return false;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mReportLocation=" + mReportLocation);
    }
}
