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

import static com.android.server.location.gnss.GnssManagerService.TAG;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.location.GnssAntennaInfo;
import android.location.GnssCapabilities;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.Location;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.location.gnss.GnssConfiguration;
import com.android.server.location.gnss.GnssPowerStats;
import com.android.server.location.injector.EmergencyHelper;
import com.android.server.location.injector.Injector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Objects;

/**
 * Entry point for most GNSS HAL commands and callbacks.
 */
public class GnssNative {

    // IMPORTANT - must match GnssPositionMode enum in IGnss.hal
    public static final int GNSS_POSITION_MODE_STANDALONE = 0;
    public static final int GNSS_POSITION_MODE_MS_BASED = 1;
    public static final int GNSS_POSITION_MODE_MS_ASSISTED = 2;

    @IntDef(prefix = "GNSS_POSITION_MODE_", value = {GNSS_POSITION_MODE_STANDALONE,
            GNSS_POSITION_MODE_MS_BASED, GNSS_POSITION_MODE_MS_ASSISTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GnssPositionMode {}

    // IMPORTANT - must match GnssPositionRecurrence enum in IGnss.hal
    public static final int GNSS_POSITION_RECURRENCE_PERIODIC = 0;
    public static final int GNSS_POSITION_RECURRENCE_SINGLE = 1;

    @IntDef(prefix = "GNSS_POSITION_RECURRENCE_", value = {GNSS_POSITION_RECURRENCE_PERIODIC,
            GNSS_POSITION_RECURRENCE_SINGLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GnssPositionRecurrence {}

    // IMPORTANT - must match the GnssLocationFlags enum in types.hal
    public static final int GNSS_LOCATION_HAS_LAT_LONG = 1;
    public static final int GNSS_LOCATION_HAS_ALTITUDE = 2;
    public static final int GNSS_LOCATION_HAS_SPEED = 4;
    public static final int GNSS_LOCATION_HAS_BEARING = 8;
    public static final int GNSS_LOCATION_HAS_HORIZONTAL_ACCURACY = 16;
    public static final int GNSS_LOCATION_HAS_VERTICAL_ACCURACY = 32;
    public static final int GNSS_LOCATION_HAS_SPEED_ACCURACY = 64;
    public static final int GNSS_LOCATION_HAS_BEARING_ACCURACY = 128;

    @IntDef(flag = true, prefix = "GNSS_LOCATION_", value = {GNSS_LOCATION_HAS_LAT_LONG,
            GNSS_LOCATION_HAS_ALTITUDE, GNSS_LOCATION_HAS_SPEED, GNSS_LOCATION_HAS_BEARING,
            GNSS_LOCATION_HAS_HORIZONTAL_ACCURACY, GNSS_LOCATION_HAS_VERTICAL_ACCURACY,
            GNSS_LOCATION_HAS_SPEED_ACCURACY, GNSS_LOCATION_HAS_BEARING_ACCURACY})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GnssLocationFlags {}

    // IMPORTANT - must match the ElapsedRealtimeFlags enum in types.hal
    public static final int GNSS_REALTIME_HAS_TIMESTAMP_NS = 1;
    public static final int GNSS_REALTIME_HAS_TIME_UNCERTAINTY_NS = 2;

    @IntDef(flag = true, value = {GNSS_REALTIME_HAS_TIMESTAMP_NS,
            GNSS_REALTIME_HAS_TIME_UNCERTAINTY_NS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GnssRealtimeFlags {}

    // IMPORTANT - must match the GnssAidingData enum in IGnss.hal
    public static final int GNSS_AIDING_TYPE_EPHEMERIS = 0x0001;
    public static final int GNSS_AIDING_TYPE_ALMANAC = 0x0002;
    public static final int GNSS_AIDING_TYPE_POSITION = 0x0004;
    public static final int GNSS_AIDING_TYPE_TIME = 0x0008;
    public static final int GNSS_AIDING_TYPE_IONO = 0x0010;
    public static final int GNSS_AIDING_TYPE_UTC = 0x0020;
    public static final int GNSS_AIDING_TYPE_HEALTH = 0x0040;
    public static final int GNSS_AIDING_TYPE_SVDIR = 0x0080;
    public static final int GNSS_AIDING_TYPE_SVSTEER = 0x0100;
    public static final int GNSS_AIDING_TYPE_SADATA = 0x0200;
    public static final int GNSS_AIDING_TYPE_RTI = 0x0400;
    public static final int GNSS_AIDING_TYPE_CELLDB_INFO = 0x8000;
    public static final int GNSS_AIDING_TYPE_ALL = 0xFFFF;

    @IntDef(flag = true, prefix = "GNSS_AIDING_", value = {GNSS_AIDING_TYPE_EPHEMERIS,
            GNSS_AIDING_TYPE_ALMANAC, GNSS_AIDING_TYPE_POSITION, GNSS_AIDING_TYPE_TIME,
            GNSS_AIDING_TYPE_IONO, GNSS_AIDING_TYPE_UTC, GNSS_AIDING_TYPE_HEALTH,
            GNSS_AIDING_TYPE_SVDIR, GNSS_AIDING_TYPE_SVSTEER, GNSS_AIDING_TYPE_SADATA,
            GNSS_AIDING_TYPE_RTI, GNSS_AIDING_TYPE_CELLDB_INFO, GNSS_AIDING_TYPE_ALL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GnssAidingTypeFlags {}

    // IMPORTANT - must match OEM definitions, this isn't part of a hal for some reason
    public static final int AGPS_REF_LOCATION_TYPE_GSM_CELLID = 1;
    public static final int AGPS_REF_LOCATION_TYPE_UMTS_CELLID = 2;

    @IntDef(prefix = "AGPS_REF_LOCATION_TYPE_", value = {AGPS_REF_LOCATION_TYPE_GSM_CELLID,
            AGPS_REF_LOCATION_TYPE_UMTS_CELLID})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AgpsReferenceLocationType {}

    // IMPORTANT - must match OEM definitions, this isn't part of a hal for some reason
    public static final int AGPS_SETID_TYPE_NONE = 0;
    public static final int AGPS_SETID_TYPE_IMSI = 1;
    public static final int AGPS_SETID_TYPE_MSISDN = 2;

    @IntDef(prefix = "AGPS_SETID_TYPE_", value = {AGPS_SETID_TYPE_NONE, AGPS_SETID_TYPE_IMSI,
            AGPS_SETID_TYPE_MSISDN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AgpsSetIdType {}

    /** Callbacks relevant to the entire HAL. */
    public interface BaseCallbacks {
        default void onHalStarted() {}
        void onHalRestarted();
        default void onCapabilitiesChanged(GnssCapabilities oldCapabilities,
                GnssCapabilities newCapabilities) {}
    }

    /** Callbacks for status events. */
    public interface StatusCallbacks {

        // IMPORTANT - must match GnssStatusValue enum in IGnssCallback.hal
        int GNSS_STATUS_NONE = 0;
        int GNSS_STATUS_SESSION_BEGIN = 1;
        int GNSS_STATUS_SESSION_END = 2;
        int GNSS_STATUS_ENGINE_ON = 3;
        int GNSS_STATUS_ENGINE_OFF = 4;

        @IntDef(prefix = "GNSS_STATUS_", value = {GNSS_STATUS_NONE, GNSS_STATUS_SESSION_BEGIN,
                GNSS_STATUS_SESSION_END, GNSS_STATUS_ENGINE_ON, GNSS_STATUS_ENGINE_OFF})
        @Retention(RetentionPolicy.SOURCE)
        @interface GnssStatusValue {}

        void onReportStatus(@GnssStatusValue int status);
        void onReportFirstFix(int ttff);
    }

    /** Callbacks for SV status events. */
    public interface SvStatusCallbacks {
        void onReportSvStatus(GnssStatus gnssStatus);
    }

    /** Callbacks for NMEA events. */
    public interface NmeaCallbacks {
        void onReportNmea(long timestamp);
    }

    /** Callbacks for location events. */
    public interface LocationCallbacks {
        void onReportLocation(boolean hasLatLong, Location location);
        void onReportLocations(Location[] locations);
    }

    /** Callbacks for measurement events. */
    public interface MeasurementCallbacks {
        void onReportMeasurements(GnssMeasurementsEvent event);
    }

    /** Callbacks for antenna info events. */
    public interface AntennaInfoCallbacks {
        void onReportAntennaInfo(List<GnssAntennaInfo> antennaInfos);
    }

    /** Callbacks for navigation message events. */
    public interface NavigationMessageCallbacks {
        void onReportNavigationMessage(GnssNavigationMessage event);
    }

    /** Callbacks for geofence events. */
    public interface GeofenceCallbacks {

        // IMPORTANT - must match GeofenceTransition enum in IGnssGeofenceCallback.hal
        int GEOFENCE_TRANSITION_ENTERED = 1 << 0L;
        int GEOFENCE_TRANSITION_EXITED = 1 << 1L;
        int GEOFENCE_TRANSITION_UNCERTAIN = 1 << 2L;

        @IntDef(prefix = "GEOFENCE_TRANSITION_", value = {GEOFENCE_TRANSITION_ENTERED,
                GEOFENCE_TRANSITION_EXITED, GEOFENCE_TRANSITION_UNCERTAIN})
        @Retention(RetentionPolicy.SOURCE)
        @interface GeofenceTransition {}

        // IMPORTANT - must match GeofenceAvailability enum in IGnssGeofenceCallback.hal
        int GEOFENCE_AVAILABILITY_UNAVAILABLE = 1 << 0L;
        int GEOFENCE_AVAILABILITY_AVAILABLE = 1 << 1L;

        @IntDef(prefix = "GEOFENCE_AVAILABILITY_", value = {GEOFENCE_AVAILABILITY_UNAVAILABLE,
                GEOFENCE_AVAILABILITY_AVAILABLE})
        @Retention(RetentionPolicy.SOURCE)
        @interface GeofenceAvailability {}

        // IMPORTANT - must match GeofenceStatus enum in IGnssGeofenceCallback.hal
        int GEOFENCE_STATUS_OPERATION_SUCCESS = 0;
        int GEOFENCE_STATUS_ERROR_TOO_MANY_GEOFENCES = 100;
        int GEOFENCE_STATUS_ERROR_ID_EXISTS = -101;
        int GEOFENCE_STATUS_ERROR_ID_UNKNOWN = -102;
        int GEOFENCE_STATUS_ERROR_INVALID_TRANSITION = -103;
        int GEOFENCE_STATUS_ERROR_GENERIC = -149;

        @IntDef(prefix = "GEOFENCE_STATUS_", value = {GEOFENCE_STATUS_OPERATION_SUCCESS,
                GEOFENCE_STATUS_ERROR_TOO_MANY_GEOFENCES, GEOFENCE_STATUS_ERROR_ID_EXISTS,
                GEOFENCE_STATUS_ERROR_ID_UNKNOWN, GEOFENCE_STATUS_ERROR_INVALID_TRANSITION,
                GEOFENCE_STATUS_ERROR_GENERIC})
        @Retention(RetentionPolicy.SOURCE)
        @interface GeofenceStatus {}

        void onReportGeofenceTransition(int geofenceId, Location location,
                @GeofenceTransition int transition, long timestamp);
        void onReportGeofenceStatus(@GeofenceAvailability int availabilityStatus,
                Location location);
        void onReportGeofenceAddStatus(int geofenceId, @GeofenceStatus int status);
        void onReportGeofenceRemoveStatus(int geofenceId, @GeofenceStatus int status);
        void onReportGeofencePauseStatus(int geofenceId, @GeofenceStatus int status);
        void onReportGeofenceResumeStatus(int geofenceId, @GeofenceStatus int status);
    }

    /** Callbacks for the HAL requesting time. */
    public interface TimeCallbacks {
        void onRequestUtcTime();
    }

    /** Callbacks for the HAL requesting locations. */
    public interface LocationRequestCallbacks {
        void onRequestLocation(boolean independentFromGnss, boolean isUserEmergency);
        void onRequestRefLocation();
    }

    /** Callbacks for HAL requesting PSDS download. */
    public interface PsdsCallbacks {
        void onRequestPsdsDownload(int psdsType);
    }

    /** Callbacks for AGPS functionality. */
    public interface AGpsCallbacks {

        // IMPORTANT - must match OEM definitions, this isn't part of a hal for some reason
        int AGPS_REQUEST_SETID_IMSI = 1 << 0L;
        int AGPS_REQUEST_SETID_MSISDN = 1 << 1L;

        @IntDef(flag = true, prefix = "AGPS_REQUEST_SETID_", value = {AGPS_REQUEST_SETID_IMSI,
                AGPS_REQUEST_SETID_MSISDN})
        @Retention(RetentionPolicy.SOURCE)
        @interface AgpsSetIdFlags {}

        void onReportAGpsStatus(int agpsType, int agpsStatus, byte[] suplIpAddr);
        void onRequestSetID(@AgpsSetIdFlags int flags);
    }

    /** Callbacks for notifications. */
    public interface NotificationCallbacks {
        void onReportNiNotification(int notificationId, int niType, int notifyFlags,
                int timeout, int defaultResponse, String requestorId, String text,
                int requestorIdEncoding, int textEncoding);
        void onReportNfwNotification(String proxyAppPackageName, byte protocolStack,
                String otherProtocolStackName, byte requestor, String requestorId,
                byte responseType, boolean inEmergencyMode, boolean isCachedLocation);
    }

    // set lower than the current ITAR limit of 600m/s to allow this to trigger even if GPS HAL
    // stops output right at 600m/s, depriving this of the information of a device that reaches
    // greater than 600m/s, and higher than the speed of sound to avoid impacting most use cases.
    private static final float ITAR_SPEED_LIMIT_METERS_PER_SECOND = 400.0f;

    /**
     * Indicates that this method is a native entry point. Useful purely for IDEs which can
     * understand entry points, and thus eliminate incorrect warnings about methods not used.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    private @interface NativeEntryPoint {}

    @GuardedBy("GnssNative.class")
    private static GnssHal sGnssHal;

    @GuardedBy("GnssNative.class")
    private static boolean sGnssHalInitialized;

    @GuardedBy("GnssNative.class")
    private static GnssNative sInstance;

    /**
     * Sets GnssHal instance to use for testing.
     */
    @VisibleForTesting
    public static synchronized void setGnssHalForTest(GnssHal gnssHal) {
        sGnssHal = Objects.requireNonNull(gnssHal);
        sGnssHalInitialized = false;
        sInstance = null;
    }

    private static synchronized void initializeHal() {
        if (!sGnssHalInitialized) {
            if (sGnssHal == null) {
                sGnssHal = new GnssHal();
            }
            sGnssHal.classInitOnce();
            sGnssHalInitialized = true;
        }
    }

    /**
     * Returns true if GNSS is supported on this device. If true, then
     * {@link #create(Injector, GnssConfiguration)} may be invoked.
     */
    public static synchronized boolean isSupported() {
        initializeHal();
        return sGnssHal.isSupported();
    }

    /**
     * Creates a new instance of GnssNative. Should only be invoked if {@link #isSupported()} is
     * true. May only be invoked once.
     */
    public static synchronized GnssNative create(Injector injector,
            GnssConfiguration configuration) {
        // side effect - ensures initialization
        Preconditions.checkState(isSupported());
        Preconditions.checkState(sInstance == null);
        return (sInstance = new GnssNative(sGnssHal, injector, configuration));
    }

    private final GnssHal mGnssHal;
    private final EmergencyHelper mEmergencyHelper;
    private final GnssConfiguration mConfiguration;

    // these callbacks may have multiple implementations
    private BaseCallbacks[] mBaseCallbacks = new BaseCallbacks[0];
    private StatusCallbacks[] mStatusCallbacks = new StatusCallbacks[0];
    private SvStatusCallbacks[] mSvStatusCallbacks = new SvStatusCallbacks[0];
    private NmeaCallbacks[] mNmeaCallbacks = new NmeaCallbacks[0];
    private LocationCallbacks[] mLocationCallbacks = new LocationCallbacks[0];
    private MeasurementCallbacks[] mMeasurementCallbacks = new MeasurementCallbacks[0];
    private AntennaInfoCallbacks[] mAntennaInfoCallbacks = new AntennaInfoCallbacks[0];
    private NavigationMessageCallbacks[] mNavigationMessageCallbacks =
            new NavigationMessageCallbacks[0];

    // these callbacks may only have a single implementation
    private GeofenceCallbacks mGeofenceCallbacks;
    private TimeCallbacks mTimeCallbacks;
    private LocationRequestCallbacks mLocationRequestCallbacks;
    private PsdsCallbacks mPsdsCallbacks;
    private AGpsCallbacks mAGpsCallbacks;
    private NotificationCallbacks mNotificationCallbacks;

    private boolean mRegistered;

    private volatile boolean mItarSpeedLimitExceeded;

    private GnssCapabilities mCapabilities = new GnssCapabilities.Builder().build();
    private @GnssCapabilities.TopHalCapabilityFlags int mTopFlags;
    private @Nullable GnssPowerStats mPowerStats = null;
    private int mHardwareYear = 0;
    private @Nullable String mHardwareModelName = null;
    private long mStartRealtimeMs = 0;
    private boolean mHasFirstFix = false;

    private GnssNative(GnssHal gnssHal, Injector injector, GnssConfiguration configuration) {
        mGnssHal = Objects.requireNonNull(gnssHal);
        mEmergencyHelper = injector.getEmergencyHelper();
        mConfiguration = configuration;
    }

    public void addBaseCallbacks(BaseCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        mBaseCallbacks = ArrayUtils.appendElement(BaseCallbacks.class, mBaseCallbacks, callbacks);
    }

    public void addStatusCallbacks(StatusCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        mStatusCallbacks = ArrayUtils.appendElement(StatusCallbacks.class, mStatusCallbacks,
                callbacks);
    }

    public void addSvStatusCallbacks(SvStatusCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        mSvStatusCallbacks = ArrayUtils.appendElement(SvStatusCallbacks.class, mSvStatusCallbacks,
                callbacks);
    }

    public void addNmeaCallbacks(NmeaCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        mNmeaCallbacks = ArrayUtils.appendElement(NmeaCallbacks.class, mNmeaCallbacks,
                callbacks);
    }

    public void addLocationCallbacks(LocationCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        mLocationCallbacks = ArrayUtils.appendElement(LocationCallbacks.class, mLocationCallbacks,
                callbacks);
    }

    public void addMeasurementCallbacks(MeasurementCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        mMeasurementCallbacks = ArrayUtils.appendElement(MeasurementCallbacks.class,
                mMeasurementCallbacks, callbacks);
    }

    public void addAntennaInfoCallbacks(AntennaInfoCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        mAntennaInfoCallbacks = ArrayUtils.appendElement(AntennaInfoCallbacks.class,
                mAntennaInfoCallbacks, callbacks);
    }

    public void addNavigationMessageCallbacks(NavigationMessageCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        mNavigationMessageCallbacks = ArrayUtils.appendElement(NavigationMessageCallbacks.class,
                mNavigationMessageCallbacks, callbacks);
    }

    public void setGeofenceCallbacks(GeofenceCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        Preconditions.checkState(mGeofenceCallbacks == null);
        mGeofenceCallbacks = Objects.requireNonNull(callbacks);
    }

    public void setTimeCallbacks(TimeCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        Preconditions.checkState(mTimeCallbacks == null);
        mTimeCallbacks = Objects.requireNonNull(callbacks);
    }

    public void setLocationRequestCallbacks(LocationRequestCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        Preconditions.checkState(mLocationRequestCallbacks == null);
        mLocationRequestCallbacks = Objects.requireNonNull(callbacks);
    }

    public void setPsdsCallbacks(PsdsCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        Preconditions.checkState(mPsdsCallbacks == null);
        mPsdsCallbacks = Objects.requireNonNull(callbacks);
    }

    public void setAGpsCallbacks(AGpsCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        Preconditions.checkState(mAGpsCallbacks == null);
        mAGpsCallbacks = Objects.requireNonNull(callbacks);
    }

    public void setNotificationCallbacks(NotificationCallbacks callbacks) {
        Preconditions.checkState(!mRegistered);
        Preconditions.checkState(mNotificationCallbacks == null);
        mNotificationCallbacks = Objects.requireNonNull(callbacks);
    }

    /**
     * Registers with the HAL and allows callbacks to begin. Once registered with the native HAL,
     * no more callbacks can be added or set. Must only be called once.
     */
    public void register() {
        Preconditions.checkState(!mRegistered);
        mRegistered = true;

        initializeGnss(false);
        Log.i(TAG, "gnss hal started");

        for (int i = 0; i < mBaseCallbacks.length; i++) {
            mBaseCallbacks[i].onHalStarted();
        }
    }

    private void initializeGnss(boolean restart) {
        Preconditions.checkState(mRegistered);
        mTopFlags = 0;
        mGnssHal.initOnce(GnssNative.this, restart);

        // gnss chipset appears to require an init/cleanup cycle on startup in order to properly
        // initialize - undocumented and no idea why this is the case
        if (mGnssHal.init()) {
            mGnssHal.cleanup();
            Log.i(TAG, "gnss hal initialized");
        } else {
            Log.e(TAG, "gnss hal initialization failed");
        }
    }

    public GnssConfiguration getConfiguration() {
        return mConfiguration;
    }

    /**
     * Starts up GNSS HAL, and has undocumented side effect of informing HAL that location is
     * allowed by settings.
     */
    public boolean init() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.init();
    }

    /**
     * Shuts down GNSS HAL, and has undocumented side effect of informing HAL that location is not
     * allowed by settings.
     */
    public void cleanup() {
        Preconditions.checkState(mRegistered);
        mGnssHal.cleanup();
    }

    /**
     * Returns the latest power stats from the GNSS HAL.
     */
    public @Nullable GnssPowerStats getPowerStats() {
        return mPowerStats;
    }

    /**
     * Returns current capabilities of the GNSS HAL.
     */
    public GnssCapabilities getCapabilities() {
        return mCapabilities;
    }

    /**
     * Returns hardware year of GNSS chipset.
     */
    public int getHardwareYear() {
        return mHardwareYear;
    }

    /**
     * Returns hardware model name of GNSS chipset.
     */
    public @Nullable String getHardwareModelName() {
        return mHardwareModelName;
    }

    /**
     * Returns true if the ITAR speed limit is currently being exceeded, and thus location
     * information may be blocked.
     */
    public boolean isItarSpeedLimitExceeded() {
        return mItarSpeedLimitExceeded;
    }

    /**
     * Starts the GNSS HAL.
     */
    public boolean start() {
        Preconditions.checkState(mRegistered);
        mStartRealtimeMs = SystemClock.elapsedRealtime();
        mHasFirstFix = false;
        return mGnssHal.start();
    }

    /**
     * Stops the GNSS HAL.
     */
    public boolean stop() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.stop();
    }

    /**
     * Sets the position mode.
     */
    public boolean setPositionMode(@GnssPositionMode int mode,
            @GnssPositionRecurrence int recurrence, int minInterval, int preferredAccuracy,
            int preferredTime, boolean lowPowerMode) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.setPositionMode(mode, recurrence, minInterval, preferredAccuracy,
                preferredTime, lowPowerMode);
    }

    /**
     * Returns a debug string from the GNSS HAL.
     */
    public String getInternalState() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.getInternalState();
    }

    /**
     * Deletes any aiding data specified by the given flags.
     */
    public void deleteAidingData(@GnssAidingTypeFlags int flags) {
        Preconditions.checkState(mRegistered);
        mGnssHal.deleteAidingData(flags);
    }

    /**
     * Reads an NMEA message into the given buffer, returning the number of bytes loaded into the
     * buffer.
     */
    public int readNmea(byte[] buffer, int bufferSize) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.readNmea(buffer, bufferSize);
    }

    /**
     * Injects location information into the GNSS HAL.
     */
    public void injectLocation(Location location) {
        Preconditions.checkState(mRegistered);
        if (location.hasAccuracy()) {
            mGnssHal.injectLocation(location.getLatitude(), location.getLongitude(),
                    location.getAccuracy());
        }
    }

    /**
     * Injects a location into the GNSS HAL in response to a HAL request for location.
     */
    public void injectBestLocation(Location location) {
        Preconditions.checkState(mRegistered);

        int gnssLocationFlags = GNSS_LOCATION_HAS_LAT_LONG
                | (location.hasAltitude() ? GNSS_LOCATION_HAS_ALTITUDE : 0)
                | (location.hasSpeed() ? GNSS_LOCATION_HAS_SPEED : 0)
                | (location.hasBearing() ? GNSS_LOCATION_HAS_BEARING : 0)
                | (location.hasAccuracy() ? GNSS_LOCATION_HAS_HORIZONTAL_ACCURACY : 0)
                | (location.hasVerticalAccuracy() ? GNSS_LOCATION_HAS_VERTICAL_ACCURACY : 0)
                | (location.hasSpeedAccuracy() ? GNSS_LOCATION_HAS_SPEED_ACCURACY : 0)
                | (location.hasBearingAccuracy() ? GNSS_LOCATION_HAS_BEARING_ACCURACY : 0);

        double latitudeDegrees = location.getLatitude();
        double longitudeDegrees = location.getLongitude();
        double altitudeMeters = location.getAltitude();
        float speedMetersPerSec = location.getSpeed();
        float bearingDegrees = location.getBearing();
        float horizontalAccuracyMeters = location.getAccuracy();
        float verticalAccuracyMeters = location.getVerticalAccuracyMeters();
        float speedAccuracyMetersPerSecond = location.getSpeedAccuracyMetersPerSecond();
        float bearingAccuracyDegrees = location.getBearingAccuracyDegrees();
        long timestamp = location.getTime();

        int elapsedRealtimeFlags = GNSS_REALTIME_HAS_TIMESTAMP_NS
                | (location.hasElapsedRealtimeUncertaintyNanos()
                ? GNSS_REALTIME_HAS_TIME_UNCERTAINTY_NS : 0);
        long elapsedRealtimeNanos = location.getElapsedRealtimeNanos();
        double elapsedRealtimeUncertaintyNanos = location.getElapsedRealtimeUncertaintyNanos();

        mGnssHal.injectBestLocation(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                altitudeMeters, speedMetersPerSec, bearingDegrees, horizontalAccuracyMeters,
                verticalAccuracyMeters, speedAccuracyMetersPerSecond, bearingAccuracyDegrees,
                timestamp, elapsedRealtimeFlags, elapsedRealtimeNanos,
                elapsedRealtimeUncertaintyNanos);
    }

    /**
     * Injects time information into the GNSS HAL.
     */
    public void injectTime(long time, long timeReference, int uncertainty) {
        Preconditions.checkState(mRegistered);
        mGnssHal.injectTime(time, timeReference, uncertainty);
    }

    /**
     * Returns true if navigation message collection is supported.
     */
    public boolean isNavigationMessageCollectionSupported() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.isNavigationMessageCollectionSupported();
    }

    /**
     * Starts navigation message collection.
     */
    public boolean startNavigationMessageCollection() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.startNavigationMessageCollection();
    }

    /**
     * Stops navigation message collection.
     */
    public boolean stopNavigationMessageCollection() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.stopNavigationMessageCollection();
    }

    /**
     * Returns true if antenna info is supported.
     */
    public boolean isAntennaInfoSupported() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.isAntennaInfoSupported();
    }

    /**
     * Starts antenna info listening.
     */
    public boolean startAntennaInfoListening() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.startAntennaInfoListening();
    }

