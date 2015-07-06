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

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.internal.location.GpsNetInitiatedHandler;
import com.android.internal.location.GpsNetInitiatedHandler.GpsNiNotification;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.location.GeofenceHardware;
import android.hardware.location.GeofenceHardwareImpl;
import android.location.Criteria;
import android.location.FusedBatchOptions;
import android.location.GpsMeasurementsEvent;
import android.location.GpsNavigationMessageEvent;
import android.location.IGpsGeofenceHardware;
import android.location.IGpsStatusListener;
import android.location.IGpsStatusProvider;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.provider.Telephony.Carriers;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.Log;
import android.util.NtpTrustedTime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Map.Entry;
import java.util.Properties;

import libcore.io.IoUtils;

/**
 * A GPS implementation of LocationProvider used by LocationManager.
 *
 * {@hide}
 */
public class GpsLocationProvider implements LocationProviderInterface {

    private static final String TAG = "GpsLocationProvider";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private static final ProviderProperties PROPERTIES = new ProviderProperties(
            true, true, false, false, true, true, true,
            Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);

    // these need to match GpsPositionMode enum in gps.h
    private static final int GPS_POSITION_MODE_STANDALONE = 0;
    private static final int GPS_POSITION_MODE_MS_BASED = 1;
    private static final int GPS_POSITION_MODE_MS_ASSISTED = 2;

    // these need to match GpsPositionRecurrence enum in gps.h
    private static final int GPS_POSITION_RECURRENCE_PERIODIC = 0;
    private static final int GPS_POSITION_RECURRENCE_SINGLE = 1;

    // these need to match GpsStatusValue defines in gps.h
    private static final int GPS_STATUS_NONE = 0;
    private static final int GPS_STATUS_SESSION_BEGIN = 1;
    private static final int GPS_STATUS_SESSION_END = 2;
    private static final int GPS_STATUS_ENGINE_ON = 3;
    private static final int GPS_STATUS_ENGINE_OFF = 4;

    // these need to match GpsApgsStatusValue defines in gps.h
    /** AGPS status event values. */
    private static final int GPS_REQUEST_AGPS_DATA_CONN = 1;
    private static final int GPS_RELEASE_AGPS_DATA_CONN = 2;
    private static final int GPS_AGPS_DATA_CONNECTED = 3;
    private static final int GPS_AGPS_DATA_CONN_DONE = 4;
    private static final int GPS_AGPS_DATA_CONN_FAILED = 5;

    // these need to match GpsLocationFlags enum in gps.h
    private static final int LOCATION_INVALID = 0;
    private static final int LOCATION_HAS_LAT_LONG = 1;
    private static final int LOCATION_HAS_ALTITUDE = 2;
    private static final int LOCATION_HAS_SPEED = 4;
    private static final int LOCATION_HAS_BEARING = 8;
    private static final int LOCATION_HAS_ACCURACY = 16;

    // IMPORTANT - the GPS_DELETE_* symbols here must match constants in gps.h
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

    // The GPS_CAPABILITY_* flags must match the values in gps.h
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

    // these need to match AGpsType enum in gps.h
    private static final int AGPS_TYPE_SUPL = 1;
    private static final int AGPS_TYPE_C2K = 2;

    // these must match the definitions in gps.h
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
    private static final int UPDATE_LOCATION = 7;
    private static final int ADD_LISTENER = 8;
    private static final int REMOVE_LISTENER = 9;
    private static final int INJECT_NTP_TIME_FINISHED = 10;
    private static final int DOWNLOAD_XTRA_DATA_FINISHED = 11;
    private static final int SUBSCRIPTION_OR_SIM_CHANGED = 12;
    private static final int INITIALIZE_HANDLER = 13;

    // Request setid
    private static final int AGPS_RIL_REQUEST_SETID_IMSI = 1;
    private static final int AGPS_RIL_REQUEST_SETID_MSISDN = 2;

    // Request ref location
    private static final int AGPS_RIL_REQUEST_REFLOC_CELLID = 1;
    private static final int AGPS_RIL_REQUEST_REFLOC_MAC = 2;

    // ref. location info
    private static final int AGPS_REF_LOCATION_TYPE_GSM_CELLID = 1;
    private static final int AGPS_REF_LOCATION_TYPE_UMTS_CELLID = 2;
    private static final int AGPS_REG_LOCATION_TYPE_MAC        = 3;

    // set id info
    private static final int AGPS_SETID_TYPE_NONE = 0;
    private static final int AGPS_SETID_TYPE_IMSI = 1;
    private static final int AGPS_SETID_TYPE_MSISDN = 2;

    private static final String PROPERTIES_FILE_PREFIX = "/etc/gps";
    private static final String PROPERTIES_FILE_SUFFIX = ".conf";
    private static final String DEFAULT_PROPERTIES_FILE = PROPERTIES_FILE_PREFIX + PROPERTIES_FILE_SUFFIX;

    private static final int GPS_GEOFENCE_UNAVAILABLE = 1<<0L;
    private static final int GPS_GEOFENCE_AVAILABLE = 1<<1L;

    // GPS Geofence errors. Should match gps.h constants.
    private static final int GPS_GEOFENCE_OPERATION_SUCCESS = 0;
    private static final int GPS_GEOFENCE_ERROR_TOO_MANY_GEOFENCES = 100;
    private static final int GPS_GEOFENCE_ERROR_ID_EXISTS  = -101;
    private static final int GPS_GEOFENCE_ERROR_ID_UNKNOWN = -102;
    private static final int GPS_GEOFENCE_ERROR_INVALID_TRANSITION = -103;
    private static final int GPS_GEOFENCE_ERROR_GENERIC = -149;

    // TCP/IP constants.
    // Valid TCP/UDP port range is (0, 65535].
    private static final int TCP_MIN_PORT = 0;
    private static final int TCP_MAX_PORT = 0xffff;

    // Value of batterySaverGpsMode such that GPS isn't affected by battery saver mode.
    private static final int BATTERY_SAVER_MODE_NO_CHANGE = 0;
    // Value of batterySaverGpsMode such that GPS is disabled when battery saver mode
    // is enabled and the screen is off.
    private static final int BATTERY_SAVER_MODE_DISABLED_WHEN_SCREEN_OFF = 1;
    // Secure setting for GPS behavior when battery saver mode is on.
    private static final String BATTERY_SAVER_GPS_MODE = "batterySaverGpsMode";

