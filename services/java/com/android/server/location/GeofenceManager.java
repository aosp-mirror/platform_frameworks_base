/*
 * Copyright (C) 20012 The Android Open Source Project
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

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import android.Manifest.permission;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Geofence;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

public class GeofenceManager implements LocationListener, PendingIntent.OnFinished {
    private static final String TAG = "GeofenceManager";

    /**
     * Assume a maximum land speed, as a heuristic to throttle location updates.
     * (Air travel should result in an airplane mode toggle which will
     * force a new location update anyway).
     */
    private static final int MAX_SPEED_M_S = 100;  // 360 km/hr (high speed train)

    private final Context mContext;
    private final LocationManager mLocationManager;
    private final PowerManager.WakeLock mWakeLock;
    private final Looper mLooper;  // looper thread to take location updates on

    private Object mLock = new Object();

    // access to members below is synchronized on mLock
    private Location mLastLocation;
    private List<GeofenceState> mFences = new LinkedList<GeofenceState>();

    public GeofenceManager(Context context) {
        mContext = context;
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mLooper = Looper.myLooper();

        LocationRequest request = new LocationRequest()
                .setQuality(LocationRequest.POWER_NONE)
                .setFastestInterval(0);
        mLocationManager.requestLocationUpdates(request, this, Looper.myLooper());
    }

    public void addFence(LocationRequest request, Geofence geofence, PendingIntent intent, int uid,
            String packageName) {
        GeofenceState state = new GeofenceState(geofence, mLastLocation,
                request.getExpireAt(), packageName, intent);

        synchronized (mLock) {
            // first make sure it doesn't already exist
            for (int i = mFences.size() - 1; i >= 0; i--) {
                GeofenceState w = mFences.get(i);
                if (geofence.equals(w.mFence) && intent.equals(w.mIntent)) {
                    // already exists, remove the old one
                    mFences.remove(i);
                    break;
                }
            }
            mFences.add(state);
            updateProviderRequirementsLocked();
        }
    }

    public void removeFence(Geofence fence, PendingIntent intent) {
        synchronized (mLock) {
            Iterator<GeofenceState> iter = mFences.iterator();
            while (iter.hasNext()) {
                GeofenceState state = iter.next();
                if (state.mIntent.equals(intent)) {

                    if (fence == null) {
                        // alwaus remove
                        iter.remove();
                    } else {
                        // just remove matching fences
                        if (fence.equals(state.mFence)) {
                            iter.remove();
                        }
                    }
                }
            }
            updateProviderRequirementsLocked();
        }
    }

    public void removeFence(String packageName) {
        synchronized (mLock) {
            Iterator<GeofenceState> iter = mFences.iterator();
            while (iter.hasNext()) {
                GeofenceState state = iter.next();
                if (state.mPackageName.equals(packageName)) {
                    iter.remove();
                }
            }
            updateProviderRequirementsLocked();
        }
    }

    private void removeExpiredFencesLocked() {
        long time = SystemClock.elapsedRealtime();
        Iterator<GeofenceState> iter = mFences.iterator();
        while (iter.hasNext()) {
            GeofenceState state = iter.next();
            if (state.mExpireAt < time) {
                iter.remove();
            }
        }
    }

    private void processLocation(Location location) {
        List<PendingIntent> enterIntents = new LinkedList<PendingIntent>();
        List<PendingIntent> exitIntents = new LinkedList<PendingIntent>();

        synchronized (mLock) {
            mLastLocation = location;

            removeExpiredFencesLocked();

            for (GeofenceState state : mFences) {
                int event = state.processLocation(location);
                if ((event & GeofenceState.FLAG_ENTER) != 0) {
                    enterIntents.add(state.mIntent);
                }
                if ((event & GeofenceState.FLAG_EXIT) != 0) {
                    exitIntents.add(state.mIntent);
                }
            }
            updateProviderRequirementsLocked();
        }

        // release lock before sending intents
        for (PendingIntent intent : exitIntents) {
            sendIntentExit(intent);
        }
        for (PendingIntent intent : enterIntents) {
            sendIntentEnter(intent);
        }
    }

    private void sendIntentEnter(PendingIntent pendingIntent) {
        Intent intent = new Intent();
        intent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
        sendIntent(pendingIntent, intent);
    }

    private void sendIntentExit(PendingIntent pendingIntent) {
        Intent intent = new Intent();
        intent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
        sendIntent(pendingIntent, intent);
    }

    private void sendIntent(PendingIntent pendingIntent, Intent intent) {
        try {
            mWakeLock.acquire();
            pendingIntent.send(mContext, 0, intent, this, null, permission.ACCESS_FINE_LOCATION);
        } catch (PendingIntent.CanceledException e) {
            removeFence(null, pendingIntent);
            mWakeLock.release();
        }
    }

    private void updateProviderRequirementsLocked() {
        double minDistance = Double.MAX_VALUE;
        for (GeofenceState state : mFences) {
            if (state.getDistance() < minDistance) {
                minDistance = state.getDistance();
            }
        }

        if (minDistance == Double.MAX_VALUE) {
            disableLocationLocked();
        } else {
            int intervalMs = (int)(minDistance * 1000) / MAX_SPEED_M_S;
            requestLocationLocked(intervalMs);
        }
    }

    private void requestLocationLocked(int intervalMs) {
        mLocationManager.requestLocationUpdates(new LocationRequest().setInterval(intervalMs), this,
                mLooper);
    }

    private void disableLocationLocked() {
        mLocationManager.removeUpdates(this);
    }

    @Override
    public void onLocationChanged(Location location) {
        processLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @Override
    public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode,
            String resultData, Bundle resultExtras) {
        mWakeLock.release();
    }

    public void dump(PrintWriter pw) {
        pw.println("  Geofences:");

        for (GeofenceState state : mFences) {
            pw.append("    ");
            pw.append(state.mPackageName);
            pw.append(" ");
            pw.append(state.mFence.toString());
            pw.append("\n");
        }
    }
}
