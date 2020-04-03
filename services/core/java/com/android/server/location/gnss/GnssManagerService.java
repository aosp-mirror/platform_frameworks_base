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

import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.util.identity.CallerIdentity.PERMISSION_FINE;

import android.Manifest;
import android.annotation.Nullable;
import android.content.Context;
import android.location.GnssAntennaInfo;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
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
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.location.AppForegroundHelper;
import com.android.server.location.AppOpsHelper;
import com.android.server.location.LocationUsageLogger;
import com.android.server.location.SettingsHelper;
import com.android.server.location.UserInfoHelper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/** Manages Gnss providers and related Gnss functions for LocationManagerService. */
public class GnssManagerService implements GnssNative.Callbacks {

    public static final String TAG = "GnssManager";
    public static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ATTRIBUTION_ID = "GnssService";

    public static boolean isGnssSupported() {
        return GnssNative.isSupported();
    }

    private final Context mContext;
    private final SettingsHelper mSettingsHelper;
    private final AppOpsHelper mAppOpsHelper;
    private final AppForegroundHelper mAppForegroundHelper;
    private final LocationManagerInternal mLocationManagerInternal;

    private final GnssLocationProvider mGnssLocationProvider;
    private final GnssStatusProvider mGnssStatusProvider;
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

    private final Object mGnssBatchingLock = new Object();

    @GuardedBy("mGnssBatchingLock")
    @Nullable private IBatchedLocationCallback mGnssBatchingCallback;
    @GuardedBy("mGnssBatchingLock")
    @Nullable private CallerIdentity mGnssBatchingIdentity;
    @GuardedBy("mGnssBatchingLock")
    @Nullable private Binder.DeathRecipient mGnssBatchingDeathRecipient;
    @GuardedBy("mGnssBatchingLock")
    private boolean mGnssBatchingInProgress = false;

    public GnssManagerService(Context context, UserInfoHelper userInfoHelper,
            SettingsHelper settingsHelper, AppOpsHelper appOpsHelper,
            AppForegroundHelper appForegroundHelper, LocationUsageLogger locationUsageLogger) {
        this(context, userInfoHelper, settingsHelper, appOpsHelper, appForegroundHelper,
                locationUsageLogger, null);
    }

    @VisibleForTesting
    GnssManagerService(Context context, UserInfoHelper userInfoHelper,
            SettingsHelper settingsHelper, AppOpsHelper appOpsHelper,
            AppForegroundHelper appForegroundHelper, LocationUsageLogger locationUsageLogger,
            GnssLocationProvider gnssLocationProvider) {
        Preconditions.checkState(isGnssSupported());

        GnssNative.initialize();

        mContext = context.createAttributionContext(ATTRIBUTION_ID);
        mSettingsHelper = settingsHelper;
        mAppOpsHelper = appOpsHelper;
        mAppForegroundHelper = appForegroundHelper;
        mLocationManagerInternal = LocalServices.getService(LocationManagerInternal.class);

        if (gnssLocationProvider == null) {
            gnssLocationProvider = new GnssLocationProvider(mContext, userInfoHelper,
                    mSettingsHelper, mAppOpsHelper, mAppForegroundHelper, locationUsageLogger);
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

        // allow gnss access to begin - we must assume that callbacks can start immediately
        GnssNative.register(this);
    }

    /** Called when system is ready. */
    public synchronized void onSystemReady() {
        mAppOpsHelper.onSystemReady();
        mSettingsHelper.onSystemReady();
        mAppForegroundHelper.onSystemReady();

        mGnssLocationProvider.onSystemReady();
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
    public long getGnssCapabilities() {
        return mGnssCapabilitiesProvider.getGnssCapabilities();
    }

    /**
     * Get size of GNSS batch (GNSS location results are batched together for power savings).
     */
    public int getGnssBatchSize(String packageName) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        synchronized (mGnssBatchingLock) {
            return mGnssBatchingProvider.getBatchSize();
        }
    }

    /**
     * Starts GNSS batch collection. GNSS positions are collected in a batch before being delivered
     * as a collection.
     */
    public boolean startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName,
            String attributionTag) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        if (!mAppOpsHelper.checkLocationAccess(identity)) {
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
            @Nullable String attributionTag) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        identity.enforceLocationPermission(PERMISSION_FINE);

