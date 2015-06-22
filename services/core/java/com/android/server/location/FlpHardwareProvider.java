/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.hardware.location.GeofenceHardware;
import android.hardware.location.GeofenceHardwareImpl;
import android.hardware.location.GeofenceHardwareRequestParcelable;
import android.hardware.location.IFusedLocationHardware;
import android.hardware.location.IFusedLocationHardwareSink;
import android.location.IFusedGeofenceHardware;
import android.location.FusedBatchOptions;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * This class is an interop layer for JVM types and the JNI code that interacts
 * with the FLP HAL implementation.
 *
 * {@hide}
 */
public class FlpHardwareProvider {
    private static final int FIRST_VERSION_WITH_FLUSH_LOCATIONS = 2;
    private GeofenceHardwareImpl mGeofenceHardwareSink = null;
    private IFusedLocationHardwareSink mLocationSink = null;
    // Capabilities provided by FlpCallbacks
    private boolean mHaveBatchingCapabilities;
    private int mBatchingCapabilities;
    private int mVersion = 1;

    private static FlpHardwareProvider sSingletonInstance = null;

    private final static String TAG = "FlpHardwareProvider";
    private final Context mContext;
    private final Object mLocationSinkLock = new Object();

    // FlpHal result codes, they must be equal to the ones in fused_location.h
    private static final int FLP_RESULT_SUCCESS = 0;
    private static final int FLP_RESULT_ERROR = -1;
    private static final int FLP_RESULT_INSUFFICIENT_MEMORY = -2;
    private static final int FLP_RESULT_TOO_MANY_GEOFENCES = -3;
    private static final int FLP_RESULT_ID_EXISTS = -4;
    private static final int FLP_RESULT_ID_UNKNOWN = -5;
    private static final int FLP_RESULT_INVALID_GEOFENCE_TRANSITION = -6;

    // FlpHal monitor status codes, they must be equal to the ones in fused_location.h
    private static final int FLP_GEOFENCE_MONITOR_STATUS_UNAVAILABLE = 1<<0;
    private static final int FLP_GEOFENCE_MONITOR_STATUS_AVAILABLE = 1<<1;

    public static FlpHardwareProvider getInstance(Context context) {
        if (sSingletonInstance == null) {
            sSingletonInstance = new FlpHardwareProvider(context);
            sSingletonInstance.nativeInit();
        }

        return sSingletonInstance;
    }

