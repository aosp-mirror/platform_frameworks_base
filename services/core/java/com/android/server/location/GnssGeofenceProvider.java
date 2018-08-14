package com.android.server.location;

import android.location.IGpsGeofenceHardware;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

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

    private final GnssGeofenceProviderNative mNative;
    private final SparseArray<GeofenceEntry> mGeofenceEntries = new SparseArray<>();
    private final Handler mHandler;

    GnssGeofenceProvider(Looper looper) {
        this(looper, new GnssGeofenceProviderNative());
    }

    @VisibleForTesting
    GnssGeofenceProvider(Looper looper, GnssGeofenceProviderNative gnssGeofenceProviderNative) {
        mHandler = new Handler(looper);
        mNative = gnssGeofenceProviderNative;
    }

    // TODO(b/37460011): use this method in HAL death recovery.
    void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        mHandler.post(() -> {
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
        });
    }

    private boolean runOnHandlerThread(Callable<Boolean> callable) {
        FutureTask<Boolean> futureTask = new FutureTask<>(callable);
        mHandler.post(futureTask);
        try {
            return futureTask.get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Failed running callable.", e);
        }
        return false;
    }

    @Override
    public boolean isHardwareGeofenceSupported() {
        return runOnHandlerThread(mNative::isGeofenceSupported);
    }

    @Override
    public boolean addCircularHardwareGeofence(int geofenceId, double latitude,
            double longitude, double radius, int lastTransition, int monitorTransitions,
            int notificationResponsiveness, int unknownTimer) {
        return runOnHandlerThread(() -> {
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
        });
    }

    @Override
    public boolean removeHardwareGeofence(int geofenceId) {
        return runOnHandlerThread(() -> {
            boolean removed = mNative.removeGeofence(geofenceId);
            if (removed) {
                mGeofenceEntries.remove(geofenceId);
            }
            return removed;
        });
    }

    @Override
    public boolean pauseHardwareGeofence(int geofenceId) {
        return runOnHandlerThread(() -> {
            boolean paused = mNative.pauseGeofence(geofenceId);
            if (paused) {
                GeofenceEntry entry = mGeofenceEntries.get(geofenceId);
                if (entry != null) {
                    entry.paused = true;
                }
            }
            return paused;
        });
    }

    @Override
    public boolean resumeHardwareGeofence(int geofenceId, int monitorTransitions) {
        return runOnHandlerThread(() -> {
            boolean resumed = mNative.resumeGeofence(geofenceId, monitorTransitions);
            if (resumed) {
                GeofenceEntry entry = mGeofenceEntries.get(geofenceId);
                if (entry != null) {
                    entry.paused = false;
                    entry.monitorTransitions = monitorTransitions;
                }
            }
            return resumed;
        });
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
