/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.location.fused;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;

import com.android.location.provider.LocationProviderBase;
import com.android.location.provider.LocationRequestUnbundled;
import com.android.location.provider.ProviderRequestUnbundled;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;

public class FusionEngine implements LocationListener {
    public interface Callback {
        void reportLocation(Location location);
    }

    private static final String TAG = "FusedLocation";
    private static final String NETWORK = LocationManager.NETWORK_PROVIDER;
    private static final String GPS = LocationManager.GPS_PROVIDER;
    private static final String FUSED = LocationProviderBase.FUSED_PROVIDER;

    public static final long SWITCH_ON_FRESHNESS_CLIFF_NS = 11 * 1000000000L; // 11 seconds

    private final LocationManager mLocationManager;
    private final Looper mLooper;
    private final Callback mCallback;

    // all fields are only used on mLooper thread. except for in dump() which is not thread-safe
    private Location mFusedLocation;
    private Location mGpsLocation;
    private Location mNetworkLocation;

    private ProviderRequestUnbundled mRequest;

    private final HashMap<String, ProviderStats> mStats = new HashMap<>();

    FusionEngine(Context context, Looper looper, Callback callback) {
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        mNetworkLocation = new Location("");
        mNetworkLocation.setAccuracy(Float.MAX_VALUE);
        mGpsLocation = new Location("");
        mGpsLocation.setAccuracy(Float.MAX_VALUE);
        mLooper = looper;
        mCallback = callback;

        mStats.put(GPS, new ProviderStats());
        mStats.put(NETWORK, new ProviderStats());
    }

    /** Called on mLooper thread */
    public void setRequest(ProviderRequestUnbundled request) {
        mRequest = request;
        updateRequirements();
    }

    private static class ProviderStats {
        public boolean requested;
        public long requestTime;
        public long minTime;

        @Override
        public String toString() {
            return (requested ? " REQUESTED" : " ---");
        }
    }

    private void enableProvider(String name, long minTime) {
        ProviderStats stats = mStats.get(name);
        if (stats == null) return;

        if (mLocationManager.isProviderEnabled(name)) {
            if (!stats.requested) {
                stats.requestTime = SystemClock.elapsedRealtime();
                stats.requested = true;
                stats.minTime = minTime;
                mLocationManager.requestLocationUpdates(name, minTime, 0, this, mLooper);
            } else if (stats.minTime != minTime) {
                stats.minTime = minTime;
                mLocationManager.requestLocationUpdates(name, minTime, 0, this, mLooper);
            }
        }
    }

    private void disableProvider(String name) {
        ProviderStats stats = mStats.get(name);
        if (stats == null) return;

        if (stats.requested) {
            stats.requested = false;
            mLocationManager.removeUpdates(this);  //TODO GLOBAL
        }
    }

    private void updateRequirements() {
        if (mRequest == null || !mRequest.getReportLocation()) {
            mRequest = null;
            disableProvider(NETWORK);
            disableProvider(GPS);
            return;
        }

        long networkInterval = Long.MAX_VALUE;
        long gpsInterval = Long.MAX_VALUE;
        for (LocationRequestUnbundled request : mRequest.getLocationRequests()) {
            switch (request.getQuality()) {
                case LocationRequestUnbundled.ACCURACY_FINE:
                case LocationRequestUnbundled.POWER_HIGH:
                    if (request.getInterval() < gpsInterval) {
                        gpsInterval = request.getInterval();
                    }
                    if (request.getInterval() < networkInterval) {
                        networkInterval = request.getInterval();
                    }
                    break;
                case LocationRequestUnbundled.ACCURACY_BLOCK:
                case LocationRequestUnbundled.ACCURACY_CITY:
                case LocationRequestUnbundled.POWER_LOW:
                    if (request.getInterval() < networkInterval) {
                        networkInterval = request.getInterval();
                    }
                    break;
            }
        }

        if (gpsInterval < Long.MAX_VALUE) {
            enableProvider(GPS, gpsInterval);
        } else {
            disableProvider(GPS);
        }
        if (networkInterval < Long.MAX_VALUE) {
            enableProvider(NETWORK, networkInterval);
        } else {
            disableProvider(NETWORK);
        }
    }

    /**
     * Test whether one location (a) is better to use than another (b).
     */
    private static boolean isBetterThan(Location locationA, Location locationB) {
        if (locationA == null) {
            return false;
        }
        if (locationB == null) {
            return true;
        }
        // A provider is better if the reading is sufficiently newer.  Heading
        // underground can cause GPS to stop reporting fixes.  In this case it's
        // appropriate to revert to cell, even when its accuracy is less.
        if (locationA.getElapsedRealtimeNanos()
                > locationB.getElapsedRealtimeNanos() + SWITCH_ON_FRESHNESS_CLIFF_NS) {
            return true;
        }

        // A provider is better if it has better accuracy.  Assuming both readings
        // are fresh (and by that accurate), choose the one with the smaller
        // accuracy circle.
        if (!locationA.hasAccuracy()) {
            return false;
        }
        if (!locationB.hasAccuracy()) {
            return true;
        }
        return locationA.getAccuracy() < locationB.getAccuracy();
    }

    private void updateFusedLocation() {
        // may the best location win!
        if (isBetterThan(mGpsLocation, mNetworkLocation)) {
            mFusedLocation = new Location(mGpsLocation);
        } else {
            mFusedLocation = new Location(mNetworkLocation);
        }
        mFusedLocation.setProvider(FUSED);
        if (mNetworkLocation != null) {
            // copy NO_GPS_LOCATION extra from mNetworkLocation into mFusedLocation
            Bundle srcExtras = mNetworkLocation.getExtras();
            if (srcExtras != null) {
                Parcelable srcParcelable =
                        srcExtras.getParcelable(LocationProviderBase.EXTRA_NO_GPS_LOCATION);
                if (srcParcelable instanceof Location) {
                    Bundle dstExtras = mFusedLocation.getExtras();
                    if (dstExtras == null) {
                        dstExtras = new Bundle();
                        mFusedLocation.setExtras(dstExtras);
                    }
                    dstExtras.putParcelable(LocationProviderBase.EXTRA_NO_GPS_LOCATION,
                            srcParcelable);
                }
            }
        }

        if (mCallback != null) {
            mCallback.reportLocation(mFusedLocation);
        } else {
            Log.w(TAG, "Location updates received while fusion engine not started");
        }
    }

    /** Called on mLooper thread */
    @Override
    public void onLocationChanged(Location location) {
        if (GPS.equals(location.getProvider())) {
            mGpsLocation = location;
            updateFusedLocation();
        } else if (NETWORK.equals(location.getProvider())) {
            mNetworkLocation = location;
            updateFusedLocation();
        }
    }

    /** Called on mLooper thread */
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    /** Called on mLooper thread */
    @Override
    public void onProviderEnabled(String provider) {
    }

    /** Called on mLooper thread */
    @Override
    public void onProviderDisabled(String provider) {
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        StringBuilder s = new StringBuilder();
        s.append(mRequest).append('\n');
        s.append("fused=").append(mFusedLocation).append('\n');
        s.append(String.format("gps %s\n", mGpsLocation));
        s.append("    ").append(mStats.get(GPS)).append('\n');
        s.append(String.format("net %s\n", mNetworkLocation));
        s.append("    ").append(mStats.get(NETWORK)).append('\n');
        pw.append(s);
    }

    /** Called on mLooper thread */
    public void switchUser() {
        // reset state to prevent location data leakage
        mFusedLocation = null;
        mGpsLocation = null;
        mNetworkLocation = null;
    }
}
