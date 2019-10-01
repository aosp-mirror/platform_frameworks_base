/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.location.GnssCapabilities;
import android.location.GnssMeasurementCorrections;
import android.location.IBatchedLocationCallback;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.IGpsGeofenceHardware;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import android.os.RemoteException;
import android.stats.location.LocationStatsEnums;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocationManagerServiceUtils.LinkedListener;
import com.android.server.LocationManagerServiceUtils.LinkedListenerBase;
import com.android.server.location.AbstractLocationProvider;
import com.android.server.location.CallerIdentity;
import com.android.server.location.GnssBatchingProvider;
import com.android.server.location.GnssCapabilitiesProvider;
import com.android.server.location.GnssLocationProvider;
import com.android.server.location.GnssMeasurementCorrectionsProvider;
import com.android.server.location.GnssMeasurementsProvider;
import com.android.server.location.GnssNavigationMessageProvider;
import com.android.server.location.GnssStatusListenerHelper;
import com.android.server.location.RemoteListenerHelper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Manages Gnss providers and related Gnss functions for LocationManagerService. */
public class GnssManagerService {
    private static final String TAG = "LocationManagerService";
    private static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    // Providers
    private final GnssLocationProvider mGnssLocationProvider;
    private final GnssStatusListenerHelper mGnssStatusProvider;
    private final GnssMeasurementsProvider mGnssMeasurementsProvider;
    private final GnssMeasurementCorrectionsProvider mGnssMeasurementCorrectionsProvider;
    private final GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private final GnssLocationProvider.GnssSystemInfoProvider mGnssSystemInfoProvider;
    private final GnssLocationProvider.GnssMetricsProvider mGnssMetricsProvider;
    private final GnssCapabilitiesProvider mGnssCapabilitiesProvider;
    private final GnssBatchingProvider mGnssBatchingProvider;

    private final INetInitiatedListener mNetInitiatedListener;
    private final IGpsGeofenceHardware mGpsGeofenceProxy;
    private final LocationManagerService mLocationManagerService;
    private final LocationUsageLogger mLocationUsageLogger;

    @GuardedBy("mGnssMeasurementsListeners")
    private final ArrayMap<IBinder,
            LinkedListener<IGnssMeasurementsListener>>
            mGnssMeasurementsListeners = new ArrayMap<>();

    @GuardedBy("mGnssNavigationMessageListeners")
    private final ArrayMap<
            IBinder, LinkedListener<IGnssNavigationMessageListener>>
            mGnssNavigationMessageListeners = new ArrayMap<>();

    @GuardedBy("mGnssStatusListeners")
    private final ArrayMap<IBinder, LinkedListener<IGnssStatusListener>>
            mGnssStatusListeners = new ArrayMap<>();

    @GuardedBy("mGnssBatchingLock")
    private IBatchedLocationCallback mGnssBatchingCallback;

    @GuardedBy("mGnssBatchingLock")
    private LinkedListener<IBatchedLocationCallback>
            mGnssBatchingDeathCallback;

    @GuardedBy("mGnssBatchingLock")
    private boolean mGnssBatchingInProgress = false;

    private final Object mGnssBatchingLock = new Object();
    private final Context mContext;
    private final Handler mHandler;

    public GnssManagerService(LocationManagerService locationManagerService,
            Context context,
            AbstractLocationProvider.LocationProviderManager gnssProviderManager,
            LocationUsageLogger locationUsageLogger) {
        this(locationManagerService, context, new GnssLocationProvider(context, gnssProviderManager,
                FgThread.getHandler().getLooper()), locationUsageLogger);
    }

