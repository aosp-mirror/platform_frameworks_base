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

import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.location.LocationManager.GPS_PROVIDER;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.location.GnssCapabilities;
import android.location.GnssMeasurementCorrections;
import android.location.GnssRequest;
import android.location.IBatchedLocationCallback;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.IGpsGeofenceHardware;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationManagerInternal;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.stats.location.LocationStatsEnums;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.LocationManagerServiceUtils.LinkedListener;
import com.android.server.LocationManagerServiceUtils.LinkedListenerBase;
import com.android.server.location.AppForegroundHelper;
import com.android.server.location.CallerIdentity;
import com.android.server.location.LocationUsageLogger;
import com.android.server.location.RemoteListenerHelper;
import com.android.server.location.SettingsHelper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Manages Gnss providers and related Gnss functions for LocationManagerService. */
public class GnssManagerService {

    private static final String TAG = "GnssManagerService";

    public static boolean isGnssSupported() {
        return GnssLocationProvider.isSupported();
    }

    private final Context mContext;
    private final SettingsHelper mSettingsHelper;
    private final AppForegroundHelper mAppForegroundHelper;
    private final LocationUsageLogger mLocationUsageLogger;

    private final GnssLocationProvider mGnssLocationProvider;
    private final GnssStatusListenerHelper mGnssStatusProvider;
    private final GnssMeasurementsProvider mGnssMeasurementsProvider;
    private final GnssMeasurementCorrectionsProvider mGnssMeasurementCorrectionsProvider;
    private final GnssAntennaInfoProvider mGnssAntennaInfoProvider;
    private final GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private final GnssLocationProvider.GnssSystemInfoProvider mGnssSystemInfoProvider;
    private final GnssLocationProvider.GnssMetricsProvider mGnssMetricsProvider;
    private final GnssCapabilitiesProvider mGnssCapabilitiesProvider;
    private final GnssBatchingProvider mGnssBatchingProvider;
    private final INetInitiatedListener mNetInitiatedListener;
    private final IGpsGeofenceHardware mGpsGeofenceProxy;

    @GuardedBy("mGnssMeasurementsListeners")
    private final ArrayMap<IBinder, LinkedListener<GnssRequest, IGnssMeasurementsListener>>
            mGnssMeasurementsListeners = new ArrayMap<>();

    @GuardedBy("mGnssAntennaInfoListeners")
    private final ArrayMap<IBinder,
            LinkedListener<Void, IGnssAntennaInfoListener>>
            mGnssAntennaInfoListeners = new ArrayMap<>();

    @GuardedBy("mGnssNavigationMessageListeners")
    private final ArrayMap<IBinder, LinkedListener<Void, IGnssNavigationMessageListener>>
            mGnssNavigationMessageListeners = new ArrayMap<>();

    @GuardedBy("mGnssStatusListeners")
    private final ArrayMap<IBinder, LinkedListener<Void, IGnssStatusListener>>
            mGnssStatusListeners = new ArrayMap<>();

    @GuardedBy("this")
    @Nullable private LocationManagerInternal mLocationManagerInternal;
    @GuardedBy("this")
    @Nullable private AppOpsManager mAppOpsManager;

    private final Object mGnssBatchingLock = new Object();

    @GuardedBy("mGnssBatchingLock")
    @Nullable private IBatchedLocationCallback mGnssBatchingCallback;

    @GuardedBy("mGnssBatchingLock")
    @Nullable
    private LinkedListener<Void, IBatchedLocationCallback> mGnssBatchingDeathCallback;

    @GuardedBy("mGnssBatchingLock")
    private boolean mGnssBatchingInProgress = false;

    public GnssManagerService(Context context, SettingsHelper settingsHelper,
            AppForegroundHelper appForegroundHelper, LocationUsageLogger locationUsageLogger) {
        this(context, settingsHelper, appForegroundHelper, locationUsageLogger, null);
    }

