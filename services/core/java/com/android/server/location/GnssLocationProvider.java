/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.location.GeofenceHardware;
import android.hardware.location.GeofenceHardwareImpl;
import android.location.Criteria;
import android.location.FusedBatchOptions;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.IGpsGeofenceHardware;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.os.AsyncTask;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.Log;
import android.util.StatsLog;

import com.android.internal.app.IBatteryStats;
import com.android.internal.location.GpsNetInitiatedHandler;
import com.android.internal.location.GpsNetInitiatedHandler.GpsNiNotification;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.location.gnssmetrics.GnssMetrics;
import com.android.server.location.GnssSatelliteBlacklistHelper.GnssSatelliteBlacklistCallback;
import com.android.server.location.NtpTimeHelper.InjectNtpTimeCallback;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A GNSS implementation of LocationProvider used by LocationManager.
 *
 * {@hide}
 */
public class GnssLocationProvider extends AbstractLocationProvider implements
        InjectNtpTimeCallback,
        GnssSatelliteBlacklistCallback {

    /**
     * Indicates that this method is a native entry point. Useful purely for IDEs which can
     * understand entry points, and thus eliminate incorrect warnings about methods not used.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    private @interface NativeEntryPoint {
    }

    private static final String TAG = "GnssLocationProvider";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private static final ProviderProperties PROPERTIES = new ProviderProperties(
            true, true, false, false, true, true, true,
            Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);

    // these need to match GnssPositionMode enum in IGnss.hal
    private static final int GPS_POSITION_MODE_STANDALONE = 0;
    private static final int GPS_POSITION_MODE_MS_BASED = 1;
    private static final int GPS_POSITION_MODE_MS_ASSISTED = 2;

    // these need to match GnssPositionRecurrence enum in IGnss.hal
    private static final int GPS_POSITION_RECURRENCE_PERIODIC = 0;
    private static final int GPS_POSITION_RECURRENCE_SINGLE = 1;

    // these need to match GnssStatusValue enum in IGnssCallback.hal
    private static final int GPS_STATUS_NONE = 0;
    private static final int GPS_STATUS_SESSION_BEGIN = 1;
    private static final int GPS_STATUS_SESSION_END = 2;
    private static final int GPS_STATUS_ENGINE_ON = 3;
    private static final int GPS_STATUS_ENGINE_OFF = 4;

    // these need to match GnssLocationFlags enum in types.hal
    private static final int LOCATION_INVALID = 0;
    private static final int LOCATION_HAS_LAT_LONG = 1;
    private static final int LOCATION_HAS_ALTITUDE = 2;
    private static final int LOCATION_HAS_SPEED = 4;
    private static final int LOCATION_HAS_BEARING = 8;
    private static final int LOCATION_HAS_HORIZONTAL_ACCURACY = 16;
    private static final int LOCATION_HAS_VERTICAL_ACCURACY = 32;
    private static final int LOCATION_HAS_SPEED_ACCURACY = 64;
    private static final int LOCATION_HAS_BEARING_ACCURACY = 128;

    // these need to match ElapsedRealtimeFlags enum in types.hal
    private static final int ELAPSED_REALTIME_HAS_TIMESTAMP_NS = 1;

    // IMPORTANT - the GPS_DELETE_* symbols here must match GnssAidingData enum in IGnss.hal
    private static final int GPS_DELETE_EPHEMERIS = 0x0001;
    private static final int GPS_DELETE_ALMANAC = 0x0002;
    private static final int GPS_DELETE_POSITION = 0x0004;
    private static final int GPS_DELETE_TIME = 0x0008;
    private static final int GPS_DELETE_IONO = 0x0010;
    private static final int GPS_DELETE_UTC = 0x0020;
    private static final int GPS_DELETE_HEALTH = 0x0040;
    private static final int GPS_DELETE_SVDIR = 0x0080;
    private static final int GPS_DELETE_SVSTEER = 0x0100;
    private static final int GPS_DELETE_SADATA = 0x0200;
    private static final int GPS_DELETE_RTI = 0x0400;
    private static final int GPS_DELETE_CELLDB_INFO = 0x8000;
    private static final int GPS_DELETE_ALL = 0xFFFF;

    // The GPS_CAPABILITY_* flags must match Capabilities enum in IGnssCallback.hal
    private static final int GPS_CAPABILITY_SCHEDULING = 0x0000001;
    private static final int GPS_CAPABILITY_MSB = 0x0000002;
    private static final int GPS_CAPABILITY_MSA = 0x0000004;
    private static final int GPS_CAPABILITY_SINGLE_SHOT = 0x0000008;
    private static final int GPS_CAPABILITY_ON_DEMAND_TIME = 0x0000010;
    private static final int GPS_CAPABILITY_GEOFENCING = 0x0000020;
    public static final int GPS_CAPABILITY_MEASUREMENTS = 0x0000040;
    private static final int GPS_CAPABILITY_NAV_MESSAGES = 0x0000080;

    // The AGPS SUPL mode
    private static final int AGPS_SUPL_MODE_MSA = 0x02;
    private static final int AGPS_SUPL_MODE_MSB = 0x01;

    private static final int SET_REQUEST = 3;
    private static final int INJECT_NTP_TIME = 5;
    private static final int DOWNLOAD_XTRA_DATA = 6;
    private static final int UPDATE_LOCATION = 7;  // Handle external location from network listener
    private static final int DOWNLOAD_XTRA_DATA_FINISHED = 11;
    private static final int SUBSCRIPTION_OR_CARRIER_CONFIG_CHANGED = 12;
    private static final int INITIALIZE_HANDLER = 13;
    private static final int REQUEST_LOCATION = 16;
    private static final int REPORT_LOCATION = 17; // HAL reports location
    private static final int REPORT_SV_STATUS = 18; // HAL reports SV status

    // Request setid
    private static final int AGPS_RIL_REQUEST_SETID_IMSI = 1;
    private static final int AGPS_RIL_REQUEST_SETID_MSISDN = 2;

    // ref. location info
    private static final int AGPS_REF_LOCATION_TYPE_GSM_CELLID = 1;
    private static final int AGPS_REF_LOCATION_TYPE_UMTS_CELLID = 2;

    // set id info
    private static final int AGPS_SETID_TYPE_NONE = 0;
    private static final int AGPS_SETID_TYPE_IMSI = 1;
    private static final int AGPS_SETID_TYPE_MSISDN = 2;

    private static final int GPS_GEOFENCE_UNAVAILABLE = 1 << 0L;
    private static final int GPS_GEOFENCE_AVAILABLE = 1 << 1L;

    // GPS Geofence errors. Should match GeofenceStatus enum in IGnssGeofenceCallback.hal.
    private static final int GPS_GEOFENCE_OPERATION_SUCCESS = 0;
    private static final int GPS_GEOFENCE_ERROR_TOO_MANY_GEOFENCES = 100;
    private static final int GPS_GEOFENCE_ERROR_ID_EXISTS = -101;
    private static final int GPS_GEOFENCE_ERROR_ID_UNKNOWN = -102;
    private static final int GPS_GEOFENCE_ERROR_INVALID_TRANSITION = -103;
    private static final int GPS_GEOFENCE_ERROR_GENERIC = -149;

    // TCP/IP constants.
    // Valid TCP/UDP port range is (0, 65535].
    private static final int TCP_MIN_PORT = 0;
    private static final int TCP_MAX_PORT = 0xffff;

    // 1 second, or 1 Hz frequency.
    private static final long LOCATION_UPDATE_MIN_TIME_INTERVAL_MILLIS = 1000;
    // Default update duration in milliseconds for REQUEST_LOCATION.
    private static final long LOCATION_UPDATE_DURATION_MILLIS = 10 * 1000;

    /** simpler wrapper for ProviderRequest + Worksource */
    private static class GpsRequest {
        public ProviderRequest request;
        public WorkSource source;

        public GpsRequest(ProviderRequest request, WorkSource source) {
            this.request = request;
            this.source = source;
        }
    }

    // Threadsafe class to hold stats reported in the Extras Bundle
    private static class LocationExtras {
        private int mSvCount;
        private int mMeanCn0;
        private int mMaxCn0;
        private final Bundle mBundle;

        public LocationExtras() {
            mBundle = new Bundle();
        }

        public void set(int svCount, int meanCn0, int maxCn0) {
            synchronized (this) {
                mSvCount = svCount;
                mMeanCn0 = meanCn0;
                mMaxCn0 = maxCn0;
            }
            setBundle(mBundle);
        }

        public void reset() {
            set(0, 0, 0);
        }

        // Also used by outside methods to add to other bundles
        public void setBundle(Bundle extras) {
            if (extras != null) {
                synchronized (this) {
                    extras.putInt("satellites", mSvCount);
                    extras.putInt("meanCn0", mMeanCn0);
                    extras.putInt("maxCn0", mMaxCn0);
                }
            }
        }

        public Bundle getBundle() {
            synchronized (this) {
                return new Bundle(mBundle);
            }
        }
    }

    private final Object mLock = new Object();

    // current status
    private int mStatus = LocationProvider.TEMPORARILY_UNAVAILABLE;

    // time for last status update
    private long mStatusUpdateTime = SystemClock.elapsedRealtime();

    // turn off GPS fix icon if we haven't received a fix in 10 seconds
    private static final long RECENT_FIX_TIMEOUT = 10 * 1000;

    // stop trying if we do not receive a fix within 60 seconds
    private static final int NO_FIX_TIMEOUT = 60 * 1000;

    // if the fix interval is below this we leave GPS on,
    // if above then we cycle the GPS driver.
    // Typical hot TTTF is ~5 seconds, so 10 seconds seems sane.
    private static final int GPS_POLLING_THRESHOLD_INTERVAL = 10 * 1000;

    // how long to wait if we have a network error in NTP or XTRA downloading
    // the initial value of the exponential backoff
    // current setting - 5 minutes
    private static final long RETRY_INTERVAL = 5 * 60 * 1000;
    // how long to wait if we have a network error in NTP or XTRA downloading
    // the max value of the exponential backoff
    // current setting - 4 hours
    private static final long MAX_RETRY_INTERVAL = 4 * 60 * 60 * 1000;

    // Timeout when holding wakelocks for downloading XTRA data.
    private static final long DOWNLOAD_XTRA_DATA_TIMEOUT_MS = 60 * 1000;

    private final ExponentialBackOff mXtraBackOff = new ExponentialBackOff(RETRY_INTERVAL,
            MAX_RETRY_INTERVAL);

    // true if we are enabled, protected by this
    private boolean mEnabled;

    private boolean mShutdown;

    // states for injecting ntp and downloading xtra data
    private static final int STATE_PENDING_NETWORK = 0;
    private static final int STATE_DOWNLOADING = 1;
    private static final int STATE_IDLE = 2;

    // flags to trigger NTP or XTRA data download when network becomes available
    // initialized to true so we do NTP and XTRA when the network comes up after booting
    private int mDownloadXtraDataPending = STATE_PENDING_NETWORK;

    // true if GPS is navigating
    private boolean mNavigating;

    // requested frequency of fixes, in milliseconds
    private int mFixInterval = 1000;

    // true if low power mode for the GNSS chipset is part of the latest request.
    private boolean mLowPowerMode = false;

    // true if we started navigation
    private boolean mStarted;

    // capabilities of the GPS engine
    private int mEngineCapabilities;

    // true if XTRA is supported
    private boolean mSupportsXtra;

    // for calculating time to first fix
    private long mFixRequestTime = 0;
    // time to first fix for most recent session
    private int mTimeToFirstFix = 0;
    // time we received our last fix
    private long mLastFixTime;

    private int mPositionMode;
    private GnssPositionMode mLastPositionMode;

    // Current request from underlying location clients.
    private ProviderRequest mProviderRequest;
    // The WorkSource associated with the most recent client request (i.e, most recent call to
    // setRequest).
    private WorkSource mWorkSource = null;
    // True if gps should be disabled (used to support battery saver mode in settings).
    private boolean mDisableGps = false;

    /**
     * Properties loaded from PROPERTIES_FILE.
     * It must be accessed only inside {@link #mHandler}.
     */
    private GnssConfiguration mGnssConfiguration;

    private String mSuplServerHost;
    private int mSuplServerPort = TCP_MIN_PORT;
    private String mC2KServerHost;
    private int mC2KServerPort;
    private boolean mSuplEsEnabled = false;

    private final Looper mLooper;
    private final LocationExtras mLocationExtras = new LocationExtras();
    private final GnssStatusListenerHelper mGnssStatusListenerHelper;
    private final GnssMeasurementsProvider mGnssMeasurementsProvider;
    private final GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private final LocationChangeListener mNetworkLocationListener = new NetworkLocationListener();
    private final LocationChangeListener mFusedLocationListener = new FusedLocationListener();
    private final NtpTimeHelper mNtpTimeHelper;
    private final GnssBatchingProvider mGnssBatchingProvider;
    private final GnssGeofenceProvider mGnssGeofenceProvider;
    // Available only on GNSS HAL 2.0 implementations and later.
    private GnssVisibilityControl mGnssVisibilityControl;

    // Handler for processing events
    private Handler mHandler;

    private final GnssNetworkConnectivityHandler mNetworkConnectivityHandler;
    private final GpsNetInitiatedHandler mNIHandler;

    // Wakelocks
    private final static String WAKELOCK_KEY = "GnssLocationProvider";
    private final PowerManager.WakeLock mWakeLock;
    private static final String DOWNLOAD_EXTRA_WAKELOCK_KEY = "GnssLocationProviderXtraDownload";
    private final PowerManager.WakeLock mDownloadXtraWakeLock;

    // Alarms
    private final static String ALARM_WAKEUP = "com.android.internal.location.ALARM_WAKEUP";
    private final static String ALARM_TIMEOUT = "com.android.internal.location.ALARM_TIMEOUT";

    private final PowerManager mPowerManager;
    private final AlarmManager mAlarmManager;
    private final PendingIntent mWakeupIntent;
    private final PendingIntent mTimeoutIntent;

    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStats;

    // Current list of underlying location clients.
    // only modified on handler thread
    private WorkSource mClientSource = new WorkSource();

    private GeofenceHardwareImpl mGeofenceHardwareImpl;

    // Volatile for simple inter-thread sync on these values.
    private volatile int mHardwareYear = 0;
    private volatile String mHardwareModelName;

    // Set lower than the current ITAR limit of 600m/s to allow this to trigger even if GPS HAL
    // stops output right at 600m/s, depriving this of the information of a device that reaches
    // greater than 600m/s, and higher than the speed of sound to avoid impacting most use cases.
    private static final float ITAR_SPEED_LIMIT_METERS_PER_SECOND = 400.0F;

    private volatile boolean mItarSpeedLimitExceeded = false;

    // GNSS Metrics
    private GnssMetrics mGnssMetrics;

    public GnssStatusListenerHelper getGnssStatusProvider() {
        return mGnssStatusListenerHelper;
    }

    public IGpsGeofenceHardware getGpsGeofenceProxy() {
        return mGnssGeofenceProvider;
    }

    public GnssMeasurementsProvider getGnssMeasurementsProvider() {
        return mGnssMeasurementsProvider;
    }

    public GnssNavigationMessageProvider getGnssNavigationMessageProvider() {
        return mGnssNavigationMessageProvider;
    }
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "receive broadcast intent, action: " + action);
            if (action == null) {
                return;
            }

            switch (action) {
                case ALARM_WAKEUP:
                    startNavigating();
                    break;
                case ALARM_TIMEOUT:
                    hibernate();
                    break;
                case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                case Intent.ACTION_SCREEN_OFF:
                case Intent.ACTION_SCREEN_ON:
                    updateLowPowerMode();
                    break;
                case CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED:
                    subscriptionOrCarrierConfigChanged(context);
                    break;
            }
        }
    };

    // TODO: replace OnSubscriptionsChangedListener with ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED
    //       broadcast receiver.
    private final OnSubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    sendMessage(SUBSCRIPTION_OR_CARRIER_CONFIG_CHANGED, 0, null);
                }
            };

    /**
     * Implements {@link GnssSatelliteBlacklistCallback#onUpdateSatelliteBlacklist}.
     */
    @Override
    public void onUpdateSatelliteBlacklist(int[] constellations, int[] svids) {
        mHandler.post(() -> mGnssConfiguration.setSatelliteBlacklist(constellations, svids));
    }

    private void subscriptionOrCarrierConfigChanged(Context context) {
        if (DEBUG) Log.d(TAG, "received SIM related action: ");
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        CarrierConfigManager configManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        String mccMnc = phone.getSimOperator();
        boolean isKeepLppProfile = false;
        if (!TextUtils.isEmpty(mccMnc)) {
            if (DEBUG) Log.d(TAG, "SIM MCC/MNC is available: " + mccMnc);
            synchronized (mLock) {
                if (configManager != null) {
                    PersistableBundle b = configManager.getConfig();
                    if (b != null) {
                        isKeepLppProfile =
                                b.getBoolean(CarrierConfigManager.Gps.KEY_PERSIST_LPP_MODE_BOOL);
                    }
                }
                if (isKeepLppProfile) {
                    // load current properties for the carrier
                    mGnssConfiguration.loadPropertiesFromCarrierConfig();
                    String lpp_profile = mGnssConfiguration.getLppProfile();
                    // set the persist property LPP_PROFILE for the value
                    if (lpp_profile != null) {
                        SystemProperties.set(GnssConfiguration.LPP_PROFILE, lpp_profile);
                    }
                } else {
                    // reset the persist property
                    SystemProperties.set(GnssConfiguration.LPP_PROFILE, "");
                }
                reloadGpsProperties();
                mNIHandler.setSuplEsEnabled(mSuplEsEnabled);
            }
        } else {
            if (DEBUG) Log.d(TAG, "SIM MCC/MNC is still not available");
        }
    }

    private void updateLowPowerMode() {
        // Disable GPS if we are in device idle mode.
        boolean disableGps = mPowerManager.isDeviceIdleMode();
        final PowerSaveState result =
                mPowerManager.getPowerSaveState(ServiceType.LOCATION);
        switch (result.locationMode) {
            case PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF:
            case PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF:
                // If we are in battery saver mode and the screen is off, disable GPS.
                disableGps |= result.batterySaverEnabled && !mPowerManager.isInteractive();
                break;
        }
        if (disableGps != mDisableGps) {
            mDisableGps = disableGps;
            updateEnabled();
            updateRequirements();
        }
    }

    public static boolean isSupported() {
        return native_is_supported();
    }

    private void reloadGpsProperties() {
        mGnssConfiguration.reloadGpsProperties();
        setSuplHostPort();
        // TODO: we should get rid of C2K specific setting.
        mC2KServerHost = mGnssConfiguration.getC2KHost();
        mC2KServerPort = mGnssConfiguration.getC2KPort(TCP_MIN_PORT);
        mNIHandler.setEmergencyExtensionSeconds(mGnssConfiguration.getEsExtensionSec());
        mSuplEsEnabled = mGnssConfiguration.getSuplEs(0) == 1;
        if (mGnssVisibilityControl != null) {
            mGnssVisibilityControl.updateProxyApps(mGnssConfiguration.getProxyApps());
        }
    }

    public GnssLocationProvider(Context context, LocationProviderManager locationProviderManager,
            Looper looper) {
        super(context, locationProviderManager);

        mLooper = looper;

        // Create a wake lock
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
        mWakeLock.setReferenceCounted(true);

        // Create a separate wake lock for xtra downloader as it may be released due to timeout.
        mDownloadXtraWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, DOWNLOAD_EXTRA_WAKELOCK_KEY);
        mDownloadXtraWakeLock.setReferenceCounted(true);

        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mWakeupIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ALARM_WAKEUP), 0);
        mTimeoutIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ALARM_TIMEOUT), 0);

        mNetworkConnectivityHandler = new GnssNetworkConnectivityHandler(
                context,
                GnssLocationProvider.this::onNetworkAvailable,
                looper);

        // App ops service to keep track of who is accessing the GPS
        mAppOps = mContext.getSystemService(AppOpsManager.class);

        // Battery statistics service to be notified when GPS turns on or off
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        // Construct internal handler
        mHandler = new ProviderHandler(looper);

        // Load GPS configuration and register listeners in the background:
        // some operations, such as opening files and registering broadcast receivers, can take a
        // relative long time, so the ctor() is kept to create objects needed by this instance,
        // while IO initialization and registration is delegated to our internal handler
        // this approach is just fine because events are posted to our handler anyway
        mGnssConfiguration = new GnssConfiguration(mContext);
        sendMessage(INITIALIZE_HANDLER, 0, null);

        // Create a GPS net-initiated handler.
        mNIHandler = new GpsNetInitiatedHandler(context,
                mNetInitiatedListener,
                mSuplEsEnabled);

        mGnssStatusListenerHelper = new GnssStatusListenerHelper(mContext, mHandler) {
            @Override
            protected boolean isAvailableInPlatform() {
                return isSupported();
            }

            @Override
            protected boolean isGpsEnabled() {
                return isEnabled();
            }
        };

        mGnssMeasurementsProvider = new GnssMeasurementsProvider(mContext, mHandler) {
            @Override
            protected boolean isGpsEnabled() {
                return isEnabled();
            }
        };

        mGnssNavigationMessageProvider = new GnssNavigationMessageProvider(mContext, mHandler) {
            @Override
            protected boolean isGpsEnabled() {
                return isEnabled();
            }
        };

        mGnssMetrics = new GnssMetrics(mBatteryStats);
        mNtpTimeHelper = new NtpTimeHelper(mContext, looper, this);
        GnssSatelliteBlacklistHelper gnssSatelliteBlacklistHelper =
                new GnssSatelliteBlacklistHelper(mContext,
                        looper, this);
        mHandler.post(gnssSatelliteBlacklistHelper::updateSatelliteBlacklist);
        mGnssBatchingProvider = new GnssBatchingProvider();
        mGnssGeofenceProvider = new GnssGeofenceProvider();

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (getSendingUserId() == UserHandle.USER_ALL) {
                    mShutdown = true;
                    updateEnabled();
                }
            }
        }, UserHandle.ALL, new IntentFilter(Intent.ACTION_SHUTDOWN), null, mHandler);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE),
                true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateEnabled();
                    }
                }, UserHandle.USER_ALL);

        setProperties(PROPERTIES);
        setEnabled(true);
    }

    /**
     * Implements {@link InjectNtpTimeCallback#injectTime}
     */
    @Override
    public void injectTime(long time, long timeReference, int uncertainty) {
        native_inject_time(time, timeReference, uncertainty);
    }

    /**
     * Implements {@link GnssNetworkConnectivityHandler.GnssNetworkListener#onNetworkAvailable()}
     */
    private void onNetworkAvailable() {
        mNtpTimeHelper.onNetworkAvailable();
        if (mDownloadXtraDataPending == STATE_PENDING_NETWORK) {
            if (mSupportsXtra) {
                // Download only if supported, (prevents an unnecessary on-boot download)
                xtraDownloadRequest();
            }
        }
    }

    private void handleRequestLocation(boolean independentFromGnss) {
        if (isRequestLocationRateLimited()) {
            if (DEBUG) {
                Log.d(TAG, "RequestLocation is denied due to too frequent requests.");
            }
            return;
        }
        ContentResolver resolver = mContext.getContentResolver();
        long durationMillis = Settings.Global.getLong(
                resolver,
                Settings.Global.GNSS_HAL_LOCATION_REQUEST_DURATION_MILLIS,
                LOCATION_UPDATE_DURATION_MILLIS);
        if (durationMillis == 0) {
            Log.i(TAG, "GNSS HAL location request is disabled by Settings.");
            return;
        }

        LocationManager locationManager = (LocationManager) mContext.getSystemService(
                Context.LOCATION_SERVICE);
        String provider;
        LocationChangeListener locationListener;

        if (independentFromGnss) {
            // For fast GNSS TTFF
            provider = LocationManager.NETWORK_PROVIDER;
            locationListener = mNetworkLocationListener;
        } else {
            // For Device-Based Hybrid (E911)
            provider = LocationManager.FUSED_PROVIDER;
            locationListener = mFusedLocationListener;
        }

        Log.i(TAG,
                String.format(
                        "GNSS HAL Requesting location updates from %s provider for %d millis.",
                        provider, durationMillis));
        try {
            locationManager.requestLocationUpdates(provider,
                    LOCATION_UPDATE_MIN_TIME_INTERVAL_MILLIS, /*minDistance=*/ 0,
                    locationListener, mHandler.getLooper());
            locationListener.mNumLocationUpdateRequest++;
            mHandler.postDelayed(() -> {
                if (--locationListener.mNumLocationUpdateRequest == 0) {
                    Log.i(TAG,
                            String.format("Removing location updates from %s provider.", provider));
                    locationManager.removeUpdates(locationListener);
                }
            }, durationMillis);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unable to request location.", e);
        }
    }

    private void injectBestLocation(Location location) {
        int gnssLocationFlags = LOCATION_HAS_LAT_LONG |
                (location.hasAltitude() ? LOCATION_HAS_ALTITUDE : 0) |
                (location.hasSpeed() ? LOCATION_HAS_SPEED : 0) |
                (location.hasBearing() ? LOCATION_HAS_BEARING : 0) |
                (location.hasAccuracy() ? LOCATION_HAS_HORIZONTAL_ACCURACY : 0) |
                (location.hasVerticalAccuracy() ? LOCATION_HAS_VERTICAL_ACCURACY : 0) |
                (location.hasSpeedAccuracy() ? LOCATION_HAS_SPEED_ACCURACY : 0) |
                (location.hasBearingAccuracy() ? LOCATION_HAS_BEARING_ACCURACY : 0);

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

        int elapsedRealtimeFlags = ELAPSED_REALTIME_HAS_TIMESTAMP_NS;
        long elapsedRealtimeNanos = location.getElapsedRealtimeNanos();

        native_inject_best_location(
                gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                altitudeMeters, speedMetersPerSec, bearingDegrees,
                horizontalAccuracyMeters, verticalAccuracyMeters,
                speedAccuracyMetersPerSecond, bearingAccuracyDegrees, timestamp,
                elapsedRealtimeFlags, elapsedRealtimeNanos);
    }

    /** Returns true if the location request is too frequent. */
    private boolean isRequestLocationRateLimited() {
        // TODO: implement exponential backoff.
        return false;
    }

    private void handleDownloadXtraData() {
        if (!mSupportsXtra) {
            // native code reports xtra not supported, don't try
            Log.d(TAG, "handleDownloadXtraData() called when Xtra not supported");
            return;
        }
        if (mDownloadXtraDataPending == STATE_DOWNLOADING) {
            // already downloading data
            return;
        }
        if (!mNetworkConnectivityHandler.isDataNetworkConnected()) {
            // try again when network is up
            mDownloadXtraDataPending = STATE_PENDING_NETWORK;
            return;
        }
        mDownloadXtraDataPending = STATE_DOWNLOADING;

        // hold wake lock while task runs
        mDownloadXtraWakeLock.acquire(DOWNLOAD_XTRA_DATA_TIMEOUT_MS);
        Log.i(TAG, "WakeLock acquired by handleDownloadXtraData()");
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            GpsXtraDownloader xtraDownloader = new GpsXtraDownloader(
                    mGnssConfiguration.getProperties());
            byte[] data = xtraDownloader.downloadXtraData();
            if (data != null) {
                if (DEBUG) Log.d(TAG, "calling native_inject_xtra_data");
                native_inject_xtra_data(data, data.length);
                mXtraBackOff.reset();
            }

            sendMessage(DOWNLOAD_XTRA_DATA_FINISHED, 0, null);

            if (data == null) {
                // try again later
                // since this is delayed and not urgent we do not hold a wake lock here
                mHandler.sendEmptyMessageDelayed(DOWNLOAD_XTRA_DATA,
                        mXtraBackOff.nextBackoffMillis());
            }

            // Release wake lock held by task, synchronize on mLock in case multiple
            // download tasks overrun.
            synchronized (mLock) {
                if (mDownloadXtraWakeLock.isHeld()) {
                    // This wakelock may have time-out, if a timeout was specified.
                    // Catch (and ignore) any timeout exceptions.
                    try {
                        mDownloadXtraWakeLock.release();
                        if (DEBUG) Log.d(TAG, "WakeLock released by handleDownloadXtraData()");
                    } catch (Exception e) {
                        Log.i(TAG, "Wakelock timeout & release race exception in "
                                + "handleDownloadXtraData()", e);
                    }
                } else {
                    Log.e(TAG, "WakeLock expired before release in "
                            + "handleDownloadXtraData()");
                }
            }
        });
    }

    private void handleUpdateLocation(Location location) {
        if (location.hasAccuracy()) {
            native_inject_location(location.getLatitude(), location.getLongitude(),
                    location.getAccuracy());
        }
    }

    private void setSuplHostPort() {
        mSuplServerHost = mGnssConfiguration.getSuplHost();
        mSuplServerPort = mGnssConfiguration.getSuplPort(TCP_MIN_PORT);
        if (mSuplServerHost != null
                && mSuplServerPort > TCP_MIN_PORT
                && mSuplServerPort <= TCP_MAX_PORT) {
            native_set_agps_server(GnssNetworkConnectivityHandler.AGPS_TYPE_SUPL,
                    mSuplServerHost, mSuplServerPort);
        }
    }

    /**
     * Checks what SUPL mode to use, according to the AGPS mode as well as the
     * allowed mode from properties.
     *
     * @param agpsEnabled whether AGPS is enabled by settings value
     * @return SUPL mode (MSA vs MSB vs STANDALONE)
     */
    private int getSuplMode(boolean agpsEnabled) {
        if (agpsEnabled) {
            int suplMode = mGnssConfiguration.getSuplMode(0);
            if (suplMode == 0) {
                return GPS_POSITION_MODE_STANDALONE;
            }

            // MS-Based is the preferred mode for Assisted-GPS position computation, so we favor
            // such mode when it is available
            if (hasCapability(GPS_CAPABILITY_MSB) && (suplMode & AGPS_SUPL_MODE_MSB) != 0) {
                return GPS_POSITION_MODE_MS_BASED;
            }
        }
        return GPS_POSITION_MODE_STANDALONE;
    }

    private boolean handleEnable() {
        if (DEBUG) Log.d(TAG, "handleEnable");

        boolean inited = native_init();

        if (inited) {
            mSupportsXtra = native_supports_xtra();

            // TODO: remove the following native calls if we can make sure they are redundant.
            if (mSuplServerHost != null) {
                native_set_agps_server(GnssNetworkConnectivityHandler.AGPS_TYPE_SUPL,
                        mSuplServerHost, mSuplServerPort);
            }
            if (mC2KServerHost != null) {
                native_set_agps_server(GnssNetworkConnectivityHandler.AGPS_TYPE_C2K,
                        mC2KServerHost, mC2KServerPort);
            }

            mGnssMeasurementsProvider.onGpsEnabledChanged();
            mGnssNavigationMessageProvider.onGpsEnabledChanged();
            mGnssBatchingProvider.enable();
        } else {
            Log.w(TAG, "Failed to enable location provider");
        }

        return inited;
    }

    private void handleDisable() {
        if (DEBUG) Log.d(TAG, "handleDisable");

        updateClientUids(new WorkSource());
        stopNavigating();
        mAlarmManager.cancel(mWakeupIntent);
        mAlarmManager.cancel(mTimeoutIntent);

        mGnssBatchingProvider.disable();
        // do this before releasing wakelock
        native_cleanup();

        mGnssMeasurementsProvider.onGpsEnabledChanged();
        mGnssNavigationMessageProvider.onGpsEnabledChanged();
    }

    private void updateEnabled() {
        synchronized (mLock) {
            boolean enabled =
                    ((mProviderRequest != null && mProviderRequest.reportLocation
                            && mProviderRequest.locationSettingsIgnored) || (
                            mContext.getSystemService(LocationManager.class).isLocationEnabled()
                                    && !mDisableGps)) && !mShutdown;
            if (enabled == mEnabled) {
                return;
            }

            if (enabled) {
                mEnabled = handleEnable();
            } else {
                mEnabled = false;
                handleDisable();
            }
        }
    }

    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    @Override
    public int getStatus(Bundle extras) {
        mLocationExtras.setBundle(extras);
        return mStatus;
    }

    private void updateStatus(int status) {
        if (status != mStatus) {
            mStatus = status;
            mStatusUpdateTime = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public long getStatusUpdateTime() {
        return mStatusUpdateTime;
    }

    @Override
    public void setRequest(ProviderRequest request, WorkSource source) {
        sendMessage(SET_REQUEST, 0, new GpsRequest(request, source));
    }

    private void handleSetRequest(ProviderRequest request, WorkSource source) {
        mProviderRequest = request;
        mWorkSource = source;
        updateEnabled();
        updateRequirements();
    }

    // Called when the requirements for GPS may have changed
    private void updateRequirements() {
        if (mProviderRequest == null || mWorkSource == null) {
            return;
        }

        if (DEBUG) Log.d(TAG, "setRequest " + mProviderRequest);
        if (mProviderRequest.reportLocation && isEnabled()) {
            // update client uids
            updateClientUids(mWorkSource);

            mFixInterval = (int) mProviderRequest.interval;
            mLowPowerMode = mProviderRequest.lowPowerMode;
            // check for overflow
            if (mFixInterval != mProviderRequest.interval) {
                Log.w(TAG, "interval overflow: " + mProviderRequest.interval);
                mFixInterval = Integer.MAX_VALUE;
            }

            // apply request to GPS engine
            if (mStarted && hasCapability(GPS_CAPABILITY_SCHEDULING)) {
                // change period and/or lowPowerMode
                if (!setPositionMode(mPositionMode, GPS_POSITION_RECURRENCE_PERIODIC,
                        mFixInterval, 0, 0, mLowPowerMode)) {
                    Log.e(TAG, "set_position_mode failed in updateRequirements");
                }
            } else if (!mStarted) {
                // start GPS
                startNavigating();
            } else {
                // GNSS Engine is already ON, but no GPS_CAPABILITY_SCHEDULING
                mAlarmManager.cancel(mTimeoutIntent);
                if (mFixInterval >= NO_FIX_TIMEOUT) {
                    // set timer to give up if we do not receive a fix within NO_FIX_TIMEOUT
                    // and our fix interval is not short
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + NO_FIX_TIMEOUT, mTimeoutIntent);
                }
            }
        } else {
            updateClientUids(new WorkSource());

            stopNavigating();
            mAlarmManager.cancel(mWakeupIntent);
            mAlarmManager.cancel(mTimeoutIntent);
        }
    }

    private boolean setPositionMode(int mode, int recurrence, int minInterval,
            int preferredAccuracy, int preferredTime, boolean lowPowerMode) {
        GnssPositionMode positionMode = new GnssPositionMode(mode, recurrence, minInterval,
                preferredAccuracy, preferredTime, lowPowerMode);
        if (mLastPositionMode != null && mLastPositionMode.equals(positionMode)) {
            return true;
        }

        boolean result = native_set_position_mode(mode, recurrence, minInterval,
                preferredAccuracy, preferredTime, lowPowerMode);
        if (result) {
            mLastPositionMode = positionMode;
        } else {
            mLastPositionMode = null;
        }
        return result;
    }

    private void updateClientUids(WorkSource source) {
        if (source.equals(mClientSource)) {
            return;
        }

        // (1) Inform BatteryStats that the list of IDs we're tracking changed.
        try {
            mBatteryStats.noteGpsChanged(mClientSource, source);
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException", e);
        }

        // (2) Inform AppOps service about the list of changes to UIDs.

        List<WorkChain>[] diffs = WorkSource.diffChains(mClientSource, source);
        if (diffs != null) {
            List<WorkChain> newChains = diffs[0];
            List<WorkChain> goneChains = diffs[1];

            if (newChains != null) {
                for (WorkChain newChain : newChains) {
                    mAppOps.startOpNoThrow(AppOpsManager.OP_GPS, newChain.getAttributionUid(),
                            newChain.getAttributionTag());
                }
            }

            if (goneChains != null) {
                for (WorkChain goneChain : goneChains) {
                    mAppOps.finishOp(AppOpsManager.OP_GPS, goneChain.getAttributionUid(),
                            goneChain.getAttributionTag());
                }
            }

            mClientSource.transferWorkChains(source);
        }

        // Update the flat UIDs and names list and inform app-ops of all changes.
        WorkSource[] changes = mClientSource.setReturningDiffs(source);
        if (changes != null) {
            WorkSource newWork = changes[0];
            WorkSource goneWork = changes[1];

            // Update sources that were not previously tracked.
            if (newWork != null) {
                for (int i = 0; i < newWork.size(); i++) {
                    mAppOps.startOpNoThrow(AppOpsManager.OP_GPS,
                            newWork.get(i), newWork.getName(i));
                }
            }

            // Update sources that are no longer tracked.
            if (goneWork != null) {
                for (int i = 0; i < goneWork.size(); i++) {
                    mAppOps.finishOp(AppOpsManager.OP_GPS, goneWork.get(i), goneWork.getName(i));
                }
            }
        }
    }

    @Override
    public void sendExtraCommand(String command, Bundle extras) {

        long identity = Binder.clearCallingIdentity();
        try {
            if ("delete_aiding_data".equals(command)) {
                deleteAidingData(extras);
            } else if ("force_time_injection".equals(command)) {
                requestUtcTime();
            } else if ("force_xtra_injection".equals(command)) {
                if (mSupportsXtra) {
                    xtraDownloadRequest();
                }
            } else {
                Log.w(TAG, "sendExtraCommand: unknown command " + command);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void deleteAidingData(Bundle extras) {
        int flags;

        if (extras == null) {
            flags = GPS_DELETE_ALL;
        } else {
            flags = 0;
            if (extras.getBoolean("ephemeris")) flags |= GPS_DELETE_EPHEMERIS;
            if (extras.getBoolean("almanac")) flags |= GPS_DELETE_ALMANAC;
            if (extras.getBoolean("position")) flags |= GPS_DELETE_POSITION;
            if (extras.getBoolean("time")) flags |= GPS_DELETE_TIME;
            if (extras.getBoolean("iono")) flags |= GPS_DELETE_IONO;
            if (extras.getBoolean("utc")) flags |= GPS_DELETE_UTC;
            if (extras.getBoolean("health")) flags |= GPS_DELETE_HEALTH;
            if (extras.getBoolean("svdir")) flags |= GPS_DELETE_SVDIR;
            if (extras.getBoolean("svsteer")) flags |= GPS_DELETE_SVSTEER;
            if (extras.getBoolean("sadata")) flags |= GPS_DELETE_SADATA;
            if (extras.getBoolean("rti")) flags |= GPS_DELETE_RTI;
            if (extras.getBoolean("celldb-info")) flags |= GPS_DELETE_CELLDB_INFO;
            if (extras.getBoolean("all")) flags |= GPS_DELETE_ALL;
        }

        if (flags != 0) {
            native_delete_aiding_data(flags);
        }
    }

    private void startNavigating() {
        if (!mStarted) {
            if (DEBUG) Log.d(TAG, "startNavigating");
            mTimeToFirstFix = 0;
            mLastFixTime = 0;
            mStarted = true;
            mPositionMode = GPS_POSITION_MODE_STANDALONE;
            // Notify about suppressed output, if speed limit was previously exceeded.
            // Elsewhere, we check again with every speed output reported.
            if (mItarSpeedLimitExceeded) {
                Log.i(TAG, "startNavigating with ITAR limit in place. Output limited  " +
                        "until slow enough speed reported.");
            }

            boolean agpsEnabled =
                    (Settings.Global.getInt(mContext.getContentResolver(),
                            Settings.Global.ASSISTED_GPS_ENABLED, 1) != 0);
            mPositionMode = getSuplMode(agpsEnabled);

            if (DEBUG) {
                String mode;

                switch (mPositionMode) {
                    case GPS_POSITION_MODE_STANDALONE:
                        mode = "standalone";
                        break;
                    case GPS_POSITION_MODE_MS_ASSISTED:
                        mode = "MS_ASSISTED";
                        break;
                    case GPS_POSITION_MODE_MS_BASED:
                        mode = "MS_BASED";
                        break;
                    default:
                        mode = "unknown";
                        break;
                }
                Log.d(TAG, "setting position_mode to " + mode);
            }

            int interval = (hasCapability(GPS_CAPABILITY_SCHEDULING) ? mFixInterval : 1000);
            mLowPowerMode = mProviderRequest.lowPowerMode;
            if (!setPositionMode(mPositionMode, GPS_POSITION_RECURRENCE_PERIODIC,
                    interval, 0, 0, mLowPowerMode)) {
                mStarted = false;
                Log.e(TAG, "set_position_mode failed in startNavigating()");
                return;
            }
            if (!native_start()) {
                mStarted = false;
                Log.e(TAG, "native_start failed in startNavigating()");
                return;
            }

            // reset SV count to zero
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE);
            mLocationExtras.reset();
            mFixRequestTime = SystemClock.elapsedRealtime();
            if (!hasCapability(GPS_CAPABILITY_SCHEDULING)) {
                // set timer to give up if we do not receive a fix within NO_FIX_TIMEOUT
                // and our fix interval is not short
                if (mFixInterval >= NO_FIX_TIMEOUT) {
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + NO_FIX_TIMEOUT, mTimeoutIntent);
                }
            }
        }
    }

    private void stopNavigating() {
        if (DEBUG) Log.d(TAG, "stopNavigating");
        if (mStarted) {
            mStarted = false;
            native_stop();
            mLastFixTime = 0;
            // native_stop() may reset the position mode in hardware.
            mLastPositionMode = null;

            // reset SV count to zero
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE);
            mLocationExtras.reset();
        }
    }

    private void hibernate() {
        // stop GPS until our next fix interval arrives
        stopNavigating();
        mAlarmManager.cancel(mTimeoutIntent);
        mAlarmManager.cancel(mWakeupIntent);
        long now = SystemClock.elapsedRealtime();
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + mFixInterval, mWakeupIntent);
    }

    private boolean hasCapability(int capability) {
        return ((mEngineCapabilities & capability) != 0);
    }

    @NativeEntryPoint
    private void reportLocation(boolean hasLatLong, Location location) {
        sendMessage(REPORT_LOCATION, hasLatLong ? 1 : 0, location);
    }

    private void handleReportLocation(boolean hasLatLong, Location location) {
        if (location.hasSpeed()) {
            mItarSpeedLimitExceeded = location.getSpeed() > ITAR_SPEED_LIMIT_METERS_PER_SECOND;
        }

        if (mItarSpeedLimitExceeded) {
            Log.i(TAG, "Hal reported a speed in excess of ITAR limit." +
                    "  GPS/GNSS Navigation output blocked.");
            if (mStarted) {
                mGnssMetrics.logReceivedLocationStatus(false);
            }
            return;  // No output of location allowed
        }

        if (VERBOSE) Log.v(TAG, "reportLocation " + location.toString());

        location.setExtras(mLocationExtras.getBundle());

        reportLocation(location);

        if (mStarted) {
            mGnssMetrics.logReceivedLocationStatus(hasLatLong);
            if (hasLatLong) {
                if (location.hasAccuracy()) {
                    mGnssMetrics.logPositionAccuracyMeters(location.getAccuracy());
                }
                if (mTimeToFirstFix > 0) {
                    int timeBetweenFixes = (int) (SystemClock.elapsedRealtime() - mLastFixTime);
                    mGnssMetrics.logMissedReports(mFixInterval, timeBetweenFixes);
                }
            }
        }

        mLastFixTime = SystemClock.elapsedRealtime();
        // report time to first fix
        if (mTimeToFirstFix == 0 && hasLatLong) {
            mTimeToFirstFix = (int) (mLastFixTime - mFixRequestTime);
            if (DEBUG) Log.d(TAG, "TTFF: " + mTimeToFirstFix);
            if (mStarted) {
                mGnssMetrics.logTimeToFirstFixMilliSecs(mTimeToFirstFix);
            }

            // notify status listeners
            mGnssStatusListenerHelper.onFirstFix(mTimeToFirstFix);
        }

        if (mStarted && mStatus != LocationProvider.AVAILABLE) {
            // For devices that use framework scheduling, a timer may be set to ensure we don't
            // spend too much power searching for a location, when the requested update rate is
            // slow.
            // As we just recievied a location, we'll cancel that timer.
            if (!hasCapability(GPS_CAPABILITY_SCHEDULING) && mFixInterval < NO_FIX_TIMEOUT) {
                mAlarmManager.cancel(mTimeoutIntent);
            }

            // send an intent to notify that the GPS is receiving fixes.
            Intent intent = new Intent(LocationManager.GPS_FIX_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, true);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            updateStatus(LocationProvider.AVAILABLE);
        }

        if (!hasCapability(GPS_CAPABILITY_SCHEDULING) && mStarted &&
                mFixInterval > GPS_POLLING_THRESHOLD_INTERVAL) {
            if (DEBUG) Log.d(TAG, "got fix, hibernating");
            hibernate();
        }
    }

    @NativeEntryPoint
    private void reportStatus(int status) {
        if (DEBUG) Log.v(TAG, "reportStatus status: " + status);

        boolean wasNavigating = mNavigating;
        switch (status) {
            case GPS_STATUS_SESSION_BEGIN:
                mNavigating = true;
                break;
            case GPS_STATUS_SESSION_END:
                mNavigating = false;
                break;
            case GPS_STATUS_ENGINE_ON:
                break;
            case GPS_STATUS_ENGINE_OFF:
                mNavigating = false;
                break;
        }

        if (wasNavigating != mNavigating) {
            mGnssStatusListenerHelper.onStatusChanged(mNavigating);

            // send an intent to notify that the GPS has been enabled or disabled
            Intent intent = new Intent(LocationManager.GPS_ENABLED_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, mNavigating);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    // Helper class to carry data to handler for reportSvStatus
    private static class SvStatusInfo {
        private int mSvCount;
        private int[] mSvidWithFlags;
        private float[] mCn0s;
        private float[] mSvElevations;
        private float[] mSvAzimuths;
        private float[] mSvCarrierFreqs;
    }

    @NativeEntryPoint
    private void reportSvStatus(int svCount, int[] svidWithFlags, float[] cn0s,
            float[] svElevations, float[] svAzimuths, float[] svCarrierFreqs) {
        SvStatusInfo svStatusInfo = new SvStatusInfo();
        svStatusInfo.mSvCount = svCount;
        svStatusInfo.mSvidWithFlags = svidWithFlags;
        svStatusInfo.mCn0s = cn0s;
        svStatusInfo.mSvElevations = svElevations;
        svStatusInfo.mSvAzimuths = svAzimuths;
        svStatusInfo.mSvCarrierFreqs = svCarrierFreqs;

        sendMessage(REPORT_SV_STATUS, 0, svStatusInfo);
    }

    private void handleReportSvStatus(SvStatusInfo info) {
        mGnssStatusListenerHelper.onSvStatusChanged(
                info.mSvCount,
                info.mSvidWithFlags,
                info.mCn0s,
                info.mSvElevations,
                info.mSvAzimuths,
                info.mSvCarrierFreqs);

        // Log CN0 as part of GNSS metrics
        mGnssMetrics.logCn0(info.mCn0s, info.mSvCount);

        if (VERBOSE) {
            Log.v(TAG, "SV count: " + info.mSvCount);
        }
        // Calculate number of satellites used in fix.
        int usedInFixCount = 0;
        int maxCn0 = 0;
        int meanCn0 = 0;
        for (int i = 0; i < info.mSvCount; i++) {
            if ((info.mSvidWithFlags[i] & GnssStatus.GNSS_SV_FLAGS_USED_IN_FIX) != 0) {
                ++usedInFixCount;
                if (info.mCn0s[i] > maxCn0) {
                    maxCn0 = (int) info.mCn0s[i];
                }
                meanCn0 += info.mCn0s[i];
            }
            if (VERBOSE) {
                Log.v(TAG, "svid: " + (info.mSvidWithFlags[i] >> GnssStatus.SVID_SHIFT_WIDTH) +
                        " cn0: " + info.mCn0s[i] +
                        " elev: " + info.mSvElevations[i] +
                        " azimuth: " + info.mSvAzimuths[i] +
                        " carrier frequency: " + info.mSvCarrierFreqs[i] +
                        ((info.mSvidWithFlags[i] & GnssStatus.GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA) == 0
                                ? "  " : " E") +
                        ((info.mSvidWithFlags[i] & GnssStatus.GNSS_SV_FLAGS_HAS_ALMANAC_DATA) == 0
                                ? "  " : " A") +
                        ((info.mSvidWithFlags[i] & GnssStatus.GNSS_SV_FLAGS_USED_IN_FIX) == 0
                                ? "" : "U") +
                        ((info.mSvidWithFlags[i] &
                                GnssStatus.GNSS_SV_FLAGS_HAS_CARRIER_FREQUENCY) == 0
                                ? "" : "F"));
            }
        }
        if (usedInFixCount > 0) {
            meanCn0 /= usedInFixCount;
        }
        // return number of sats used in fix instead of total reported
        mLocationExtras.set(usedInFixCount, meanCn0, maxCn0);

        if (mNavigating && mStatus == LocationProvider.AVAILABLE && mLastFixTime > 0 &&
                SystemClock.elapsedRealtime() - mLastFixTime > RECENT_FIX_TIMEOUT) {
            // send an intent to notify that the GPS is no longer receiving fixes.
            Intent intent = new Intent(LocationManager.GPS_FIX_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, false);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE);
        }
    }

    @NativeEntryPoint
    private void reportAGpsStatus(int agpsType, int agpsStatus, byte[] suplIpAddr) {
        mNetworkConnectivityHandler.onReportAGpsStatus(agpsType, agpsStatus, suplIpAddr);
    }

    @NativeEntryPoint
    private void reportNmea(long timestamp) {
        if (!mItarSpeedLimitExceeded) {
            int length = native_read_nmea(mNmeaBuffer, mNmeaBuffer.length);
            String nmea = new String(mNmeaBuffer, 0 /* offset */, length);
            mGnssStatusListenerHelper.onNmeaReceived(timestamp, nmea);
        }
    }

    @NativeEntryPoint
    private void reportMeasurementData(GnssMeasurementsEvent event) {
        if (!mItarSpeedLimitExceeded) {
            // send to handler to allow native to return quickly
            mHandler.post(() -> mGnssMeasurementsProvider.onMeasurementsAvailable(event));
        }
    }

    @NativeEntryPoint
    private void reportNavigationMessage(GnssNavigationMessage event) {
        if (!mItarSpeedLimitExceeded) {
            // send to handler to allow native to return quickly
            mHandler.post(() -> mGnssNavigationMessageProvider.onNavigationMessageAvailable(event));
        }
    }

    @NativeEntryPoint
    private void setEngineCapabilities(final int capabilities) {
        // send to handler thread for fast native return, and in-order handling
        mHandler.post(
                () -> {
                    mEngineCapabilities = capabilities;

                    if (hasCapability(GPS_CAPABILITY_ON_DEMAND_TIME)) {
                        mNtpTimeHelper.enablePeriodicTimeInjection();
                        requestUtcTime();
                    }

                    mGnssMeasurementsProvider.onCapabilitiesUpdated(capabilities);
                    mGnssNavigationMessageProvider.onCapabilitiesUpdated(
                            hasCapability(GPS_CAPABILITY_NAV_MESSAGES));
                    restartRequests();
                });
    }

    private void restartRequests() {
        Log.i(TAG, "restartRequests");

        restartLocationRequest();
        mGnssMeasurementsProvider.resumeIfStarted();
        mGnssNavigationMessageProvider.resumeIfStarted();
        mGnssBatchingProvider.resumeIfStarted();
        mGnssGeofenceProvider.resumeIfStarted();
    }

    private void restartLocationRequest() {
        if (DEBUG) Log.d(TAG, "restartLocationRequest");
        mStarted = false;
        updateRequirements();
    }

    @NativeEntryPoint
    private void setGnssYearOfHardware(final int yearOfHardware) {
        // mHardwareYear is simply set here, to be read elsewhere, and is volatile for safe sync
        if (DEBUG) Log.d(TAG, "setGnssYearOfHardware called with " + yearOfHardware);
        mHardwareYear = yearOfHardware;
    }

    @NativeEntryPoint
    private void setGnssHardwareModelName(final String modelName) {
        // mHardwareModelName is simply set here, to be read elsewhere, and volatile for safe sync
        if (DEBUG) Log.d(TAG, "setGnssModelName called with " + modelName);
        mHardwareModelName = modelName;
    }

    @NativeEntryPoint
    private void reportGnssServiceDied() {
        if (DEBUG) Log.d(TAG, "reportGnssServiceDied");
        mHandler.post(() -> {
            class_init_native();
            native_init_once();
            if (isEnabled()) {
                synchronized (mLock) {
                    mEnabled = false;
                }
                updateEnabled();

                // resend configuration into the restarted HAL service.
                reloadGpsProperties();
            }
        });
    }

    public interface GnssSystemInfoProvider {
        /**
         * Returns the year of underlying GPS hardware.
         */
        int getGnssYearOfHardware();

        /**
         * Returns the model name of underlying GPS hardware.
         */
        String getGnssHardwareModelName();
    }

    /**
     * @hide
     */
    public GnssSystemInfoProvider getGnssSystemInfoProvider() {
        return new GnssSystemInfoProvider() {
            @Override
            public int getGnssYearOfHardware() {
                return mHardwareYear;
            }

            @Override
            public String getGnssHardwareModelName() {
                return mHardwareModelName;
            }
        };
    }

    /**
     * @hide
     */
    public GnssBatchingProvider getGnssBatchingProvider() {
        return mGnssBatchingProvider;
    }

    public interface GnssMetricsProvider {
        /**
         * Returns GNSS metrics as proto string
         */
        String getGnssMetricsAsProtoString();
    }

    /**
     * @hide
     */
    public GnssMetricsProvider getGnssMetricsProvider() {
        return () -> mGnssMetrics.dumpGnssMetricsAsProtoString();
    }

    @NativeEntryPoint
    private void reportLocationBatch(Location[] locationArray) {
        List<Location> locations = new ArrayList<>(Arrays.asList(locationArray));
        if (DEBUG) {
            Log.d(TAG, "Location batch of size " + locationArray.length + " reported");
        }
        reportLocation(locations);
    }

    @NativeEntryPoint
    private void xtraDownloadRequest() {
        if (DEBUG) Log.d(TAG, "xtraDownloadRequest");
        sendMessage(DOWNLOAD_XTRA_DATA, 0, null);
    }

    /**
     * Converts the GPS HAL status to the internal Geofence Hardware status.
     */
    private static int getGeofenceStatus(int status) {
        switch (status) {
            case GPS_GEOFENCE_OPERATION_SUCCESS:
                return GeofenceHardware.GEOFENCE_SUCCESS;
            case GPS_GEOFENCE_ERROR_GENERIC:
                return GeofenceHardware.GEOFENCE_FAILURE;
            case GPS_GEOFENCE_ERROR_ID_EXISTS:
                return GeofenceHardware.GEOFENCE_ERROR_ID_EXISTS;
            case GPS_GEOFENCE_ERROR_INVALID_TRANSITION:
                return GeofenceHardware.GEOFENCE_ERROR_INVALID_TRANSITION;
            case GPS_GEOFENCE_ERROR_TOO_MANY_GEOFENCES:
                return GeofenceHardware.GEOFENCE_ERROR_TOO_MANY_GEOFENCES;
            case GPS_GEOFENCE_ERROR_ID_UNKNOWN:
                return GeofenceHardware.GEOFENCE_ERROR_ID_UNKNOWN;
            default:
                return -1;
        }
    }

    @NativeEntryPoint
    private void reportGeofenceTransition(int geofenceId, Location location, int transition,
            long transitionTimestamp) {
        mHandler.post(() -> {
            if (mGeofenceHardwareImpl == null) {
                mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
            }

            mGeofenceHardwareImpl.reportGeofenceTransition(
                    geofenceId,
                    location,
                    transition,
                    transitionTimestamp,
                    GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                    FusedBatchOptions.SourceTechnologies.GNSS);
        });
    }

    @NativeEntryPoint
    private void reportGeofenceStatus(int status, Location location) {
        mHandler.post(() -> {
            if (mGeofenceHardwareImpl == null) {
                mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
            }
            int monitorStatus = GeofenceHardware.MONITOR_CURRENTLY_UNAVAILABLE;
            if (status == GPS_GEOFENCE_AVAILABLE) {
                monitorStatus = GeofenceHardware.MONITOR_CURRENTLY_AVAILABLE;
            }
            mGeofenceHardwareImpl.reportGeofenceMonitorStatus(
                    GeofenceHardware.MONITORING_TYPE_GPS_HARDWARE,
                    monitorStatus,
                    location,
                    FusedBatchOptions.SourceTechnologies.GNSS);
        });
    }

    @NativeEntryPoint
    private void reportGeofenceAddStatus(int geofenceId, int status) {
        mHandler.post(() -> {
            if (mGeofenceHardwareImpl == null) {
                mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
            }
            mGeofenceHardwareImpl.reportGeofenceAddStatus(geofenceId, getGeofenceStatus(status));
        });
    }

    @NativeEntryPoint
    private void reportGeofenceRemoveStatus(int geofenceId, int status) {
        mHandler.post(() -> {
            if (mGeofenceHardwareImpl == null) {
                mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
            }
            mGeofenceHardwareImpl.reportGeofenceRemoveStatus(geofenceId, getGeofenceStatus(status));
        });
    }

    @NativeEntryPoint
    private void reportGeofencePauseStatus(int geofenceId, int status) {
        mHandler.post(() -> {
            if (mGeofenceHardwareImpl == null) {
                mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
            }
            mGeofenceHardwareImpl.reportGeofencePauseStatus(geofenceId, getGeofenceStatus(status));
        });
    }

    @NativeEntryPoint
    private void reportGeofenceResumeStatus(int geofenceId, int status) {
        mHandler.post(() -> {
            if (mGeofenceHardwareImpl == null) {
                mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
            }
            mGeofenceHardwareImpl.reportGeofenceResumeStatus(geofenceId, getGeofenceStatus(status));
        });
    }

    //=============================================================
    // NI Client support
    //=============================================================
    private final INetInitiatedListener mNetInitiatedListener = new INetInitiatedListener.Stub() {
        // Sends a response for an NI request to HAL.
        @Override
        public boolean sendNiResponse(int notificationId, int userResponse) {
            // TODO Add Permission check

            if (DEBUG) {
                Log.d(TAG, "sendNiResponse, notifId: " + notificationId +
                        ", response: " + userResponse);
            }
            native_send_ni_response(notificationId, userResponse);

            StatsLog.write(StatsLog.GNSS_NI_EVENT_REPORTED,
                    StatsLog.GNSS_NI_EVENT_REPORTED__EVENT_TYPE__NI_RESPONSE,
                    notificationId,
                    /* niType= */ 0,
                    /* needNotify= */ false,
                    /* needVerify= */ false,
                    /* privacyOverride= */ false,
                    /* timeout= */ 0,
                    /* defaultResponse= */ 0,
                    /* requestorId= */ null,
                    /* text= */ null,
                    /* requestorIdEncoding= */ 0,
                    /* textEncoding= */ 0,
                    mSuplEsEnabled,
                    isEnabled(),
                    userResponse);

            return true;
        }
    };

    public INetInitiatedListener getNetInitiatedListener() {
        return mNetInitiatedListener;
    }

    /** Reports a NI notification. */
    @NativeEntryPoint
    public void reportNiNotification(
            int notificationId,
            int niType,
            int notifyFlags,
            int timeout,
            int defaultResponse,
            String requestorId,
            String text,
            int requestorIdEncoding,
            int textEncoding
    ) {
        Log.i(TAG, "reportNiNotification: entered");
        Log.i(TAG, "notificationId: " + notificationId +
                ", niType: " + niType +
                ", notifyFlags: " + notifyFlags +
                ", timeout: " + timeout +
                ", defaultResponse: " + defaultResponse);

        Log.i(TAG, "requestorId: " + requestorId +
                ", text: " + text +
                ", requestorIdEncoding: " + requestorIdEncoding +
                ", textEncoding: " + textEncoding);

        GpsNiNotification notification = new GpsNiNotification();

        notification.notificationId = notificationId;
        notification.niType = niType;
        notification.needNotify = (notifyFlags & GpsNetInitiatedHandler.GPS_NI_NEED_NOTIFY) != 0;
        notification.needVerify = (notifyFlags & GpsNetInitiatedHandler.GPS_NI_NEED_VERIFY) != 0;
        notification.privacyOverride =
                (notifyFlags & GpsNetInitiatedHandler.GPS_NI_PRIVACY_OVERRIDE) != 0;
        notification.timeout = timeout;
        notification.defaultResponse = defaultResponse;
        notification.requestorId = requestorId;
        notification.text = text;
        notification.requestorIdEncoding = requestorIdEncoding;
        notification.textEncoding = textEncoding;

        mNIHandler.handleNiNotification(notification);
        StatsLog.write(StatsLog.GNSS_NI_EVENT_REPORTED,
                StatsLog.GNSS_NI_EVENT_REPORTED__EVENT_TYPE__NI_REQUEST,
                notification.notificationId,
                notification.niType,
                notification.needNotify,
                notification.needVerify,
                notification.privacyOverride,
                notification.timeout,
                notification.defaultResponse,
                notification.requestorId,
                notification.text,
                notification.requestorIdEncoding,
                notification.textEncoding,
                mSuplEsEnabled,
                isEnabled(),
                /* userResponse= */ 0);
    }

    /**
     * We should be careful about receiving null string from the TelephonyManager,
     * because sending null String to JNI function would cause a crash.
     */
    @NativeEntryPoint
    private void requestSetID(int flags) {
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        int type = AGPS_SETID_TYPE_NONE;
        String data = "";

        if ((flags & AGPS_RIL_REQUEST_SETID_IMSI) == AGPS_RIL_REQUEST_SETID_IMSI) {
            String data_temp = phone.getSubscriberId();
            if (data_temp == null) {
                // This means the framework does not have the SIM card ready.
            } else {
                // This means the framework has the SIM card.
                data = data_temp;
                type = AGPS_SETID_TYPE_IMSI;
            }
        } else if ((flags & AGPS_RIL_REQUEST_SETID_MSISDN) == AGPS_RIL_REQUEST_SETID_MSISDN) {
            String data_temp = phone.getLine1Number();
            if (data_temp == null) {
                // This means the framework does not have the SIM card ready.
            } else {
                // This means the framework has the SIM card.
                data = data_temp;
                type = AGPS_SETID_TYPE_MSISDN;
            }
        }
        native_agps_set_id(type, data);
    }

    @NativeEntryPoint
    private void requestLocation(boolean independentFromGnss) {
        if (DEBUG) {
            Log.d(TAG, "requestLocation. independentFromGnss: " + independentFromGnss);
        }
        sendMessage(REQUEST_LOCATION, 0, independentFromGnss);
    }

    @NativeEntryPoint
    private void requestUtcTime() {
        if (DEBUG) Log.d(TAG, "utcTimeRequest");
        sendMessage(INJECT_NTP_TIME, 0, null);
    }

    @NativeEntryPoint
    private void requestRefLocation() {
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        final int phoneType = phone.getPhoneType();
        if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            GsmCellLocation gsm_cell = (GsmCellLocation) phone.getCellLocation();
            if ((gsm_cell != null) && (phone.getNetworkOperator() != null)
                    && (phone.getNetworkOperator().length() > 3)) {
                int type;
                int mcc = Integer.parseInt(phone.getNetworkOperator().substring(0, 3));
                int mnc = Integer.parseInt(phone.getNetworkOperator().substring(3));
                int networkType = phone.getNetworkType();
                if (networkType == TelephonyManager.NETWORK_TYPE_UMTS
                        || networkType == TelephonyManager.NETWORK_TYPE_HSDPA
                        || networkType == TelephonyManager.NETWORK_TYPE_HSUPA
                        || networkType == TelephonyManager.NETWORK_TYPE_HSPA
                        || networkType == TelephonyManager.NETWORK_TYPE_HSPAP) {
                    type = AGPS_REF_LOCATION_TYPE_UMTS_CELLID;
                } else {
                    type = AGPS_REF_LOCATION_TYPE_GSM_CELLID;
                }
                native_agps_set_ref_location_cellid(type, mcc, mnc,
                        gsm_cell.getLac(), gsm_cell.getCid());
            } else {
                Log.e(TAG, "Error getting cell location info.");
            }
        } else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            Log.e(TAG, "CDMA not supported.");
        }
    }

    // Implements method nfwNotifyCb() in IGnssVisibilityControlCallback.hal.
    @NativeEntryPoint
    private void reportNfwNotification(String proxyAppPackageName, byte protocolStack,
            String otherProtocolStackName, byte requestor, String requestorId, byte responseType,
            boolean inEmergencyMode, boolean isCachedLocation) {
        if (mGnssVisibilityControl == null) {
            Log.e(TAG, "reportNfwNotification: mGnssVisibilityControl is not initialized.");
            return;
        }

        mGnssVisibilityControl.reportNfwNotification(proxyAppPackageName, protocolStack,
                otherProtocolStackName, requestor, requestorId, responseType, inEmergencyMode,
                isCachedLocation);
    }

    // Implements method isInEmergencySession() in IGnssVisibilityControlCallback.hal.
    @NativeEntryPoint
    boolean isInEmergencySession() {
        return mNIHandler.getInEmergency();
    }

    private void sendMessage(int message, int arg, Object obj) {
        // hold a wake lock until this message is delivered
        // note that this assumes the message will not be removed from the queue before
        // it is handled (otherwise the wake lock would be leaked).
        mWakeLock.acquire();
        if (DEBUG) {
            Log.d(TAG, "WakeLock acquired by sendMessage(" + messageIdAsString(message) + ", " + arg
                    + ", " + obj + ")");
        }
        mHandler.obtainMessage(message, arg, 1, obj).sendToTarget();
    }

    private final class ProviderHandler extends Handler {
        public ProviderHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            int message = msg.what;
            switch (message) {
                case SET_REQUEST:
                    GpsRequest gpsRequest = (GpsRequest) msg.obj;
                    handleSetRequest(gpsRequest.request, gpsRequest.source);
                    break;
                case INJECT_NTP_TIME:
                    mNtpTimeHelper.retrieveAndInjectNtpTime();
                    break;
                case REQUEST_LOCATION:
                    handleRequestLocation((boolean) msg.obj);
                    break;
                case DOWNLOAD_XTRA_DATA:
                    handleDownloadXtraData();
                    break;
                case DOWNLOAD_XTRA_DATA_FINISHED:
                    mDownloadXtraDataPending = STATE_IDLE;
                    break;
                case UPDATE_LOCATION:
                    handleUpdateLocation((Location) msg.obj);
                    break;
                case SUBSCRIPTION_OR_CARRIER_CONFIG_CHANGED:
                    subscriptionOrCarrierConfigChanged(mContext);
                    break;
                case INITIALIZE_HANDLER:
                    handleInitialize();
                    break;
                case REPORT_LOCATION:
                    handleReportLocation(msg.arg1 == 1, (Location) msg.obj);
                    break;
                case REPORT_SV_STATUS:
                    handleReportSvStatus((SvStatusInfo) msg.obj);
                    break;
            }
            if (msg.arg2 == 1) {
                // wakelock was taken for this message, release it
                mWakeLock.release();
                if (DEBUG) {
                    Log.d(TAG, "WakeLock released by handleMessage(" + messageIdAsString(message)
                            + ", " + msg.arg1 + ", " + msg.obj + ")");
                }
            }
        }

        /**
         * This method is bound to {@link #GnssLocationProvider(Context, LocationProviderManager,
         * Looper)}.
         * It is in charge of loading properties and registering for events that will be posted to
         * this handler.
         */
        private void handleInitialize() {
            native_init_once();

            /*
             * A cycle of native_init() and native_cleanup() is needed so that callbacks are
             * registered after bootup even when location is disabled.
             * This will allow Emergency SUPL to work even when location is disabled before device
             * restart.
             */
            boolean isInitialized = native_init();
            if (!isInitialized) {
                Log.w(TAG, "Native initialization failed at bootup");
            } else {
                native_cleanup();
            }

            if (native_is_gnss_visibility_control_supported()) {
                mGnssVisibilityControl = new GnssVisibilityControl(mContext, mLooper);
            }

            // load default GPS configuration
            // (this configuration might change in the future based on SIM changes)
            reloadGpsProperties();

            // TODO: When this object "finishes" we should unregister by invoking
            // SubscriptionManager.getInstance(mContext).unregister
            // (mOnSubscriptionsChangedListener);
            // This is not strictly necessary because it will be unregistered if the
            // notification fails but it is good form.

            // Register for SubscriptionInfo list changes which is guaranteed
            // to invoke onSubscriptionsChanged the first time.
            SubscriptionManager.from(mContext)
                    .addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

            // listen for events
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ALARM_WAKEUP);
            intentFilter.addAction(ALARM_TIMEOUT);
            intentFilter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
            intentFilter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
            mContext.registerReceiver(mBroadcastReceiver, intentFilter, null, this);

            mNetworkConnectivityHandler.registerNetworkCallbacks();

            // listen for PASSIVE_PROVIDER updates
            LocationManager locManager =
                    (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            long minTime = 0;
            float minDistance = 0;
            LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                    LocationManager.PASSIVE_PROVIDER,
                    minTime,
                    minDistance,
                    false);
            // Don't keep track of this request since it's done on behalf of other clients
            // (which are kept track of separately).
            request.setHideFromAppOps(true);
            locManager.requestLocationUpdates(
                    request,
                    new NetworkLocationListener(),
                    getLooper());

            updateEnabled();
        }
    }

    private abstract class LocationChangeListener implements LocationListener {
        private int mNumLocationUpdateRequest;

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    }

    private final class NetworkLocationListener extends LocationChangeListener {
        @Override
        public void onLocationChanged(Location location) {
            // this callback happens on mHandler looper
            if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())) {
                handleUpdateLocation(location);
            }
        }
    }

    private final class FusedLocationListener extends LocationChangeListener {
        @Override
        public void onLocationChanged(Location location) {
            if (LocationManager.FUSED_PROVIDER.equals(location.getProvider())) {
                injectBestLocation(location);
            }
        }
    }

    /**
     * @return A string representing the given message ID.
     */
    private String messageIdAsString(int message) {
        switch (message) {
            case SET_REQUEST:
                return "SET_REQUEST";
            case INJECT_NTP_TIME:
                return "INJECT_NTP_TIME";
            case REQUEST_LOCATION:
                return "REQUEST_LOCATION";
            case DOWNLOAD_XTRA_DATA:
                return "DOWNLOAD_XTRA_DATA";
            case DOWNLOAD_XTRA_DATA_FINISHED:
                return "DOWNLOAD_XTRA_DATA_FINISHED";
            case UPDATE_LOCATION:
                return "UPDATE_LOCATION";
            case SUBSCRIPTION_OR_CARRIER_CONFIG_CHANGED:
                return "SUBSCRIPTION_OR_CARRIER_CONFIG_CHANGED";
            case INITIALIZE_HANDLER:
                return "INITIALIZE_HANDLER";
            case REPORT_LOCATION:
                return "REPORT_LOCATION";
            case REPORT_SV_STATUS:
                return "REPORT_SV_STATUS";
            default:
                return "<Unknown>";
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        StringBuilder s = new StringBuilder();
        s.append("  mStarted=").append(mStarted).append('\n');
        s.append("  mFixInterval=").append(mFixInterval).append('\n');
        s.append("  mLowPowerMode=").append(mLowPowerMode).append('\n');
        s.append("  mGnssMeasurementsProvider.isRegistered()=")
                .append(mGnssMeasurementsProvider.isRegistered()).append('\n');
        s.append("  mGnssNavigationMessageProvider.isRegistered()=")
                .append(mGnssNavigationMessageProvider.isRegistered()).append('\n');
        s.append("  mDisableGps (battery saver mode)=").append(mDisableGps).append('\n');
        s.append("  mEngineCapabilities=0x").append(Integer.toHexString(mEngineCapabilities));
        s.append(" ( ");
        if (hasCapability(GPS_CAPABILITY_SCHEDULING)) s.append("SCHEDULING ");
        if (hasCapability(GPS_CAPABILITY_MSB)) s.append("MSB ");
        if (hasCapability(GPS_CAPABILITY_MSA)) s.append("MSA ");
        if (hasCapability(GPS_CAPABILITY_SINGLE_SHOT)) s.append("SINGLE_SHOT ");
        if (hasCapability(GPS_CAPABILITY_ON_DEMAND_TIME)) s.append("ON_DEMAND_TIME ");
        if (hasCapability(GPS_CAPABILITY_GEOFENCING)) s.append("GEOFENCING ");
        if (hasCapability(GPS_CAPABILITY_MEASUREMENTS)) s.append("MEASUREMENTS ");
        if (hasCapability(GPS_CAPABILITY_NAV_MESSAGES)) s.append("NAV_MESSAGES ");
        s.append(")\n");
        s.append(mGnssMetrics.dumpGnssMetricsAsText());
        s.append("  native internal state: ").append(native_get_internal_state());
        s.append("\n");
        pw.append(s);
    }

    // preallocated to avoid memory allocation in reportNmea()
    private byte[] mNmeaBuffer = new byte[120];

    static {
        class_init_native();
    }

    private static native void class_init_native();

    private static native boolean native_is_supported();

    private static native boolean native_is_gnss_visibility_control_supported();

    private static native void native_init_once();

    private native boolean native_init();

    private native void native_cleanup();

    private native boolean native_set_position_mode(int mode, int recurrence, int min_interval,
            int preferred_accuracy, int preferred_time, boolean lowPowerMode);

    private native boolean native_start();

    private native boolean native_stop();

    private native void native_delete_aiding_data(int flags);

    private native int native_read_nmea(byte[] buffer, int bufferSize);

    private native void native_inject_best_location(
            int gnssLocationFlags, double latitudeDegrees, double longitudeDegrees,
            double altitudeMeters, float speedMetersPerSec, float bearingDegrees,
            float horizontalAccuracyMeters, float verticalAccuracyMeters,
            float speedAccuracyMetersPerSecond, float bearingAccuracyDegrees,
            long timestamp, int elapsedRealtimeFlags, long elapsedRealtimeNanos);

    private native void native_inject_location(double latitude, double longitude, float accuracy);

    // XTRA Support
    private native void native_inject_time(long time, long timeReference, int uncertainty);

    private native boolean native_supports_xtra();

    private native void native_inject_xtra_data(byte[] data, int length);

    // DEBUG Support
    private native String native_get_internal_state();

    // AGPS Support
    private native void native_agps_ni_message(byte[] msg, int length);

    private native void native_set_agps_server(int type, String hostname, int port);

    // Network-initiated (NI) Support
    private native void native_send_ni_response(int notificationId, int userResponse);

    // AGPS ril support
    private native void native_agps_set_ref_location_cellid(int type, int mcc, int mnc,
            int lac, int cid);

    private native void native_agps_set_id(int type, String setid);
}
