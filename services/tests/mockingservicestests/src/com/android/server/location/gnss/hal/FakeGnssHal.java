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

package com.android.server.location.gnss.hal;

import static com.android.server.location.gnss.hal.GnssNative.GNSS_LOCATION_HAS_ALTITUDE;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_LOCATION_HAS_BEARING;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_LOCATION_HAS_BEARING_ACCURACY;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_LOCATION_HAS_HORIZONTAL_ACCURACY;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_LOCATION_HAS_LAT_LONG;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_LOCATION_HAS_SPEED;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_LOCATION_HAS_SPEED_ACCURACY;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_LOCATION_HAS_VERTICAL_ACCURACY;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_REALTIME_HAS_TIMESTAMP_NS;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_REALTIME_HAS_TIME_UNCERTAINTY_NS;
import static com.android.server.location.gnss.hal.GnssNative.GeofenceCallbacks.GEOFENCE_STATUS_ERROR_ID_EXISTS;
import static com.android.server.location.gnss.hal.GnssNative.GeofenceCallbacks.GEOFENCE_STATUS_ERROR_ID_UNKNOWN;
import static com.android.server.location.gnss.hal.GnssNative.GeofenceCallbacks.GEOFENCE_STATUS_OPERATION_SUCCESS;
import static com.android.server.location.gnss.hal.GnssNative.GeofenceCallbacks.GEOFENCE_TRANSITION_ENTERED;
import static com.android.server.location.gnss.hal.GnssNative.GeofenceCallbacks.GEOFENCE_TRANSITION_EXITED;

import android.annotation.Nullable;
import android.location.GnssAntennaInfo;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.Location;

import com.android.server.location.gnss.GnssPowerStats;
import com.android.server.location.gnss.hal.GnssNative.GnssLocationFlags;
import com.android.server.location.gnss.hal.GnssNative.GnssRealtimeFlags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Fake GNSS HAL for testing.
 */
public final class FakeGnssHal extends GnssNative.GnssHal {

    public static class GnssHalPositionMode {

        public final int Mode;
        public final int Recurrence;
        public final int MinInterval;
        public final int PreferredAccuracy;
        public final int PreferredTime;
        public final boolean LowPowerMode;

        GnssHalPositionMode() {
            Mode = 0;
            Recurrence = 0;
            MinInterval = 0;
            PreferredAccuracy = 0;
            PreferredTime = 0;
            LowPowerMode = false;
        }