        synchronized (mGnssBatchingLock) {
            Binder.DeathRecipient deathRecipient = () -> {
                synchronized (mGnssBatchingLock) {
                    stopGnssBatch();
                    removeGnssBatchingCallback();
                }
            };

            try {
                callback.asBinder().linkToDeath(mGnssBatchingDeathRecipient, 0);
                mGnssBatchingCallback = callback;
                mGnssBatchingIdentity = identity;
                mGnssBatchingDeathRecipient = deathRecipient;
                return true;
            } catch (RemoteException e) {
                return false;
            }
        }
    }

    /**
     * Force flush GNSS location results from batch.
     *
     * @param packageName name of requesting package
     */
    public void flushGnssBatch(String packageName) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        synchronized (mGnssBatchingLock) {
            mGnssBatchingProvider.flush();
        }
    }

    /**
     * Removes GNSS batching callback.
     */
    public void removeGnssBatchingCallback() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        synchronized (mGnssBatchingLock) {
            if (mGnssBatchingCallback == null) {
                return;
            }

            mGnssBatchingCallback.asBinder().unlinkToDeath(mGnssBatchingDeathRecipient, 0);
            mGnssBatchingCallback = null;
            mGnssBatchingIdentity = null;
            mGnssBatchingDeathRecipient = null;
        }
    }

    /**
     * Stop GNSS batch collection.
     */
    public boolean stopGnssBatch() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);

        synchronized (mGnssBatchingLock) {
            mGnssBatchingInProgress = false;
            return mGnssBatchingProvider.stop();
        }
    }

    /**
     * Registers listener for GNSS status changes.
     */
    public void registerGnssStatusCallback(IGnssStatusListener listener, String packageName,
            @Nullable String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        identity.enforceLocationPermission(PERMISSION_FINE);

        mGnssStatusProvider.addListener(identity, listener);
    }

    /**
     * Unregisters listener for GNSS status changes.
     */
    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        mGnssStatusProvider.removeListener(listener);
    }

    /**
     * Adds a GNSS measurements listener.
     */
    public void addGnssMeasurementsListener(GnssRequest request, IGnssMeasurementsListener listener,
            String packageName, @Nullable String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        identity.enforceLocationPermission(PERMISSION_FINE);

        if (request.isFullTracking()) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);
        }

        mGnssMeasurementsProvider.addListener(request, identity, listener);
    }

    /**
     * Injects GNSS measurement corrections.
     */
    public void injectGnssMeasurementCorrections(
            GnssMeasurementCorrections measurementCorrections, String packageName) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        mGnssMeasurementCorrectionsProvider.injectGnssMeasurementCorrections(
                measurementCorrections);
    }

    /**
     * Removes a GNSS measurements listener.
     */
    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        mGnssMeasurementsProvider.removeListener(listener);
    }

    /**
     * Adds a GNSS Antenna Info listener.
     *
     * @param listener    called when GNSS antenna info is received
     * @param packageName name of requesting package
     */
    public void addGnssAntennaInfoListener(IGnssAntennaInfoListener listener, String packageName,
            @Nullable String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        identity.enforceLocationPermission(PERMISSION_FINE);

        mGnssAntennaInfoProvider.addListener(identity, listener);
    }

    /**
     * Removes a GNSS Antenna Info listener.
     *
     * @param listener called when GNSS antenna info is received
     */
    public void removeGnssAntennaInfoListener(IGnssAntennaInfoListener listener) {
        mGnssAntennaInfoProvider.removeListener(listener);
    }

    /**
     * Adds a GNSS navigation message listener.
     */
    public void addGnssNavigationMessageListener(IGnssNavigationMessageListener listener,
            String packageName, @Nullable String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        identity.enforceLocationPermission(PERMISSION_FINE);

        mGnssNavigationMessageProvider.addListener(identity, listener);
    }

    /**
     * Removes a GNSS navigation message listener.
     */
    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        mGnssNavigationMessageProvider.removeListener(listener);
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
        IBatchedLocationCallback callback;
        CallerIdentity identity;
        synchronized (mGnssBatchingLock) {
            callback = mGnssBatchingCallback;
            identity = mGnssBatchingIdentity;
        }

        if (callback == null || identity == null) {
            return;
        }

        if (!mLocationManagerInternal.isProviderEnabledForUser(GPS_PROVIDER, identity.userId)) {
            Log.w(TAG, "reportLocationBatch() called without user permission");
            return;
        }

        try {
            callback.onLocationBatch(locations);
        } catch (RemoteException e) {
            // ignore
        }
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

        ipw.println("Antenna Info Provider:");
        ipw.increaseIndent();
        mGnssAntennaInfoProvider.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println("Measurement Provider:");
        ipw.increaseIndent();
        mGnssMeasurementsProvider.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println("Navigation Message Provider:");
        ipw.increaseIndent();
        mGnssNavigationMessageProvider.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println("Status Provider:");
        ipw.increaseIndent();
        mGnssStatusProvider.dump(fd, ipw, args);
        ipw.decreaseIndent();

        synchronized (mGnssBatchingLock) {
            if (mGnssBatchingInProgress) {
                ipw.println("GNSS batching in progress");
            }
        }
    }

    // all native callbacks - to be funneled to various locations as appropriate

    @Override
    public void reportLocation(boolean hasLatLong, Location location) {
        mGnssLocationProvider.reportLocation(hasLatLong, location);
    }

    @Override
    public void reportStatus(int status) {
        mGnssLocationProvider.reportStatus(status);
    }

    @Override
    public void reportSvStatus(int svCount, int[] svidWithFlags, float[] cn0DbHzs,
            float[] elevations, float[] azimuths, float[] carrierFrequencies,
            float[] basebandCn0DbHzs) {
        mGnssLocationProvider.reportSvStatus(svCount, svidWithFlags, cn0DbHzs, elevations, azimuths,
                carrierFrequencies, basebandCn0DbHzs);
    }

    @Override
    public void reportAGpsStatus(int agpsType, int agpsStatus, byte[] suplIpAddr) {
        mGnssLocationProvider.reportAGpsStatus(agpsType, agpsStatus, suplIpAddr);
    }

    @Override
    public void reportNmea(long timestamp) {
        mGnssLocationProvider.reportNmea(timestamp);
    }

    @Override
    public void reportMeasurementData(GnssMeasurementsEvent event) {
        mGnssLocationProvider.reportMeasurementData(event);
    }

    @Override
    public void reportAntennaInfo(List<GnssAntennaInfo> antennaInfos) {
        mGnssLocationProvider.reportAntennaInfo(antennaInfos);
    }

    @Override
    public void reportNavigationMessage(GnssNavigationMessage event) {
        mGnssLocationProvider.reportNavigationMessage(event);
    }

    @Override
    public void setTopHalCapabilities(int topHalCapabilities) {
        mGnssLocationProvider.setTopHalCapabilities(topHalCapabilities);
    }

    @Override
    public void setSubHalMeasurementCorrectionsCapabilities(int subHalCapabilities) {
        mGnssLocationProvider.setSubHalMeasurementCorrectionsCapabilities(subHalCapabilities);
    }

    @Override
    public void setGnssYearOfHardware(int yearOfHardware) {
        mGnssLocationProvider.setGnssYearOfHardware(yearOfHardware);
    }

    @Override
    public void setGnssHardwareModelName(String modelName) {
        mGnssLocationProvider.setGnssHardwareModelName(modelName);
    }

    @Override
    public void reportGnssServiceRestarted() {
        mGnssLocationProvider.reportGnssServiceRestarted();
    }

    @Override
    public void reportLocationBatch(Location[] locationArray) {
        mGnssLocationProvider.reportLocationBatch(locationArray);
    }

    @Override
    public void psdsDownloadRequest() {
        mGnssLocationProvider.psdsDownloadRequest();
    }

    @Override
    public void reportGeofenceTransition(int geofenceId, Location location, int transition,
            long transitionTimestamp) {
        mGnssLocationProvider.reportGeofenceTransition(geofenceId, location, transition,
                transitionTimestamp);
    }

    @Override
    public void reportGeofenceStatus(int status, Location location) {
        mGnssLocationProvider.reportGeofenceStatus(status, location);
    }

    @Override
    public void reportGeofenceAddStatus(int geofenceId, int status) {
        mGnssLocationProvider.reportGeofenceAddStatus(geofenceId, status);
    }

    @Override
    public void reportGeofenceRemoveStatus(int geofenceId, int status) {
        mGnssLocationProvider.reportGeofenceRemoveStatus(geofenceId, status);
    }

    @Override
    public void reportGeofencePauseStatus(int geofenceId, int status) {
        mGnssLocationProvider.reportGeofencePauseStatus(geofenceId, status);
    }

    @Override
    public void reportGeofenceResumeStatus(int geofenceId, int status) {
        mGnssLocationProvider.reportGeofenceResumeStatus(geofenceId, status);
    }

    @Override
    public void reportNiNotification(int notificationId, int niType, int notifyFlags,
            int timeout, int defaultResponse, String requestorId, String text,
            int requestorIdEncoding, int textEncoding) {
        mGnssLocationProvider.reportNiNotification(notificationId, niType, notifyFlags, timeout,
                defaultResponse, requestorId, text, requestorIdEncoding, textEncoding);
    }

    @Override
    public void requestSetID(int flags) {
        mGnssLocationProvider.requestSetID(flags);
    }

    @Override
    public void requestLocation(boolean independentFromGnss, boolean isUserEmergency) {
        mGnssLocationProvider.requestLocation(independentFromGnss, isUserEmergency);
    }

    @Override
    public void requestUtcTime() {
        mGnssLocationProvider.requestUtcTime();
    }

    @Override
    public void requestRefLocation() {
        mGnssLocationProvider.requestRefLocation();
    }

    @Override
    public void reportNfwNotification(String proxyAppPackageName, byte protocolStack,
            String otherProtocolStackName, byte requestor, String requestorId,
            byte responseType, boolean inEmergencyMode, boolean isCachedLocation) {
        mGnssLocationProvider.reportNfwNotification(proxyAppPackageName, protocolStack,
                otherProtocolStackName, requestor, requestorId, responseType, inEmergencyMode,
                isCachedLocation);
    }

    @Override
    public boolean isInEmergencySession() {
        return mGnssLocationProvider.isInEmergencySession();
    }
}
