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
import android.location.IGnssAntennaInfoListener;
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
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.server.FgThread;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.Injector;

import java.io.FileDescriptor;
import java.util.List;

/** Manages Gnss providers and related Gnss functions for LocationManagerService. */
public class GnssManagerService {

    public static final String TAG = "GnssManager";
    public static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ATTRIBUTION_ID = "GnssService";

    final Context mContext;
    private final GnssNative mGnssNative;

    private final GnssLocationProvider mGnssLocationProvider;
    private final GnssStatusProvider mGnssStatusProvider;
    private final GnssNmeaProvider mGnssNmeaProvider;
    private final GnssMeasurementsProvider mGnssMeasurementsProvider;
    private final GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private final GnssAntennaInfoProvider mGnssAntennaInfoProvider;
    private final IGpsGeofenceHardware mGnssGeofenceProxy;

    private final GnssGeofenceHalModule mGeofenceHalModule;
    private final GnssCapabilitiesHalModule mCapabilitiesHalModule;

    private final GnssMetrics mGnssMetrics;

    public GnssManagerService(Context context, Injector injector, GnssNative gnssNative) {
        mContext = context.createAttributionContext(ATTRIBUTION_ID);
        mGnssNative = gnssNative;

        mGnssMetrics = new GnssMetrics(mContext, IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME)), mGnssNative);

        mGnssLocationProvider = new GnssLocationProvider(mContext, mGnssNative, mGnssMetrics);
        mGnssStatusProvider = new GnssStatusProvider(injector, mGnssNative);
        mGnssNmeaProvider = new GnssNmeaProvider(injector, mGnssNative);
        mGnssMeasurementsProvider = new GnssMeasurementsProvider(injector, mGnssNative);
        mGnssNavigationMessageProvider = new GnssNavigationMessageProvider(injector, mGnssNative);
        mGnssAntennaInfoProvider = new GnssAntennaInfoProvider(mGnssNative);
        mGnssGeofenceProxy = new GnssGeofenceProxy(mGnssNative);

        mGeofenceHalModule = new GnssGeofenceHalModule(mGnssNative);
        mCapabilitiesHalModule = new GnssCapabilitiesHalModule(mGnssNative);

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

    /**
     * Set whether the GnssLocationProvider is suspended on the device. This method was added to
     * help support power management use cases on automotive devices.
     */
    public void setAutomotiveGnssSuspended(boolean suspended) {
        mGnssLocationProvider.setAutomotiveGnssSuspended(suspended);
    }

    /**
     * Return whether the GnssLocationProvider is suspended or not. This method was added to
     * help support power management use cases on automotive devices.
     */
    public boolean isAutomotiveGnssSuspended() {
        return mGnssLocationProvider.isAutomotiveGnssSuspended();
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
        return mGnssAntennaInfoProvider.getAntennaInfos();
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
            @Nullable String attributionTag, String listenerId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
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
            @Nullable String attributionTag, String listenerId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
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
            @Nullable String attributionTag, String listenerId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);
        if (request.isCorrelationVectorOutputsEnabled()) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE, null);
        }
        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
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
            String packageName, @Nullable String attributionTag, String listenerId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION, null);

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
        mGnssNavigationMessageProvider.addListener(identity, listener);
    }

    /**
     * Removes a GNSS navigation message listener.
     */
    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        mGnssNavigationMessageProvider.removeListener(listener);
    }

    /**
     * Adds a GNSS antenna info listener.
     */
    public void addGnssAntennaInfoListener(IGnssAntennaInfoListener listener, String packageName,
            @Nullable String attributionTag, String listenerId) {

        CallerIdentity identity = CallerIdentity.fromBinder(mContext, packageName, attributionTag,
                listenerId);
        mGnssAntennaInfoProvider.addListener(identity, listener);
    }

    /**
     * Removes a GNSS antenna info listener.
     */
    public void removeGnssAntennaInfoListener(IGnssAntennaInfoListener listener) {
        mGnssAntennaInfoProvider.removeListener(listener);
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
        ipw.println("GNSS Hardware Model Name: " + getGnssHardwareModelName());

        if (mGnssStatusProvider.isSupported()) {
            ipw.println("Status Provider:");
            ipw.increaseIndent();
            mGnssStatusProvider.dump(fd, ipw, args);
            ipw.decreaseIndent();
        }

        if (mGnssMeasurementsProvider.isSupported()) {
            ipw.println("Measurements Provider:");
            ipw.increaseIndent();
            mGnssMeasurementsProvider.dump(fd, ipw, args);
            ipw.decreaseIndent();
        }

        if (mGnssNavigationMessageProvider.isSupported()) {
            ipw.println("Navigation Message Provider:");
            ipw.increaseIndent();
            mGnssNavigationMessageProvider.dump(fd, ipw, args);
            ipw.decreaseIndent();
        }

        if (mGnssAntennaInfoProvider.isSupported()) {
            ipw.println("Antenna Info Provider:");
            ipw.increaseIndent();
            ipw.println("Antenna Infos: " + mGnssAntennaInfoProvider.getAntennaInfos());
            mGnssAntennaInfoProvider.dump(fd, ipw, args);
            ipw.decreaseIndent();
        }

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
            final long ident = Binder.clearCallingIdentity();
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
}
