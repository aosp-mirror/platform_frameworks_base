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
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Manages GNSS Geofence operations.
 */
class GnssGeofenceProvider extends IGpsGeofenceHardware.Stub {

    private static final String TAG = "GnssGeofenceProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

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
    @GuardedBy("mLock")
    private final GnssGeofenceProviderNative mNative;
    @GuardedBy("mLock")
    private final SparseArray<GeofenceEntry> mGeofenceEntries = new SparseArray<>();

    GnssGeofenceProvider() {
        this(new GnssGeofenceProviderNative());
    }

    @VisibleForTesting
    GnssGeofenceProvider(GnssGeofenceProviderNative gnssGeofenceProviderNative) {
        mNative = gnssGeofenceProviderNative;
    }

    void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        synchronized (mLock) {
            for (int i = 0; i < mGeofenceEntries.size(); i++) {
                GeofenceEntry entry = mGeofenceEntries.valueAt(i);
                boolean added = mNative.addGeofence(entry.geofenceId, entry.latitude,
                        entry.longitude,
                        entry.radius,
                        entry.lastTransition, entry.monitorTransitions,
                        entry.notificationResponsiveness, entry.unknownTimer);
                if (added && entry.paused) {
                    mNative.pauseGeofence(entry.geofenceId);
                }
            }
        }
    }

    @Override
    public boolean isHardwareGeofenceSupported() {
        synchronized (mLock) {
            return mNative.isGeofenceSupported();
        }
    }

    @Override
    public boolean addCircularHardwareGeofence(int geofenceId, double latitude,
            double longitude, double radius, int lastTransition, int monitorTransitions,
            int notificationResponsiveness, int unknownTimer) {
        synchronized (mLock) {
            boolean added = mNative.addGeofence(geofenceId, latitude, longitude, radius,
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
            boolean removed = mNative.removeGeofence(geofenceId);
            if (removed) {
                mGeofenceEntries.remove(geofenceId);
            }
            return removed;
        }
    }

    @Override
    public boolean pauseHardwareGeofence(int geofenceId) {
        synchronized (mLock) {
            boolean paused = mNative.pauseGeofence(geofenceId);
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
            boolean resumed = mNative.resumeGeofence(geofenceId, monitorTransitions);
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

    @VisibleForTesting
    static class GnssGeofenceProviderNative {
        public boolean isGeofenceSupported() {
            return native_is_geofence_supported();
        }

        public boolean addGeofence(int geofenceId, double latitude, double longitude, double radius,
                int lastTransition, int monitorTransitions, int notificationResponsiveness,
                int unknownTimer) {
            return native_add_geofence(geofenceId, latitude, longitude, radius, lastTransition,
                    monitorTransitions, notificationResponsiveness, unknownTimer);
        }

        public boolean removeGeofence(int geofenceId) {
            return native_remove_geofence(geofenceId);
        }

        public boolean resumeGeofence(int geofenceId, int transitions) {
            return native_resume_geofence(geofenceId, transitions);
        }

        public boolean pauseGeofence(int geofenceId) {
            return native_pause_geofence(geofenceId);
        }
    }

    private static native boolean native_is_geofence_supported();

    private static native boolean native_add_geofence(int geofenceId, double latitude,
            double longitude, double radius, int lastTransition, int monitorTransitions,
            int notificationResponsivenes, int unknownTimer);

    private static native boolean native_remove_geofence(int geofenceId);

    private static native boolean native_resume_geofence(int geofenceId, int transitions);

    private static native boolean native_pause_geofence(int geofenceId);
}