    // Can use this constructor to inject GnssLocationProvider for testing
    @VisibleForTesting
    GnssManagerService(Context context, SettingsHelper settingsHelper,
            AppForegroundHelper appForegroundHelper, LocationUsageLogger locationUsageLogger,
            GnssLocationProvider gnssLocationProvider) {
        Preconditions.checkState(isGnssSupported());

        mContext = context;
        mSettingsHelper = settingsHelper;
        mAppForegroundHelper = appForegroundHelper;
        mLocationUsageLogger = locationUsageLogger;

        if (gnssLocationProvider == null) {
            gnssLocationProvider = new GnssLocationProvider(mContext);
        }

        mGnssLocationProvider = gnssLocationProvider;
        mGnssStatusProvider = mGnssLocationProvider.getGnssStatusProvider();
        mGnssMeasurementsProvider = mGnssLocationProvider.getGnssMeasurementsProvider();
        mGnssAntennaInfoProvider = mGnssLocationProvider.getGnssAntennaInfoProvider();
        mGnssMeasurementCorrectionsProvider =
                mGnssLocationProvider.getGnssMeasurementCorrectionsProvider();
        mGnssNavigationMessageProvider = mGnssLocationProvider.getGnssNavigationMessageProvider();
        mGnssSystemInfoProvider = mGnssLocationProvider.getGnssSystemInfoProvider();
        mGnssMetricsProvider = mGnssLocationProvider.getGnssMetricsProvider();
        mGnssCapabilitiesProvider = mGnssLocationProvider.getGnssCapabilitiesProvider();
        mGnssBatchingProvider = mGnssLocationProvider.getGnssBatchingProvider();
        mNetInitiatedListener = mGnssLocationProvider.getNetInitiatedListener();
        mGpsGeofenceProxy = mGnssLocationProvider.getGpsGeofenceProxy();
    }

    /** Called when system is ready. */
    public synchronized void onSystemReady() {
        if (mLocationManagerInternal != null) {
            return;
        }

        mSettingsHelper.onSystemReady();
        mAppForegroundHelper.onSystemReady();

        mLocationManagerInternal = LocalServices.getService(LocationManagerInternal.class);
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);