    // Can use this constructor to inject GnssLocationProvider for testing
    @VisibleForTesting
    GnssManagerService(LocationManagerService locationManagerService,
            Context context,
            GnssLocationProvider gnssLocationProvider,
            LocationUsageLogger locationUsageLogger) {
        mContext = context;
        mHandler = FgThread.getHandler();

        mGnssLocationProvider =
                gnssLocationProvider;

        mGnssStatusProvider = mGnssLocationProvider.getGnssStatusProvider();
        mGnssMeasurementsProvider = mGnssLocationProvider.getGnssMeasurementsProvider();
        mGnssMeasurementCorrectionsProvider =
                mGnssLocationProvider.getGnssMeasurementCorrectionsProvider();
        mGnssNavigationMessageProvider = mGnssLocationProvider.getGnssNavigationMessageProvider();
        mGnssSystemInfoProvider = mGnssLocationProvider.getGnssSystemInfoProvider();
        mGnssMetricsProvider = mGnssLocationProvider.getGnssMetricsProvider();
        mGnssCapabilitiesProvider = mGnssLocationProvider.getGnssCapabilitiesProvider();
        mGnssBatchingProvider = mGnssLocationProvider.getGnssBatchingProvider();

        mNetInitiatedListener = mGnssLocationProvider.getNetInitiatedListener();
        mGpsGeofenceProxy = mGnssLocationProvider.getGpsGeofenceProxy();
        mLocationManagerService = locationManagerService;
        mLocationUsageLogger = locationUsageLogger;

        registerUidListener();
    }

    public static boolean isGnssSupported() {
        return GnssLocationProvider.isSupported();
    }

    private boolean hasGnssPermissions(String packageName) {
        mContext.enforceCallingPermission(
                Manifest.permission.ACCESS_FINE_LOCATION,
                "Fine location permission not granted.");

        int uid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            return mContext.getSystemService(
                    AppOpsManager.class).checkOp(AppOpsManager.OP_FINE_LOCATION, uid, packageName)
                    == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public GnssLocationProvider getGnssLocationProvider() {
        return mGnssLocationProvider;
    }

    public IGpsGeofenceHardware getGpsGeofenceProxy() {
        return mGpsGeofenceProxy;
    }

    /**
     * Get year of GNSS hardware.
     *
     * @return year of GNSS hardware as an int if possible, otherwise zero
     */
    public int getGnssYearOfHardware() {
        if (mGnssSystemInfoProvider != null) {
            return mGnssSystemInfoProvider.getGnssYearOfHardware();
        } else {
            return 0;
        }
    }

    /**
     * Get model name of GNSS hardware.
     *
     * @return GNSS hardware model name as a string if possible, otherwise null
     */
    public String getGnssHardwareModelName() {
        if (mGnssSystemInfoProvider != null) {
            return mGnssSystemInfoProvider.getGnssHardwareModelName();
        } else {
            return null;
        }
    }

    /**
     * Get GNSS hardware capabilities. The capabilities are described in {@link
     * android.location.GnssCapabilities} and their integer values correspond to the
     * bit positions in the returned {@code long} value.
     *
     * @param packageName name of requesting package
     * @return capabilities supported by the GNSS chipset
     */
    public long getGnssCapabilities(String packageName) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to obtain GNSS chipset capabilities.");
        if (!hasGnssPermissions(packageName) || mGnssCapabilitiesProvider == null) {
            return GnssCapabilities.INVALID_CAPABILITIES;
        }
        return mGnssCapabilitiesProvider.getGnssCapabilities();
    }