    /** simpler wrapper for ProviderRequest + Worksource */
    private static class GpsRequest {
        public ProviderRequest request;
        public WorkSource source;
        public GpsRequest(ProviderRequest request, WorkSource source) {
            this.request = request;
            this.source = source;
        }
    }

    private Object mLock = new Object();

    private int mLocationFlags = LOCATION_INVALID;

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

    // how often to request NTP time, in milliseconds
    // current setting 24 hours
    private static final long NTP_INTERVAL = 24*60*60*1000;
    // how long to wait if we have a network error in NTP or XTRA downloading
    // the initial value of the exponential backoff
    // current setting - 5 minutes
    private static final long RETRY_INTERVAL = 5*60*1000;
    // how long to wait if we have a network error in NTP or XTRA downloading
    // the max value of the exponential backoff
    // current setting - 4 hours
    private static final long MAX_RETRY_INTERVAL = 4*60*60*1000;

    private BackOff mNtpBackOff = new BackOff(RETRY_INTERVAL, MAX_RETRY_INTERVAL);
    private BackOff mXtraBackOff = new BackOff(RETRY_INTERVAL, MAX_RETRY_INTERVAL);

    // true if we are enabled, protected by this
    private boolean mEnabled;

    // true if we have network connectivity
    private boolean mNetworkAvailable;

    // states for injecting ntp and downloading xtra data
    private static final int STATE_PENDING_NETWORK = 0;
    private static final int STATE_DOWNLOADING = 1;
    private static final int STATE_IDLE = 2;

    // flags to trigger NTP or XTRA data download when network becomes available
    // initialized to true so we do NTP and XTRA when the network comes up after booting
    private int mInjectNtpTimePending = STATE_PENDING_NETWORK;
    private int mDownloadXtraDataPending = STATE_PENDING_NETWORK;

    // set to true if the GPS engine does not do on-demand NTP time requests
    private boolean mPeriodicTimeInjection;

    // true if GPS is navigating
    private boolean mNavigating;

    // true if GPS engine is on
    private boolean mEngineOn;

    // requested frequency of fixes, in milliseconds
    private int mFixInterval = 1000;

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
    // Current list of underlying location clients.
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
    private final NtpTrustedTime mNtpTime;
    private final ILocationManager mILocationManager;
    private Location mLocation = new Location(LocationManager.GPS_PROVIDER);
    private Bundle mLocationExtras = new Bundle();
    private final GpsStatusListenerHelper mListenerHelper;
    private final GpsMeasurementsProvider mGpsMeasurementsProvider;
    private final GpsNavigationMessageProvider mGpsNavigationMessageProvider;

    // Handler for processing events
    private Handler mHandler;

    private String mAGpsApn;
    private int mApnIpType;
    private int mAGpsDataConnectionState;
    private InetAddress mAGpsDataConnectionIpAddr;
    private final ConnectivityManager mConnMgr;
    private final GpsNetInitiatedHandler mNIHandler;

    // Wakelocks
    private final static String WAKELOCK_KEY = "GpsLocationProvider";
    private final PowerManager.WakeLock mWakeLock;

    // Alarms
    private final static String ALARM_WAKEUP = "com.android.internal.location.ALARM_WAKEUP";
    private final static String ALARM_TIMEOUT = "com.android.internal.location.ALARM_TIMEOUT";

    // SIM/Carrier info.
    private final static String SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED";

    private final PowerManager mPowerManager;
    private final AlarmManager mAlarmManager;
    private final PendingIntent mWakeupIntent;
    private final PendingIntent mTimeoutIntent;

    private final IAppOpsService mAppOpsService;
    private final IBatteryStats mBatteryStats;

    // only modified on handler thread
    private WorkSource mClientSource = new WorkSource();

    private GeofenceHardwareImpl mGeofenceHardwareImpl;

    private final IGpsStatusProvider mGpsStatusProvider = new IGpsStatusProvider.Stub() {
        @Override
        public void addGpsStatusListener(IGpsStatusListener listener) {
            mListenerHelper.addListener(listener);
        }

        @Override
        public void removeGpsStatusListener(IGpsStatusListener listener) {
            mListenerHelper.removeListener(listener);
        }
    };

    public IGpsStatusProvider getGpsStatusProvider() {
        return mGpsStatusProvider;
    }

    public IGpsGeofenceHardware getGpsGeofenceProxy() {
        return mGpsGeofenceBinder;
    }

    public GpsMeasurementsProvider getGpsMeasurementsProvider() {
        return mGpsMeasurementsProvider;
    }

    public GpsNavigationMessageProvider getGpsNavigationMessageProvider() {
        return mGpsNavigationMessageProvider;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (DEBUG) Log.d(TAG, "receive broadcast intent, action: " + action);
            if (action.equals(ALARM_WAKEUP)) {
                startNavigating(false);
            } else if (action.equals(ALARM_TIMEOUT)) {
                hibernate();
            } else if (action.equals(Intents.DATA_SMS_RECEIVED_ACTION)) {
                checkSmsSuplInit(intent);
            } else if (action.equals(Intents.WAP_PUSH_RECEIVED_ACTION)) {
                checkWapSuplInit(intent);
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                // retrieve NetworkInfo result for this UID
                NetworkInfo info =
                        intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
                ConnectivityManager connManager = (ConnectivityManager)
                        mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                info = connManager.getNetworkInfo(info.getType());

                int networkState;
                if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false) ||
                        !info.isConnected()) {
                    networkState = LocationProvider.TEMPORARILY_UNAVAILABLE;
                } else {
                    networkState = LocationProvider.AVAILABLE;
                }

                updateNetworkState(networkState, info);
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

    private void subscriptionOrSimChanged(Context context) {
        Log.d(TAG, "received SIM related action: ");
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        String mccMnc = phone.getSimOperator();
        if (!TextUtils.isEmpty(mccMnc)) {
            Log.d(TAG, "SIM MCC/MNC is available: " + mccMnc);
            synchronized (mLock) {
                reloadGpsProperties(context, mProperties);
                mNIHandler.setSuplEsEnabled(mSuplEsEnabled);
            }
        } else {
            Log.d(TAG, "SIM MCC/MNC is still not available");
        }
    }

