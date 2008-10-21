/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.test;


import android.location.Criteria;
import android.location.Location;
import android.location.LocationProviderImpl;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * @hide - This is part of a framework that is under development and should not be used for
 * active development.
 */
public class TestLocationProvider extends LocationProviderImpl {

    public static final String PROVIDER_NAME = "test";
    public static final double LAT = 0;
    public static final double LON = 1;
    public static final double ALTITUDE = 10000;
    public static final float SPEED = 10;
    public static final float BEARING = 1;
    public static final int STATUS = AVAILABLE;

    private Location mLocation;
    private boolean mEnabled;

    public TestLocationProvider() {
        super(PROVIDER_NAME);
        mLocation = new Location(PROVIDER_NAME);
        updateLocation();
    }

    //LocationProvider methods

    @Override
    public int getAccuracy() {
        return Criteria.ACCURACY_COARSE;
    }

    @Override
    public int getPowerRequirement() {
        return Criteria.NO_REQUIREMENT;
    }

    @Override
    public boolean hasMonetaryCost() {
        return false;
    }

    @Override
    public boolean requiresCell() {
        return false;
    }

    @Override
    public boolean requiresNetwork() {
        return false;
    }

    @Override
    public boolean requiresSatellite() {
        return false;
    }

    @Override
    public boolean supportsAltitude() {
        return true;
    }

    @Override
    public boolean supportsBearing() {
        return true;
    }

    @Override
    public boolean supportsSpeed() {
        return true;
    }

    //LocationProviderImpl methods
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
    public boolean getLocation(Location l) {
        updateLocation();
        l.set(mLocation);
        return true;
    }

    @Override
    public int getStatus(Bundle extras) {
        return STATUS;
    }

    private void updateLocation() {
        long time = SystemClock.uptimeMillis();
        long multiplier = (time/5000)%500000;
        mLocation.setLatitude(LAT*multiplier);
        mLocation.setLongitude(LON*multiplier);
        mLocation.setAltitude(ALTITUDE);
        mLocation.setSpeed(SPEED);
        mLocation.setBearing(BEARING*multiplier);

        Bundle extras = new Bundle();
        extras.putInt("extraTest", 24);
        mLocation.setExtras(extras);
        mLocation.setTime(time);
    }

}
