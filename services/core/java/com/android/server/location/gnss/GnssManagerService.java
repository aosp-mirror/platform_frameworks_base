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

import android.Manifest;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.hardware.location.GeofenceHardware;
import android.hardware.location.GeofenceHardwareImpl;
import android.location.FusedBatchOptions;
import android.location.GnssAntennaInfo;
import android.location.GnssCapabilities;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementRequest;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssNmeaListener;
import android.location.IGnssStatusListener;
import android.location.IGpsGeofenceHardware;
import android.location.Location;
import android.location.LocationManager;
import android.location.util.identity.CallerIdentity;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.server.FgThread;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.Injector;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;

/** Manages Gnss providers and related Gnss functions for LocationManagerService. */
public class GnssManagerService {

    public static final String TAG = "GnssManager";
    public static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ATTRIBUTION_ID = "GnssService";

    private final Context mContext;
    private final GnssNative mGnssNative;

    private final GnssLocationProvider mGnssLocationProvider;
    private final GnssStatusProvider mGnssStatusProvider;
    private final GnssNmeaProvider mGnssNmeaProvider;
    private final GnssMeasurementsProvider mGnssMeasurementsProvider;
    private final GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private final IGpsGeofenceHardware mGnssGeofenceProxy;

    private final GnssGeofenceHalModule mGeofenceHalModule;
    private final GnssCapabilitiesHalModule mCapabilitiesHalModule;
    private final GnssAntennaInfoHalModule mAntennaInfoHalModule;

    private final GnssMetrics mGnssMetrics;