    private FlpHardwareProvider(Context context) {
        mContext = context;

        // register for listening for passive provider data
        LocationManager manager = (LocationManager) mContext.getSystemService(
                Context.LOCATION_SERVICE);
        final long minTime = 0;
        final float minDistance = 0;
        final boolean oneShot = false;
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                LocationManager.PASSIVE_PROVIDER,
                minTime,
                minDistance,
                oneShot);
        // Don't keep track of this request since it's done on behalf of other clients
        // (which are kept track of separately).
        request.setHideFromAppOps(true);
        manager.requestLocationUpdates(
                request,
                new NetworkLocationListener(),
                Looper.myLooper());
    }

    public static boolean isSupported() {
        return nativeIsSupported();
    }

    /**
     * Private callback functions used by FLP HAL.
     */
    // FlpCallbacks members
    private void onLocationReport(Location[] locations) {
        for (Location location : locations) {
            location.setProvider(LocationManager.FUSED_PROVIDER);
            // set the elapsed time-stamp just as GPS provider does
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }

        IFusedLocationHardwareSink sink;
        synchronized (mLocationSinkLock) {
            sink = mLocationSink;
        }
        try {
            if (sink != null) {
                sink.onLocationAvailable(locations);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling onLocationAvailable");
        }
    }

    private void onBatchingCapabilities(int capabilities) {
        synchronized (mLocationSinkLock) {
            mHaveBatchingCapabilities = true;
            mBatchingCapabilities = capabilities;
        }

        maybeSendCapabilities();

        if (mGeofenceHardwareSink != null) {
            mGeofenceHardwareSink.setVersion(getVersion());
        }
    }

    private void onBatchingStatus(int status) {
        IFusedLocationHardwareSink sink;
        synchronized (mLocationSinkLock) {
            sink = mLocationSink;
        }
        try {
            if (sink != null) {
                sink.onStatusChanged(status);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling onBatchingStatus");
        }
    }

    // Returns the current version of the FLP HAL.  This depends both on the version of the
    // structure returned by the hardware layer, and whether or not we've received the
    // capabilities callback on initialization.  Assume original version until we get
    // the new initialization callback.
    private int getVersion() {
        synchronized (mLocationSinkLock) {
            if (mHaveBatchingCapabilities) {
                return mVersion;
            }
        }
        return 1;
    }

    private void setVersion(int version) {
        mVersion = version;
        if (mGeofenceHardwareSink != null) {
            mGeofenceHardwareSink.setVersion(getVersion());
        }
    }

    private void maybeSendCapabilities() {
        IFusedLocationHardwareSink sink;
        boolean haveBatchingCapabilities;
        int batchingCapabilities;
        synchronized (mLocationSinkLock) {
            sink = mLocationSink;
            haveBatchingCapabilities = mHaveBatchingCapabilities;
            batchingCapabilities = mBatchingCapabilities;
        }
        try {
            if (sink != null && haveBatchingCapabilities) {
                sink.onCapabilities(batchingCapabilities);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling onLocationAvailable");
        }
    }

    // FlpDiagnosticCallbacks members
    private void onDataReport(String data) {
        IFusedLocationHardwareSink sink;
        synchronized (mLocationSinkLock) {
            sink = mLocationSink;
        }
        try {
            if (mLocationSink != null) {
                sink.onDiagnosticDataAvailable(data);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling onDiagnosticDataAvailable");
        }
    }

    // FlpGeofenceCallbacks members
    private void onGeofenceTransition(
            int geofenceId,
            Location location,
            int transition,
            long timestamp,
            int sourcesUsed) {
        // the transition Id does not require translation because the values in fused_location.h
        // and GeofenceHardware are in sync
        getGeofenceHardwareSink().reportGeofenceTransition(
                geofenceId,
                updateLocationInformation(location),
                transition,
                timestamp,
                GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE,
                sourcesUsed);
    }

    private void onGeofenceMonitorStatus(int status, int source, Location location) {
        // allow the location to be optional in this event
        Location updatedLocation = null;
        if(location != null) {
            updatedLocation = updateLocationInformation(location);
        }

        int monitorStatus;
        switch (status) {
            case FLP_GEOFENCE_MONITOR_STATUS_UNAVAILABLE:
                monitorStatus = GeofenceHardware.MONITOR_CURRENTLY_UNAVAILABLE;
                break;
            case FLP_GEOFENCE_MONITOR_STATUS_AVAILABLE:
                monitorStatus = GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE;
                break;
            default:
                Log.e(TAG, "Invalid FlpHal Geofence monitor status: " + status);
                monitorStatus = GeofenceHardware.MONITOR_CURRENTLY_UNAVAILABLE;
                break;
        }

        getGeofenceHardwareSink().reportGeofenceMonitorStatus(
                GeofenceHardware.MONITORING_TYPE_FUSED_HARDWARE,
                monitorStatus,
                updatedLocation,
                source);
    }

    private void onGeofenceAdd(int geofenceId, int result) {
        getGeofenceHardwareSink().reportGeofenceAddStatus(
                geofenceId,
                translateToGeofenceHardwareStatus(result));
    }

    private void onGeofenceRemove(int geofenceId, int result) {
        getGeofenceHardwareSink().reportGeofenceRemoveStatus(
                geofenceId,
                translateToGeofenceHardwareStatus(result));
    }

    private void onGeofencePause(int geofenceId, int result) {
        getGeofenceHardwareSink().reportGeofencePauseStatus(
                geofenceId,
                translateToGeofenceHardwareStatus(result));
    }

    private void onGeofenceResume(int geofenceId, int result) {
        getGeofenceHardwareSink().reportGeofenceResumeStatus(
                geofenceId,
                translateToGeofenceHardwareStatus(result));
    }

    private void onGeofencingCapabilities(int capabilities) {
        getGeofenceHardwareSink().onCapabilities(capabilities);
    }

    /**
     * Private native methods accessing FLP HAL.
     */
    static { nativeClassInit(); }

    // Core members
    private static native void nativeClassInit();
    private static native boolean nativeIsSupported();

    // FlpLocationInterface members
    private native void nativeInit();
    private native int nativeGetBatchSize();
    private native void nativeStartBatching(int requestId, FusedBatchOptions options);
    private native void nativeUpdateBatchingOptions(int requestId, FusedBatchOptions optionsObject);
    private native void nativeStopBatching(int id);
    private native void nativeRequestBatchedLocation(int lastNLocations);
    private native void nativeFlushBatchedLocations();
    private native void nativeInjectLocation(Location location);
    // TODO [Fix] sort out the lifetime of the instance
    private native void nativeCleanup();

    // FlpDiagnosticsInterface members
    private native boolean nativeIsDiagnosticSupported();
    private native void nativeInjectDiagnosticData(String data);

    // FlpDeviceContextInterface members
    private native boolean nativeIsDeviceContextSupported();
    private native void nativeInjectDeviceContext(int deviceEnabledContext);

    // FlpGeofencingInterface members
    private native boolean nativeIsGeofencingSupported();
    private native void nativeAddGeofences(
            GeofenceHardwareRequestParcelable[] geofenceRequestsArray);
    private native void nativePauseGeofence(int geofenceId);
    private native void  nativeResumeGeofence(int geofenceId, int monitorTransitions);
    private native void nativeModifyGeofenceOption(
        int geofenceId,
        int lastTransition,
        int monitorTransitions,
        int notificationResponsiveness,
        int unknownTimer,
        int sourcesToUse);
    private native void nativeRemoveGeofences(int[] geofenceIdsArray);

    /**
     * Interface implementations for services built on top of this functionality.
     */
    public static final String LOCATION = "Location";
    public static final String GEOFENCING = "Geofencing";

    public IFusedLocationHardware getLocationHardware() {
        return mLocationHardware;
    }

    public IFusedGeofenceHardware getGeofenceHardware() {
        return mGeofenceHardwareService;
    }

    private final IFusedLocationHardware mLocationHardware = new IFusedLocationHardware.Stub() {
        @Override
        public void registerSink(IFusedLocationHardwareSink eventSink) {
            synchronized (mLocationSinkLock) {
                // only one sink is allowed at the moment
                if (mLocationSink != null) {
                    Log.e(TAG, "Replacing an existing IFusedLocationHardware sink");
                }

                mLocationSink = eventSink;
            }
            maybeSendCapabilities();
        }

        @Override
        public void unregisterSink(IFusedLocationHardwareSink eventSink) {
            synchronized (mLocationSinkLock) {
                // don't throw if the sink is not registered, simply make it a no-op
                if (mLocationSink == eventSink) {
                    mLocationSink = null;
                }
            }
        }

        @Override
        public int getSupportedBatchSize() {
            return nativeGetBatchSize();
        }

        @Override
        public void startBatching(int requestId, FusedBatchOptions options) {
            nativeStartBatching(requestId, options);
        }

        @Override
        public void stopBatching(int requestId) {
            nativeStopBatching(requestId);
        }

        @Override
        public void updateBatchingOptions(int requestId, FusedBatchOptions options) {
            nativeUpdateBatchingOptions(requestId, options);
        }

        @Override
        public void requestBatchOfLocations(int batchSizeRequested) {
            nativeRequestBatchedLocation(batchSizeRequested);
        }

        @Override
        public void flushBatchedLocations() {
            if (getVersion() >= FIRST_VERSION_WITH_FLUSH_LOCATIONS) {
                nativeFlushBatchedLocations();
            } else {
                Log.wtf(TAG,
                        "Tried to call flushBatchedLocations on an unsupported implementation");
            }
        }

        @Override
        public boolean supportsDiagnosticDataInjection() {
            return nativeIsDiagnosticSupported();
        }

        @Override
        public void injectDiagnosticData(String data) {
            nativeInjectDiagnosticData(data);
        }

        @Override
        public boolean supportsDeviceContextInjection() {
            return nativeIsDeviceContextSupported();
        }

        @Override
        public void injectDeviceContext(int deviceEnabledContext) {
            nativeInjectDeviceContext(deviceEnabledContext);
        }

        @Override
        public int getVersion() {
            return FlpHardwareProvider.this.getVersion();
        }
    };

    private final IFusedGeofenceHardware mGeofenceHardwareService =
            new IFusedGeofenceHardware.Stub() {
        @Override
        public boolean isSupported() {
            return nativeIsGeofencingSupported();
        }

        @Override
        public void addGeofences(GeofenceHardwareRequestParcelable[] geofenceRequestsArray) {
            nativeAddGeofences(geofenceRequestsArray);
        }

        @Override
        public void removeGeofences(int[] geofenceIds) {
            nativeRemoveGeofences(geofenceIds);
        }

        @Override
        public void pauseMonitoringGeofence(int geofenceId) {
            nativePauseGeofence(geofenceId);
        }

        @Override
        public void resumeMonitoringGeofence(int geofenceId, int monitorTransitions) {
            nativeResumeGeofence(geofenceId, monitorTransitions);
        }

        @Override
        public void modifyGeofenceOptions(int geofenceId,
                int lastTransition,
                int monitorTransitions,
                int notificationResponsiveness,
                int unknownTimer,
                int sourcesToUse) {
            nativeModifyGeofenceOption(
                    geofenceId,
                    lastTransition,
                    monitorTransitions,
                    notificationResponsiveness,
                    unknownTimer,
                    sourcesToUse);
        }
    };

    /**
     * Internal classes and functions used by the provider.
     */
    private final class NetworkLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            if (
                !LocationManager.NETWORK_PROVIDER.equals(location.getProvider()) ||
                !location.hasAccuracy()
                ) {
                return;
            }

            nativeInjectLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) { }

        @Override
        public void onProviderEnabled(String provider) { }

        @Override
        public void onProviderDisabled(String provider) { }
    }

    private GeofenceHardwareImpl getGeofenceHardwareSink() {
        if (mGeofenceHardwareSink == null) {
            mGeofenceHardwareSink = GeofenceHardwareImpl.getInstance(mContext);
            mGeofenceHardwareSink.setVersion(getVersion());
        }

        return mGeofenceHardwareSink;
    }

    private static int translateToGeofenceHardwareStatus(int flpHalResult) {
        switch(flpHalResult) {
            case FLP_RESULT_SUCCESS:
                return GeofenceHardware.GEOFENCE_SUCCESS;
            case FLP_RESULT_ERROR:
                return GeofenceHardware.GEOFENCE_FAILURE;
            case FLP_RESULT_INSUFFICIENT_MEMORY:
                return GeofenceHardware.GEOFENCE_ERROR_INSUFFICIENT_MEMORY;
            case FLP_RESULT_TOO_MANY_GEOFENCES:
                return GeofenceHardware.GEOFENCE_ERROR_TOO_MANY_GEOFENCES;
            case FLP_RESULT_ID_EXISTS:
                return GeofenceHardware.GEOFENCE_ERROR_ID_EXISTS;
            case FLP_RESULT_ID_UNKNOWN:
                return GeofenceHardware.GEOFENCE_ERROR_ID_UNKNOWN;
            case FLP_RESULT_INVALID_GEOFENCE_TRANSITION:
                return GeofenceHardware.GEOFENCE_ERROR_INVALID_TRANSITION;
            default:
                Log.e(TAG, String.format("Invalid FlpHal result code: %d", flpHalResult));
                return GeofenceHardware.GEOFENCE_FAILURE;
        }
    }

    private Location updateLocationInformation(Location location) {
        location.setProvider(LocationManager.FUSED_PROVIDER);
        // set the elapsed time-stamp just as GPS provider does
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }
}
