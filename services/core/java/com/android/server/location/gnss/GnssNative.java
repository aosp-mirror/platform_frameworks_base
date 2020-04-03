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

import android.location.GnssAntennaInfo;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.Location;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Entry point for all GNSS native callbacks, and responsible for initializing the GNSS HAL.
 */
class GnssNative {

    interface Callbacks {
        void reportLocation(boolean hasLatLong, Location location);
        void reportStatus(int status);
        void reportSvStatus(int svCount, int[] svidWithFlags, float[] cn0DbHzs,
                float[] elevations, float[] azimuths, float[] carrierFrequencies,
                float[] basebandCn0DbHzs);
        void reportAGpsStatus(int agpsType, int agpsStatus, byte[] suplIpAddr);
        void reportNmea(long timestamp);
        void reportMeasurementData(GnssMeasurementsEvent event);
        void reportAntennaInfo(List<GnssAntennaInfo> antennaInfos);
        void reportNavigationMessage(GnssNavigationMessage event);
        void setTopHalCapabilities(int topHalCapabilities);
        void setSubHalMeasurementCorrectionsCapabilities(int subHalCapabilities);
        void setGnssYearOfHardware(int yearOfHardware);
        void setGnssHardwareModelName(String modelName);
        void reportGnssServiceRestarted();
        void reportLocationBatch(Location[] locationArray);
        void psdsDownloadRequest();
        void reportGeofenceTransition(int geofenceId, Location location, int transition,
                long transitionTimestamp);
        void reportGeofenceStatus(int status, Location location);
        void reportGeofenceAddStatus(int geofenceId, int status);
        void reportGeofenceRemoveStatus(int geofenceId, int status);
        void reportGeofencePauseStatus(int geofenceId, int status);
        void reportGeofenceResumeStatus(int geofenceId, int status);
        void reportNiNotification(
                int notificationId,
                int niType,
                int notifyFlags,
                int timeout,
                int defaultResponse,
                String requestorId,
                String text,
                int requestorIdEncoding,
                int textEncoding
        );
        void requestSetID(int flags);
        void requestLocation(boolean independentFromGnss, boolean isUserEmergency);
        void requestUtcTime();
        void requestRefLocation();
        void reportNfwNotification(String proxyAppPackageName, byte protocolStack,
                String otherProtocolStackName, byte requestor, String requestorId,
                byte responseType, boolean inEmergencyMode, boolean isCachedLocation);
        boolean isInEmergencySession();
    }

