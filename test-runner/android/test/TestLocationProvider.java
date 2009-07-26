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
import android.location.ILocationManager;
import android.location.ILocationProvider;
import android.location.Location;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * @hide - This is part of a framework that is under development and should not be used for
 * active development.
 */
public class TestLocationProvider extends ILocationProvider.Stub {

    public static final String PROVIDER_NAME = "test";
    public static final double LAT = 0;
    public static final double LON = 1;
    public static final double ALTITUDE = 10000;
    public static final float SPEED = 10;
    public static final float BEARING = 1;
    public static final int STATUS = LocationProvider.AVAILABLE;
    private static final long LOCATION_INTERVAL = 1000;

    private static final String TAG = "TestLocationProvider";

    private final ILocationManager mLocationManager;
    private Location mLocation;
    private boolean mEnabled;
    private TestLocationProviderThread mThread;

    private class TestLocationProviderThread extends Thread {

        private boolean mDone = false;

        public TestLocationProviderThread() {
            super("TestLocationProviderThread");
        }

        public void run() {            
            // thread exits after disable() is called
            synchronized (this) {
                while (!mDone) {
                    try {
                        wait(LOCATION_INTERVAL);
                    } catch (InterruptedException e) {
                    }
                    
                    if (!mDone) {
                        TestLocationProvider.this.updateLocation();
                    }
                }
            }
        }
        
        synchronized void setDone() {
            mDone = true;
            notify();
        }
    }

    public TestLocationProvider(ILocationManager locationManager) {
        mLocationManager = locationManager;
        mLocation = new Location(PROVIDER_NAME);
    }

    public int getAccuracy() {
        return Criteria.ACCURACY_COARSE;
    }

    public int getPowerRequirement() {
        return Criteria.NO_REQUIREMENT;
    }

    public boolean hasMonetaryCost() {
        return false;
    }

    public boolean requiresCell() {
        return false;
    }

    public boolean requiresNetwork() {
        return false;
    }

    public boolean requiresSatellite() {
        return false;
    }

    public boolean supportsAltitude() {
        return true;
    }

    public boolean supportsBearing() {
        return true;
    }

    public boolean supportsSpeed() {
        return true;
    }

    public synchronized void disable() {
        mEnabled = false;
        if (mThread != null) {
            mThread.setDone();
            try {
                mThread.join();
            } catch (InterruptedException e) {
            }
            mThread = null;
        }
    }

    public synchronized void enable() {
       mEnabled = true;
        mThread = new TestLocationProviderThread();
        mThread.start();
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public int getStatus(Bundle extras) {
        return STATUS;
    }

    public long getStatusUpdateTime() {
        return 0;
    }

    public void enableLocationTracking(boolean enable) {
    }

    public void setMinTime(long minTime) {
    }

    public void updateNetworkState(int state) {
    }

    public void updateLocation(Location location) {
    }

    public boolean sendExtraCommand(String command, Bundle extras) {
        return false;
    }

    public void addListener(int uid) {
    }

    public void removeListener(int uid) {
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
        try {
            mLocationManager.reportLocation(mLocation);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling updateLocation");
        }
    }

}
