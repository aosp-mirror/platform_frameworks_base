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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

public class GeofenceManager implements LocationListener, PendingIntent.OnFinished {
    static final String TAG = "GeofenceManager";

    /**
     * Assume a maximum land speed, as a heuristic to throttle location updates.
     * (Air travel should result in an airplane mode toggle which will
     * force a new location update anyway).
     */
    static final int MAX_SPEED_M_S = 100;  // 360 km/hr (high speed train)

    class GeofenceWrapper {
        final Geofence fence;
        final long expiry;
        final String packageName;
        final PendingIntent intent;

        public GeofenceWrapper(Geofence fence, long expiry, String packageName,
                PendingIntent intent) {
            this.fence = fence;
            this.expiry = expiry;
            this.packageName = packageName;
            this.intent = intent;
        }
    }

    final Context mContext;
    final LocationManager mLocationManager;
    final PowerManager.WakeLock mWakeLock;
    final Looper mLooper;  // looper thread to take location updates on

    // access to members below is synchronized on this
    Location mLastLocation;
    List<GeofenceWrapper> mFences = new LinkedList<GeofenceWrapper>();

    public GeofenceManager(Context context) {
        mContext = context;
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mLooper = Looper.myLooper();
        mLocationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, this);
    }

    public void addFence(double latitude, double longitude, float radius, long expiration,
            PendingIntent intent, int uid, String packageName) {
        long expiry = SystemClock.elapsedRealtime() + expiration;
        if (expiration < 0) {
            expiry = Long.MAX_VALUE;
        }
        Geofence fence = new Geofence(latitude, longitude, radius, mLastLocation);
        GeofenceWrapper fenceWrapper = new GeofenceWrapper(fence, expiry, packageName, intent);

        synchronized (this) {
            mFences.add(fenceWrapper);
            updateProviderRequirements();
        }
    }

    public void removeFence(PendingIntent intent) {
        synchronized (this) {
            Iterator<GeofenceWrapper> iter = mFences.iterator();
            while (iter.hasNext()) {
                GeofenceWrapper fenceWrapper = iter.next();
                if (fenceWrapper.intent.equals(intent)) {
                    iter.remove();
                }
            }
            updateProviderRequirements();
        }
    }

    public void removeFence(String packageName) {
        synchronized (this) {
            Iterator<GeofenceWrapper> iter = mFences.iterator();
            while (iter.hasNext()) {
                GeofenceWrapper fenceWrapper = iter.next();
                if (fenceWrapper.packageName.equals(packageName)) {
                    iter.remove();
                }
            }
            updateProviderRequirements();
        }
    }

    void removeExpiredFences() {
        synchronized (this) {
            long time = SystemClock.elapsedRealtime();
            Iterator<GeofenceWrapper> iter = mFences.iterator();
            while (iter.hasNext()) {
                GeofenceWrapper fenceWrapper = iter.next();
                if (fenceWrapper.expiry < time) {
                    iter.remove();
                }
            }
        }
    }

    void processLocation(Location location) {
        List<PendingIntent> enterIntents = new LinkedList<PendingIntent>();
        List<PendingIntent> exitIntents = new LinkedList<PendingIntent>();

        synchronized (this) {
            mLastLocation = location;

            removeExpiredFences();

            for (GeofenceWrapper fenceWrapper : mFences) {
                int event = fenceWrapper.fence.processLocation(location);
                if ((event & Geofence.FLAG_ENTER) != 0) {
                    enterIntents.add(fenceWrapper.intent);
                }
                if ((event & Geofence.FLAG_EXIT) != 0) {
                    exitIntents.add(fenceWrapper.intent);
                }
            }
            updateProviderRequirements();
        }

        // release lock before sending intents
        for (PendingIntent intent : exitIntents) {
            sendIntentExit(intent);
        }
        for (PendingIntent intent : enterIntents) {
            sendIntentEnter(intent);
        }
    }

    void sendIntentEnter(PendingIntent pendingIntent) {
        Intent intent = new Intent();
        intent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
        sendIntent(pendingIntent, intent);
    }

    void sendIntentExit(PendingIntent pendingIntent) {
        Intent intent = new Intent();
        intent.putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
        sendIntent(pendingIntent, intent);
    }

    void sendIntent(PendingIntent pendingIntent, Intent intent) {
        try {
            mWakeLock.acquire();
            pendingIntent.send(mContext, 0, intent, this, null, permission.ACCESS_FINE_LOCATION);
        } catch (PendingIntent.CanceledException e) {
            removeFence(pendingIntent);
            mWakeLock.release();
        }
    }

    void updateProviderRequirements() {
        synchronized (this) {
            double minDistance = Double.MAX_VALUE;
            for (GeofenceWrapper alert : mFences) {
                if (alert.fence.getDistance() < minDistance) {
                    minDistance = alert.fence.getDistance();
                }
            }

            if (minDistance == Double.MAX_VALUE) {
                disableLocation();
            } else {
                int intervalMs = (int)(minDistance * 1000) / MAX_SPEED_M_S;
                setLocationInterval(intervalMs);
            }
        }
    }

    void setLocationInterval(int intervalMs) {
        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0, this,
                mLooper);
    }

    void disableLocation() {
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
        for (GeofenceWrapper fenceWrapper : mFences) {
            pw.append("    ");
            pw.append(fenceWrapper.packageName);
            pw.append(" ");
            pw.append(fenceWrapper.fence.toString());
            pw.append("\n");
        }
    }
}