    /**
     * Stops antenna info listening.
     */
    public boolean stopAntennaInfoListening() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.stopAntennaInfoListening();
    }

    /**
     * Returns true if measurement collection is supported.
     */
    public boolean isMeasurementSupported() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.isMeasurementSupported();
    }

    /**
     * Starts measurement collection.
     */
    public boolean startMeasurementCollection(boolean enableFullTracking,
            boolean enableCorrVecOutputs) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.startMeasurementCollection(enableFullTracking, enableCorrVecOutputs);
    }

    /**
     * Stops measurement collection.
     */
    public boolean stopMeasurementCollection() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.stopMeasurementCollection();
    }

    /**
     * Returns true if measurement corrections are supported.
     */
    public boolean isMeasurementCorrectionsSupported() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.isMeasurementCorrectionsSupported();
    }

    /**
     * Injects measurement corrections into the GNSS HAL.
     */
    public boolean injectMeasurementCorrections(GnssMeasurementCorrections corrections) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.injectMeasurementCorrections(corrections);
    }

    /**
     * Initialize batching.
     */
    public boolean initBatching() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.initBatching();
    }

    /**
     * Cleanup batching.
     */
    public void cleanupBatching() {
        Preconditions.checkState(mRegistered);
        mGnssHal.cleanupBatching();
    }

    /**
     * Start batching.
     */
    public boolean startBatch(long periodNanos, boolean wakeOnFifoFull) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.startBatch(periodNanos, wakeOnFifoFull);
    }

    /**
     * Flush batching.
     */
    public void flushBatch() {
        Preconditions.checkState(mRegistered);
        mGnssHal.flushBatch();
    }

    /**
     * Stop batching.
     */
    public void stopBatch() {
        Preconditions.checkState(mRegistered);
        mGnssHal.stopBatch();
    }

    /**
     * Get current batching size.
     */
    public int getBatchSize() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.getBatchSize();
    }

    /**
     * Check if GNSS geofencing is supported.
     */
    public boolean isGeofencingSupported() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.isGeofencingSupported();
    }

    /**
     * Add geofence.
     */
    public boolean addGeofence(int geofenceId, double latitude, double longitude, double radius,
            int lastTransition, int monitorTransitions, int notificationResponsiveness,
            int unknownTimer) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.addGeofence(geofenceId, latitude, longitude, radius, lastTransition,
                monitorTransitions, notificationResponsiveness, unknownTimer);
    }

    /**
     * Resume geofence.
     */
    public boolean resumeGeofence(int geofenceId, int monitorTransitions) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.resumeGeofence(geofenceId, monitorTransitions);
    }

    /**
     * Pause geofence.
     */
    public boolean pauseGeofence(int geofenceId) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.pauseGeofence(geofenceId);
    }

    /**
     * Remove geofence.
     */
    public boolean removeGeofence(int geofenceId) {
        Preconditions.checkState(mRegistered);
        return mGnssHal.removeGeofence(geofenceId);
    }

    /**
     * Returns true if visibility control is supported.
     */
    public boolean isGnssVisibilityControlSupported() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.isGnssVisibilityControlSupported();
    }

    /**
     * Send a network initiated respnse.
     */
    public void sendNiResponse(int notificationId, int userResponse) {
        Preconditions.checkState(mRegistered);
        mGnssHal.sendNiResponse(notificationId, userResponse);
    }

    /**
     * Request an eventual update of GNSS power statistics.
     */
    public void requestPowerStats() {
        Preconditions.checkState(mRegistered);
        mGnssHal.requestPowerStats();
    }

    /**
     * Sets AGPS server information.
     */
    public void setAgpsServer(int type, String hostname, int port) {
        Preconditions.checkState(mRegistered);
        mGnssHal.setAgpsServer(type, hostname, port);
    }

    /**
     * Sets AGPS set id.
     */
    public void setAgpsSetId(@AgpsSetIdType int type, String setId) {
        Preconditions.checkState(mRegistered);
        mGnssHal.setAgpsSetId(type, setId);
    }

    /**
     * Sets AGPS reference cell id location.
     */
    public void setAgpsReferenceLocationCellId(@AgpsReferenceLocationType int type, int mcc,
            int mnc, int lac, int cid) {
        Preconditions.checkState(mRegistered);
        mGnssHal.setAgpsReferenceLocationCellId(type, mcc, mnc, lac, cid);
    }

    /**
     * Returns true if Predicted Satellite Data Service APIs are supported.
     */
    public boolean isPsdsSupported() {
        Preconditions.checkState(mRegistered);
        return mGnssHal.isPsdsSupported();
    }

    /**
     * Injects Predicited Satellite Data Service data into the GNSS HAL.
     */
    public void injectPsdsData(byte[] data, int length, int psdsType) {
        Preconditions.checkState(mRegistered);
        mGnssHal.injectPsdsData(data, length, psdsType);
    }

    @NativeEntryPoint
    void reportGnssServiceDied() {
        // Not necessary to clear (and restore) binder identity since it runs on another thread.
        Log.e(TAG, "gnss hal died - restarting shortly...");

        // move to another thread just in case there is some awkward gnss thread dependency with
        // the death notification. there shouldn't be, but you never know with gnss...
        FgThread.getExecutor().execute(this::restartHal);
    }

    @VisibleForTesting
    void restartHal() {
        initializeGnss(true);
        Log.e(TAG, "gnss hal restarted");

        for (int i = 0; i < mBaseCallbacks.length; i++) {
            mBaseCallbacks[i].onHalRestarted();
        }
    }

    @NativeEntryPoint
    void reportLocation(boolean hasLatLong, Location location) {
        Binder.withCleanCallingIdentity(() -> {
            if (hasLatLong && !mHasFirstFix) {
                mHasFirstFix = true;

                // notify status listeners
                int ttff = (int) (SystemClock.elapsedRealtime() - mStartRealtimeMs);
                for (int i = 0; i < mStatusCallbacks.length; i++) {
                    mStatusCallbacks[i].onReportFirstFix(ttff);
                }
            }

            if (location.hasSpeed()) {
                boolean exceeded = location.getSpeed() > ITAR_SPEED_LIMIT_METERS_PER_SECOND;
                if (!mItarSpeedLimitExceeded && exceeded) {
                    Log.w(TAG, "speed nearing ITAR threshold - blocking further GNSS output");
                } else if (mItarSpeedLimitExceeded && !exceeded) {
                    Log.w(TAG, "speed leaving ITAR threshold - allowing further GNSS output");
                }
                mItarSpeedLimitExceeded = exceeded;
            }

            if (mItarSpeedLimitExceeded) {
                return;
            }

            for (int i = 0; i < mLocationCallbacks.length; i++) {
                mLocationCallbacks[i].onReportLocation(hasLatLong, location);
            }
        });
    }

    @NativeEntryPoint
    void reportStatus(@StatusCallbacks.GnssStatusValue int gnssStatus) {
        Binder.withCleanCallingIdentity(() -> {
            for (int i = 0; i < mStatusCallbacks.length; i++) {
                mStatusCallbacks[i].onReportStatus(gnssStatus);
            }
        });
    }

    @NativeEntryPoint
    void reportSvStatus(int svCount, int[] svidWithFlags, float[] cn0DbHzs,
            float[] elevations, float[] azimuths, float[] carrierFrequencies,
            float[] basebandCn0DbHzs) {
        Binder.withCleanCallingIdentity(() -> {
            GnssStatus gnssStatus = GnssStatus.wrap(svCount, svidWithFlags, cn0DbHzs, elevations,
                    azimuths, carrierFrequencies, basebandCn0DbHzs);
            for (int i = 0; i < mSvStatusCallbacks.length; i++) {
                mSvStatusCallbacks[i].onReportSvStatus(gnssStatus);
            }
        });
    }

    @NativeEntryPoint
    void reportAGpsStatus(int agpsType, int agpsStatus, byte[] suplIpAddr) {
        Binder.withCleanCallingIdentity(
                () -> mAGpsCallbacks.onReportAGpsStatus(agpsType, agpsStatus, suplIpAddr));
    }

    @NativeEntryPoint
    void reportNmea(long timestamp) {
        Binder.withCleanCallingIdentity(() -> {
            if (mItarSpeedLimitExceeded) {
                return;
            }

            for (int i = 0; i < mNmeaCallbacks.length; i++) {
                mNmeaCallbacks[i].onReportNmea(timestamp);
            }
        });
    }

    @NativeEntryPoint
    void reportMeasurementData(GnssMeasurementsEvent event) {
        Binder.withCleanCallingIdentity(() -> {
            if (mItarSpeedLimitExceeded) {
                return;
            }

            for (int i = 0; i < mMeasurementCallbacks.length; i++) {
                mMeasurementCallbacks[i].onReportMeasurements(event);
            }
        });
    }

    @NativeEntryPoint
    void reportAntennaInfo(List<GnssAntennaInfo> antennaInfos) {
        Binder.withCleanCallingIdentity(() -> {
            for (int i = 0; i < mAntennaInfoCallbacks.length; i++) {
                mAntennaInfoCallbacks[i].onReportAntennaInfo(antennaInfos);
            }
        });
    }

    @NativeEntryPoint
    void reportNavigationMessage(GnssNavigationMessage event) {
        Binder.withCleanCallingIdentity(() -> {
            if (mItarSpeedLimitExceeded) {
                return;
            }

            for (int i = 0; i < mNavigationMessageCallbacks.length; i++) {
                mNavigationMessageCallbacks[i].onReportNavigationMessage(event);
            }
        });
    }

    @NativeEntryPoint
    void setTopHalCapabilities(@GnssCapabilities.TopHalCapabilityFlags int capabilities) {
        // Here the bits specified by 'capabilities' are turned on. It is handled differently from
        // sub hal because top hal capabilities could be set by HIDL HAL and/or AIDL HAL. Each of
        // them possesses a different set of capabilities.
        mTopFlags |= capabilities;
        GnssCapabilities oldCapabilities = mCapabilities;
        mCapabilities = oldCapabilities.withTopHalFlags(mTopFlags);
        onCapabilitiesChanged(oldCapabilities, mCapabilities);
    }

    @NativeEntryPoint
    void setSubHalMeasurementCorrectionsCapabilities(
            @GnssCapabilities.SubHalMeasurementCorrectionsCapabilityFlags int capabilities) {
        GnssCapabilities oldCapabilities = mCapabilities;
        mCapabilities = oldCapabilities.withSubHalMeasurementCorrectionsFlags(capabilities);
        onCapabilitiesChanged(oldCapabilities, mCapabilities);
    }

    @NativeEntryPoint
    void setSubHalPowerIndicationCapabilities(
            @GnssCapabilities.SubHalPowerCapabilityFlags int capabilities) {
        GnssCapabilities oldCapabilities = mCapabilities;
        mCapabilities = oldCapabilities.withSubHalPowerFlags(capabilities);
        onCapabilitiesChanged(oldCapabilities, mCapabilities);
    }

    private void onCapabilitiesChanged(GnssCapabilities oldCapabilities,
            GnssCapabilities newCapabilities) {
        Binder.withCleanCallingIdentity(() -> {
            if (newCapabilities.equals(oldCapabilities)) {
                return;
            }

            Log.i(TAG, "gnss capabilities changed to " + newCapabilities);

            for (int i = 0; i < mBaseCallbacks.length; i++) {
                mBaseCallbacks[i].onCapabilitiesChanged(oldCapabilities, newCapabilities);
            }
        });
    }

    @NativeEntryPoint
    void reportGnssPowerStats(GnssPowerStats powerStats) {
        mPowerStats = powerStats;
    }

    @NativeEntryPoint
    void setGnssYearOfHardware(int year) {
        mHardwareYear = year;
    }

    @NativeEntryPoint
    private void setGnssHardwareModelName(String modelName) {
        mHardwareModelName = modelName;
    }

    @NativeEntryPoint
    void reportLocationBatch(Location[] locations) {
        Binder.withCleanCallingIdentity(() -> {
            for (int i = 0; i < mLocationCallbacks.length; i++) {
                mLocationCallbacks[i].onReportLocations(locations);
            }
        });
    }

    @NativeEntryPoint
    void psdsDownloadRequest(int psdsType) {
        Binder.withCleanCallingIdentity(() -> mPsdsCallbacks.onRequestPsdsDownload(psdsType));
    }

    @NativeEntryPoint
    void reportGeofenceTransition(int geofenceId, Location location, int transition,
            long transitionTimestamp) {
        Binder.withCleanCallingIdentity(
                () -> mGeofenceCallbacks.onReportGeofenceTransition(geofenceId, location,
                        transition, transitionTimestamp));
    }

    @NativeEntryPoint
    void reportGeofenceStatus(int status, Location location) {
        Binder.withCleanCallingIdentity(
                () -> mGeofenceCallbacks.onReportGeofenceStatus(status, location));
    }

    @NativeEntryPoint
    void reportGeofenceAddStatus(int geofenceId, @GeofenceCallbacks.GeofenceStatus int status) {
        Binder.withCleanCallingIdentity(
                () -> mGeofenceCallbacks.onReportGeofenceAddStatus(geofenceId, status));
    }

    @NativeEntryPoint
    void reportGeofenceRemoveStatus(int geofenceId, @GeofenceCallbacks.GeofenceStatus int status) {
        Binder.withCleanCallingIdentity(
                () -> mGeofenceCallbacks.onReportGeofenceRemoveStatus(geofenceId, status));
    }

    @NativeEntryPoint
    void reportGeofencePauseStatus(int geofenceId, @GeofenceCallbacks.GeofenceStatus int status) {
        Binder.withCleanCallingIdentity(
                () -> mGeofenceCallbacks.onReportGeofencePauseStatus(geofenceId, status));
    }

    @NativeEntryPoint
    void reportGeofenceResumeStatus(int geofenceId, @GeofenceCallbacks.GeofenceStatus int status) {
        Binder.withCleanCallingIdentity(
                () -> mGeofenceCallbacks.onReportGeofenceResumeStatus(geofenceId, status));
    }

    @NativeEntryPoint
    void reportNiNotification(int notificationId, int niType, int notifyFlags,
            int timeout, int defaultResponse, String requestorId, String text,
            int requestorIdEncoding, int textEncoding) {
        Binder.withCleanCallingIdentity(
                () -> mNotificationCallbacks.onReportNiNotification(notificationId, niType,
                        notifyFlags, timeout, defaultResponse, requestorId, text,
                        requestorIdEncoding, textEncoding));
    }

    @NativeEntryPoint
    void requestSetID(int flags) {
        Binder.withCleanCallingIdentity(() -> mAGpsCallbacks.onRequestSetID(flags));
    }

    @NativeEntryPoint
    void requestLocation(boolean independentFromGnss, boolean isUserEmergency) {
        Binder.withCleanCallingIdentity(
                () -> mLocationRequestCallbacks.onRequestLocation(independentFromGnss,
                        isUserEmergency));
    }

    @NativeEntryPoint
    void requestUtcTime() {
        Binder.withCleanCallingIdentity(() -> mTimeCallbacks.onRequestUtcTime());
    }

    @NativeEntryPoint
    void requestRefLocation() {
        Binder.withCleanCallingIdentity(
                () -> mLocationRequestCallbacks.onRequestRefLocation());
    }

    @NativeEntryPoint
    void reportNfwNotification(String proxyAppPackageName, byte protocolStack,
            String otherProtocolStackName, byte requestor, String requestorId,
            byte responseType, boolean inEmergencyMode, boolean isCachedLocation) {
        Binder.withCleanCallingIdentity(
                () -> mNotificationCallbacks.onReportNfwNotification(proxyAppPackageName,
                        protocolStack, otherProtocolStackName, requestor, requestorId, responseType,
                        inEmergencyMode, isCachedLocation));
    }

    @NativeEntryPoint
    boolean isInEmergencySession() {
        return Binder.withCleanCallingIdentity(
                () -> mEmergencyHelper.isInEmergency(mConfiguration.getEsExtensionSec()));
    }

    /**
     * Encapsulates actual HAL methods for testing purposes.
     */
    @VisibleForTesting
    public static class GnssHal {

        protected GnssHal() {}

        protected void classInitOnce() {
            native_class_init_once();
        }

        protected boolean isSupported() {
            return native_is_supported();
        }

        protected void initOnce(GnssNative gnssNative, boolean reinitializeGnssServiceHandle) {
            gnssNative.native_init_once(reinitializeGnssServiceHandle);
        }

        protected boolean init() {
            return native_init();
        }

        protected void cleanup() {
            native_cleanup();
        }

        protected boolean start() {
            return native_start();
        }

        protected boolean stop() {
            return native_stop();
        }

        protected boolean setPositionMode(@GnssPositionMode int mode,
                @GnssPositionRecurrence int recurrence, int minInterval, int preferredAccuracy,
                int preferredTime, boolean lowPowerMode) {
            return native_set_position_mode(mode, recurrence, minInterval, preferredAccuracy,
                    preferredTime, lowPowerMode);
        }

        protected String getInternalState() {
            return native_get_internal_state();
        }

        protected void deleteAidingData(@GnssAidingTypeFlags int flags) {
            native_delete_aiding_data(flags);
        }

        protected int readNmea(byte[] buffer, int bufferSize) {
            return native_read_nmea(buffer, bufferSize);
        }

        protected void injectLocation(double latitude, double longitude, float accuracy) {
            native_inject_location(latitude, longitude, accuracy);
        }

        protected void injectBestLocation(@GnssLocationFlags int gnssLocationFlags, double latitude,
                double longitude, double altitude, float speed, float bearing,
                float horizontalAccuracy, float verticalAccuracy, float speedAccuracy,
                float bearingAccuracy, long timestamp, @GnssRealtimeFlags int elapsedRealtimeFlags,
                long elapsedRealtimeNanos, double elapsedRealtimeUncertaintyNanos) {
            native_inject_best_location(gnssLocationFlags, latitude, longitude, altitude, speed,
                    bearing, horizontalAccuracy, verticalAccuracy, speedAccuracy, bearingAccuracy,
                    timestamp, elapsedRealtimeFlags, elapsedRealtimeNanos,
                    elapsedRealtimeUncertaintyNanos);
        }

        protected void injectTime(long time, long timeReference, int uncertainty) {
            native_inject_time(time, timeReference, uncertainty);
        }

        protected boolean isNavigationMessageCollectionSupported() {
            return native_is_navigation_message_supported();
        }

        protected boolean startNavigationMessageCollection() {
            return native_start_navigation_message_collection();
        }

        protected boolean stopNavigationMessageCollection() {
            return native_stop_navigation_message_collection();
        }

        protected boolean isAntennaInfoSupported() {
            return native_is_antenna_info_supported();
        }

        protected boolean startAntennaInfoListening() {
            return native_start_antenna_info_listening();
        }

        protected boolean stopAntennaInfoListening() {
            return native_stop_antenna_info_listening();
        }

        protected boolean isMeasurementSupported() {
            return native_is_measurement_supported();
        }

        protected boolean startMeasurementCollection(boolean enableFullTracking,
                boolean enableCorrVecOutputs) {
            return native_start_measurement_collection(enableFullTracking, enableCorrVecOutputs);
        }

        protected boolean stopMeasurementCollection() {
            return native_stop_measurement_collection();
        }

        protected boolean isMeasurementCorrectionsSupported() {
            return native_is_measurement_corrections_supported();
        }

        protected boolean injectMeasurementCorrections(GnssMeasurementCorrections corrections) {
            return native_inject_measurement_corrections(corrections);
        }

        protected int getBatchSize() {
            return native_get_batch_size();
        }

        protected boolean initBatching() {
            return native_init_batching();
        }

        protected void cleanupBatching() {
            native_cleanup_batching();
        }

        protected boolean startBatch(long periodNanos, boolean wakeOnFifoFull) {
            return native_start_batch(periodNanos, wakeOnFifoFull);
        }

        protected void flushBatch() {
            native_flush_batch();
        }

        protected void stopBatch() {
            native_stop_batch();
        }

        protected boolean isGeofencingSupported() {
            return native_is_geofence_supported();
        }

        protected boolean addGeofence(int geofenceId, double latitude, double longitude,
                double radius, int lastTransition, int monitorTransitions,
                int notificationResponsiveness, int unknownTimer) {
            return native_add_geofence(geofenceId, latitude, longitude, radius, lastTransition,
                    monitorTransitions, notificationResponsiveness, unknownTimer);
        }

        protected boolean resumeGeofence(int geofenceId, int monitorTransitions) {
            return native_resume_geofence(geofenceId, monitorTransitions);
        }

        protected boolean pauseGeofence(int geofenceId) {
            return native_pause_geofence(geofenceId);
        }

        protected boolean removeGeofence(int geofenceId) {
            return native_remove_geofence(geofenceId);
        }

        protected boolean isGnssVisibilityControlSupported() {
            return native_is_gnss_visibility_control_supported();
        }

        protected void sendNiResponse(int notificationId, int userResponse) {
            native_send_ni_response(notificationId, userResponse);
        }

        protected void requestPowerStats() {
            native_request_power_stats();
        }

        protected void setAgpsServer(int type, String hostname, int port) {
            native_set_agps_server(type, hostname, port);
        }

        protected void setAgpsSetId(@AgpsSetIdType int type, String setId) {
            native_agps_set_id(type, setId);
        }

        protected void setAgpsReferenceLocationCellId(@AgpsReferenceLocationType int type, int mcc,
                int mnc, int lac, int cid) {
            native_agps_set_ref_location_cellid(type, mcc, mnc, lac, cid);
        }

        protected boolean isPsdsSupported() {
            return native_supports_psds();
        }

        protected void injectPsdsData(byte[] data, int length, int psdsType) {
            native_inject_psds_data(data, length, psdsType);
        }
    }

    // basic APIs

    private static native void native_class_init_once();

    private static native boolean native_is_supported();

    private native void native_init_once(boolean reinitializeGnssServiceHandle);

    private static native boolean native_init();

    private static native void native_cleanup();

    private static native boolean native_start();

    private static native boolean native_stop();

    private static native boolean native_set_position_mode(int mode, int recurrence,
            int minInterval, int preferredAccuracy, int preferredTime, boolean lowPowerMode);

    private static native String native_get_internal_state();

    private static native void native_delete_aiding_data(int flags);

    // NMEA APIs

    private static native int native_read_nmea(byte[] buffer, int bufferSize);

    // location injection APIs

    private static native void native_inject_location(double latitude, double longitude,
            float accuracy);


    private static native void native_inject_best_location(
            int gnssLocationFlags, double latitudeDegrees, double longitudeDegrees,
            double altitudeMeters, float speedMetersPerSec, float bearingDegrees,
            float horizontalAccuracyMeters, float verticalAccuracyMeters,
            float speedAccuracyMetersPerSecond, float bearingAccuracyDegrees,
            long timestamp, int elapsedRealtimeFlags, long elapsedRealtimeNanos,
            double elapsedRealtimeUncertaintyNanos);

    // time injection APIs

    private static native void native_inject_time(long time, long timeReference, int uncertainty);

    // navigation message APIs

    private static native boolean native_is_navigation_message_supported();

    private static native boolean native_start_navigation_message_collection();

    private static native boolean native_stop_navigation_message_collection();

    // antenna info APIS
    // TODO: in a next version of the HAL, consider removing the necessity for listening to antenna
    //   info changes, and simply report them always, same as capabilities.

    private static native boolean native_is_antenna_info_supported();

    private static native boolean native_start_antenna_info_listening();

    private static native boolean native_stop_antenna_info_listening();

    // measurement APIs

    private static native boolean native_is_measurement_supported();

    private static native boolean native_start_measurement_collection(boolean enableFullTracking,
            boolean enableCorrVecOutputs);

    private static native boolean native_stop_measurement_collection();

    // measurement corrections APIs

    private static native boolean native_is_measurement_corrections_supported();

    private static native boolean native_inject_measurement_corrections(
            GnssMeasurementCorrections corrections);

    // batching APIs

    private static native boolean native_init_batching();

    private static native void native_cleanup_batching();

    private static native boolean native_start_batch(long periodNanos, boolean wakeOnFifoFull);

    private static native void native_flush_batch();

    private static native boolean native_stop_batch();

    private static native int native_get_batch_size();

    // geofence APIs

    private static native boolean native_is_geofence_supported();

    private static native boolean native_add_geofence(int geofenceId, double latitude,
            double longitude, double radius, int lastTransition, int monitorTransitions,
            int notificationResponsivenes, int unknownTimer);

    private static native boolean native_resume_geofence(int geofenceId, int monitorTransitions);

    private static native boolean native_pause_geofence(int geofenceId);

    private static native boolean native_remove_geofence(int geofenceId);

    // network initiated (NI) APIs

    private static native boolean native_is_gnss_visibility_control_supported();

    private static native void native_send_ni_response(int notificationId, int userResponse);

    // power stats APIs

    private static native void native_request_power_stats();

    // AGPS APIs

    private static native void native_set_agps_server(int type, String hostname, int port);

    private static native void native_agps_set_id(int type, String setid);

    private static native void native_agps_set_ref_location_cellid(int type, int mcc, int mnc,
            int lac, int cid);

    // PSDS APIs

    private static native boolean native_supports_psds();

    private static native void native_inject_psds_data(byte[] data, int length, int psdsType);
}