    private void checkSmsSuplInit(Intent intent) {
        SmsMessage[] messages = Intents.getMessagesFromIntent(intent);
        for (int i=0; i <messages.length; i++) {
            byte[] supl_init = messages[i].getUserData();
            native_agps_ni_message(supl_init,supl_init.length);
        }
    }

    private void checkWapSuplInit(Intent intent) {
        byte[] supl_init = (byte[]) intent.getExtra("data");
        native_agps_ni_message(supl_init,supl_init.length);
    }

    private void updateLowPowerMode() {
        // Disable GPS if we are in device idle mode.
        boolean disableGps = mPowerManager.isDeviceIdleMode();
        switch (Settings.Secure.getInt(mContext.getContentResolver(), BATTERY_SAVER_GPS_MODE,
                BATTERY_SAVER_MODE_DISABLED_WHEN_SCREEN_OFF)) {
            case BATTERY_SAVER_MODE_DISABLED_WHEN_SCREEN_OFF:
                // If we are in battery saver mode and the screen is off, disable GPS.
                disableGps |= mPowerManager.isPowerSaveMode() && !mPowerManager.isInteractive();
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

    private void reloadGpsProperties(Context context, Properties properties) {
        Log.d(TAG, "Reset GPS properties, previous size = " + properties.size());
        loadPropertiesFromResource(context, properties);
        boolean isPropertiesLoadedFromFile = false;
        final String gpsHardware = SystemProperties.get("ro.hardware.gps");
        if (!TextUtils.isEmpty(gpsHardware)) {
            final String propFilename =
                    PROPERTIES_FILE_PREFIX + "." + gpsHardware + PROPERTIES_FILE_SUFFIX;
            isPropertiesLoadedFromFile =
                    loadPropertiesFromFile(propFilename, properties);
        }
        if (!isPropertiesLoadedFromFile) {
            loadPropertiesFromFile(DEFAULT_PROPERTIES_FILE, properties);
        }
        Log.d(TAG, "GPS properties reloaded, size = " + properties.size());

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
            try {
                // Convert properties to string contents and send it to HAL.
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
                properties.store(baos, null);
                native_configuration_update(baos.toString());
                Log.d(TAG, "final config = " + baos.toString());
            } catch (IOException ex) {
                Log.w(TAG, "failed to dump properties contents");
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
            Log.d(TAG, "GpsParamsResource: " + item);
            // We need to support "KEY =", but not "=VALUE".
            String[] split = item.split("=");
            if (split.length == 2) {
                properties.setProperty(split[0].trim().toUpperCase(), split[1]);
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
            Log.w(TAG, "Could not open GPS configuration file " + filename);
            return false;
        }
        return true;
    }

    public GpsLocationProvider(Context context, ILocationManager ilocationManager,
            Looper looper) {
        mContext = context;
        mNtpTime = NtpTrustedTime.getInstance(context);
        mILocationManager = ilocationManager;

        mLocation.setExtras(mLocationExtras);

        // Create a wake lock
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
        mWakeLock.setReferenceCounted(true);

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        mWakeupIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ALARM_WAKEUP), 0);
        mTimeoutIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ALARM_TIMEOUT), 0);

        mConnMgr = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // App ops service to keep track of who is accessing the GPS
        mAppOpsService = IAppOpsService.Stub.asInterface(ServiceManager.getService(
                Context.APP_OPS_SERVICE));

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

        mListenerHelper = new GpsStatusListenerHelper(mHandler) {
            @Override
            protected boolean isAvailableInPlatform() {
                return isSupported();
            }

            @Override
            protected boolean isGpsEnabled() {
                return isEnabled();
            }
        };