    /**
     * Indicates that this method is a native entry point. Useful purely for IDEs which can
     * understand entry points, and thus eliminate incorrect warnings about methods not used.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    private @interface NativeEntryPoint {}

    @GuardedBy("GnssNative.class")
    private static boolean sInitialized;

    @GuardedBy("GnssNative.class")
    private static GnssNativeInitNative sInitNative = new GnssNativeInitNative();

    @GuardedBy("GnssNative.class")
    private static GnssNative sInstance;

    @VisibleForTesting
    public static synchronized void setInitNativeForTest(GnssNativeInitNative initNative) {
        sInitNative = initNative;
    }

    public static synchronized boolean isSupported() {
        initialize();
        return sInitNative.isSupported();
    }

    static synchronized void initialize() {
        if (!sInitialized) {
            sInitNative.classInitOnce();
            sInitialized = true;
        }
    }

    @VisibleForTesting
    public static synchronized void resetCallbacksForTest() {
        sInstance = null;
    }

    static synchronized void register(Callbacks callbacks) {
        Preconditions.checkState(sInstance == null);
        initialize();
        sInstance = new GnssNative(callbacks);
    }

    private final Callbacks mCallbacks;

    private GnssNative(Callbacks callbacks) {
        mCallbacks = callbacks;
        sInitNative.initOnce(this, false);
    }

    @NativeEntryPoint
    private void reportLocation(boolean hasLatLong, Location location) {
        mCallbacks.reportLocation(hasLatLong, location);
    }

    @NativeEntryPoint
    private void reportStatus(int status) {
        mCallbacks.reportStatus(status);
    }

    @NativeEntryPoint
    private void reportSvStatus(int svCount, int[] svidWithFlags, float[] cn0DbHzs,
            float[] elevations, float[] azimuths, float[] carrierFrequencies,
            float[] basebandCn0DbHzs) {
        mCallbacks.reportSvStatus(svCount, svidWithFlags, cn0DbHzs, elevations, azimuths,
                carrierFrequencies, basebandCn0DbHzs);
    }

    @NativeEntryPoint
    private void reportAGpsStatus(int agpsType, int agpsStatus, byte[] suplIpAddr) {
        mCallbacks.reportAGpsStatus(agpsType, agpsStatus, suplIpAddr);
    }

    @NativeEntryPoint
    private void reportNmea(long timestamp) {
        mCallbacks.reportNmea(timestamp);
    }

    @NativeEntryPoint
    private void reportMeasurementData(GnssMeasurementsEvent event) {
        mCallbacks.reportMeasurementData(event);
    }

    @NativeEntryPoint
    private void reportAntennaInfo(List<GnssAntennaInfo> antennaInfos) {
        mCallbacks.reportAntennaInfo(antennaInfos);
    }

    @NativeEntryPoint
    private void reportNavigationMessage(GnssNavigationMessage event) {
        mCallbacks.reportNavigationMessage(event);
    }

    @NativeEntryPoint
    private void setTopHalCapabilities(int topHalCapabilities) {
        mCallbacks.setTopHalCapabilities(topHalCapabilities);
    }

    @NativeEntryPoint
    private void setSubHalMeasurementCorrectionsCapabilities(int subHalCapabilities) {
        mCallbacks.setSubHalMeasurementCorrectionsCapabilities(subHalCapabilities);
    }

    @NativeEntryPoint
    private void setGnssYearOfHardware(int yearOfHardware) {
        mCallbacks.setGnssYearOfHardware(yearOfHardware);
    }

    @NativeEntryPoint
    private void setGnssHardwareModelName(String modelName) {
        mCallbacks.setGnssHardwareModelName(modelName);
    }

    @NativeEntryPoint
    private void reportGnssServiceDied() {
        FgThread.getExecutor().execute(() -> {
            sInitNative.initOnce(GnssNative.this, true);
            mCallbacks.reportGnssServiceRestarted();
        });
    }

    @NativeEntryPoint
    private void reportLocationBatch(Location[] locationArray) {
        mCallbacks.reportLocationBatch(locationArray);
    }

    @NativeEntryPoint
    private void psdsDownloadRequest() {
        mCallbacks.psdsDownloadRequest();
    }

    @NativeEntryPoint
    private void reportGeofenceTransition(int geofenceId, Location location, int transition,
            long transitionTimestamp) {
        mCallbacks.reportGeofenceTransition(geofenceId, location, transition, transitionTimestamp);
    }

    @NativeEntryPoint
    private void reportGeofenceStatus(int status, Location location) {
        mCallbacks.reportGeofenceStatus(status, location);
    }

    @NativeEntryPoint
    private void reportGeofenceAddStatus(int geofenceId, int status) {
        mCallbacks.reportGeofenceAddStatus(geofenceId, status);
    }

    @NativeEntryPoint
    private void reportGeofenceRemoveStatus(int geofenceId, int status) {
        mCallbacks.reportGeofenceRemoveStatus(geofenceId, status);
    }

    @NativeEntryPoint
    private void reportGeofencePauseStatus(int geofenceId, int status) {
        mCallbacks.reportGeofencePauseStatus(geofenceId, status);
    }

    @NativeEntryPoint
    private void reportGeofenceResumeStatus(int geofenceId, int status) {
        mCallbacks.reportGeofenceResumeStatus(geofenceId, status);
    }

    @NativeEntryPoint
    private void reportNiNotification(int notificationId, int niType, int notifyFlags,
            int timeout, int defaultResponse, String requestorId, String text,
            int requestorIdEncoding, int textEncoding) {
        mCallbacks.reportNiNotification(notificationId, niType, notifyFlags, timeout,
                defaultResponse, requestorId, text, requestorIdEncoding, textEncoding);
    }

    @NativeEntryPoint
    private void requestSetID(int flags) {
        mCallbacks.requestSetID(flags);
    }

    @NativeEntryPoint
    private void requestLocation(boolean independentFromGnss, boolean isUserEmergency) {
        mCallbacks.requestLocation(independentFromGnss, isUserEmergency);
    }

    @NativeEntryPoint
    private void requestUtcTime() {
        mCallbacks.requestUtcTime();
    }

    @NativeEntryPoint
    private void requestRefLocation() {
        mCallbacks.requestRefLocation();
    }

    @NativeEntryPoint
    private void reportNfwNotification(String proxyAppPackageName, byte protocolStack,
            String otherProtocolStackName, byte requestor, String requestorId,
            byte responseType, boolean inEmergencyMode, boolean isCachedLocation) {
        mCallbacks.reportNfwNotification(proxyAppPackageName, protocolStack, otherProtocolStackName,
                requestor, requestorId, responseType, inEmergencyMode, isCachedLocation);
    }

    @NativeEntryPoint
    private boolean isInEmergencySession() {
        return mCallbacks.isInEmergencySession();
    }

    @VisibleForTesting
    public static class GnssNativeInitNative {

        public void classInitOnce() {
            native_class_init_once();
        }

        public boolean isSupported() {
            return native_is_supported();
        }

        public void initOnce(GnssNative gnssNative, boolean reinitializeGnssServiceHandle) {
            gnssNative.native_init_once(reinitializeGnssServiceHandle);
        }
    }

    static native void native_class_init_once();

    static native boolean native_is_supported();

    native void native_init_once(boolean reinitializeGnssServiceHandle);
}