        public GnssHalPositionMode(int mode, int recurrence, int minInterval, int preferredAccuracy,
                int preferredTime, boolean lowPowerMode) {
            Mode = mode;
            Recurrence = recurrence;
            MinInterval = minInterval;
            PreferredAccuracy = preferredAccuracy;
            PreferredTime = preferredTime;
            LowPowerMode = lowPowerMode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            GnssHalPositionMode that = (GnssHalPositionMode) o;
            return Mode == that.Mode
                    && Recurrence == that.Recurrence
                    && MinInterval == that.MinInterval
                    && PreferredAccuracy == that.PreferredAccuracy
                    && PreferredTime == that.PreferredTime
                    && LowPowerMode == that.LowPowerMode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Recurrence, MinInterval);
        }
    }

    public static class GnssHalBatchingMode {

        public final long PeriodNanos;
        public final float MinUpdateDistanceMeters;
        public final boolean WakeOnFifoFull;

        GnssHalBatchingMode() {
            PeriodNanos = 0;
            MinUpdateDistanceMeters = 0.0f;
            WakeOnFifoFull = false;
        }

        public GnssHalBatchingMode(long periodNanos, float minUpdateDistanceMeters,
                boolean wakeOnFifoFull) {
            PeriodNanos = periodNanos;
            MinUpdateDistanceMeters = minUpdateDistanceMeters;
            WakeOnFifoFull = wakeOnFifoFull;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            GnssHalBatchingMode that = (GnssHalBatchingMode) o;
            return PeriodNanos == that.PeriodNanos
                    && MinUpdateDistanceMeters == that.MinUpdateDistanceMeters
                    && WakeOnFifoFull == that.WakeOnFifoFull;
        }

        @Override
        public int hashCode() {
            return Objects.hash(PeriodNanos, MinUpdateDistanceMeters, WakeOnFifoFull);
        }
    }

    public static class GnssHalInjectedTime {

        public final long Time;
        public final long TimeReference;
        public final int Uncertainty;

        public GnssHalInjectedTime(long time, long timeReference, int uncertainty) {
            Time = time;
            TimeReference = timeReference;
            Uncertainty = uncertainty;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            GnssHalInjectedTime that = (GnssHalInjectedTime) o;
            return Time == that.Time
                    && TimeReference == that.TimeReference
                    && Uncertainty == that.Uncertainty;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Time);
        }
    }

    public static class GnssHalGeofence {

        public final int GeofenceId;
        public final Location Center;
        public final double Radius;
        public int LastTransition;
        public int MonitorTransitions;
        public final int NotificationResponsiveness;
        public final int UnknownTimer;
        public boolean Paused;

        public GnssHalGeofence(int geofenceId, double latitude, double longitude, double radius,
                int lastTransition, int monitorTransitions, int notificationResponsiveness,
                int unknownTimer, boolean paused) {
            GeofenceId = geofenceId;
            Center = new Location("");
            Center.setLatitude(latitude);
            Center.setLongitude(longitude);
            Radius = radius;
            LastTransition = lastTransition;
            MonitorTransitions = monitorTransitions;
            NotificationResponsiveness = notificationResponsiveness;
            UnknownTimer = unknownTimer;
            Paused = paused;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            GnssHalGeofence that = (GnssHalGeofence) o;
            return GeofenceId == that.GeofenceId
                    && Double.compare(that.Radius, Radius) == 0
                    && LastTransition == that.LastTransition
                    && MonitorTransitions == that.MonitorTransitions
                    && NotificationResponsiveness == that.NotificationResponsiveness
                    && UnknownTimer == that.UnknownTimer
                    && Paused == that.Paused
                    && Center.equals(that.Center);
        }

        @Override
        public int hashCode() {
            return Objects.hash(GeofenceId);
        }
    }

    private static class HalState {
        private boolean mStarted = false;
        private boolean mBatchingStarted = false;
        private boolean mNavigationMessagesStarted = false;
        private boolean mAntennaInfoListeningStarted = false;
        private boolean mMeasurementCollectionStarted = false;
        private boolean mMeasurementCollectionFullTracking = false;
        private boolean mMeasurementCollectionCorrVecOutputsEnabled = false;
        private int mMeasurementCollectionIntervalMillis = 0;
        private GnssHalPositionMode mPositionMode = new GnssHalPositionMode();
        private GnssHalBatchingMode mBatchingMode = new GnssHalBatchingMode();
        private final ArrayList<Location> mBatchedLocations = new ArrayList<>();
        private Location mInjectedLocation = null;
        private Location mInjectedBestLocation = null;
        private GnssHalInjectedTime mInjectedTime = null;
        private GnssMeasurementCorrections mInjectedMeasurementCorrections = null;
        private final HashMap<Integer, GnssHalGeofence> mGeofences = new HashMap<>();
        private GnssPowerStats mPowerStats = new GnssPowerStats(0, 0, 0, 0, 0, 0, 0, 0,
                new double[0]);
    }

    private @Nullable GnssNative mGnssNative;
    private HalState mState = new HalState();

    private boolean mIsNavigationMessageCollectionSupported = true;
    private boolean mIsAntennaInfoListeningSupported = true;
    private boolean mIsMeasurementSupported = true;
    private boolean mIsMeasurementCorrectionsSupported = true;
    private int mBatchSize = 0;
    private boolean mIsGeofencingSupported = true;
    private boolean mIsVisibilityControlSupported = true;

    public FakeGnssHal() {}

    public void restartHal() {
        mState = new HalState();
        Objects.requireNonNull(mGnssNative).restartHal();
    }

    public void setIsNavigationMessageCollectionSupported(boolean supported) {
        mIsNavigationMessageCollectionSupported = supported;
    }

    public void setIsAntennaInfoListeningSupported(boolean supported) {
        mIsAntennaInfoListeningSupported = supported;
    }

    public void setIsMeasurementSupported(boolean supported) {
        mIsMeasurementSupported = supported;
    }

    public void setIsMeasurementCorrectionsSupported(boolean supported) {
        mIsMeasurementCorrectionsSupported = supported;
    }

    public void setBatchSize(int batchSize) {
        mBatchSize = batchSize;
    }

    public void setIsGeofencingSupported(boolean supported) {
        mIsGeofencingSupported = supported;
    }

    public void setPowerStats(GnssPowerStats powerStats) {
        mState.mPowerStats = powerStats;
    }

    public void setIsVisibilityControlSupported(boolean supported) {
        mIsVisibilityControlSupported = supported;
    }

    public GnssHalPositionMode getPositionMode() {
        return mState.mPositionMode;
    }

    public void reportLocation(Location location) {
        if (mState.mStarted) {
            Objects.requireNonNull(mGnssNative).reportLocation(true, location);
        }
        if (mState.mBatchingStarted) {
            mState.mBatchedLocations.add(location);
            if (mState.mBatchedLocations.size() >= mBatchSize) {
                if (mState.mBatchingMode.WakeOnFifoFull) {
                    flushBatch();
                } else {
                    mState.mBatchedLocations.remove(0);
                }
            }
        }
        for (GnssHalGeofence geofence : mState.mGeofences.values()) {
            if (!geofence.Paused) {
                if (geofence.Center.distanceTo(location) > geofence.Radius) {
                    if (geofence.LastTransition != GEOFENCE_TRANSITION_EXITED) {
                        geofence.LastTransition = GEOFENCE_TRANSITION_EXITED;
                        if ((geofence.MonitorTransitions & GEOFENCE_TRANSITION_EXITED) != 0) {
                            Objects.requireNonNull(mGnssNative).reportGeofenceTransition(
                                    geofence.GeofenceId, location, GEOFENCE_TRANSITION_EXITED,
                                    location.getTime());
                        }
                    }
                } else {
                    if (geofence.LastTransition != GEOFENCE_TRANSITION_ENTERED) {
                        geofence.LastTransition = GEOFENCE_TRANSITION_ENTERED;
                        if ((geofence.MonitorTransitions & GEOFENCE_TRANSITION_ENTERED) != 0) {
                            Objects.requireNonNull(mGnssNative).reportGeofenceTransition(
                                    geofence.GeofenceId, location, GEOFENCE_TRANSITION_ENTERED,
                                    location.getTime());
                        }
                    }
                }
            }
        }
    }

    public void reportNavigationMessage(GnssNavigationMessage message) {
        if (mState.mNavigationMessagesStarted) {
            Objects.requireNonNull(mGnssNative).reportNavigationMessage(message);
        }
    }

    public void reportAntennaInfo(List<GnssAntennaInfo> antennaInfos) {
        if (mState.mAntennaInfoListeningStarted) {
            Objects.requireNonNull(mGnssNative).reportAntennaInfo(antennaInfos);
        }
    }

    public boolean isMeasurementCollectionFullTracking() {
        return mState.mMeasurementCollectionFullTracking;
    }

    public void reportMeasurement(GnssMeasurementsEvent event) {
        if (mState.mMeasurementCollectionStarted) {
            Objects.requireNonNull(mGnssNative).reportMeasurementData(event);
        }
    }

    public GnssHalInjectedTime getLastInjectedTime() {
        return mState.mInjectedTime;
    }

    public GnssMeasurementCorrections getLastInjectedCorrections() {
        return mState.mInjectedMeasurementCorrections;
    }

    public Collection<GnssHalGeofence> getGeofences() {
        return mState.mGeofences.values();
    }

    @Override
    protected void classInitOnce() {}

    @Override
    protected boolean isSupported() {
        return true;
    }

    @Override
    protected void initOnce(GnssNative gnssNative, boolean reinitializeGnssServiceHandle) {
        mGnssNative = Objects.requireNonNull(gnssNative);
    }

    @Override
    protected boolean init() {
        return true;
    }

    @Override
    protected void cleanup() {}

    @Override
    protected boolean start() {
        mState.mStarted = true;
        return true;
    }

    @Override
    protected boolean stop() {
        mState.mStarted = false;
        return true;
    }

    @Override
    protected boolean setPositionMode(int mode, int recurrence, int minInterval,
            int preferredAccuracy, int preferredTime, boolean lowPowerMode) {
        mState.mPositionMode = new GnssHalPositionMode(mode, recurrence, minInterval,
                preferredAccuracy, preferredTime, lowPowerMode);
        return true;
    }

    @Override
    protected String getInternalState() {
        return "DebugState";
    }

    @Override
    protected void deleteAidingData(int flags) {}

    @Override
    protected int readNmea(byte[] buffer, int bufferSize) {
        return 0;
    }

    @Override
    protected void injectLocation(@GnssLocationFlags int gnssLocationFlags, double latitude,
            double longitude, double altitude, float speed, float bearing, float horizontalAccuracy,
            float verticalAccuracy, float speedAccuracy, float bearingAccuracy, long timestamp,
            @GnssRealtimeFlags int elapsedRealtimeFlags, long elapsedRealtimeNanos,
            double elapsedRealtimeUncertaintyNanos) {
        mState.mInjectedLocation = new Location("injected");
        mState.mInjectedLocation.setLatitude(latitude);
        mState.mInjectedLocation.setLongitude(longitude);
        mState.mInjectedLocation.setAccuracy(horizontalAccuracy);
    }

    @Override
    protected void injectBestLocation(@GnssLocationFlags int gnssLocationFlags, double latitude,
            double longitude, double altitude, float speed, float bearing, float horizontalAccuracy,
            float verticalAccuracy, float speedAccuracy, float bearingAccuracy, long timestamp,
            @GnssRealtimeFlags int elapsedRealtimeFlags, long elapsedRealtimeNanos,
            double elapsedRealtimeUncertaintyNanos) {
        mState.mInjectedBestLocation = new Location("injectedBest");
        if ((gnssLocationFlags & GNSS_LOCATION_HAS_LAT_LONG) != 0) {
            mState.mInjectedBestLocation.setLatitude(latitude);
            mState.mInjectedBestLocation.setLongitude(longitude);
        }
        if ((gnssLocationFlags & GNSS_LOCATION_HAS_ALTITUDE) != 0) {
            mState.mInjectedBestLocation.setAltitude(altitude);
        }
        if ((gnssLocationFlags & GNSS_LOCATION_HAS_SPEED) != 0) {
            mState.mInjectedBestLocation.setSpeed(speed);
        }
        if ((gnssLocationFlags & GNSS_LOCATION_HAS_BEARING) != 0) {
            mState.mInjectedBestLocation.setBearing(bearing);
        }
        if ((gnssLocationFlags & GNSS_LOCATION_HAS_HORIZONTAL_ACCURACY) != 0) {
            mState.mInjectedBestLocation.setAccuracy(horizontalAccuracy);
        }
        if ((gnssLocationFlags & GNSS_LOCATION_HAS_VERTICAL_ACCURACY) != 0) {
            mState.mInjectedBestLocation.setVerticalAccuracyMeters(verticalAccuracy);
        }
        if ((gnssLocationFlags & GNSS_LOCATION_HAS_SPEED_ACCURACY) != 0) {
            mState.mInjectedBestLocation.setSpeedAccuracyMetersPerSecond(speedAccuracy);
        }
        if ((gnssLocationFlags & GNSS_LOCATION_HAS_BEARING_ACCURACY) != 0) {
            mState.mInjectedBestLocation.setBearingAccuracyDegrees(bearingAccuracy);
        }
        mState.mInjectedBestLocation.setTime(timestamp);
        if ((elapsedRealtimeFlags & GNSS_REALTIME_HAS_TIMESTAMP_NS) != 0) {
            mState.mInjectedBestLocation.setElapsedRealtimeNanos(elapsedRealtimeNanos);
        }
        if ((elapsedRealtimeFlags & GNSS_REALTIME_HAS_TIME_UNCERTAINTY_NS) != 0) {
            mState.mInjectedBestLocation.setElapsedRealtimeUncertaintyNanos(
                    elapsedRealtimeUncertaintyNanos);
        }
    }

    @Override
    protected void injectTime(long time, long timeReference, int uncertainty) {
        mState.mInjectedTime = new GnssHalInjectedTime(time, timeReference, uncertainty);
    }

    @Override
    protected boolean isNavigationMessageCollectionSupported() {
        return mIsNavigationMessageCollectionSupported;
    }

    @Override
    protected boolean startNavigationMessageCollection() {
        mState.mNavigationMessagesStarted = true;
        return true;
    }

    @Override
    protected boolean stopNavigationMessageCollection() {
        mState.mNavigationMessagesStarted = false;
        return true;
    }

    @Override
    protected boolean isAntennaInfoSupported() {
        return mIsAntennaInfoListeningSupported;
    }

    @Override
    protected boolean startAntennaInfoListening() {
        mState.mAntennaInfoListeningStarted = true;
        return true;
    }

    @Override
    protected boolean stopAntennaInfoListening() {
        mState.mAntennaInfoListeningStarted = false;
        return true;
    }

    @Override
    protected boolean isMeasurementSupported() {
        return mIsMeasurementSupported;
    }

    @Override
    protected boolean startMeasurementCollection(boolean enableFullTracking,
            boolean enableCorrVecOutputs, int intervalMillis) {
        mState.mMeasurementCollectionStarted = true;
        mState.mMeasurementCollectionFullTracking = enableFullTracking;
        mState.mMeasurementCollectionCorrVecOutputsEnabled = enableCorrVecOutputs;
        mState.mMeasurementCollectionIntervalMillis = intervalMillis;
        return true;
    }

    @Override
    protected boolean stopMeasurementCollection() {
        mState.mMeasurementCollectionStarted = false;
        mState.mMeasurementCollectionFullTracking = false;
        mState.mMeasurementCollectionCorrVecOutputsEnabled = false;
        mState.mMeasurementCollectionIntervalMillis = 0;
        return true;
    }

    @Override
    protected boolean isMeasurementCorrectionsSupported() {
        return mIsMeasurementCorrectionsSupported;
    }

    @Override
    protected boolean injectMeasurementCorrections(GnssMeasurementCorrections corrections) {
        mState.mInjectedMeasurementCorrections = corrections;
        return true;
    }

    @Override
    protected int getBatchSize() {
        return mBatchSize;
    }

    @Override
    protected boolean initBatching() {
        return true;
    }

    @Override
    protected void cleanupBatching() {}

    @Override
    protected boolean startBatch(long periodNanos, float minUpdateDistanceMeters,
            boolean wakeOnFifoFull) {
        mState.mBatchingStarted = true;
        mState.mBatchingMode = new GnssHalBatchingMode(periodNanos, minUpdateDistanceMeters,
                wakeOnFifoFull);
        return true;
    }

    @Override
    protected void flushBatch() {
        Location[] locations = mState.mBatchedLocations.toArray(new Location[0]);
        mState.mBatchedLocations.clear();
        Objects.requireNonNull(mGnssNative).reportLocationBatch(locations);
    }

    @Override
    protected void stopBatch() {
        mState.mBatchingStarted = false;
        mState.mBatchingMode = new GnssHalBatchingMode();
        mState.mBatchedLocations.clear();
    }

    @Override
    protected boolean isGeofencingSupported() {
        return mIsGeofencingSupported;
    }

    @Override
    protected boolean addGeofence(int geofenceId, double latitude, double longitude, double radius,
            int lastTransition, int monitorTransitions, int notificationResponsiveness,
            int unknownTimer) {
        if (mState.mGeofences.containsKey(geofenceId)) {
            Objects.requireNonNull(mGnssNative).reportGeofenceAddStatus(geofenceId,
                    GEOFENCE_STATUS_ERROR_ID_EXISTS);
        } else {
            mState.mGeofences.put(geofenceId,
                    new GnssHalGeofence(geofenceId, latitude, longitude, radius, lastTransition,
                            monitorTransitions, notificationResponsiveness, unknownTimer, false));
            Objects.requireNonNull(mGnssNative).reportGeofenceAddStatus(geofenceId,
                    GEOFENCE_STATUS_OPERATION_SUCCESS);
        }
        return true;
    }

    @Override
    protected boolean resumeGeofence(int geofenceId, int monitorTransitions) {
        GnssHalGeofence geofence = mState.mGeofences.get(geofenceId);
        if (geofence != null) {
            geofence.Paused = false;
            geofence.MonitorTransitions = monitorTransitions;
            Objects.requireNonNull(mGnssNative).reportGeofenceAddStatus(geofenceId,
                    GEOFENCE_STATUS_OPERATION_SUCCESS);
        } else {
            Objects.requireNonNull(mGnssNative).reportGeofenceAddStatus(geofenceId,
                    GEOFENCE_STATUS_ERROR_ID_UNKNOWN);
        }
        return true;
    }

    @Override
    protected boolean pauseGeofence(int geofenceId) {
        GnssHalGeofence geofence = mState.mGeofences.get(geofenceId);
        if (geofence != null) {
            geofence.Paused = true;
            Objects.requireNonNull(mGnssNative).reportGeofenceAddStatus(geofenceId,
                    GEOFENCE_STATUS_OPERATION_SUCCESS);
        } else {
            Objects.requireNonNull(mGnssNative).reportGeofenceAddStatus(geofenceId,
                    GEOFENCE_STATUS_ERROR_ID_UNKNOWN);
        }
        return true;
    }

    @Override
    protected boolean removeGeofence(int geofenceId) {
        if (mState.mGeofences.remove(geofenceId) != null) {
            Objects.requireNonNull(mGnssNative).reportGeofenceRemoveStatus(geofenceId,
                    GEOFENCE_STATUS_OPERATION_SUCCESS);
        } else {
            Objects.requireNonNull(mGnssNative).reportGeofenceRemoveStatus(geofenceId,
                    GEOFENCE_STATUS_ERROR_ID_UNKNOWN);
        }
        return true;
    }

    @Override
    protected boolean isGnssVisibilityControlSupported() {
        return mIsVisibilityControlSupported;
    }

    @Override
    protected void sendNiResponse(int notificationId, int userResponse) {}

    @Override
    protected void requestPowerStats() {
        Objects.requireNonNull(mGnssNative).reportGnssPowerStats(mState.mPowerStats);
    }

    @Override
    protected void setAgpsServer(int type, String hostname, int port) {}

    @Override
    protected void setAgpsSetId(int type, String setId) {}

    @Override
    protected void setAgpsReferenceLocationCellId(int type, int mcc, int mnc, int lac, long cid,
            int tac, int pcid, int arfcn) {}

    @Override
    protected boolean isPsdsSupported() {
        return true;
    }

    @Override
    protected void injectPsdsData(byte[] data, int length, int psdsType) {}
}