    public GnssManagerService(Context context, Injector injector, GnssNative gnssNative) {
        mContext = context.createAttributionContext(ATTRIBUTION_ID);
        mGnssNative = gnssNative;

        mGnssMetrics = new GnssMetrics(mContext, IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME)));

        mGnssLocationProvider = new GnssLocationProvider(mContext, injector, mGnssNative,
                mGnssMetrics);
        mGnssStatusProvider = new GnssStatusProvider(injector, mGnssNative);
        mGnssNmeaProvider = new GnssNmeaProvider(injector, mGnssNative);
        mGnssMeasurementsProvider = new GnssMeasurementsProvider(injector, mGnssNative);
        mGnssNavigationMessageProvider = new GnssNavigationMessageProvider(injector, mGnssNative);
        mGnssGeofenceProxy = new GnssGeofenceProxy(mGnssNative);

        mGeofenceHalModule = new GnssGeofenceHalModule(mGnssNative);
        mCapabilitiesHalModule = new GnssCapabilitiesHalModule(mGnssNative);
        mAntennaInfoHalModule = new GnssAntennaInfoHalModule(mGnssNative);

        // allow gnss access to begin - we must assume that callbacks can start immediately
        mGnssNative.register();
    }

    /** Called when system is ready. */
    public void onSystemReady() {
        mGnssLocationProvider.onSystemReady();
    }

    /** Retrieve the GnssLocationProvider. */
    public GnssLocationProvider getGnssLocationProvider() {
        return mGnssLocationProvider;
    }

    /** Retrieve the IGpsGeofenceHardware. */
    public IGpsGeofenceHardware getGnssGeofenceProxy() {
        return mGnssGeofenceProxy;
    }

    /**
     * Get year of GNSS hardware.
     */
    public int getGnssYearOfHardware() {
        return mGnssNative.getHardwareYear();
    }

    /**
     * Get model name of GNSS hardware.
     */
    @Nullable
    public String getGnssHardwareModelName() {
        return mGnssNative.getHardwareModelName();
    }

    /**
     * Get GNSS hardware capabilities.
     */
    public GnssCapabilities getGnssCapabilities() {
        return mGnssNative.getCapabilities();
    }

    /**
     * Get GNSS antenna information.
     */
    public @Nullable List<GnssAntennaInfo> getGnssAntennaInfos() {
        return mAntennaInfoHalModule.getAntennaInfos();
    }

    /**
     * Get size of GNSS batch (GNSS location results are batched together for power savings).
     */
    public int getGnssBatchSize() {
        return mGnssLocationProvider.getBatchSize();
    }

    /**
     * Registers listener for GNSS status changes.
     */
    public void registerGnssStatusCallback(IGnssStatusListener listener, String packageName,
            @Nullable String attributionTag) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        mGnssStatusProvider.addListener(identity, listener);
    }

    /**
     * Unregisters listener for GNSS status changes.
     */
    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        mGnssStatusProvider.removeListener(listener);
    }

    /**
     * Registers listener for GNSS NMEA messages.
     */
    public void registerGnssNmeaCallback(IGnssNmeaListener listener, String packageName,
            @Nullable String attributionTag) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        mGnssNmeaProvider.addListener(identity, listener);
    }

    /**
     * Unregisters listener for GNSS NMEA messages.
     */
    public void unregisterGnssNmeaCallback(IGnssNmeaListener listener) {
        mGnssNmeaProvider.removeListener(listener);
    }

    /**
     * Adds a GNSS measurements listener.
     */
    public void addGnssMeasurementsListener(GnssMeasurementRequest request,
            IGnssMeasurementsListener listener, String packageName,
            @Nullable String attributionTag) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);
        if (request.isCorrelationVectorOutputsEnabled()) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);
        }
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
        mGnssMeasurementsProvider.addListener(request, identity, listener);
    }

    /**
     * Injects GNSS measurement corrections.
     */
    public void injectGnssMeasurementCorrections(GnssMeasurementCorrections corrections) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        if (!mGnssNative.injectMeasurementCorrections(corrections)) {
            Log.w(TAG, "failed to inject GNSS measurement corrections");
        }
    }

    /**
     * Removes a GNSS measurements listener.
     */
    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        mGnssMeasurementsProvider.removeListener(listener);
    }

    /**
     * Adds a GNSS navigation message listener.
     */
    public void addGnssNavigationMessageListener(IGnssNavigationMessageListener listener,
            String packageName, @Nullable String attributionTag) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag);
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
            mGnssLocationProvider.getNetInitiatedListener().sendNiResponse(notifId, userResponse);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Dump info for debugging.
     */
    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        if (args.length > 0 && args[0].equals("--gnssmetrics")) {
            ipw.append(mGnssMetrics.dumpGnssMetricsAsProtoString());
            return;
        }

        ipw.println("Capabilities: " + mGnssNative.getCapabilities());

        List<GnssAntennaInfo> infos = mAntennaInfoHalModule.getAntennaInfos();
        if (infos != null) {
            ipw.println("Antenna Infos: " + infos);
        }

        ipw.println("Measurements Provider:");
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

        GnssPowerStats powerStats = mGnssNative.getPowerStats();
        if (powerStats != null) {
            ipw.println("Last Power Stats:");
            ipw.increaseIndent();
            powerStats.dump(fd, ipw, args, mGnssNative.getCapabilities());
            ipw.decreaseIndent();
        }
    }

    private class GnssCapabilitiesHalModule implements GnssNative.BaseCallbacks {

        GnssCapabilitiesHalModule(GnssNative gnssNative) {
            gnssNative.addBaseCallbacks(this);
        }

        @Override
        public void onHalRestarted() {}

        @Override
        public void onCapabilitiesChanged(GnssCapabilities oldCapabilities,
                GnssCapabilities newCapabilities) {
            long ident = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent(LocationManager.ACTION_GNSS_CAPABILITIES_CHANGED)
                        .putExtra(LocationManager.EXTRA_GNSS_CAPABILITIES, newCapabilities)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private class GnssGeofenceHalModule implements GnssNative.GeofenceCallbacks {

        private GeofenceHardwareImpl mGeofenceHardwareImpl;

        GnssGeofenceHalModule(GnssNative gnssNative) {
            gnssNative.setGeofenceCallbacks(this);
        }

        private synchronized GeofenceHardwareImpl getGeofenceHardware() {
            if (mGeofenceHardwareImpl == null) {
                mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
            }
            return mGeofenceHardwareImpl;
        }

        @Override
        public void onReportGeofenceTransition(int geofenceId, Location location,
                @GeofenceTransition int transition, long timestamp) {
            FgThread.getHandler().post(() -> getGeofenceHardware().reportGeofenceTransition(
                    geofenceId, location, transition, timestamp,
                    GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                    FusedBatchOptions.SourceTechnologies.GNSS));
        }

        @Override
        public void onReportGeofenceStatus(@GeofenceAvailability int status, Location location) {
            FgThread.getHandler().post(() -> {
                int monitorStatus = GeofenceHardware.MONITOR_CURRENTLY_UNAVAILABLE;
                if (status == GEOFENCE_AVAILABILITY_AVAILABLE) {
                    monitorStatus = GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE;
                }
                getGeofenceHardware().reportGeofenceMonitorStatus(
                        GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                        monitorStatus,
                        location,
                        FusedBatchOptions.SourceTechnologies.GNSS);
            });
        }

        @Override
        public void onReportGeofenceAddStatus(int geofenceId, @GeofenceStatus int status) {
            FgThread.getHandler().post(() -> getGeofenceHardware().reportGeofenceAddStatus(
                    geofenceId, translateGeofenceStatus(status)));
        }

        @Override
        public void onReportGeofenceRemoveStatus(int geofenceId, @GeofenceStatus int status) {
            FgThread.getHandler().post(() -> getGeofenceHardware().reportGeofenceRemoveStatus(
                    geofenceId, translateGeofenceStatus(status)));
        }

        @Override
        public void onReportGeofencePauseStatus(int geofenceId, @GeofenceStatus int status) {
            FgThread.getHandler().post(() -> getGeofenceHardware().reportGeofencePauseStatus(
                    geofenceId, translateGeofenceStatus(status)));
        }

        @Override
        public void onReportGeofenceResumeStatus(int geofenceId, @GeofenceStatus int status) {
            FgThread.getHandler().post(() -> getGeofenceHardware().reportGeofenceResumeStatus(
                    geofenceId, translateGeofenceStatus(status)));
        }

        private int translateGeofenceStatus(@GeofenceStatus int status) {
            switch (status) {
                case GEOFENCE_STATUS_OPERATION_SUCCESS:
                    return GeofenceHardware.GEOFENCE_SUCCESS;
                case GEOFENCE_STATUS_ERROR_GENERIC:
                    return GeofenceHardware.GEOFENCE_FAILURE;
                case GEOFENCE_STATUS_ERROR_ID_EXISTS:
                    return GeofenceHardware.GEOFENCE_ERROR_ID_EXISTS;
                case GEOFENCE_STATUS_ERROR_INVALID_TRANSITION:
                    return GeofenceHardware.GEOFENCE_ERROR_INVALID_TRANSITION;
                case GEOFENCE_STATUS_ERROR_TOO_MANY_GEOFENCES:
                    return GeofenceHardware.GEOFENCE_ERROR_TOO_MANY_GEOFENCES;
                case GEOFENCE_STATUS_ERROR_ID_UNKNOWN:
                    return GeofenceHardware.GEOFENCE_ERROR_ID_UNKNOWN;
                default:
                    return -1;
            }
        }
    }

    private class GnssAntennaInfoHalModule implements GnssNative.BaseCallbacks,
            GnssNative.AntennaInfoCallbacks {

        private final GnssNative mGnssNative;

        private volatile @Nullable List<GnssAntennaInfo> mAntennaInfos;

        GnssAntennaInfoHalModule(GnssNative gnssNative) {
            mGnssNative = gnssNative;
            mGnssNative.addBaseCallbacks(this);
            mGnssNative.addAntennaInfoCallbacks(this);
        }

        @Nullable List<GnssAntennaInfo> getAntennaInfos() {
            return mAntennaInfos;
        }

        @Override
        public void onHalStarted() {
            mGnssNative.startAntennaInfoListening();
        }

        @Override
        public void onHalRestarted() {
            mGnssNative.startAntennaInfoListening();
        }

        @Override
        public void onReportAntennaInfo(List<GnssAntennaInfo> antennaInfos) {
            if (antennaInfos.equals(mAntennaInfos)) {
                return;
            }

            mAntennaInfos = antennaInfos;

            long ident = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent(LocationManager.ACTION_GNSS_ANTENNA_INFOS_CHANGED)
                        .putParcelableArrayListExtra(LocationManager.EXTRA_GNSS_ANTENNA_INFOS,
                                new ArrayList<>(antennaInfos))
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
}
