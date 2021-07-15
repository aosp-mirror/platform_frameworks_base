/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

import android.location.IGpsGeofenceHardware;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.location.gnss.hal.GnssNative;

/**
 * Manages GNSS Geofence operations.
 */
class GnssGeofenceProxy extends IGpsGeofenceHardware.Stub implements GnssNative.BaseCallbacks {

    /** Holds the parameters of a geofence. */
    private static class GeofenceEntry {
        public int geofenceId;
        public double latitude;
        public double longitude;
        public double radius;
        public int lastTransition;
        public int monitorTransitions;
        public int notificationResponsiveness;
        public int unknownTimer;
        public boolean paused;
    }

    private final Object mLock = new Object();

    private final GnssNative mGnssNative;

    @GuardedBy("mLock")
    private final SparseArray<GeofenceEntry> mGeofenceEntries = new SparseArray<>();

    GnssGeofenceProxy(GnssNative gnssNative) {
        mGnssNative = gnssNative;

        mGnssNative.addBaseCallbacks(this);
    }

    @Override
    public boolean isHardwareGeofenceSupported() {
        synchronized (mLock) {
            return mGnssNative.isGeofencingSupported();
        }
    }

    @Override
    public boolean addCircularHardwareGeofence(int geofenceId, double latitude,
            double longitude, double radius, int lastTransition, int monitorTransitions,
            int notificationResponsiveness, int unknownTimer) {
        synchronized (mLock) {
            boolean added = mGnssNative.addGeofence(geofenceId, latitude, longitude, radius,
                    lastTransition, monitorTransitions, notificationResponsiveness,
                    unknownTimer);
            if (added) {
                GeofenceEntry entry = new GeofenceEntry();
                entry.geofenceId = geofenceId;
                entry.latitude = latitude;
                entry.longitude = longitude;
                entry.radius = radius;
                entry.lastTransition = lastTransition;
                entry.monitorTransitions = monitorTransitions;
                entry.notificationResponsiveness = notificationResponsiveness;
                entry.unknownTimer = unknownTimer;
                mGeofenceEntries.put(geofenceId, entry);
            }
            return added;
        }
    }

    @Override
    public boolean removeHardwareGeofence(int geofenceId) {
        synchronized (mLock) {
            boolean removed = mGnssNative.removeGeofence(geofenceId);
            if (removed) {
                mGeofenceEntries.remove(geofenceId);
            }
            return removed;
        }
    }

    @Override
    public boolean pauseHardwareGeofence(int geofenceId) {
        synchronized (mLock) {
            boolean paused = mGnssNative.pauseGeofence(geofenceId);
            if (paused) {
                GeofenceEntry entry = mGeofenceEntries.get(geofenceId);
                if (entry != null) {
                    entry.paused = true;
                }
            }
            return paused;
        }
    }

    @Override
    public boolean resumeHardwareGeofence(int geofenceId, int monitorTransitions) {
        synchronized (mLock) {
            boolean resumed = mGnssNative.resumeGeofence(geofenceId, monitorTransitions);
            if (resumed) {
                GeofenceEntry entry = mGeofenceEntries.get(geofenceId);
                if (entry != null) {
                    entry.paused = false;
                    entry.monitorTransitions = monitorTransitions;
                }
            }
            return resumed;
        }
    }

    @Override
    public void onHalRestarted() {
        synchronized (mLock) {
            for (int i = 0; i < mGeofenceEntries.size(); i++) {
                GeofenceEntry entry = mGeofenceEntries.valueAt(i);
                boolean added = mGnssNative.addGeofence(entry.geofenceId, entry.latitude,
                        entry.longitude,
                        entry.radius,
                        entry.lastTransition, entry.monitorTransitions,
                        entry.notificationResponsiveness, entry.unknownTimer);
                if (added && entry.paused) {
                    mGnssNative.pauseGeofence(entry.geofenceId);
                }
            }
        }
    }
}