        mAppForegroundHelper.addListener(this::onAppForegroundChanged);
    }

    /** Retrieve the GnssLocationProvider. */
    public GnssLocationProvider getGnssLocationProvider() {
        return mGnssLocationProvider;
    }

    /** Retrieve the IGpsGeofenceHardware. */
    public IGpsGeofenceHardware getGpsGeofenceProxy() {
        return mGpsGeofenceProxy;
    }

    /**
     * Get year of GNSS hardware.
     */
    public int getGnssYearOfHardware() {
        return mGnssSystemInfoProvider.getGnssYearOfHardware();
    }

    /**
     * Get model name of GNSS hardware.
     */
    @Nullable
    public String getGnssHardwareModelName() {
        return mGnssSystemInfoProvider.getGnssHardwareModelName();
    }

    /**
     * Get GNSS hardware capabilities. The capabilities returned are a bitfield as described in
     * {@link android.location.GnssCapabilities}.
     */
    public long getGnssCapabilities(String packageName) {
        if (!checkLocationAppOp(packageName)) {
            return GnssCapabilities.INVALID_CAPABILITIES;
        }

        return mGnssCapabilitiesProvider.getGnssCapabilities();
    }

    /**
     * Get size of GNSS batch (GNSS location results are batched together for power savings).
     */
    public int getGnssBatchSize(String packageName) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        if (!checkLocationAppOp(packageName)) {
            return 0;
        }

        synchronized (mGnssBatchingLock) {
            return mGnssBatchingProvider.getBatchSize();
        }
    }

    /**
     * Starts GNSS batch collection. GNSS positions are collected in a batch before being delivered
     * as a collection.
     */
    public boolean startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        if (!checkLocationAppOp(packageName)) {
            return false;
        }

        synchronized (mGnssBatchingLock) {
            if (mGnssBatchingInProgress) {
                // Current design does not expect multiple starts to be called repeatedly
                Log.e(TAG, "startGnssBatch unexpectedly called w/o stopping prior batch");
                stopGnssBatch();
            }

            mGnssBatchingInProgress = true;
            return mGnssBatchingProvider.start(periodNanos, wakeOnFifoFull);
        }
    }

    /**
     * Adds a GNSS batching callback for delivering GNSS location batch results.
     */
    public boolean addGnssBatchingCallback(IBatchedLocationCallback callback, String packageName,
            @Nullable String featureId, @NonNull String listenerIdentity) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        if (!checkLocationAppOp(packageName)) {
            return false;
        }

        CallerIdentity callerIdentity =
                new CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid(), packageName,
                        featureId, listenerIdentity);
        synchronized (mGnssBatchingLock) {
            mGnssBatchingCallback = callback;
            mGnssBatchingDeathCallback =
                    new LinkedListener<>(
                            /* request= */ null,
                            callback,
                            "BatchedLocationCallback",
                            callerIdentity,
                            (IBatchedLocationCallback listener) -> {
                                stopGnssBatch();
                                removeGnssBatchingCallback();
                            });

            return mGnssBatchingDeathCallback.linkToListenerDeathNotificationLocked(
                    callback.asBinder());
        }
    }

    /**
     * Force flush GNSS location results from batch.
     *
     * @param packageName name of requesting package
     */
    public void flushGnssBatch(String packageName) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        if (!checkLocationAppOp(packageName)) {
            return;
        }

        synchronized (mGnssBatchingLock) {
            mGnssBatchingProvider.flush();
        }
    }

    /**
     * Removes GNSS batching callback.
     */
    public void removeGnssBatchingCallback() {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE, null);

        synchronized (mGnssBatchingLock) {
            mGnssBatchingDeathCallback.unlinkFromListenerDeathNotificationLocked(
                    mGnssBatchingCallback.asBinder());
            mGnssBatchingCallback = null;
            mGnssBatchingDeathCallback = null;
        }
    }

    /**
     * Stop GNSS batch collection.
     */
    public boolean stopGnssBatch() {
        mContext.enforceCallingPermission(android.Manifest.permission.LOCATION_HARDWARE, null);

        synchronized (mGnssBatchingLock) {
            mGnssBatchingInProgress = false;
            return mGnssBatchingProvider.stop();
        }
    }

    private void onAppForegroundChanged(int uid, boolean foreground) {
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
        synchronized (mGnssAntennaInfoListeners) {
            updateListenersOnForegroundChangedLocked(
                    mGnssAntennaInfoListeners,
                    mGnssAntennaInfoProvider,
                    IGnssAntennaInfoListener.Stub::asInterface,
                    uid,
                    foreground);
        }
    }

    private <TRequest, TListener extends IInterface> void updateListenersOnForegroundChangedLocked(
            Map<IBinder, LinkedListener<TRequest, TListener>> gnssDataListeners,
            RemoteListenerHelper<TRequest, TListener> gnssDataProvider,
            Function<IBinder, TListener> mapBinderToListener,
            int uid,
            boolean foreground) {
        for (Map.Entry<IBinder, LinkedListener<TRequest, TListener>> entry :
                gnssDataListeners.entrySet()) {
            LinkedListener<TRequest, TListener> linkedListener = entry.getValue();
            CallerIdentity callerIdentity = linkedListener.getCallerIdentity();
            TRequest request = linkedListener.getRequest();
            if (callerIdentity.mUid != uid) {
                continue;
            }

            TListener listener = mapBinderToListener.apply(entry.getKey());
            if (foreground || isThrottlingExempt(callerIdentity)) {
                gnssDataProvider.addListener(request, listener, callerIdentity);
            } else {
                gnssDataProvider.removeListener(listener);
            }
        }
    }

    private <TListener extends IInterface, TRequest> boolean addGnssDataListenerLocked(
            @Nullable TRequest request,
            TListener listener,
            String packageName,
            @Nullable String featureId,
            @NonNull String listenerIdentifier,
            RemoteListenerHelper<TRequest, TListener> gnssDataProvider,
            ArrayMap<IBinder,
                    LinkedListener<TRequest, TListener>> gnssDataListeners,
            Consumer<TListener> binderDeathCallback) {
        mContext.enforceCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        if (!checkLocationAppOp(packageName)) {
            return false;
        }

        CallerIdentity callerIdentity = new CallerIdentity(Binder.getCallingUid(),
                Binder.getCallingPid(), packageName, featureId, listenerIdentifier);
        LinkedListener<TRequest, TListener> linkedListener = new LinkedListener<>(request, listener,
                listenerIdentifier, callerIdentity, binderDeathCallback);
        IBinder binder = listener.asBinder();
        if (!linkedListener.linkToListenerDeathNotificationLocked(binder)) {
            return false;
        }

        gnssDataListeners.put(binder, linkedListener);
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
                    mAppForegroundHelper.getImportance(callerIdentity.mUid));
        }
        if (mAppForegroundHelper.isAppForeground(callerIdentity.mUid)
                || isThrottlingExempt(callerIdentity)) {
            gnssDataProvider.addListener(request, listener, callerIdentity);
        }
        return true;
    }

    private <TRequest, TListener extends IInterface> void removeGnssDataListenerLocked(
            TListener listener,
            RemoteListenerHelper<TRequest, TListener> gnssDataProvider,
            ArrayMap<IBinder, LinkedListener<TRequest, TListener>> gnssDataListeners) {
        if (gnssDataProvider == null) {
            Log.e(
                    TAG,
                    "Can not remove GNSS data listener. GNSS data provider "
                            + "not available.");
            return;
        }

        IBinder binder = listener.asBinder();
        LinkedListener<TRequest, TListener> linkedListener =
                gnssDataListeners.remove(binder);
        if (linkedListener == null) {
            return;
        }
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
                    mAppForegroundHelper.getImportance(Binder.getCallingUid()));
        }
        linkedListener.unlinkFromListenerDeathNotificationLocked(binder);
        gnssDataProvider.removeListener(listener);
    }

    /**
     * Registers listener for GNSS status changes.
     */
    public boolean registerGnssStatusCallback(IGnssStatusListener listener, String packageName,
            @Nullable String featureId) {
        synchronized (mGnssStatusListeners) {
            return addGnssDataListenerLocked(
                    /* request= */ null,
                    listener,
                    packageName,
                    featureId,
                    "Gnss status",
                    mGnssStatusProvider,
                    mGnssStatusListeners,
                    this::unregisterGnssStatusCallback);
        }
    }

    /**
     * Unregisters listener for GNSS status changes.
     */
    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        synchronized (mGnssStatusListeners) {
            removeGnssDataListenerLocked(listener, mGnssStatusProvider, mGnssStatusListeners);
        }
    }

    /**
     * Adds a GNSS measurements listener.
     */
    public boolean addGnssMeasurementsListener(@Nullable GnssRequest request,
            IGnssMeasurementsListener listener, String packageName,
            @Nullable String featureId,
            @NonNull String listenerIdentifier) {
        if (request != null && request.isFullTracking()) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.LOCATION_HARDWARE,
                    null);
        }
        synchronized (mGnssMeasurementsListeners) {
            return addGnssDataListenerLocked(
                    request,
                    listener,
                    packageName,
                    featureId,
                    listenerIdentifier,
                    mGnssMeasurementsProvider,
                    mGnssMeasurementsListeners,
                    this::removeGnssMeasurementsListener);
        }
    }

    /**
     * Injects GNSS measurement corrections.
     */
    public void injectGnssMeasurementCorrections(
            GnssMeasurementCorrections measurementCorrections, String packageName) {
        mContext.enforceCallingPermission(Manifest.permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        if (!checkLocationAppOp(packageName)) {
            return;
        }

        mGnssMeasurementCorrectionsProvider.injectGnssMeasurementCorrections(
                measurementCorrections);
    }

    /**
     * Removes a GNSS measurements listener.
     */
    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        synchronized (mGnssMeasurementsListeners) {
            removeGnssDataListenerLocked(listener, mGnssMeasurementsProvider,
                    mGnssMeasurementsListeners);
        }
    }

    /**
     * Adds a GNSS Antenna Info listener.
     *
     * @param listener    called when GNSS antenna info is received
     * @param packageName name of requesting package
     * @return true if listener is successfully added, false otherwise
     */
    public boolean addGnssAntennaInfoListener(
            IGnssAntennaInfoListener listener, String packageName,
            @Nullable String featureId, @NonNull String listenerIdentifier) {
        synchronized (mGnssAntennaInfoListeners) {
            return addGnssDataListenerLocked(
                    /* request= */ null,
                    listener,
                    packageName,
                    featureId,
                    listenerIdentifier,
                    mGnssAntennaInfoProvider,
                    mGnssAntennaInfoListeners,
                    this::removeGnssAntennaInfoListener);
        }
    }

    /**
     * Removes a GNSS Antenna Info listener.
     *
     * @param listener called when GNSS antenna info is received
     */
    public void removeGnssAntennaInfoListener(IGnssAntennaInfoListener listener) {
        synchronized (mGnssAntennaInfoListeners) {
            removeGnssDataListenerLocked(
                    listener, mGnssAntennaInfoProvider, mGnssAntennaInfoListeners);
        }
    }

    /**
     * Adds a GNSS navigation message listener.
     */
    public boolean addGnssNavigationMessageListener(
            IGnssNavigationMessageListener listener, String packageName,
            @Nullable String featureId, @NonNull String listenerIdentifier) {
        synchronized (mGnssNavigationMessageListeners) {
            return addGnssDataListenerLocked(
                    /* request= */ null,
                    listener,
                    packageName,
                    featureId,
                    listenerIdentifier,
                    mGnssNavigationMessageProvider,
                    mGnssNavigationMessageListeners,
                    this::removeGnssNavigationMessageListener);
        }
    }

    /**
     * Removes a GNSS navigation message listener.
     */
    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        synchronized (mGnssNavigationMessageListeners) {
            removeGnssDataListenerLocked(
                    listener, mGnssNavigationMessageProvider, mGnssNavigationMessageListeners);
        }
    }

    /**
     * Send Ni Response, indicating a location request initiated by a network carrier.
     */
    public void sendNiResponse(int notifId, int userResponse) {
        try {
            mNetInitiatedListener.sendNiResponse(notifId, userResponse);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in LocationManagerService.sendNiResponse");
        }
    }

    /**
     * Report location results to GNSS batching listener.
     */
    public void onReportLocation(List<Location> locations) {
        IBatchedLocationCallback gnssBatchingCallback;
        LinkedListener<Void, IBatchedLocationCallback> gnssBatchingDeathCallback;
        synchronized (mGnssBatchingLock) {
            gnssBatchingCallback = mGnssBatchingCallback;
            gnssBatchingDeathCallback = mGnssBatchingDeathCallback;
        }

        if (gnssBatchingCallback == null || gnssBatchingDeathCallback == null) {
            return;
        }

        int userId = UserHandle.getUserId(gnssBatchingDeathCallback.getCallerIdentity().mUid);
        if (!mLocationManagerInternal.isProviderEnabledForUser(GPS_PROVIDER, userId)) {
            Log.w(TAG, "reportLocationBatch() called without user permission");
            return;
        }

        try {
            gnssBatchingCallback.onLocationBatch(locations);
        } catch (RemoteException e) {
            Log.e(TAG, "reportLocationBatch() failed", e);
        }
    }

    private boolean isThrottlingExempt(CallerIdentity callerIdentity) {
        if (callerIdentity.mUid == Process.SYSTEM_UID) {
            return true;
        }

        if (mSettingsHelper.getBackgroundThrottlePackageWhitelist().contains(
                callerIdentity.mPackageName)) {
            return true;
        }

        synchronized (this) {
            Preconditions.checkState(mLocationManagerInternal != null);
        }
        return mLocationManagerInternal.isProviderPackage(callerIdentity.mPackageName);
    }

    private boolean checkLocationAppOp(String packageName) {
        synchronized (this) {
            Preconditions.checkState(mAppOpsManager != null);
        }
        return mAppOpsManager.checkOp(OP_FINE_LOCATION, Binder.getCallingUid(), packageName)
                == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Dump info for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
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
            for (LinkedListenerBase listener : mGnssMeasurementsListeners.values()) {
                ipw.println(listener);
            }
        }
        ipw.decreaseIndent();

        ipw.println("GnssNavigationMessage Listeners:");
        ipw.increaseIndent();
        synchronized (mGnssNavigationMessageListeners) {
            for (LinkedListenerBase listener : mGnssNavigationMessageListeners.values()) {
                ipw.println(listener);
            }
        }
        ipw.decreaseIndent();

        ipw.println("GnssStatus Listeners:");
        ipw.increaseIndent();
        synchronized (mGnssStatusListeners) {
            for (LinkedListenerBase listener : mGnssStatusListeners.values()) {
                ipw.println(listener);
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