        mGpsMeasurementsProvider = new GpsMeasurementsProvider(mHandler) {
            @Override
            public boolean isAvailableInPlatform() {
                return native_is_measurement_supported();
            }

            @Override
            protected boolean registerWithService() {
                return native_start_measurement_collection();
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

        mGpsNavigationMessageProvider = new GpsNavigationMessageProvider(mHandler) {
            @Override
            protected boolean isAvailableInPlatform() {
                return native_is_navigation_message_supported();
            }

            @Override
            protected boolean registerWithService() {
                return native_start_navigation_message_collection();
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

    public void updateNetworkState(int state, NetworkInfo info) {
        sendMessage(UPDATE_NETWORK_STATE, state, info);
    }

    private void handleUpdateNetworkState(int state, NetworkInfo info) {
        mNetworkAvailable = (state == LocationProvider.AVAILABLE);

        if (DEBUG) {
            Log.d(TAG, "updateNetworkState " + (mNetworkAvailable ? "available" : "unavailable")
                + " info: " + info);
        }

        if (info != null) {
            if (native_is_agps_ril_supported()) {
                boolean dataEnabled = TelephonyManager.getDefault().getDataEnabled();
                boolean networkAvailable = info.isAvailable() && dataEnabled;
                String defaultApn = getSelectedApn();
                if (defaultApn == null) {
                    defaultApn = "dummy-apn";
                }

                native_update_network_state(info.isConnected(), info.getType(),
                        info.isRoaming(), networkAvailable,
                        info.getExtraInfo(), defaultApn);
            } else if (DEBUG) {
                Log.d(TAG, "Skipped network state update because AGPS-RIL in GPS HAL is not"
                        + " supported");
            }
        }

        if (info != null && info.getType() == ConnectivityManager.TYPE_MOBILE_SUPL
                && mAGpsDataConnectionState == AGPS_DATA_CONNECTION_OPENING) {
            if (mNetworkAvailable) {
                String apnName = info.getExtraInfo();
                if (apnName == null) {
                    /* Assign a dummy value in the case of C2K as otherwise we will have a runtime
                    exception in the following call to native_agps_data_conn_open*/
                    apnName = "dummy-apn";
                }
                mAGpsApn = apnName;
                mApnIpType = getApnIpType(apnName);
                setRouting();
                if (DEBUG) {
                    String message = String.format(
                            "native_agps_data_conn_open: mAgpsApn=%s, mApnIpType=%s",
                            mAGpsApn, mApnIpType);
                    Log.d(TAG, message);
                }
                native_agps_data_conn_open(mAGpsApn, mApnIpType);
                mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPEN;
            } else {
                Log.e(TAG, "call native_agps_data_conn_failed, info: " + info);
                mAGpsApn = null;
                mApnIpType = APN_INVALID;
                mAGpsDataConnectionState = AGPS_DATA_CONNECTION_CLOSED;
                native_agps_data_conn_failed();
            }
        }

        if (mNetworkAvailable) {
            if (mInjectNtpTimePending == STATE_PENDING_NETWORK) {
                sendMessage(INJECT_NTP_TIME, 0, null);
            }
            if (mDownloadXtraDataPending == STATE_PENDING_NETWORK) {
                sendMessage(DOWNLOAD_XTRA_DATA, 0, null);
            }
        }
    }

    private void handleInjectNtpTime() {
        if (mInjectNtpTimePending == STATE_DOWNLOADING) {
            // already downloading data
            return;
        }
        if (!mNetworkAvailable) {
            // try again when network is up
            mInjectNtpTimePending = STATE_PENDING_NETWORK;
            return;
        }
        mInjectNtpTimePending = STATE_DOWNLOADING;

        // hold wake lock while task runs
        mWakeLock.acquire();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                long delay;

                // force refresh NTP cache when outdated
                if (mNtpTime.getCacheAge() >= NTP_INTERVAL) {
                    mNtpTime.forceRefresh();
                }

                // only update when NTP time is fresh
                if (mNtpTime.getCacheAge() < NTP_INTERVAL) {
                    long time = mNtpTime.getCachedNtpTime();
                    long timeReference = mNtpTime.getCachedNtpTimeReference();
                    long certainty = mNtpTime.getCacheCertainty();
                    long now = System.currentTimeMillis();

                    Log.d(TAG, "NTP server returned: "
                            + time + " (" + new Date(time)
                            + ") reference: " + timeReference
                            + " certainty: " + certainty
                            + " system time offset: " + (time - now));

                    native_inject_time(time, timeReference, (int) certainty);
                    delay = NTP_INTERVAL;
                    mNtpBackOff.reset();
                } else {
                    if (DEBUG) Log.d(TAG, "requestTime failed");
                    delay = mNtpBackOff.nextBackoffMillis();
                }

                sendMessage(INJECT_NTP_TIME_FINISHED, 0, null);

                if (mPeriodicTimeInjection) {
                    // send delayed message for next NTP injection
                    // since this is delayed and not urgent we do not hold a wake lock here
                    mHandler.sendEmptyMessageDelayed(INJECT_NTP_TIME, delay);
                }

                // release wake lock held by task
                mWakeLock.release();
            }
        });
    }

    private void handleDownloadXtraData() {
        if (mDownloadXtraDataPending == STATE_DOWNLOADING) {
            // already downloading data
            return;
        }
        if (!mNetworkAvailable) {
            // try again when network is up
            mDownloadXtraDataPending = STATE_PENDING_NETWORK;
            return;
        }
        mDownloadXtraDataPending = STATE_DOWNLOADING;

        // hold wake lock while task runs
        mWakeLock.acquire();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                GpsXtraDownloader xtraDownloader = new GpsXtraDownloader(mProperties);
                byte[] data = xtraDownloader.downloadXtraData();
                if (data != null) {
                    if (DEBUG) {
                        Log.d(TAG, "calling native_inject_xtra_data");
                    }
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

                // release wake lock held by task
                mWakeLock.release();
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
     * @param properties GPS properties
     * @param agpsEnabled whether AGPS is enabled by settings value
     * @param singleShot whether "singleshot" is needed
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

            mGpsMeasurementsProvider.onGpsEnabledChanged();
            mGpsNavigationMessageProvider.onGpsEnabledChanged();
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

        // do this before releasing wakelock
        native_cleanup();

        mGpsMeasurementsProvider.onGpsEnabledChanged();
        mGpsNavigationMessageProvider.onGpsEnabledChanged();
    }

    @Override
    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    @Override
    public int getStatus(Bundle extras) {
        if (extras != null) {
            extras.putInt("satellites", mSvCount);
        }
        return mStatus;
    }

    private void updateStatus(int status, int svCount) {
        if (status != mStatus || svCount != mSvCount) {
            mStatus = status;
            mSvCount = svCount;
            mLocationExtras.putInt("satellites", svCount);
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
        if (mProviderRequest.reportLocation && !mDisableGps) {
            // update client uids
            updateClientUids(mWorkSource);

            mFixInterval = (int) mProviderRequest.interval;

            // check for overflow
            if (mFixInterval != mProviderRequest.interval) {
                Log.w(TAG, "interval overflow: " + mProviderRequest.interval);
                mFixInterval = Integer.MAX_VALUE;
            }

            // apply request to GPS engine
            if (mStarted && hasCapability(GPS_CAPABILITY_SCHEDULING)) {
                // change period
                if (!native_set_position_mode(mPositionMode, GPS_POSITION_RECURRENCE_PERIODIC,
                        mFixInterval, 0, 0)) {
                    Log.e(TAG, "set_position_mode failed in setMinTime()");
                }
            } else if (!mStarted) {
                // start GPS
                startNavigating(singleShot);
            }
        } else {
            updateClientUids(new WorkSource());

            stopNavigating();
            mAlarmManager.cancel(mWakeupIntent);
            mAlarmManager.cancel(mTimeoutIntent);
        }
    }

    private void updateClientUids(WorkSource source) {
        // Update work source.
        WorkSource[] changes = mClientSource.setReturningDiffs(source);
        if (changes == null) {
            return;
        }
        WorkSource newWork = changes[0];
        WorkSource goneWork = changes[1];

        // Update sources that were not previously tracked.
        if (newWork != null) {
            int lastuid = -1;
            for (int i=0; i<newWork.size(); i++) {
                try {
                    int uid = newWork.get(i);
                    mAppOpsService.startOperation(AppOpsManager.getToken(mAppOpsService),
                            AppOpsManager.OP_GPS, uid, newWork.getName(i));
                    if (uid != lastuid) {
                        lastuid = uid;
                        mBatteryStats.noteStartGps(uid);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException", e);
                }
            }
        }

        // Update sources that are no longer tracked.
        if (goneWork != null) {
            int lastuid = -1;
            for (int i=0; i<goneWork.size(); i++) {
                try {
                    int uid = goneWork.get(i);
                    mAppOpsService.finishOperation(AppOpsManager.getToken(mAppOpsService),
                            AppOpsManager.OP_GPS, uid, goneWork.getName(i));
                    if (uid != lastuid) {
                        lastuid = uid;
                        mBatteryStats.noteStopGps(uid);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException", e);
                }
            }
        }
    }

    @Override
    public boolean sendExtraCommand(String command, Bundle extras) {

        long identity = Binder.clearCallingIdentity();
        boolean result = false;

        if ("delete_aiding_data".equals(command)) {
            result = deleteAidingData(extras);
        } else if ("force_time_injection".equals(command)) {
            sendMessage(INJECT_NTP_TIME, 0, null);
            result = true;
        } else if ("force_xtra_injection".equals(command)) {
            if (mSupportsXtra) {
                xtraDownloadRequest();
                result = true;
            }
        } else {
            Log.w(TAG, "sendExtraCommand: unknown command " + command);
        }

        Binder.restoreCallingIdentity(identity);
        return result;
    }

    private IGpsGeofenceHardware mGpsGeofenceBinder = new IGpsGeofenceHardware.Stub() {
        public boolean isHardwareGeofenceSupported() {
            return native_is_geofence_supported();
        }

        public boolean addCircularHardwareGeofence(int geofenceId, double latitude,
                double longitude, double radius, int lastTransition, int monitorTransitions,
                int notificationResponsiveness, int unknownTimer) {
            return native_add_geofence(geofenceId, latitude, longitude, radius,
                    lastTransition, monitorTransitions, notificationResponsiveness, unknownTimer);
        }

        public boolean removeHardwareGeofence(int geofenceId) {
            return native_remove_geofence(geofenceId);
        }

        public boolean pauseHardwareGeofence(int geofenceId) {
            return native_pause_geofence(geofenceId);
        }

        public boolean resumeHardwareGeofence(int geofenceId, int monitorTransition) {
            return native_resume_geofence(geofenceId, monitorTransition);
        }
    };

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

            boolean agpsEnabled =
                    (Settings.Global.getInt(mContext.getContentResolver(),
                                            Settings.Global.ASSISTED_GPS_ENABLED, 1) != 0);
            mPositionMode = getSuplMode(mProperties, agpsEnabled, singleShot);

            if (DEBUG) {
                String mode;

                switch(mPositionMode) {
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
            if (!native_set_position_mode(mPositionMode, GPS_POSITION_RECURRENCE_PERIODIC,
                    interval, 0, 0)) {
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
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE, 0);
            mFixRequestTime = System.currentTimeMillis();
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
            mTimeToFirstFix = 0;
            mLastFixTime = 0;
            mLocationFlags = LOCATION_INVALID;

            // reset SV count to zero
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE, 0);
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
    private void reportLocation(int flags, double latitude, double longitude, double altitude,
            float speed, float bearing, float accuracy, long timestamp) {
        if (VERBOSE) Log.v(TAG, "reportLocation lat: " + latitude + " long: " + longitude +
                " timestamp: " + timestamp);

        synchronized (mLocation) {
            mLocationFlags = flags;
            if ((flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
                mLocation.setLatitude(latitude);
                mLocation.setLongitude(longitude);
                mLocation.setTime(timestamp);
                // It would be nice to push the elapsed real-time timestamp
                // further down the stack, but this is still useful
                mLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            }
            if ((flags & LOCATION_HAS_ALTITUDE) == LOCATION_HAS_ALTITUDE) {
                mLocation.setAltitude(altitude);
            } else {
                mLocation.removeAltitude();
            }
            if ((flags & LOCATION_HAS_SPEED) == LOCATION_HAS_SPEED) {
                mLocation.setSpeed(speed);
            } else {
                mLocation.removeSpeed();
            }
            if ((flags & LOCATION_HAS_BEARING) == LOCATION_HAS_BEARING) {
                mLocation.setBearing(bearing);
            } else {
                mLocation.removeBearing();
            }
            if ((flags & LOCATION_HAS_ACCURACY) == LOCATION_HAS_ACCURACY) {
                mLocation.setAccuracy(accuracy);
            } else {
                mLocation.removeAccuracy();
            }
            mLocation.setExtras(mLocationExtras);

            try {
                mILocationManager.reportLocation(mLocation, false);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling reportLocation");
            }
        }

        mLastFixTime = System.currentTimeMillis();
        // report time to first fix
        if (mTimeToFirstFix == 0 && (flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
            mTimeToFirstFix = (int)(mLastFixTime - mFixRequestTime);
            if (DEBUG) Log.d(TAG, "TTFF: " + mTimeToFirstFix);

            // notify status listeners
            mListenerHelper.onFirstFix(mTimeToFirstFix);
        }

        if (mSingleShot) {
            stopNavigating();
        }

        if (mStarted && mStatus != LocationProvider.AVAILABLE) {
            // we want to time out if we do not receive a fix
            // within the time out and we are requesting infrequent fixes
            if (!hasCapability(GPS_CAPABILITY_SCHEDULING) && mFixInterval < NO_FIX_TIMEOUT) {
                mAlarmManager.cancel(mTimeoutIntent);
            }

            // send an intent to notify that the GPS is receiving fixes.
            Intent intent = new Intent(LocationManager.GPS_FIX_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, true);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            updateStatus(LocationProvider.AVAILABLE, mSvCount);
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

    /**
     * called from native code to update SV info
     */
    private void reportSvStatus() {
        int svCount = native_read_sv_status(mSvs, mSnrs, mSvElevations, mSvAzimuths, mSvMasks);
        mListenerHelper.onSvStatusChanged(
                svCount,
                mSvs,
                mSnrs,
                mSvElevations,
                mSvAzimuths,
                mSvMasks[EPHEMERIS_MASK],
                mSvMasks[ALMANAC_MASK],
                mSvMasks[USED_FOR_FIX_MASK]);

        if (VERBOSE) {
            Log.v(TAG, "SV count: " + svCount +
                    " ephemerisMask: " + Integer.toHexString(mSvMasks[EPHEMERIS_MASK]) +
                    " almanacMask: " + Integer.toHexString(mSvMasks[ALMANAC_MASK]));
            for (int i = 0; i < svCount; i++) {
                Log.v(TAG, "sv: " + mSvs[i] +
                        " snr: " + mSnrs[i]/10 +
                        " elev: " + mSvElevations[i] +
                        " azimuth: " + mSvAzimuths[i] +
                        ((mSvMasks[EPHEMERIS_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "  " : " E") +
                        ((mSvMasks[ALMANAC_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "  " : " A") +
                        ((mSvMasks[USED_FOR_FIX_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "" : "U"));
            }
        }

        // return number of sets used in fix instead of total
        updateStatus(mStatus, Integer.bitCount(mSvMasks[USED_FOR_FIX_MASK]));

        if (mNavigating && mStatus == LocationProvider.AVAILABLE && mLastFixTime > 0 &&
            System.currentTimeMillis() - mLastFixTime > RECENT_FIX_TIMEOUT) {
            // send an intent to notify that the GPS is no longer receiving fixes.
            Intent intent = new Intent(LocationManager.GPS_FIX_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, false);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE, mSvCount);
        }
    }

    /**
     * called from native code to update AGPS status
     */
    private void reportAGpsStatus(int type, int status, byte[] ipaddr) {
        switch (status) {
            case GPS_REQUEST_AGPS_DATA_CONN:
                if (DEBUG) Log.d(TAG, "GPS_REQUEST_AGPS_DATA_CONN");
                Log.v(TAG, "Received SUPL IP addr[]: " + ipaddr);
                // Set mAGpsDataConnectionState before calling startUsingNetworkFeature
                //  to avoid a race condition with handleUpdateNetworkState()
                mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPENING;
                int result = mConnMgr.startUsingNetworkFeature(
                        ConnectivityManager.TYPE_MOBILE, Phone.FEATURE_ENABLE_SUPL);
                if (ipaddr != null) {
                    try {
                        mAGpsDataConnectionIpAddr = InetAddress.getByAddress(ipaddr);
                        Log.v(TAG, "IP address converted to: " + mAGpsDataConnectionIpAddr);
                    } catch (UnknownHostException e) {
                        Log.e(TAG, "Bad IP Address: " + ipaddr, e);
                        mAGpsDataConnectionIpAddr = null;
                    }
                }

                if (result == PhoneConstants.APN_ALREADY_ACTIVE) {
                    if (DEBUG) Log.d(TAG, "PhoneConstants.APN_ALREADY_ACTIVE");
                    if (mAGpsApn != null) {
                        setRouting();
                        native_agps_data_conn_open(mAGpsApn, mApnIpType);
                        mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPEN;
                    } else {
                        Log.e(TAG, "mAGpsApn not set when receiving PhoneConstants.APN_ALREADY_ACTIVE");
                        mAGpsDataConnectionState = AGPS_DATA_CONNECTION_CLOSED;
                        native_agps_data_conn_failed();
                    }
                } else if (result == PhoneConstants.APN_REQUEST_STARTED) {
                    if (DEBUG) Log.d(TAG, "PhoneConstants.APN_REQUEST_STARTED");
                    // Nothing to do here
                } else {
                    if (DEBUG) Log.d(TAG, "startUsingNetworkFeature failed, value is " +
                                     result);
                    mAGpsDataConnectionState = AGPS_DATA_CONNECTION_CLOSED;
                    native_agps_data_conn_failed();
                }
                break;
            case GPS_RELEASE_AGPS_DATA_CONN:
                if (DEBUG) Log.d(TAG, "GPS_RELEASE_AGPS_DATA_CONN");
                if (mAGpsDataConnectionState != AGPS_DATA_CONNECTION_CLOSED) {
                    mConnMgr.stopUsingNetworkFeature(
                            ConnectivityManager.TYPE_MOBILE, Phone.FEATURE_ENABLE_SUPL);
                    native_agps_data_conn_closed();
                    mAGpsDataConnectionState = AGPS_DATA_CONNECTION_CLOSED;
                    mAGpsDataConnectionIpAddr = null;
                }
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
                Log.d(TAG, "Received Unknown AGPS status: " + status);
        }
    }

    /**
     * called from native code to report NMEA data received
     */
    private void reportNmea(long timestamp) {
        int length = native_read_nmea(mNmeaBuffer, mNmeaBuffer.length);
        String nmea = new String(mNmeaBuffer, 0 /* offset */, length);
        mListenerHelper.onNmeaReceived(timestamp, nmea);
    }

    /**
     * called from native code - Gps measurements callback
     */
    private void reportMeasurementData(GpsMeasurementsEvent event) {
        mGpsMeasurementsProvider.onMeasurementsAvailable(event);
    }

    /**
     * called from native code - GPS navigation message callback
     */
    private void reportNavigationMessage(GpsNavigationMessageEvent event) {
        mGpsNavigationMessageProvider.onNavigationMessageAvailable(event);
    }

    /**
     * called from native code to inform us what the GPS engine capabilities are
     */
    private void setEngineCapabilities(int capabilities) {
        mEngineCapabilities = capabilities;

        if (!hasCapability(GPS_CAPABILITY_ON_DEMAND_TIME) && !mPeriodicTimeInjection) {
            mPeriodicTimeInjection = true;
            requestUtcTime();
        }

        mGpsMeasurementsProvider.onCapabilitiesUpdated(
                (capabilities & GPS_CAPABILITY_MEASUREMENTS) == GPS_CAPABILITY_MEASUREMENTS);
        mGpsNavigationMessageProvider.onCapabilitiesUpdated(
                (capabilities & GPS_CAPABILITY_NAV_MESSAGES) == GPS_CAPABILITY_NAV_MESSAGES);
    }

    /**
     * called from native code to request XTRA data
     */
    private void xtraDownloadRequest() {
        if (DEBUG) Log.d(TAG, "xtraDownloadRequest");
        sendMessage(DOWNLOAD_XTRA_DATA, 0, null);
    }

    /**
     * Helper method to construct a location object.
     */
    private Location buildLocation(
            int flags,
            double latitude,
            double longitude,
            double altitude,
            float speed,
            float bearing,
            float accuracy,
            long timestamp) {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        if((flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
            location.setLatitude(latitude);
            location.setLongitude(longitude);
            location.setTime(timestamp);
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }
        if((flags & LOCATION_HAS_ALTITUDE) == LOCATION_HAS_ALTITUDE) {
            location.setAltitude(altitude);
        }
        if((flags & LOCATION_HAS_SPEED) == LOCATION_HAS_SPEED) {
            location.setSpeed(speed);
        }
        if((flags & LOCATION_HAS_BEARING) == LOCATION_HAS_BEARING) {
            location.setBearing(bearing);
        }
        if((flags & LOCATION_HAS_ACCURACY) == LOCATION_HAS_ACCURACY) {
            location.setAccuracy(accuracy);
        }
        return location;
    }

    /**
     * Converts the GPS HAL status to the internal Geofence Hardware status.
     */
    private int getGeofenceStatus(int status) {
        switch(status) {
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
    private void reportGeofenceTransition(int geofenceId, int flags, double latitude,
            double longitude, double altitude, float speed, float bearing, float accuracy,
            long timestamp, int transition, long transitionTimestamp) {
        if (mGeofenceHardwareImpl == null) {
            mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
        }
        Location location = buildLocation(
                flags,
                latitude,
                longitude,
                altitude,
                speed,
                bearing,
                accuracy,
                timestamp);
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
    private void reportGeofenceStatus(int status, int flags, double latitude,
            double longitude, double altitude, float speed, float bearing, float accuracy,
            long timestamp) {
        if (mGeofenceHardwareImpl == null) {
            mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(mContext);
        }
        Location location = buildLocation(
                flags,
                latitude,
                longitude,
                altitude,
                speed,
                bearing,
                accuracy,
                timestamp);
        int monitorStatus = GeofenceHardware.MONITOR_CURRENTLY_UNAVAILABLE;
        if(status == GPS_GEOFENCE_AVAILABLE) {
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
        public boolean sendNiResponse(int notificationId, int userResponse)
        {
            // TODO Add Permission check

            if (DEBUG) Log.d(TAG, "sendNiResponse, notifId: " + notificationId +
                    ", response: " + userResponse);
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
            int textEncoding,
            String extras  // Encoded extra data
        )
    {
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
        notification.privacyOverride = (notifyFlags & GpsNetInitiatedHandler.GPS_NI_PRIVACY_OVERRIDE) != 0;
        notification.timeout = timeout;
        notification.defaultResponse = defaultResponse;
        notification.requestorId = requestorId;
        notification.text = text;
        notification.requestorIdEncoding = requestorIdEncoding;
        notification.textEncoding = textEncoding;

        // Process extras, assuming the format is
        // one of more lines of "key = value"
        Bundle bundle = new Bundle();

        if (extras == null) extras = "";
        Properties extraProp = new Properties();

        try {
            extraProp.load(new StringReader(extras));
        }
        catch (IOException e)
        {
            Log.e(TAG, "reportNiNotification cannot parse extras data: " + extras);
        }

        for (Entry<Object, Object> ent : extraProp.entrySet())
        {
            bundle.putString((String) ent.getKey(), (String) ent.getValue());
        }

        notification.extras = bundle;

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
        }
        else if ((flags & AGPS_RIL_REQUEST_SETID_MSISDN) == AGPS_RIL_REQUEST_SETID_MSISDN) {
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
     * Called from native code to request utc time info
     */

    private void requestUtcTime() {
        sendMessage(INJECT_NTP_TIME, 0, null);
    }

    /**
     * Called from native code to request reference location info
     */

    private void requestRefLocation(int flags) {
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        final int phoneType = phone.getPhoneType();
        if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            GsmCellLocation gsm_cell = (GsmCellLocation) phone.getCellLocation();
            if ((gsm_cell != null) && (phone.getNetworkOperator() != null)
                    && (phone.getNetworkOperator().length() > 3)) {
                int type;
                int mcc = Integer.parseInt(phone.getNetworkOperator().substring(0,3));
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
                Log.e(TAG,"Error getting cell location info.");
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
                    handleUpdateNetworkState(msg.arg1, (NetworkInfo)msg.obj);
                    break;
                case INJECT_NTP_TIME:
                    handleInjectNtpTime();
                    break;
                case DOWNLOAD_XTRA_DATA:
                    if (mSupportsXtra) {
                        handleDownloadXtraData();
                    }
                    break;
                case INJECT_NTP_TIME_FINISHED:
                    mInjectNtpTimePending = STATE_IDLE;
                    break;
                case DOWNLOAD_XTRA_DATA_FINISHED:
                    mDownloadXtraDataPending = STATE_IDLE;
                    break;
                case UPDATE_LOCATION:
                    handleUpdateLocation((Location)msg.obj);
                    break;
                case SUBSCRIPTION_OR_SIM_CHANGED:
                    subscriptionOrSimChanged(mContext);
                    break;
                case INITIALIZE_HANDLER:
                    initialize();
                    break;
            }
            if (msg.arg2 == 1) {
                // wakelock was taken for this message, release it
                mWakeLock.release();
            }
        }

        /**
         * This method is bound to {@link #GpsLocationProvider(Context, ILocationManager, Looper)}.
         * It is in charge of loading properties and registering for events that will be posted to
         * this handler.
         */
        private void initialize() {
            // load default GPS configuration
            // (this configuration might change in the future based on SIM changes)
            reloadGpsProperties(mContext, mProperties);

            // TODO: When this object "finishes" we should unregister by invoking
            // SubscriptionManager.getInstance(mContext).unregister(mOnSubscriptionsChangedListener);
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
            intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            intentFilter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
            intentFilter.addAction(SIM_STATE_CHANGED);
            mContext.registerReceiver(mBroadcastReceiver, intentFilter, null, this);

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

    private final class NetworkLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            // this callback happens on mHandler looper
            if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())) {
                handleUpdateLocation(location);
            }
        }
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override
        public void onProviderEnabled(String provider) { }
        @Override
        public void onProviderDisabled(String provider) { }
    }

    private String getSelectedApn() {
        Uri uri = Uri.parse("content://telephony/carriers/preferapn");
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                    uri,
                    new String[] { "apn" },
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
        if (apn == null) {
            return APN_INVALID;
        }

        // look for cached data to use
        if (apn.equals(mAGpsApn) && mApnIpType != APN_INVALID) {
            return mApnIpType;
        }

        String selection = String.format("current = 1 and apn = '%s' and carrier_enabled = 1", apn);
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                    Carriers.CONTENT_URI,
                    new String[] { Carriers.PROTOCOL },
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

        boolean result = mConnMgr.requestRouteToHostAddress(
                ConnectivityManager.TYPE_MOBILE_SUPL,
                mAGpsDataConnectionIpAddr);

        if (!result) {
            Log.e(TAG, "Error requesting route to host: " + mAGpsDataConnectionIpAddr);
        } else if (DEBUG) {
            Log.d(TAG, "Successfully requested route to host: " + mAGpsDataConnectionIpAddr);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        StringBuilder s = new StringBuilder();
        s.append("  mFixInterval=").append(mFixInterval).append("\n");
        s.append("  mDisableGps (battery saver mode)=").append(mDisableGps).append("\n");
        s.append("  mEngineCapabilities=0x").append(Integer.toHexString(mEngineCapabilities)).append(" (");
        if (hasCapability(GPS_CAPABILITY_SCHEDULING)) s.append("SCHED ");
        if (hasCapability(GPS_CAPABILITY_MSB)) s.append("MSB ");
        if (hasCapability(GPS_CAPABILITY_MSA)) s.append("MSA ");
        if (hasCapability(GPS_CAPABILITY_SINGLE_SHOT)) s.append("SINGLE_SHOT ");
        if (hasCapability(GPS_CAPABILITY_ON_DEMAND_TIME)) s.append("ON_DEMAND_TIME ");
        s.append(")\n");

        s.append(native_get_internal_state());
        pw.append(s);
    }

    /**
     * A simple implementation of exponential backoff.
     */
    private static final class BackOff {
        private static final int MULTIPLIER = 2;
        private final long mInitIntervalMillis;
        private final long mMaxIntervalMillis;
        private long mCurrentIntervalMillis;

        public BackOff(long initIntervalMillis, long maxIntervalMillis) {
            mInitIntervalMillis = initIntervalMillis;
            mMaxIntervalMillis = maxIntervalMillis;

            mCurrentIntervalMillis = mInitIntervalMillis / MULTIPLIER;
        }

        public long nextBackoffMillis() {
            if (mCurrentIntervalMillis > mMaxIntervalMillis) {
                return mMaxIntervalMillis;
            }

            mCurrentIntervalMillis *= MULTIPLIER;
            return mCurrentIntervalMillis;
        }

        public void reset() {
            mCurrentIntervalMillis = mInitIntervalMillis / MULTIPLIER;
        }
    }

    // for GPS SV statistics
    private static final int MAX_SVS = 32;
    private static final int EPHEMERIS_MASK = 0;
    private static final int ALMANAC_MASK = 1;
    private static final int USED_FOR_FIX_MASK = 2;

    // preallocated arrays, to avoid memory allocation in reportStatus()
    private int mSvs[] = new int[MAX_SVS];
    private float mSnrs[] = new float[MAX_SVS];
    private float mSvElevations[] = new float[MAX_SVS];
    private float mSvAzimuths[] = new float[MAX_SVS];
    private int mSvMasks[] = new int[3];
    private int mSvCount;
    // preallocated to avoid memory allocation in reportNmea()
    private byte[] mNmeaBuffer = new byte[120];

    static { class_init_native(); }
    private static native void class_init_native();
    private static native boolean native_is_supported();
    private static native boolean native_is_agps_ril_supported();
    private static native boolean native_is_gnss_configuration_supported();

    private native boolean native_init();
    private native void native_cleanup();
    private native boolean native_set_position_mode(int mode, int recurrence, int min_interval,
            int preferred_accuracy, int preferred_time);
    private native boolean native_start();
    private native boolean native_stop();
    private native void native_delete_aiding_data(int flags);
    // returns number of SVs
    // mask[0] is ephemeris mask and mask[1] is almanac mask
    private native int native_read_sv_status(int[] svs, float[] snrs,
            float[] elevations, float[] azimuths, int[] masks);
    private native int native_read_nmea(byte[] buffer, int bufferSize);
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
    private native void native_agps_ni_message(byte [] msg, int length);
    private native void native_set_agps_server(int type, String hostname, int port);

    // Network-initiated (NI) Support
    private native void native_send_ni_response(int notificationId, int userResponse);

    // AGPS ril suport
    private native void native_agps_set_ref_location_cellid(int type, int mcc, int mnc,
            int lac, int cid);
    private native void native_agps_set_id(int type, String setid);

    private native void native_update_network_state(boolean connected, int type,
            boolean roaming, boolean available, String extraInfo, String defaultAPN);

    // Hardware Geofence support.
    private static native boolean native_is_geofence_supported();
    private static native boolean native_add_geofence(int geofenceId, double latitude,
            double longitude, double radius, int lastTransition,int monitorTransitions,
            int notificationResponsivenes, int unknownTimer);
    private static native boolean native_remove_geofence(int geofenceId);
    private static native boolean native_resume_geofence(int geofenceId, int transitions);
    private static native boolean native_pause_geofence(int geofenceId);

    // Gps Hal measurements support.
    private static native boolean native_is_measurement_supported();
    private native boolean native_start_measurement_collection();
    private native boolean native_stop_measurement_collection();

    // Gps Navigation message support.
    private static native boolean native_is_navigation_message_supported();
    private native boolean native_start_navigation_message_collection();
    private native boolean native_stop_navigation_message_collection();

    // GNSS Configuration
    private static native void native_configuration_update(String configData);
}

