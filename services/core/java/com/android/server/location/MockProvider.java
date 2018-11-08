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

package com.android.server.location;

import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;
import android.util.PrintWriterPrinter;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A mock location provider used by LocationManagerService to implement test providers.
 *
 * {@hide}
 */
public class MockProvider extends LocationProviderInterface {
    private final String mName;
    private final ProviderProperties mProperties;
    private final ILocationManager mLocationManager;

    private final Location mLocation;

    private boolean mHasLocation;
    private boolean mEnabled;


    private int mStatus;
    private long mStatusUpdateTime;
    private Bundle mExtras;

    private static final String TAG = "MockProvider";

    public MockProvider(String name, ILocationManager locationManager,
            ProviderProperties properties) {
        if (properties == null) throw new NullPointerException("properties is null");

        mName = name;
        mLocationManager = locationManager;
        mProperties = properties;
        mLocation = new Location(name);

        mStatus = LocationProvider.AVAILABLE;
        mStatusUpdateTime = 0L;
        mExtras = null;
    }

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public ProviderProperties getProperties() {
        return mProperties;
    }

    @Override
    public void disable() {
        mEnabled = false;
    }

    @Override
    public void enable() {
        mEnabled = true;
    }

    @Override
    public boolean isEnabled() {
        return mEnabled;
    }

    @Override
    public int getStatus(Bundle extras) {
        if (mExtras != null) {
            extras.clear();
            extras.putAll(mExtras);
        }

        return mStatus;
    }

    @Override
    public long getStatusUpdateTime() {
        return mStatusUpdateTime;
    }

    public void setLocation(Location l) {
        mLocation.set(l);
        mHasLocation = true;
        if (mEnabled) {
            try {
                mLocationManager.reportLocation(mLocation, false);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling reportLocation");
            }
        }
    }

    public void clearLocation() {
        mHasLocation = false;
    }

    /**
     * @deprecated Will be removed in a future release.
     */
    @Deprecated
    public void setStatus(int status, Bundle extras, long updateTime) {
        mStatus = status;
        mStatusUpdateTime = updateTime;
        mExtras = extras;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        dump(pw, "");
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + mName);
        pw.println(prefix + "mHasLocation=" + mHasLocation);
        pw.println(prefix + "mLocation:");
        mLocation.dump(new PrintWriterPrinter(pw), prefix + "  ");
        pw.println(prefix + "mExtras=" + mExtras);
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) { }

    @Override
    public boolean sendExtraCommand(String command, Bundle extras) {
        return false;
    }
}
