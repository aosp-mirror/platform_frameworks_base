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
import android.database.Cursor;
import android.hardware.location.GeofenceHardware;
import android.hardware.location.GeofenceHardwareImpl;
import android.location.Criteria;
import android.location.FusedBatchOptions;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssStatus;
import android.location.IGnssStatusListener;
import android.location.IGnssStatusProvider;
import android.location.IGpsGeofenceHardware;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
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
import android.provider.Telephony.Carriers;
import android.provider.Telephony.Sms.Intents;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.internal.location.GpsNetInitiatedHandler;
import com.android.internal.location.GpsNetInitiatedHandler.GpsNiNotification;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.location.gnssmetrics.GnssMetrics;
import com.android.server.location.GnssSatelliteBlacklistHelper.GnssSatelliteBlacklistCallback;
import com.android.server.location.NtpTimeHelper.InjectNtpTimeCallback;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * A GNSS implementation of LocationProvider used by LocationManager.
 *
 * {@hide}
 */
public class GnssLocationProvider implements LocationProviderInterface, InjectNtpTimeCallback,
        GnssSatelliteBlacklistCallback {

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

    // these need to match AGnssStatusValue enum in IAGnssCallback.hal
    /** AGPS status event values. */
    private static final int GPS_REQUEST_AGPS_DATA_CONN = 1;
    private static final int GPS_RELEASE_AGPS_DATA_CONN = 2;
    private static final int GPS_AGPS_DATA_CONNECTED = 3;
    private static final int GPS_AGPS_DATA_CONN_DONE = 4;
    private static final int GPS_AGPS_DATA_CONN_FAILED = 5;

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
    private static final int GPS_CAPABILITY_MEASUREMENTS = 0x0000040;
    private static final int GPS_CAPABILITY_NAV_MESSAGES = 0x0000080;

    // The AGPS SUPL mode
    private static final int AGPS_SUPL_MODE_MSA = 0x02;
    private static final int AGPS_SUPL_MODE_MSB = 0x01;

    // these need to match AGnssType enum in IAGnssCallback.hal
    private static final int AGPS_TYPE_SUPL = 1;
    private static final int AGPS_TYPE_C2K = 2;

    // these must match the ApnIpType enum in IAGnss.hal
    private static final int APN_INVALID = 0;
    private static final int APN_IPV4 = 1;
    private static final int APN_IPV6 = 2;
    private static final int APN_IPV4V6 = 3;

    // for mAGpsDataConnectionState
    private static final int AGPS_DATA_CONNECTION_CLOSED = 0;
    private static final int AGPS_DATA_CONNECTION_OPENING = 1;
    private static final int AGPS_DATA_CONNECTION_OPEN = 2;

    // Handler messages
    private static final int CHECK_LOCATION = 1;
    private static final int ENABLE = 2;
    private static final int SET_REQUEST = 3;
    private static final int UPDATE_NETWORK_STATE = 4;
    private static final int INJECT_NTP_TIME = 5;
    private static final int DOWNLOAD_XTRA_DATA = 6;
    private static final int UPDATE_LOCATION = 7;  // Handle external location from network listener
    private static final int ADD_LISTENER = 8;
    private static final int REMOVE_LISTENER = 9;
    private static final int DOWNLOAD_XTRA_DATA_FINISHED = 11;
    private static final int SUBSCRIPTION_OR_SIM_CHANGED = 12;
    private static final int INITIALIZE_HANDLER = 13;
    private static final int REQUEST_SUPL_CONNECTION = 14;
    private static final int RELEASE_SUPL_CONNECTION = 15;
    private static final int REQUEST_LOCATION = 16;
    private static final int REPORT_LOCATION = 17; // HAL reports location
    private static final int REPORT_SV_STATUS = 18; // HAL reports SV status

    // Request setid
    private static final int AGPS_RIL_REQUEST_SETID_IMSI = 1;
    private static final int AGPS_RIL_REQUEST_SETID_MSISDN = 2;

    //TODO(b/33112647): Create gps_debug.conf with commented career parameters.
    private static final String DEBUG_PROPERTIES_FILE = "/etc/gps_debug.conf";

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
    // 30 seconds.
    private static final long LOCATION_UPDATE_DURATION_MILLIS = 30 * 1000;

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
            synchronized(this) {
                mSvCount = svCount;
                mMeanCn0 = meanCn0;
                mMaxCn0 = maxCn0;
            }
            setBundle(mBundle);
        }

        public void reset() {
            set(0,0,0);
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

    // states for injecting ntp and downloading xtra data
    private static final int STATE_PENDING_NETWORK = 0;
    private static final int STATE_DOWNLOADING = 1;
    private static final int STATE_IDLE = 2;

    // flags to trigger NTP or XTRA data download when network becomes available
    // initialized to true so we do NTP and XTRA when the network comes up after booting
    private int mDownloadXtraDataPending = STATE_PENDING_NETWORK;

    // true if GPS is navigating
    private boolean mNavigating;

    // true if GPS engine is on
    private boolean mEngineOn;

    // requested frequency of fixes, in milliseconds
    private int mFixInterval = 1000;

    // true if low power mode for the GNSS chipset is part of the latest request.
    private boolean mLowPowerMode = false;

    // true if we started navigation
    private boolean mStarted;

    // true if single shot request is in progress
    private boolean mSingleShot;

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

    // Current request from underlying location clients.
    private ProviderRequest mProviderRequest = null;
    // The WorkSource associated with the most recent client request (i.e, most recent call to
    // setRequest).
    private WorkSource mWorkSource = null;
    // True if gps should be disabled (used to support battery saver mode in settings).
    private boolean mDisableGps = false;

    /**
     * Properties loaded from PROPERTIES_FILE.
     * It must be accessed only inside {@link #mHandler}.
     */
    private Properties mProperties;

    private String mSuplServerHost;
    private int mSuplServerPort = TCP_MIN_PORT;
    private String mC2KServerHost;
    private int mC2KServerPort;
    private boolean mSuplEsEnabled = false;

    private final Context mContext;
    private final ILocationManager mILocationManager;
    private final LocationExtras mLocationExtras = new LocationExtras();
    private final GnssStatusListenerHelper mListenerHelper;
    private final GnssSatelliteBlacklistHelper mGnssSatelliteBlacklistHelper;
    private final GnssMeasurementsProvider mGnssMeasurementsProvider;
    private final GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private final LocationChangeListener mNetworkLocationListener = new NetworkLocationListener();
    private final LocationChangeListener mFusedLocationListener = new FusedLocationListener();
    private final NtpTimeHelper mNtpTimeHelper;
    private final GnssBatchingProvider mGnssBatchingProvider;
    private final GnssGeofenceProvider mGnssGeofenceProvider;

    // Handler for processing events
    private Handler mHandler;

    /** It must be accessed only inside {@link #mHandler}. */
    private int mAGpsDataConnectionState;
    /** It must be accessed only inside {@link #mHandler}. */
    private InetAddress mAGpsDataConnectionIpAddr;

    private final ConnectivityManager mConnMgr;
    private final GpsNetInitiatedHandler mNIHandler;

    // Wakelocks
    private final static String WAKELOCK_KEY = "GnssLocationProvider";
    private final PowerManager.WakeLock mWakeLock;
    private static final String DOWNLOAD_EXTRA_WAKELOCK_KEY = "GnssLocationProviderXtraDownload";
    private final PowerManager.WakeLock mDownloadXtraWakeLock;

    // Alarms
    private final static String ALARM_WAKEUP = "com.android.internal.location.ALARM_WAKEUP";
    private final static String ALARM_TIMEOUT = "com.android.internal.location.ALARM_TIMEOUT";

    // SIM/Carrier info.
    private final static String SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED";

    // Persist property for LPP_PROFILE
    private final static String LPP_PROFILE = "persist.sys.gps.lpp";


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

    private final IGnssStatusProvider mGnssStatusProvider = new IGnssStatusProvider.Stub() {
        @Override
        public void registerGnssStatusCallback(IGnssStatusListener callback) {
            mListenerHelper.addListener(callback);
        }

        @Override
        public void unregisterGnssStatusCallback(IGnssStatusListener callback) {
            mListenerHelper.removeListener(callback);
        }
    };

    public IGnssStatusProvider getGnssStatusProvider() {
        return mGnssStatusProvider;
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

    /**
     * Callback used to listen for data connectivity changes.
     */
    private final ConnectivityManager.NetworkCallback mNetworkConnectivityCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    mNtpTimeHelper.onNetworkAvailable();
                    if (mDownloadXtraDataPending == STATE_PENDING_NETWORK) {
                        if (mSupportsXtra) {
                            // Download only if supported, (prevents an unneccesary on-boot
                            // download)
                            xtraDownloadRequest();
                        }
                    }
                    // Always on, notify HAL so it can get data it needs
                    sendMessage(UPDATE_NETWORK_STATE, 0 /*arg*/, network);
                }

                @Override
                public void onLost(Network network) {
                    sendMessage(UPDATE_NETWORK_STATE, 0 /*arg*/, network);
                }
            };

    /**
     * Callback used to listen for availability of a requested SUPL connection.
     * It is kept as a separate instance from {@link #mNetworkConnectivityCallback} to be able to
     * manage the registration/un-registration lifetimes separate.
     */
    private final ConnectivityManager.NetworkCallback mSuplConnectivityCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // Specific to a change to a SUPL enabled network becoming ready
                    sendMessage(UPDATE_NETWORK_STATE, 0 /*arg*/, network);
                }

                @Override
                public void onLost(Network network) {
                    releaseSuplConnection(GPS_RELEASE_AGPS_DATA_CONN);
                }
            };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "receive broadcast intent, action: " + action);
            if (action == null) {
                return;
            }

            if (action.equals(ALARM_WAKEUP)) {
                startNavigating(false);
            } else if (action.equals(ALARM_TIMEOUT)) {
                hibernate();
            } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)
                    || PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)) {
                updateLowPowerMode();
            } else if (action.equals(SIM_STATE_CHANGED)) {
                subscriptionOrSimChanged(context);
            }
        }
    };

    private final OnSubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    sendMessage(SUBSCRIPTION_OR_SIM_CHANGED, 0, null);
                }
            };

    /**
     * Implements {@link GnssSatelliteBlacklistCallback#onUpdateSatelliteBlacklist}.
     */
    @Override
    public void onUpdateSatelliteBlacklist(int[] constellations, int[] svids) {
        mHandler.post(()->{
            native_set_satellite_blacklist(constellations, svids);
        });
    }

    private void subscriptionOrSimChanged(Context context) {
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
                                b.getBoolean(CarrierConfigManager.KEY_PERSIST_LPP_MODE_BOOL);
                    }
                }
                if (isKeepLppProfile) {
                    // load current properties for the carrier
                    loadPropertiesFromResource(context, mProperties);
                    String lpp_profile = mProperties.getProperty("LPP_PROFILE");
                    // set the persist property LPP_PROFILE for the value
                    if (lpp_profile != null) {
                        SystemProperties.set(LPP_PROFILE, lpp_profile);
                    }
                } else {
                    // reset the persist property
                    SystemProperties.set(LPP_PROFILE, "");
                }
                reloadGpsProperties(context, mProperties);
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
                mPowerManager.getPowerSaveState(ServiceType.GPS);
        switch (result.gpsMode) {
            case PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF:
                // If we are in battery saver mode and the screen is off, disable GPS.
                disableGps |= result.batterySaverEnabled && !mPowerManager.isInteractive();
                break;
        }
        if (disableGps != mDisableGps) {
            mDisableGps = disableGps;
            updateRequirements();
        }
    }

    public static boolean isSupported() {
        return native_is_supported();
    }

    interface SetCarrierProperty {
        public boolean set(int value);
    }

    private void reloadGpsProperties(Context context, Properties properties) {
        if (DEBUG) Log.d(TAG, "Reset GPS properties, previous size = " + properties.size());
        loadPropertiesFromResource(context, properties);

        String lpp_prof = SystemProperties.get(LPP_PROFILE);
        if (!TextUtils.isEmpty(lpp_prof)) {
            // override default value of this if lpp_prof is not empty
            properties.setProperty("LPP_PROFILE", lpp_prof);
        }
        /*
         * Overlay carrier properties from a debug configuration file.
         */
        loadPropertiesFromFile(DEBUG_PROPERTIES_FILE, properties);
        // TODO: we should get rid of C2K specific setting.
        setSuplHostPort(properties.getProperty("SUPL_HOST"),
                properties.getProperty("SUPL_PORT"));
        mC2KServerHost = properties.getProperty("C2K_HOST");
        String portString = properties.getProperty("C2K_PORT");
        if (mC2KServerHost != null && portString != null) {
            try {
                mC2KServerPort = Integer.parseInt(portString);
            } catch (NumberFormatException e) {
                Log.e(TAG, "unable to parse C2K_PORT: " + portString);
            }
        }
        if (native_is_gnss_configuration_supported()) {
            Map<String, SetCarrierProperty> map = new HashMap<String, SetCarrierProperty>() {
                {
                    put("SUPL_VER", (val) -> native_set_supl_version(val));
                    put("SUPL_MODE", (val) -> native_set_supl_mode(val));
                    put("SUPL_ES", (val) -> native_set_supl_es(val));
                    put("LPP_PROFILE", (val) -> native_set_lpp_profile(val));
                    put("A_GLONASS_POS_PROTOCOL_SELECT",
                            (val) -> native_set_gnss_pos_protocol_select(val));
                    put("USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL",
                            (val) -> native_set_emergency_supl_pdn(val));
                    put("GPS_LOCK", (val) -> native_set_gps_lock(val));
                }
            };

            for (Entry<String, SetCarrierProperty> entry : map.entrySet()) {
                String propertyName = entry.getKey();
                String propertyValueString = properties.getProperty(propertyName);
                if (propertyValueString != null) {
                    try {
                        int propertyValueInt = Integer.decode(propertyValueString);
                        boolean result = entry.getValue().set(propertyValueInt);
                        if (result == false) {
                            Log.e(TAG, "Unable to set " + propertyName);
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "unable to parse propertyName: " + propertyValueString);
                    }
                }
            }
        } else if (DEBUG) {
            Log.d(TAG, "Skipped configuration update because GNSS configuration in GPS HAL is not"
                    + " supported");
        }

        // SUPL_ES configuration.
        String suplESProperty = mProperties.getProperty("SUPL_ES");
        if (suplESProperty != null) {
            try {
                mSuplEsEnabled = (Integer.parseInt(suplESProperty) == 1);
            } catch (NumberFormatException e) {
                Log.e(TAG, "unable to parse SUPL_ES: " + suplESProperty);
            }
        }
    }

    private void loadPropertiesFromResource(Context context,
            Properties properties) {
        String[] configValues = context.getResources().getStringArray(
                com.android.internal.R.array.config_gpsParameters);
        for (String item : configValues) {
            if (DEBUG) Log.d(TAG, "GpsParamsResource: " + item);
            // We need to support "KEY =", but not "=VALUE".
            int index = item.indexOf("=");
            if (index > 0 && index + 1 < item.length()) {
                String key = item.substring(0, index);
                String value = item.substring(index + 1);
                properties.setProperty(key.trim().toUpperCase(), value);
            } else {
                Log.w(TAG, "malformed contents: " + item);
            }
        }
    }

    private boolean loadPropertiesFromFile(String filename,
            Properties properties) {
        try {
            File file = new File(filename);
            FileInputStream stream = null;
            try {
                stream = new FileInputStream(file);
                properties.load(stream);
            } finally {
                IoUtils.closeQuietly(stream);
            }

        } catch (IOException e) {
            if (DEBUG) Log.d(TAG, "Could not open GPS configuration file " + filename);
            return false;
        }
        return true;
    }

    public GnssLocationProvider(Context context, ILocationManager ilocationManager,
            Looper looper) {
        mContext = context;
        mILocationManager = ilocationManager;

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

        mConnMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

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
        mProperties = new Properties();
        sendMessage(INITIALIZE_HANDLER, 0, null);

        // Create a GPS net-initiated handler.
        mNIHandler = new GpsNetInitiatedHandler(context,
                mNetInitiatedListener,
                mSuplEsEnabled);

        mListenerHelper = new GnssStatusListenerHelper(mHandler) {
            @Override
            protected boolean isAvailableInPlatform() {
                return isSupported();
            }

            @Override
            protected boolean isGpsEnabled() {
                return isEnabled();
            }
        };

        mGnssMeasurementsProvider = new GnssMeasurementsProvider(mHandler) {
            @Override
            public boolean isAvailableInPlatform() {
                return native_is_measurement_supported();
            }

            @Override
            protected int registerWithService() {
                int devOptions = Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED , 0);
                int fullTrackingToggled = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.ENABLE_GNSS_RAW_MEAS_FULL_TRACKING , 0);
                boolean result = false;
                if (devOptions == 1 /* Developer Mode enabled */
                        && fullTrackingToggled == 1 /* Raw Measurements Full Tracking enabled */) {
                    result =  native_start_measurement_collection(true /* enableFullTracking */);
                } else {
                    result =  native_start_measurement_collection(false /* enableFullTracking */);
                }
                if (result) {
                    return RemoteListenerHelper.RESULT_SUCCESS;
                } else {
                    return RemoteListenerHelper.RESULT_INTERNAL_ERROR;
                }
            }

            @Override
            protected void unregisterFromService() {
                native_stop_measurement_collection();
            }

            @Override
            protected boolean isGpsEnabled() {
                return isEnabled();
            }
        };

        mGnssNavigationMessageProvider = new GnssNavigationMessageProvider(mHandler) {
            @Override
            protected boolean isAvailableInPlatform() {
                return native_is_navigation_message_supported();
            }

            @Override
            protected int registerWithService() {
                boolean result = native_start_navigation_message_collection();
                if (result) {
                    return RemoteListenerHelper.RESULT_SUCCESS;
                } else {
                    return RemoteListenerHelper.RESULT_INTERNAL_ERROR;
                }
            }

            @Override
            protected void unregisterFromService() {
                native_stop_navigation_message_collection();
            }

            @Override
            protected boolean isGpsEnabled() {
                return isEnabled();
            }
        };
        mGnssMetrics = new GnssMetrics(mBatteryStats);

        mNtpTimeHelper = new NtpTimeHelper(mContext, looper, this);
        mGnssSatelliteBlacklistHelper = new GnssSatelliteBlacklistHelper(mContext,
                looper, this);
        mHandler.post(mGnssSatelliteBlacklistHelper::updateSatelliteBlacklist);
        mGnssBatchingProvider = new GnssBatchingProvider();
        mGnssGeofenceProvider = new GnssGeofenceProvider(looper);
    }

    /**
     * Returns the name of this provider.
     */
    @Override
    public String getName() {
        return LocationManager.GPS_PROVIDER;
    }

    @Override
    public ProviderProperties getProperties() {
        return PROPERTIES;
    }


    /**
     * Implements {@link InjectNtpTimeCallback#injectTime}
     */
    @Override
    public void injectTime(long time, long timeReference, int uncertainty) {
        native_inject_time(time, timeReference, uncertainty);
    }

    private void handleUpdateNetworkState(Network network) {
        // retrieve NetworkInfo for this UID
        NetworkInfo info = mConnMgr.getNetworkInfo(network);

        boolean networkAvailable = false;
        boolean isConnected = false;
        int type = ConnectivityManager.TYPE_NONE;
        boolean isRoaming = false;
        String apnName = null;

        if (info != null) {
            networkAvailable = info.isAvailable() && TelephonyManager.getDefault().getDataEnabled();
            isConnected = info.isConnected();
            type = info.getType();
            isRoaming = info.isRoaming();
            apnName = info.getExtraInfo();
        }

        if (DEBUG) {
            String message = String.format(
                    "UpdateNetworkState, state=%s, connected=%s, info=%s, capabilities=%S",
                    agpsDataConnStateAsString(),
                    isConnected,
                    info,
                    mConnMgr.getNetworkCapabilities(network));
            Log.d(TAG, message);
        }

        if (native_is_agps_ril_supported()) {
            String defaultApn = getSelectedApn();
            if (defaultApn == null) {
                defaultApn = "dummy-apn";
            }

            native_update_network_state(
                    isConnected,
                    type,
                    isRoaming,
                    networkAvailable,
                    apnName,
                    defaultApn);
        } else if (DEBUG) {
            Log.d(TAG, "Skipped network state update because GPS HAL AGPS-RIL is not  supported");
        }

        if (mAGpsDataConnectionState == AGPS_DATA_CONNECTION_OPENING) {
            if (isConnected) {
                if (apnName == null) {
                    // assign a dummy value in the case of C2K as otherwise we will have a runtime
                    // exception in the following call to native_agps_data_conn_open
                    apnName = "dummy-apn";
                }
                int apnIpType = getApnIpType(apnName);
                setRouting();
                if (DEBUG) {
                    String message = String.format(
                            "native_agps_data_conn_open: mAgpsApn=%s, mApnIpType=%s",
                            apnName,
                            apnIpType);
                    Log.d(TAG, message);
                }
                native_agps_data_conn_open(apnName, apnIpType);
                mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPEN;
            } else {
                handleReleaseSuplConnection(GPS_AGPS_DATA_CONN_FAILED);
            }
        }
    }

    private void handleRequestSuplConnection(InetAddress address) {
        if (DEBUG) {
            String message = String.format(
                    "requestSuplConnection, state=%s, address=%s",
                    agpsDataConnStateAsString(),
                    address);
            Log.d(TAG, message);
        }

        if (mAGpsDataConnectionState != AGPS_DATA_CONNECTION_CLOSED) {
            return;
        }
        mAGpsDataConnectionIpAddr = address;
        mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPENING;

        NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
        NetworkRequest request = requestBuilder.build();
        mConnMgr.requestNetwork(
                request,
                mSuplConnectivityCallback);
    }

    private void handleReleaseSuplConnection(int agpsDataConnStatus) {
        if (DEBUG) {
            String message = String.format(
                    "releaseSuplConnection, state=%s, status=%s",
                    agpsDataConnStateAsString(),
                    agpsDataConnStatusAsString(agpsDataConnStatus));
            Log.d(TAG, message);
        }

        if (mAGpsDataConnectionState == AGPS_DATA_CONNECTION_CLOSED) {
            return;
        }
        mAGpsDataConnectionState = AGPS_DATA_CONNECTION_CLOSED;

        mConnMgr.unregisterNetworkCallback(mSuplConnectivityCallback);
        switch (agpsDataConnStatus) {
            case GPS_AGPS_DATA_CONN_FAILED:
                native_agps_data_conn_failed();
                break;
            case GPS_RELEASE_AGPS_DATA_CONN:
                native_agps_data_conn_closed();
                break;
            default:
                Log.e(TAG, "Invalid status to release SUPL connection: " + agpsDataConnStatus);
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
        locationManager.requestLocationUpdates(provider,
                LOCATION_UPDATE_MIN_TIME_INTERVAL_MILLIS, /*minDistance=*/ 0,
                locationListener, mHandler.getLooper());
        locationListener.numLocationUpdateRequest++;
        mHandler.postDelayed(() -> {
            if (--locationListener.numLocationUpdateRequest == 0) {
                Log.i(TAG, String.format("Removing location updates from %s provider.", provider));
                locationManager.removeUpdates(locationListener);
            }
        }, durationMillis);
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
        native_inject_best_location(gnssLocationFlags, latitudeDegrees, longitudeDegrees,
                altitudeMeters, speedMetersPerSec, bearingDegrees, horizontalAccuracyMeters,
                verticalAccuracyMeters, speedAccuracyMetersPerSecond, bearingAccuracyDegrees,
                timestamp);
    }

    /** Returns true if the location request is too frequent. */
    private boolean isRequestLocationRateLimited() {
        // TODO(b/73198123): implement exponential backoff.
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
        if (!isDataNetworkConnected()) {
            // try again when network is up
            mDownloadXtraDataPending = STATE_PENDING_NETWORK;
            return;
        }
        mDownloadXtraDataPending = STATE_DOWNLOADING;

        // hold wake lock while task runs
        mDownloadXtraWakeLock.acquire(DOWNLOAD_XTRA_DATA_TIMEOUT_MS);
        Log.i(TAG, "WakeLock acquired by handleDownloadXtraData()");
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                GpsXtraDownloader xtraDownloader = new GpsXtraDownloader(mProperties);
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
            }
        });
    }

    private void handleUpdateLocation(Location location) {
        if (location.hasAccuracy()) {
            native_inject_location(location.getLatitude(), location.getLongitude(),
                    location.getAccuracy());
        }
    }

    /**
     * Enables this provider.  When enabled, calls to getStatus()
     * must be handled.  Hardware may be started up
     * when the provider is enabled.
     */
    @Override
    public void enable() {
        synchronized (mLock) {
            if (mEnabled) return;
            mEnabled = true;
        }

        sendMessage(ENABLE, 1, null);
    }

    private void setSuplHostPort(String hostString, String portString) {
        if (hostString != null) {
            mSuplServerHost = hostString;
        }
        if (portString != null) {
            try {
                mSuplServerPort = Integer.parseInt(portString);
            } catch (NumberFormatException e) {
                Log.e(TAG, "unable to parse SUPL_PORT: " + portString);
            }
        }
        if (mSuplServerHost != null
                && mSuplServerPort > TCP_MIN_PORT
                && mSuplServerPort <= TCP_MAX_PORT) {
            native_set_agps_server(AGPS_TYPE_SUPL, mSuplServerHost, mSuplServerPort);
        }
    }

    /**
     * Checks what SUPL mode to use, according to the AGPS mode as well as the
     * allowed mode from properties.
     *
     * @param properties  GPS properties
     * @param agpsEnabled whether AGPS is enabled by settings value
     * @param singleShot  whether "singleshot" is needed
     * @return SUPL mode (MSA vs MSB vs STANDALONE)
     */
    private int getSuplMode(Properties properties, boolean agpsEnabled, boolean singleShot) {
        if (agpsEnabled) {
            String modeString = properties.getProperty("SUPL_MODE");
            int suplMode = 0;
            if (!TextUtils.isEmpty(modeString)) {
                try {
                    suplMode = Integer.parseInt(modeString);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "unable to parse SUPL_MODE: " + modeString);
                    return GPS_POSITION_MODE_STANDALONE;
                }
            }
            // MS-Based is the preferred mode for Assisted-GPS position computation, so we favor
            // such mode when it is available
            if (hasCapability(GPS_CAPABILITY_MSB) && (suplMode & AGPS_SUPL_MODE_MSB) != 0) {
                return GPS_POSITION_MODE_MS_BASED;
            }
            // for now, just as the legacy code did, we fallback to MS-Assisted if it is available,
            // do fallback only for single-shot requests, because it is too expensive to do for
            // periodic requests as well
            if (singleShot
                    && hasCapability(GPS_CAPABILITY_MSA)
                    && (suplMode & AGPS_SUPL_MODE_MSA) != 0) {
                return GPS_POSITION_MODE_MS_ASSISTED;
            }
        }
        return GPS_POSITION_MODE_STANDALONE;
    }

    private void handleEnable() {
        if (DEBUG) Log.d(TAG, "handleEnable");

        boolean enabled = native_init();

        if (enabled) {
            mSupportsXtra = native_supports_xtra();

            // TODO: remove the following native calls if we can make sure they are redundant.
            if (mSuplServerHost != null) {
                native_set_agps_server(AGPS_TYPE_SUPL, mSuplServerHost, mSuplServerPort);
            }
            if (mC2KServerHost != null) {
                native_set_agps_server(AGPS_TYPE_C2K, mC2KServerHost, mC2KServerPort);
            }

            mGnssMeasurementsProvider.onGpsEnabledChanged();
            mGnssNavigationMessageProvider.onGpsEnabledChanged();
            mGnssBatchingProvider.enable();
        } else {
            synchronized (mLock) {
                mEnabled = false;
            }
            Log.w(TAG, "Failed to enable location provider");
        }
    }

    /**
     * Disables this provider.  When disabled, calls to getStatus()
     * need not be handled.  Hardware may be shut
     * down while the provider is disabled.
     */
    @Override
    public void disable() {
        synchronized (mLock) {
            if (!mEnabled) return;
            mEnabled = false;
        }

        sendMessage(ENABLE, 0, null);
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

    @Override
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
        updateRequirements();
    }

    // Called when the requirements for GPS may have changed
    private void updateRequirements() {
        if (mProviderRequest == null || mWorkSource == null) {
            return;
        }

        boolean singleShot = false;

        // see if the request is for a single update
        if (mProviderRequest.locationRequests != null
                && mProviderRequest.locationRequests.size() > 0) {
            // if any request has zero or more than one updates
            // requested, then this is not single-shot mode
            singleShot = true;

            for (LocationRequest lr : mProviderRequest.locationRequests) {
                if (lr.getNumUpdates() != 1) {
                    singleShot = false;
                }
            }
        }

        if (DEBUG) Log.d(TAG, "setRequest " + mProviderRequest);
        if (mProviderRequest.reportLocation && !mDisableGps && isEnabled()) {
            // update client uids
            updateClientUids(mWorkSource);

            mFixInterval = (int) mProviderRequest.interval;
            mLowPowerMode = (boolean) mProviderRequest.lowPowerMode;
            // check for overflow
            if (mFixInterval != mProviderRequest.interval) {
                Log.w(TAG, "interval overflow: " + mProviderRequest.interval);
                mFixInterval = Integer.MAX_VALUE;
            }

            // apply request to GPS engine
            if (mStarted && hasCapability(GPS_CAPABILITY_SCHEDULING)) {
                // change period and/or lowPowerMode
                if (!native_set_position_mode(mPositionMode, GPS_POSITION_RECURRENCE_PERIODIC,
                        mFixInterval, 0, 0, mLowPowerMode)) {
                    Log.e(TAG, "set_position_mode failed in updateRequirements");
                }
            } else if (!mStarted) {
                // start GPS
                startNavigating(singleShot);
            } else {
                // GNSS Engine is already ON, but no GPS_CAPABILITY_SCHEDULING
                mAlarmManager.cancel(mTimeoutIntent);
                if (mFixInterval >= NO_FIX_TIMEOUT) {
                    // set timer to give up if we do not receive a fix within NO_FIX_TIMEOUT
                    // and our fix interval is not short
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + NO_FIX_TIMEOUT, mTimeoutIntent);                }
            }
        } else {
            updateClientUids(new WorkSource());

            stopNavigating();
            mAlarmManager.cancel(mWakeupIntent);
            mAlarmManager.cancel(mTimeoutIntent);
        }
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
                for (int i = 0; i < newChains.size(); ++i) {
                    final WorkChain newChain = newChains.get(i);
                    mAppOps.startOpNoThrow(AppOpsManager.OP_GPS, newChain.getAttributionUid(),
                            newChain.getAttributionTag());
                }
            }

            if (goneChains != null) {
                for (int i = 0; i < goneChains.size(); i++) {
                    final WorkChain goneChain = goneChains.get(i);
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
    public boolean sendExtraCommand(String command, Bundle extras) {

        long identity = Binder.clearCallingIdentity();
        try {
            boolean result = false;

            if ("delete_aiding_data".equals(command)) {
                result = deleteAidingData(extras);
            } else if ("force_time_injection".equals(command)) {
                requestUtcTime();
                result = true;
            } else if ("force_xtra_injection".equals(command)) {
                if (mSupportsXtra) {
                    xtraDownloadRequest();
                    result = true;
                }
            } else {
                Log.w(TAG, "sendExtraCommand: unknown command " + command);
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean deleteAidingData(Bundle extras) {
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
            return true;
        }

        return false;
    }

    private void startNavigating(boolean singleShot) {
        if (!mStarted) {
            if (DEBUG) Log.d(TAG, "startNavigating, singleShot is " + singleShot);
            mTimeToFirstFix = 0;
            mLastFixTime = 0;
            mStarted = true;
            mSingleShot = singleShot;
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
            mPositionMode = getSuplMode(mProperties, agpsEnabled, singleShot);

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
            mLowPowerMode = (boolean) mProviderRequest.lowPowerMode;
            if (!native_set_position_mode(mPositionMode, GPS_POSITION_RECURRENCE_PERIODIC,
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
            mSingleShot = false;
            native_stop();
            mLastFixTime = 0;

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


    /**
     * called from native code to update our position.
     */
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

        // It would be nice to push the elapsed real-time timestamp
        // further down the stack, but this is still useful
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setExtras(mLocationExtras.getBundle());

        try {
            mILocationManager.reportLocation(location, false);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling reportLocation");
        }

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
            mListenerHelper.onFirstFix(mTimeToFirstFix);
        }

        if (mSingleShot) {
            stopNavigating();
        }

        if (mStarted && mStatus != LocationProvider.AVAILABLE) {
            // For devices that use framework scheduling, a timer may be set to ensure we don't
            // spend too much power searching for a location, when the requested update rate is slow.
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

    /**
     * called from native code to update our status
     */
    private void reportStatus(int status) {
        if (DEBUG) Log.v(TAG, "reportStatus status: " + status);

        boolean wasNavigating = mNavigating;
        switch (status) {
            case GPS_STATUS_SESSION_BEGIN:
                mNavigating = true;
                mEngineOn = true;
                break;
            case GPS_STATUS_SESSION_END:
                mNavigating = false;
                break;
            case GPS_STATUS_ENGINE_ON:
                mEngineOn = true;
                break;
            case GPS_STATUS_ENGINE_OFF:
                mEngineOn = false;
                mNavigating = false;
                break;
        }

        if (wasNavigating != mNavigating) {
            mListenerHelper.onStatusChanged(mNavigating);

            // send an intent to notify that the GPS has been enabled or disabled
            Intent intent = new Intent(LocationManager.GPS_ENABLED_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, mNavigating);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    // Helper class to carry data to handler for reportSvStatus
    private static class SvStatusInfo {
        public int mSvCount;
        public int[] mSvidWithFlags;
        public float[] mCn0s;
        public float[] mSvElevations;
        public float[] mSvAzimuths;
        public float[] mSvCarrierFreqs;
    }

    /**
     * called from native code to update SV info
     */
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
        mListenerHelper.onSvStatusChanged(
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

    /**
     * called from native code to update AGPS status
     */
    private void reportAGpsStatus(int type, int status, byte[] ipaddr) {
        switch (status) {
            case GPS_REQUEST_AGPS_DATA_CONN:
                if (DEBUG) Log.d(TAG, "GPS_REQUEST_AGPS_DATA_CONN");
                Log.v(TAG, "Received SUPL IP addr[]: " + Arrays.toString(ipaddr));
                InetAddress connectionIpAddress = null;
                if (ipaddr != null) {
                    try {
                        connectionIpAddress = InetAddress.getByAddress(ipaddr);
                        if (DEBUG) Log.d(TAG, "IP address converted to: " + connectionIpAddress);
                    } catch (UnknownHostException e) {
                        Log.e(TAG, "Bad IP Address: " + ipaddr, e);
                    }
                }
                sendMessage(REQUEST_SUPL_CONNECTION, 0 /*arg*/, connectionIpAddress);
                break;
            case GPS_RELEASE_AGPS_DATA_CONN:
                if (DEBUG) Log.d(TAG, "GPS_RELEASE_AGPS_DATA_CONN");
                releaseSuplConnection(GPS_RELEASE_AGPS_DATA_CONN);
                break;
            case GPS_AGPS_DATA_CONNECTED:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONNECTED");
                break;
            case GPS_AGPS_DATA_CONN_DONE:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONN_DONE");
                break;
            case GPS_AGPS_DATA_CONN_FAILED:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONN_FAILED");
                break;
            default:
                if (DEBUG) Log.d(TAG, "Received Unknown AGPS status: " + status);
        }
    }

    private void releaseSuplConnection(int connStatus) {
        sendMessage(RELEASE_SUPL_CONNECTION, connStatus, null /*obj*/);
    }

    /**
     * called from native code to report NMEA data received
     */
    private void reportNmea(long timestamp) {
        if (!mItarSpeedLimitExceeded) {
            int length = native_read_nmea(mNmeaBuffer, mNmeaBuffer.length);
            String nmea = new String(mNmeaBuffer, 0 /* offset */, length);
            mListenerHelper.onNmeaReceived(timestamp, nmea);
        }
    }

    /**
     * called from native code - GNSS measurements callback
     */
    private void reportMeasurementData(GnssMeasurementsEvent event) {
        if (!mItarSpeedLimitExceeded) {
            // send to handler to allow native to return quickly
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mGnssMeasurementsProvider.onMeasurementsAvailable(event);
                }
            });
        }
    }

    /**
     * called from native code - GNSS navigation message callback
     */
    private void reportNavigationMessage(GnssNavigationMessage event) {
        if (!mItarSpeedLimitExceeded) {
            // send to handler to allow native to return quickly
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mGnssNavigationMessageProvider.onNavigationMessageAvailable(event);
                }
            });
        }
    }

    /**
     * called from native code to inform us what the GPS engine capabilities are
     */
    private void setEngineCapabilities(final int capabilities) {
        // send to handler thread for fast native return, and in-order handling
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mEngineCapabilities = capabilities;

                if (hasCapability(GPS_CAPABILITY_ON_DEMAND_TIME)) {
                    mNtpTimeHelper.enablePeriodicTimeInjection();
                    requestUtcTime();
                }

                mGnssMeasurementsProvider.onCapabilitiesUpdated(hasCapability(
                        GPS_CAPABILITY_MEASUREMENTS));
                mGnssNavigationMessageProvider.onCapabilitiesUpdated(hasCapability(
                        GPS_CAPABILITY_NAV_MESSAGES));
            }
        });
   }

    /**
     * Called from native code to inform us the hardware year.
     */
    private void setGnssYearOfHardware(final int yearOfHardware) {
        // mHardwareYear is simply set here, to be read elsewhere, and is volatile for safe sync
        if (DEBUG) Log.d(TAG, "setGnssYearOfHardware called with " + yearOfHardware);
        mHardwareYear = yearOfHardware;
    }

    /**
     * Called from native code to inform us the hardware model name.
     */
    private void setGnssHardwareModelName(final String modelName) {
        // mHardwareModelName is simply set here, to be read elsewhere, and volatile for safe sync
        if (DEBUG) Log.d(TAG, "setGnssModelName called with " + modelName);
        mHardwareModelName = modelName;
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
        return new GnssMetricsProvider() {
            @Override
            public String getGnssMetricsAsProtoString() {
                return mGnssMetrics.dumpGnssMetricsAsProtoString();
            }
        };
    }

    /**
     * called from native code - GNSS location batch callback
     */
    private void reportLocationBatch(Location[] locationArray) {
        List<Location> locations = new ArrayList<>(Arrays.asList(locationArray));
        if (DEBUG) {
            Log.d(TAG, "Location batch of size " + locationArray.length + " reported");
        }
        try {
            mILocationManager.reportLocationBatch(locations);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling reportLocationBatch");
        }
    }

    /**
     * called from native code to request XTRA data
     */
    private void xtraDownloadRequest() {
        if (DEBUG) Log.d(TAG, "xtraDownloadRequest");
        sendMessage(DOWNLOAD_XTRA_DATA, 0, null);
    }

    /**
     * Converts the GPS HAL status to the internal Geofence Hardware status.
     */
    private int getGeofenceStatus(int status) {
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

    /**
     * Called from native to report GPS Geofence transition
     * All geofence callbacks are called on the same thread
     */
    private void reportGeofenceTransition(int geofenceId, Location location, int transition,
            long transitionTimestamp) {
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
    }

    /**
     * called from native code to report GPS status change.
     */
    private void reportGeofenceStatus(int status, Location location) {
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
    }

    /**
     * called from native code - Geofence Add callback
     */
    private void reportGeofenceAddStatus(int geofenceId, int status) {
        if (mGeofenceHardwareImpl == null) {
            mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
        }
        mGeofenceHardwareImpl.reportGeofenceAddStatus(geofenceId, getGeofenceStatus(status));
    }

    /**
     * called from native code - Geofence Remove callback
     */
    private void reportGeofenceRemoveStatus(int geofenceId, int status) {
        if (mGeofenceHardwareImpl == null) {
            mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
        }
        mGeofenceHardwareImpl.reportGeofenceRemoveStatus(geofenceId, getGeofenceStatus(status));
    }

    /**
     * called from native code - Geofence Pause callback
     */
    private void reportGeofencePauseStatus(int geofenceId, int status) {
        if (mGeofenceHardwareImpl == null) {
            mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
        }
        mGeofenceHardwareImpl.reportGeofencePauseStatus(geofenceId, getGeofenceStatus(status));
    }

    /**
     * called from native code - Geofence Resume callback
     */
    private void reportGeofenceResumeStatus(int geofenceId, int status) {
        if (mGeofenceHardwareImpl == null) {
            mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
        }
        mGeofenceHardwareImpl.reportGeofenceResumeStatus(geofenceId, getGeofenceStatus(status));
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
            return true;
        }
    };

    public INetInitiatedListener getNetInitiatedListener() {
        return mNetInitiatedListener;
    }

    // Called by JNI function to report an NI request.
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
    }

    /**
     * Called from native code to request set id info.
     * We should be careful about receiving null string from the TelephonyManager,
     * because sending null String to JNI function would cause a crash.
     */

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

    /**
     * Called from native code to request location info.
     */
    private void requestLocation(boolean independentFromGnss) {
        if (DEBUG) {
            Log.d(TAG, "requestLocation. independentFromGnss: " + independentFromGnss);
        }
        sendMessage(REQUEST_LOCATION, 0, independentFromGnss);
    }

    /**
     * Called from native code to request utc time info
     */
    private void requestUtcTime() {
        if (DEBUG) Log.d(TAG, "utcTimeRequest");
        sendMessage(INJECT_NTP_TIME, 0, null);
    }

    /**
     * Called from native code to request reference location info
     */
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

    private void sendMessage(int message, int arg, Object obj) {
        // hold a wake lock until this message is delivered
        // note that this assumes the message will not be removed from the queue before
        // it is handled (otherwise the wake lock would be leaked).
        mWakeLock.acquire();
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "WakeLock acquired by sendMessage(" + messageIdAsString(message) + ", " + arg
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
                case ENABLE:
                    if (msg.arg1 == 1) {
                        handleEnable();
                    } else {
                        handleDisable();
                    }
                    break;
                case SET_REQUEST:
                    GpsRequest gpsRequest = (GpsRequest) msg.obj;
                    handleSetRequest(gpsRequest.request, gpsRequest.source);
                    break;
                case UPDATE_NETWORK_STATE:
                    handleUpdateNetworkState((Network) msg.obj);
                    break;
                case REQUEST_SUPL_CONNECTION:
                    handleRequestSuplConnection((InetAddress) msg.obj);
                    break;
                case RELEASE_SUPL_CONNECTION:
                    handleReleaseSuplConnection(msg.arg1);
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
                case SUBSCRIPTION_OR_SIM_CHANGED:
                    subscriptionOrSimChanged(mContext);
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
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "WakeLock released by handleMessage(" + messageIdAsString(message)
                            + ", " + msg.arg1 + ", " + msg.obj + ")");
                }
            }
        }

        /**
         * This method is bound to {@link #GnssLocationProvider(Context, ILocationManager, Looper)}.
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

            // load default GPS configuration
            // (this configuration might change in the future based on SIM changes)
            reloadGpsProperties(mContext, mProperties);

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
            IntentFilter intentFilter;
            if (native_is_agps_ril_supported()) {
                intentFilter = new IntentFilter();
                intentFilter.addAction(Intents.DATA_SMS_RECEIVED_ACTION);
                intentFilter.addDataScheme("sms");
                intentFilter.addDataAuthority("localhost", "7275");
                mContext.registerReceiver(mBroadcastReceiver, intentFilter, null, this);

                intentFilter = new IntentFilter();
                intentFilter.addAction(Intents.WAP_PUSH_RECEIVED_ACTION);
                try {
                    intentFilter.addDataType("application/vnd.omaloc-supl-init");
                } catch (IntentFilter.MalformedMimeTypeException e) {
                    Log.w(TAG, "Malformed SUPL init mime type");
                }
                mContext.registerReceiver(mBroadcastReceiver, intentFilter, null, this);
            } else if (DEBUG) {
                Log.d(TAG, "Skipped registration for SMS/WAP-PUSH messages because AGPS Ril in GPS"
                        + " HAL is not supported");
            }

            intentFilter = new IntentFilter();
            intentFilter.addAction(ALARM_WAKEUP);
            intentFilter.addAction(ALARM_TIMEOUT);
            intentFilter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
            intentFilter.addAction(SIM_STATE_CHANGED);
            mContext.registerReceiver(mBroadcastReceiver, intentFilter, null, this);

            // register for connectivity change events, this is equivalent to the deprecated way of
            // registering for CONNECTIVITY_ACTION broadcasts
            NetworkRequest.Builder networkRequestBuilder = new NetworkRequest.Builder();
            networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            networkRequestBuilder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
            NetworkRequest networkRequest = networkRequestBuilder.build();
            mConnMgr.registerNetworkCallback(networkRequest, mNetworkConnectivityCallback);

            // listen for PASSIVE_PROVIDER updates
            LocationManager locManager =
                    (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            long minTime = 0;
            float minDistance = 0;
            boolean oneShot = false;
            LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                    LocationManager.PASSIVE_PROVIDER,
                    minTime,
                    minDistance,
                    oneShot);
            // Don't keep track of this request since it's done on behalf of other clients
            // (which are kept track of separately).
            request.setHideFromAppOps(true);
            locManager.requestLocationUpdates(
                    request,
                    new NetworkLocationListener(),
                    getLooper());
        }
    }

    private abstract class LocationChangeListener implements LocationListener {
        int numLocationUpdateRequest;

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

    private String getSelectedApn() {
        Uri uri = Uri.parse("content://telephony/carriers/preferapn");
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                    uri,
                    new String[]{"apn"},
                    null /* selection */,
                    null /* selectionArgs */,
                    Carriers.DEFAULT_SORT_ORDER);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            } else {
                Log.e(TAG, "No APN found to select.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error encountered on selecting the APN.", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return null;
    }

    private int getApnIpType(String apn) {
        ensureInHandlerThread();
        if (apn == null) {
            return APN_INVALID;
        }

        String selection = String.format("current = 1 and apn = '%s' and carrier_enabled = 1", apn);
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                    Carriers.CONTENT_URI,
                    new String[]{Carriers.PROTOCOL},
                    selection,
                    null,
                    Carriers.DEFAULT_SORT_ORDER);

            if (null != cursor && cursor.moveToFirst()) {
                return translateToApnIpType(cursor.getString(0), apn);
            } else {
                Log.e(TAG, "No entry found in query for APN: " + apn);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error encountered on APN query for: " + apn, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return APN_INVALID;
    }

    private int translateToApnIpType(String ipProtocol, String apn) {
        if ("IP".equals(ipProtocol)) {
            return APN_IPV4;
        }
        if ("IPV6".equals(ipProtocol)) {
            return APN_IPV6;
        }
        if ("IPV4V6".equals(ipProtocol)) {
            return APN_IPV4V6;
        }

        // we hit the default case so the ipProtocol is not recognized
        String message = String.format("Unknown IP Protocol: %s, for APN: %s", ipProtocol, apn);
        Log.e(TAG, message);
        return APN_INVALID;
    }

    private void setRouting() {
        if (mAGpsDataConnectionIpAddr == null) {
            return;
        }

        // TODO: replace the use of this deprecated API
        boolean result = mConnMgr.requestRouteToHostAddress(
                ConnectivityManager.TYPE_MOBILE_SUPL,
                mAGpsDataConnectionIpAddr);

        if (!result) {
            Log.e(TAG, "Error requesting route to host: " + mAGpsDataConnectionIpAddr);
        } else if (DEBUG) {
            Log.d(TAG, "Successfully requested route to host: " + mAGpsDataConnectionIpAddr);
        }
    }

    /**
     * @return {@code true} if there is a data network available for outgoing connections,
     * {@code false} otherwise.
     */
    private boolean isDataNetworkConnected() {
        NetworkInfo activeNetworkInfo = mConnMgr.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    /**
     * Ensures the calling function is running in the thread associated with {@link #mHandler}.
     */
    private void ensureInHandlerThread() {
        if (mHandler != null && Looper.myLooper() == mHandler.getLooper()) {
            return;
        }
        throw new RuntimeException("This method must run on the Handler thread.");
    }

    /**
     * @return A string representing the current state stored in {@link #mAGpsDataConnectionState}.
     */
    private String agpsDataConnStateAsString() {
        switch (mAGpsDataConnectionState) {
            case AGPS_DATA_CONNECTION_CLOSED:
                return "CLOSED";
            case AGPS_DATA_CONNECTION_OPEN:
                return "OPEN";
            case AGPS_DATA_CONNECTION_OPENING:
                return "OPENING";
            default:
                return "<Unknown>";
        }
    }

    /**
     * @return A string representing the given GPS_AGPS_DATA status.
     */
    private String agpsDataConnStatusAsString(int agpsDataConnStatus) {
        switch (agpsDataConnStatus) {
            case GPS_AGPS_DATA_CONNECTED:
                return "CONNECTED";
            case GPS_AGPS_DATA_CONN_DONE:
                return "DONE";
            case GPS_AGPS_DATA_CONN_FAILED:
                return "FAILED";
            case GPS_RELEASE_AGPS_DATA_CONN:
                return "RELEASE";
            case GPS_REQUEST_AGPS_DATA_CONN:
                return "REQUEST";
            default:
                return "<Unknown>";
        }
    }

    /**
     * @return A string representing the given message ID.
     */
    private String messageIdAsString(int message) {
        switch (message) {
            case ENABLE:
                return "ENABLE";
            case SET_REQUEST:
                return "SET_REQUEST";
            case UPDATE_NETWORK_STATE:
                return "UPDATE_NETWORK_STATE";
            case REQUEST_SUPL_CONNECTION:
                return "REQUEST_SUPL_CONNECTION";
            case RELEASE_SUPL_CONNECTION:
                return "RELEASE_SUPL_CONNECTION";
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
            case SUBSCRIPTION_OR_SIM_CHANGED:
                return "SUBSCRIPTION_OR_SIM_CHANGED";
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

    private static native boolean native_is_agps_ril_supported();

    private static native boolean native_is_gnss_configuration_supported();

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
            int gnssLocationFlags,
            double latitudeDegrees,
            double longitudeDegrees,
            double altitudeMeters,
            float speedMetersPerSec,
            float bearingDegrees,
            float horizontalAccuracyMeters,
            float verticalAccuracyMeters,
            float speedAccuracyMetersPerSecond,
            float bearingAccuracyDegrees,
            long timestamp);

    private native void native_inject_location(double latitude, double longitude, float accuracy);

    // XTRA Support
    private native void native_inject_time(long time, long timeReference, int uncertainty);

    private native boolean native_supports_xtra();

    private native void native_inject_xtra_data(byte[] data, int length);

    // DEBUG Support
    private native String native_get_internal_state();

    // AGPS Support
    private native void native_agps_data_conn_open(String apn, int apnIpType);

    private native void native_agps_data_conn_closed();

    private native void native_agps_data_conn_failed();

    private native void native_agps_ni_message(byte[] msg, int length);

    private native void native_set_agps_server(int type, String hostname, int port);

    // Network-initiated (NI) Support
    private native void native_send_ni_response(int notificationId, int userResponse);

    // AGPS ril suport
    private native void native_agps_set_ref_location_cellid(int type, int mcc, int mnc,
            int lac, int cid);

    private native void native_agps_set_id(int type, String setid);

    private native void native_update_network_state(boolean connected, int type,
            boolean roaming, boolean available, String extraInfo, String defaultAPN);

    // Gps Hal measurements support.
    private static native boolean native_is_measurement_supported();

    private native boolean native_start_measurement_collection(boolean enableFullTracking);

    private native boolean native_stop_measurement_collection();

    // Gps Navigation message support.
    private static native boolean native_is_navigation_message_supported();

    private native boolean native_start_navigation_message_collection();

    private native boolean native_stop_navigation_message_collection();

    // GNSS Configuration
    private static native boolean native_set_supl_version(int version);

    private static native boolean native_set_supl_mode(int mode);

    private static native boolean native_set_supl_es(int es);

    private static native boolean native_set_lpp_profile(int lppProfile);

    private static native boolean native_set_gnss_pos_protocol_select(int gnssPosProtocolSelect);

    private static native boolean native_set_gps_lock(int gpsLock);

    private static native boolean native_set_emergency_supl_pdn(int emergencySuplPdn);

    private static native boolean native_set_satellite_blacklist(int[] constellations, int[] svIds);
}

