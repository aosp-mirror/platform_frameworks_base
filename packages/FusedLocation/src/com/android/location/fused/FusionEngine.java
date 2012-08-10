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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;

import com.android.location.provider.ProviderRequestUnbundled;


import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.os.WorkSource;
import android.util.Log;

public class FusionEngine implements LocationListener {
    public interface Callback {
        public void reportLocation(Location location);
    }

    private static final String TAG = "FusedLocation";
    private static final String NETWORK = LocationManager.NETWORK_PROVIDER;
    private static final String GPS = LocationManager.GPS_PROVIDER;

    // threshold below which a location is considered stale enough
    // that we shouldn't use its bearing, altitude, speed etc
    private static final double WEIGHT_THRESHOLD = 0.5;
    // accuracy in meters at which a Location's weight is halved (compared to 0 accuracy)
    private static final double ACCURACY_HALFLIFE_M = 20.0;
    // age in seconds at which a Location's weight is halved (compared to 0 age)
    private static final double AGE_HALFLIFE_S = 60.0;

    private static final double ACCURACY_DECAY_CONSTANT_M = Math.log(2) / ACCURACY_HALFLIFE_M;
    private static final double AGE_DECAY_CONSTANT_S = Math.log(2) / AGE_HALFLIFE_S;

    private final Context mContext;
    private final LocationManager mLocationManager;
    private final Looper mLooper;

    // all fields are only used on mLooper thread. except for in dump() which is not thread-safe
    private Callback mCallback;
    private Location mFusedLocation;
    private Location mGpsLocation;
    private Location mNetworkLocation;
    private double mNetworkWeight;
    private double mGpsWeight;

    private boolean mEnabled;
    private ProviderRequestUnbundled mRequest;

    private final HashMap<String, ProviderStats> mStats = new HashMap<String, ProviderStats>();

    public FusionEngine(Context context, Looper looper) {
        mContext = context;
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        mNetworkLocation = new Location("");
        mNetworkLocation.setAccuracy(Float.MAX_VALUE);
        mGpsLocation = new Location("");
        mGpsLocation.setAccuracy(Float.MAX_VALUE);
        mLooper = looper;

        mStats.put(GPS, new ProviderStats());
        mStats.get(GPS).available = mLocationManager.isProviderEnabled(GPS);
        mStats.put(NETWORK, new ProviderStats());
        mStats.get(NETWORK).available = mLocationManager.isProviderEnabled(NETWORK);
    }

    public void init(Callback callback) {
        Log.i(TAG, "engine started (" + mContext.getPackageName() + ")");
        mCallback = callback;
    }

    /**
     * Called to stop doing any work, and release all resources
     * This can happen when a better fusion engine is installed
     * in a different package, and this one is no longer needed.
     * Called on mLooper thread
     */
    public void deinit() {
        mRequest = null;
        disable();
        Log.i(TAG, "engine stopped (" + mContext.getPackageName() + ")");
    }

    private boolean isAvailable() {
        return mStats.get(GPS).available || mStats.get(NETWORK).available;
    }

    /** Called on mLooper thread */
    public void enable() {
        mEnabled = true;
        updateRequirements();
    }

    /** Called on mLooper thread */
    public void disable() {
        mEnabled = false;
        updateRequirements();
    }

    /** Called on mLooper thread */
    public void setRequirements(ProviderRequestUnbundled request, WorkSource source) {
        mRequest = request;
        mEnabled = true;
        updateRequirements();
    }