    /**
     * Get size of GNSS batch (GNSS location results are batched together for power savings).
     * Requires LOCATION_HARDWARE and GNSS permissions.
     *
     * @param packageName name of requesting package
     * @return size of the GNSS batch collection
     */
    public int getGnssBatchSize(String packageName) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "getGnssBatchSize called without GNSS permissions");
            return 0;
        }
        if (mGnssBatchingProvider == null) {
            Log.e(
                    TAG,
                    "Can not get GNSS batch size. GNSS batching provider "
                            + "not available.");
            return 0;
        }

        synchronized (mGnssBatchingLock) {
            return mGnssBatchingProvider.getBatchSize();
        }
    }

    /**
     * Starts GNSS batch collection. GNSS positions are collected in a batch before being delivered
     * as a collection.
     *
     * @param periodNanos    duration over which to collect GPS positions before delivering as a
     *                       batch
     * @param wakeOnFifoFull specifying whether to wake on full queue
     * @param packageName    name of requesting package
     * @return true of batch started successfully, false otherwise
     */
    public boolean startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "startGnssBatch called without GNSS permissions");
            return false;
        }
        if (mGnssBatchingProvider == null) {
            Log.e(
                    TAG,
                    "Can not start GNSS batching. GNSS batching provider "
                            + "not available.");
            return false;
        }

        synchronized (mGnssBatchingLock) {
            if (mGnssBatchingInProgress) {
                // Current design does not expect multiple starts to be called repeatedly
                Log.e(TAG, "startGnssBatch unexpectedly called w/o stopping prior batch");
                // Try to clean up anyway, and continue
                stopGnssBatch();
            }

            mGnssBatchingInProgress = true;
            return mGnssBatchingProvider.start(periodNanos, wakeOnFifoFull);
        }
    }

    /**
     * Adds a GNSS batching callback for delivering GNSS location batch results.
     *
     * @param callback    called when batching operation is complete to deliver GPS positions
     * @param packageName name of requesting package
     * @return true if callback is successfully added, false otherwise
     */
    public boolean addGnssBatchingCallback(IBatchedLocationCallback callback, String packageName) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "addGnssBatchingCallback called without GNSS permissions");
            return false;
        }
        if (mGnssBatchingProvider == null) {
            Log.e(
                    TAG,
                    "Can not add GNSS batching callback. GNSS batching provider "
                            + "not available.");
            return false;
        }

        CallerIdentity callerIdentity =
                new CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid(), packageName);
        synchronized (mGnssBatchingLock) {
            mGnssBatchingCallback = callback;
            mGnssBatchingDeathCallback =
                    new LocationManagerServiceUtils.LinkedListener<>(
                            callback,
                            "BatchedLocationCallback",
                            callerIdentity,
                            (IBatchedLocationCallback listener) -> {
                                stopGnssBatch();
                                removeGnssBatchingCallback();
                            });
            if (!mGnssBatchingDeathCallback.linkToListenerDeathNotificationLocked(
                    callback.asBinder())) {
                return false;
            }
            return true;
        }
    }

    /**
     * Force flush GNSS location results from batch.
     *
     * @param packageName name of requesting package
     */
    public void flushGnssBatch(String packageName) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "flushGnssBatch called without GNSS permissions");
            return;
        }

        if (mGnssBatchingProvider == null) {
            Log.e(
                    TAG,
                    "Can not flush GNSS batch. GNSS batching provider "
                            + "not available.");
            return;
        }

        synchronized (mGnssBatchingLock) {
            if (!mGnssBatchingInProgress) {
                Log.w(TAG, "flushGnssBatch called with no batch in progress");
            }
            mGnssBatchingProvider.flush();
        }
    }

    /**
     * Removes GNSS batching callback.
     */
    public void removeGnssBatchingCallback() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (mGnssBatchingProvider == null) {
            Log.e(
                    TAG,
                    "Can not add GNSS batching callback. GNSS batching provider "
                            + "not available.");
            return;
        }

        synchronized (mGnssBatchingLock) {
            mGnssBatchingDeathCallback.unlinkFromListenerDeathNotificationLocked(
                    mGnssBatchingCallback.asBinder());
            mGnssBatchingCallback = null;
            mGnssBatchingDeathCallback = null;
        }
    }

    /**
     * Stop GNSS batch collection.
     *
     * @return true if GNSS batch successfully stopped, false otherwise
     */
    public boolean stopGnssBatch() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to access hardware batching");

        if (mGnssBatchingProvider == null) {
            Log.e(
                    TAG,
                    "Can not stop GNSS batch. GNSS batching provider "
                            + "not available.");
            return false;
        }
        synchronized (mGnssBatchingLock) {
            mGnssBatchingInProgress = false;
            return mGnssBatchingProvider.stop();
        }
    }

    private void registerUidListener() {
        mContext.getSystemService(
                ActivityManager.class).addOnUidImportanceListener(
                    (uid, importance) -> {
                        // listener invoked on ui thread, move to our thread to reduce risk
                        // of blocking ui thread
                        mHandler.post(
                                () -> {
                                    onForegroundChanged(uid,
                                            LocationManagerServiceUtils.isImportanceForeground(
                                                    importance));
                                });
                    },
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
    }

    private void onForegroundChanged(int uid, boolean foreground) {
        synchronized (mGnssMeasurementsListeners) {
            updateListenersOnForegroundChangedLocked(
                    mGnssMeasurementsListeners,
                    mGnssMeasurementsProvider,
                    IGnssMeasurementsListener.Stub::asInterface,
                    uid,
                    foreground);
        }
        synchronized (mGnssNavigationMessageListeners) {
            updateListenersOnForegroundChangedLocked(
                    mGnssNavigationMessageListeners,
                    mGnssNavigationMessageProvider,
                    IGnssNavigationMessageListener.Stub::asInterface,
                    uid,
                    foreground);
        }
        synchronized (mGnssStatusListeners) {
            updateListenersOnForegroundChangedLocked(
                    mGnssStatusListeners,
                    mGnssStatusProvider,
                    IGnssStatusListener.Stub::asInterface,
                    uid,
                    foreground);
        }
    }

    private <TListener extends IInterface> void updateListenersOnForegroundChangedLocked(
            ArrayMap<IBinder, ? extends LinkedListenerBase>
                    gnssDataListeners,
            RemoteListenerHelper<TListener> gnssDataProvider,
            Function<IBinder, TListener> mapBinderToListener,
            int uid,
            boolean foreground) {
        for (Map.Entry<IBinder, ? extends LinkedListenerBase> entry :
                gnssDataListeners.entrySet()) {
            LinkedListenerBase linkedListener = entry.getValue();
            CallerIdentity callerIdentity = linkedListener.getCallerIdentity();
            if (callerIdentity.mUid != uid) {
                continue;
            }

            if (D) {
                Log.d(
                        TAG,
                        linkedListener.getListenerName()
                                + " from uid "
                                + uid
                                + " is now "
                                + LocationManagerServiceUtils.foregroundAsString(foreground));
            }

            TListener listener = mapBinderToListener.apply(entry.getKey());
            if (foreground || mLocationManagerService.isThrottlingExemptLocked(callerIdentity)) {
                gnssDataProvider.addListener(listener, callerIdentity);
            } else {
                gnssDataProvider.removeListener(listener);
            }
        }
    }

    private <TListener extends IInterface> boolean addGnssDataListenerLocked(
            TListener listener,
            String packageName,
            String listenerName,
            RemoteListenerHelper<TListener> gnssDataProvider,
            ArrayMap<IBinder,
                    LinkedListener<TListener>> gnssDataListeners,
            Consumer<TListener> binderDeathCallback) {
        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "addGnssDataListenerLocked called without GNSS permissions");
            return false;
        }

        if (gnssDataProvider == null) {
            Log.e(
                    TAG,
                    "Can not add GNSS data listener. GNSS data provider "
                            + "not available.");
            return false;
        }

        CallerIdentity callerIdentity =
                new CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid(), packageName);
        LinkedListener<TListener> linkedListener =
                new LocationManagerServiceUtils.LinkedListener<>(
                        listener, listenerName, callerIdentity, binderDeathCallback);
        IBinder binder = listener.asBinder();
        if (!linkedListener.linkToListenerDeathNotificationLocked(binder)) {
            return false;
        }

        gnssDataListeners.put(binder, linkedListener);
        long identity = Binder.clearCallingIdentity();
        try {
            if (gnssDataProvider == mGnssMeasurementsProvider
                    || gnssDataProvider == mGnssStatusProvider) {
                mLocationUsageLogger.logLocationApiUsage(
                        LocationStatsEnums.USAGE_STARTED,
                        gnssDataProvider == mGnssMeasurementsProvider
                                ? LocationStatsEnums.API_ADD_GNSS_MEASUREMENTS_LISTENER
                                : LocationStatsEnums.API_REGISTER_GNSS_STATUS_CALLBACK,
                        packageName,
                        /* LocationRequest= */ null,
                        /* hasListener= */ true,
                        /* hasIntent= */ false,
                        /* geofence= */ null,
                        LocationManagerServiceUtils.getPackageImportance(packageName,
                                mContext));
            }
            if (mLocationManagerService.isThrottlingExemptLocked(callerIdentity)
                    || LocationManagerServiceUtils.isImportanceForeground(
                    LocationManagerServiceUtils.getPackageImportance(packageName, mContext))) {
                gnssDataProvider.addListener(listener, callerIdentity);
            }
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private <TListener extends IInterface> void removeGnssDataListener(
            TListener listener,
            RemoteListenerHelper<TListener> gnssDataProvider,
            ArrayMap<IBinder,
                    LinkedListener<TListener>> gnssDataListeners) {
        if (gnssDataProvider == null) {
            Log.e(
                    TAG,
                    "Can not remove GNSS data listener. GNSS data provider "
                            + "not available.");
            return;
        }

        IBinder binder = listener.asBinder();
        LinkedListener<TListener> linkedListener =
                gnssDataListeners.remove(binder);
        if (linkedListener == null) {
            return;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            if (gnssDataProvider == mGnssMeasurementsProvider
                    || gnssDataProvider == mGnssStatusProvider) {
                mLocationUsageLogger.logLocationApiUsage(
                        LocationStatsEnums.USAGE_ENDED,
                        gnssDataProvider == mGnssMeasurementsProvider
                                ? LocationStatsEnums.API_ADD_GNSS_MEASUREMENTS_LISTENER
                                : LocationStatsEnums.API_REGISTER_GNSS_STATUS_CALLBACK,
                        linkedListener.getCallerIdentity().mPackageName,
                        /* LocationRequest= */ null,
                        /* hasListener= */ true,
                        /* hasIntent= */ false,
                        /* geofence= */ null,
                        LocationManagerServiceUtils.getPackageImportance(
                                linkedListener.getCallerIdentity().mPackageName, mContext));
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        linkedListener.unlinkFromListenerDeathNotificationLocked(binder);
        gnssDataProvider.removeListener(listener);
    }

    /**
     * Registers listener for GNSS status changes.
     *
     * @param listener    called when GNSS status changes
     * @param packageName name of requesting package
     * @return true if listener is successfully registered, false otherwise
     */
    public boolean registerGnssStatusCallback(IGnssStatusListener listener, String packageName) {
        synchronized (mGnssStatusListeners) {
            return addGnssDataListenerLocked(
                    listener,
                    packageName,
                    "GnssStatusListener",
                    mGnssStatusProvider,
                    mGnssStatusListeners,
                    this::unregisterGnssStatusCallback);
        }
    }

    /**
     * Unregisters listener for GNSS status changes.
     *
     * @param listener called when GNSS status changes
     */
    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        synchronized (mGnssStatusListeners) {
            removeGnssDataListener(listener, mGnssStatusProvider, mGnssStatusListeners);
        }
    }

    /**
     * Adds a GNSS measurements listener.
     *
     * @param listener    called when GNSS measurements are received
     * @param packageName name of requesting package
     * @return true if listener is successfully added, false otherwise
     */
    public boolean addGnssMeasurementsListener(
            IGnssMeasurementsListener listener, String packageName) {
        synchronized (mGnssMeasurementsListeners) {
            return addGnssDataListenerLocked(
                    listener,
                    packageName,
                    "GnssMeasurementsListener",
                    mGnssMeasurementsProvider,
                    mGnssMeasurementsListeners,
                    this::removeGnssMeasurementsListener);
        }
    }

    /**
     * Injects GNSS measurement corrections.
     *
     * @param measurementCorrections GNSS measurement corrections
     * @param packageName            name of requesting package
     */
    public void injectGnssMeasurementCorrections(
            GnssMeasurementCorrections measurementCorrections, String packageName) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.LOCATION_HARDWARE,
                "Location Hardware permission not granted to inject GNSS measurement corrections.");
        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "Can not inject GNSS corrections due to no permission.");
            return;
        }
        if (mGnssMeasurementCorrectionsProvider == null) {
            Log.e(
                    TAG,
                    "Can not inject GNSS corrections. GNSS measurement corrections provider "
                            + "not available.");
            return;
        }
        mGnssMeasurementCorrectionsProvider.injectGnssMeasurementCorrections(
                measurementCorrections);
    }

    /**
     * Removes a GNSS measurements listener.
     *
     * @param listener called when GNSS measurements are received
     */
    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        synchronized (mGnssMeasurementsListeners) {
            removeGnssDataListener(listener, mGnssMeasurementsProvider, mGnssMeasurementsListeners);
        }
    }

    /**
     * Adds a GNSS navigation message listener.
     *
     * @param listener    called when navigation message is received
     * @param packageName name of requesting package
     * @return true if listener is successfully added, false otherwise
     */
    public boolean addGnssNavigationMessageListener(
            IGnssNavigationMessageListener listener, String packageName) {
        synchronized (mGnssNavigationMessageListeners) {
            return addGnssDataListenerLocked(
                    listener,
                    packageName,
                    "GnssNavigationMessageListener",
                    mGnssNavigationMessageProvider,
                    mGnssNavigationMessageListeners,
                    this::removeGnssNavigationMessageListener);
        }
    }

    /**
     * Removes a GNSS navigation message listener.
     *
     * @param listener called when navigation message is received
     */
    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        removeGnssDataListener(
                listener, mGnssNavigationMessageProvider, mGnssNavigationMessageListeners);
    }

    /**
     * Send Ni Response, indicating a location request initiated by a network carrier.
     */
    public boolean sendNiResponse(int notifId, int userResponse) {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException(
                    "calling sendNiResponse from outside of the system is not allowed");
        }
        try {
            return mNetInitiatedListener.sendNiResponse(notifId, userResponse);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in LocationManagerService.sendNiResponse");
            return false;
        }
    }

    /**
     * Report location results to GNSS batching listener.
     *
     * @param locations batch of locations to report to GNSS batching callback
     */
    public void onReportLocation(List<Location> locations) {
        if (mGnssBatchingCallback == null) {
            Log.e(TAG, "reportLocationBatch() called without active Callback");
            return;
        }

        try {
            mGnssBatchingCallback.onLocationBatch(locations);
        } catch (RemoteException e) {
            Log.e(TAG, "mGnssBatchingCallback.onLocationBatch failed", e);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        if (args.length > 0 && args[0].equals("--gnssmetrics")) {
            if (mGnssMetricsProvider != null) {
                pw.append(mGnssMetricsProvider.getGnssMetricsAsProtoString());
            }
            return;
        }

        ipw.println("GnssMeasurement Listeners:");
        ipw.increaseIndent();
        synchronized (mGnssMeasurementsListeners) {
            for (LinkedListenerBase listener :
                    mGnssMeasurementsListeners
                            .values()) {
                ipw.println(listener + ": " + mLocationManagerService.isThrottlingExemptLocked(
                        listener.mCallerIdentity));
            }
        }
        ipw.decreaseIndent();

        ipw.println("GnssNavigationMessage Listeners:");
        ipw.increaseIndent();
        synchronized (mGnssNavigationMessageListeners) {
            for (LinkedListenerBase listener :
                    mGnssNavigationMessageListeners.values()) {
                ipw.println(listener + ": " + mLocationManagerService.isThrottlingExemptLocked(
                        listener.mCallerIdentity));
            }
        }
        ipw.decreaseIndent();

        ipw.println("GnssStatus Listeners:");
        ipw.increaseIndent();
        synchronized (mGnssStatusListeners) {
            for (LinkedListenerBase listener :
                    mGnssStatusListeners.values()) {
                ipw.println(listener + ": " + mLocationManagerService.isThrottlingExemptLocked(
                        listener.mCallerIdentity));
            }
        }
        ipw.decreaseIndent();

        synchronized (mGnssBatchingLock) {
            if (mGnssBatchingInProgress) {
                ipw.println("GNSS batching in progress");
            }
        }
    }
}
