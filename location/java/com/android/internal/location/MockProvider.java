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

import android.location.ILocationManager;
import android.location.Location;
import android.location.LocationProviderImpl;
import android.os.Bundle;
import android.util.PrintWriterPrinter;

import java.io.PrintWriter;

/**
 * A mock location provider used by LocationManagerService to implement test providers.
 *
 * {@hide}
 */
public class MockProvider extends LocationProviderImpl {
    private final boolean mRequiresNetwork;
    private final boolean mRequiresSatellite;
    private final boolean mRequiresCell;
    private final boolean mHasMonetaryCost;
    private final boolean mSupportsAltitude;
    private final boolean mSupportsSpeed;
    private final boolean mSupportsBearing;
    private final int mPowerRequirement;
    private final int mAccuracy;
    private final Location mLocation;
    private int mStatus;
    private long mStatusUpdateTime;
    private final Bundle mExtras = new Bundle();
    private boolean mHasLocation;
    private boolean mHasStatus;
    private boolean mEnabled;

    public MockProvider(String name,  ILocationManager locationManager,
        boolean requiresNetwork, boolean requiresSatellite,
        boolean requiresCell, boolean hasMonetaryCost, boolean supportsAltitude,
        boolean supportsSpeed, boolean supportsBearing, int powerRequirement, int accuracy) {
        super(name, locationManager);

        mRequiresNetwork = requiresNetwork;
        mRequiresSatellite = requiresSatellite;
        mRequiresCell = requiresCell;
        mHasMonetaryCost = hasMonetaryCost;
        mSupportsAltitude = supportsAltitude;
        mSupportsBearing = supportsBearing;
        mSupportsSpeed = supportsSpeed;
        mPowerRequirement = powerRequirement;
        mAccuracy = accuracy;
        mLocation = new Location(name);
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
    public int getStatus(Bundle extras) {
        if (mHasStatus) {
            extras.clear();
            extras.putAll(mExtras);
            return mStatus;
        } else {
            return AVAILABLE;
        }
    }

    @Override
    public long getStatusUpdateTime() {
        return mStatusUpdateTime;
    }

    @Override
    public boolean isEnabled() {
        return mEnabled;
    }

    @Override
    public int getAccuracy() {
        return mAccuracy;
    }

    @Override
    public int getPowerRequirement() {
        return mPowerRequirement;
    }

    @Override
    public boolean hasMonetaryCost() {
        return mHasMonetaryCost;
    }

    @Override
    public boolean requiresCell() {
        return mRequiresCell;
    }

    @Override
    public boolean requiresNetwork() {
        return mRequiresNetwork;
    }

    @Override
    public boolean requiresSatellite() {
        return mRequiresSatellite;
    }

    @Override
    public boolean supportsAltitude() {
        return mSupportsAltitude;
    }

    @Override
    public boolean supportsBearing() {
        return mSupportsBearing;
    }

    @Override
    public boolean supportsSpeed() {
        return mSupportsSpeed;
    }

    public void setLocation(Location l) {
        mLocation.set(l);
        mHasLocation = true;
        reportLocationChanged(mLocation);
    }

    public void clearLocation() {
        mHasLocation = false;
    }

    public void setStatus(int status, Bundle extras, long updateTime) {
        mStatus = status;
        mStatusUpdateTime = updateTime;
        mExtras.clear();
        if (extras != null) {
            mExtras.putAll(extras);
        }
        mHasStatus = true;
    }

    public void clearStatus() {
        mHasStatus = false;
        mStatusUpdateTime = 0;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + getName());
        pw.println(prefix + "mHasLocation=" + mHasLocation);
        pw.println(prefix + "mLocation:");
        mLocation.dump(new PrintWriterPrinter(pw), prefix + "  ");
        pw.println(prefix + "mHasStatus=" + mHasStatus);
        pw.println(prefix + "mStatus=" + mStatus);
        pw.println(prefix + "mStatusUpdateTime=" + mStatusUpdateTime);
        pw.println(prefix + "mExtras=" + mExtras);
    }
}