    private static class ProviderStats {
        public boolean available;
        public boolean requested;
        public long requestTime;
        public long minTime;
        public long lastRequestTtff;
        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            s.append(available ? "AVAILABLE" : "UNAVAILABLE");
            s.append(requested ? " REQUESTED" : " ---");
            return s.toString();
        }
    }

    private void enableProvider(String name, long minTime) {
        ProviderStats stats = mStats.get(name);

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

    private void disableProvider(String name) {
        ProviderStats stats = mStats.get(name);

        if (stats.requested) {
            stats.requested = false;
            mLocationManager.removeUpdates(this);  //TODO GLOBAL
        }
    }

    private void updateRequirements() {
        if (mEnabled == false || mRequest == null) {
            mRequest = null;
            disableProvider(NETWORK);
            disableProvider(GPS);
            return;
        }

        ProviderStats gpsStats = mStats.get(GPS);
        ProviderStats networkStats = mStats.get(NETWORK);

        long networkInterval = Long.MAX_VALUE;
        long gpsInterval = Long.MAX_VALUE;
        for (LocationRequest request : mRequest.getLocationRequests()) {
            switch (request.getQuality()) {
                case LocationRequest.ACCURACY_FINE:
                case LocationRequest.POWER_HIGH:
                    if (request.getInterval() < gpsInterval) {
                        gpsInterval = request.getInterval();
                    }
                    if (request.getInterval() < networkInterval) {
                        networkInterval = request.getInterval();
                    }
                    break;
                case LocationRequest.ACCURACY_BLOCK:
                case LocationRequest.ACCURACY_CITY:
                case LocationRequest.POWER_LOW:
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

    private static double weighAccuracy(Location loc) {
        double accuracy = loc.getAccuracy();
        return Math.exp(-accuracy * ACCURACY_DECAY_CONSTANT_M);
    }

    private static double weighAge(Location loc) {
        long ageSeconds = SystemClock.elapsedRealtimeNano() - loc.getElapsedRealtimeNano();
        ageSeconds /= 1000000000L;
        if (ageSeconds < 0) ageSeconds = 0;
        return Math.exp(-ageSeconds * AGE_DECAY_CONSTANT_S);
    }

    private double weigh(double gps, double network) {
        return (gps * mGpsWeight) + (network * mNetworkWeight);
    }

    private double weigh(double gps, double network, double wrapMin, double wrapMax) {
        // apply aliasing
        double wrapWidth = wrapMax - wrapMin;
        if (gps - network > wrapWidth / 2) network += wrapWidth;
        else if (network - gps > wrapWidth / 2) gps += wrapWidth;

        double result = weigh(gps, network);

        // remove aliasing
        if (result > wrapMax) result -= wrapWidth;
        return result;
    }

    private void updateFusedLocation() {
        // naive fusion
        mNetworkWeight = weighAccuracy(mNetworkLocation) * weighAge(mNetworkLocation);
        mGpsWeight = weighAccuracy(mGpsLocation) * weighAge(mGpsLocation);
        // scale mNetworkWeight and mGpsWeight so that they add to 1
        double totalWeight = mNetworkWeight + mGpsWeight;
        mNetworkWeight /= totalWeight;
        mGpsWeight /= totalWeight;

        Location fused = new Location(LocationManager.FUSED_PROVIDER);
        // fuse lat/long
        // assumes the two locations are close enough that earth curvature doesn't matter
        fused.setLatitude(weigh(mGpsLocation.getLatitude(), mNetworkLocation.getLatitude()));
        fused.setLongitude(weigh(mGpsLocation.getLongitude(), mNetworkLocation.getLongitude(),
                -180.0, 180.0));

        // fused accuracy
        //TODO: use some real math instead of this crude fusion
        // one suggestion is to fuse in a quadratic manner, eg
        // sqrt(weigh(gpsAcc^2, netAcc^2)).
        // another direction to explore is to consider the difference in the 2
        // locations. If the component locations overlap, the fused accuracy is
        // better than the component accuracies. If they are far apart,
        // the fused accuracy is much worse.
        fused.setAccuracy((float)weigh(mGpsLocation.getAccuracy(), mNetworkLocation.getAccuracy()));

        // fused time - now
        fused.setTime(System.currentTimeMillis());
        fused.setElapsedRealtimeNano(SystemClock.elapsedRealtimeNano());

        // fuse altitude
        if (mGpsLocation.hasAltitude() && !mNetworkLocation.hasAltitude() &&
                mGpsWeight > WEIGHT_THRESHOLD) {
            fused.setAltitude(mGpsLocation.getAltitude());   // use GPS
        } else if (!mGpsLocation.hasAltitude() && mNetworkLocation.hasAltitude() &&
                mNetworkWeight > WEIGHT_THRESHOLD) {
            fused.setAltitude(mNetworkLocation.getAltitude());   // use Network
        } else if (mGpsLocation.hasAltitude() && mNetworkLocation.hasAltitude()) {
            fused.setAltitude(weigh(mGpsLocation.getAltitude(), mNetworkLocation.getAltitude()));
        }

        // fuse speed
        if (mGpsLocation.hasSpeed() && !mNetworkLocation.hasSpeed() &&
                mGpsWeight > WEIGHT_THRESHOLD) {
            fused.setSpeed(mGpsLocation.getSpeed());   // use GPS if its not too old
        } else if (!mGpsLocation.hasSpeed() && mNetworkLocation.hasSpeed() &&
                mNetworkWeight > WEIGHT_THRESHOLD) {
            fused.setSpeed(mNetworkLocation.getSpeed());   // use Network
        } else if (mGpsLocation.hasSpeed() && mNetworkLocation.hasSpeed()) {
            fused.setSpeed((float)weigh(mGpsLocation.getSpeed(), mNetworkLocation.getSpeed()));
        }

        // fuse bearing
        if (mGpsLocation.hasBearing() && !mNetworkLocation.hasBearing() &&
                mGpsWeight > WEIGHT_THRESHOLD) {
            fused.setBearing(mGpsLocation.getBearing());   // use GPS if its not too old
        } else if (!mGpsLocation.hasBearing() && mNetworkLocation.hasBearing() &&
                mNetworkWeight > WEIGHT_THRESHOLD) {
            fused.setBearing(mNetworkLocation.getBearing());   // use Network
        } else if (mGpsLocation.hasBearing() && mNetworkLocation.hasBearing()) {
            fused.setBearing((float)weigh(mGpsLocation.getBearing(), mNetworkLocation.getBearing(),
                    0.0, 360.0));
        }

        mFusedLocation = fused;

        mCallback.reportLocation(mFusedLocation);
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
    public void onStatusChanged(String provider, int status, Bundle extras) {  }

    /** Called on mLooper thread */
    @Override
    public void onProviderEnabled(String provider) {
        ProviderStats stats = mStats.get(provider);
        if (stats == null) return;

        stats.available = true;
    }

    /** Called on mLooper thread */
    @Override
    public void onProviderDisabled(String provider) {
        ProviderStats stats = mStats.get(provider);
        if (stats == null) return;

        stats.available = false;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        StringBuilder s = new StringBuilder();
        s.append("mEnabled=" + mEnabled).append(' ').append(mRequest).append('\n');
        s.append("fused=").append(mFusedLocation).append('\n');
        s.append(String.format("gps %.3f %s\n", mGpsWeight, mGpsLocation));
        s.append("    ").append(mStats.get(GPS)).append('\n');
        s.append(String.format("net %.3f %s\n", mNetworkWeight, mNetworkLocation));
        s.append("    ").append(mStats.get(NETWORK)).append('\n');
        pw.append(s);
    }
}
