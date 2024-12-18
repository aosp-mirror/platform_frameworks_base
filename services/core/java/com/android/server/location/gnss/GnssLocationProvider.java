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

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.location.provider.ProviderProperties.ACCURACY_FINE;
import static android.location.provider.ProviderProperties.POWER_USAGE_HIGH;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.location.gnss.hal.GnssNative.AGPS_REF_LOCATION_TYPE_GSM_CELLID;
import static com.android.server.location.gnss.hal.GnssNative.AGPS_REF_LOCATION_TYPE_LTE_CELLID;
import static com.android.server.location.gnss.hal.GnssNative.AGPS_REF_LOCATION_TYPE_NR_CELLID;
import static com.android.server.location.gnss.hal.GnssNative.AGPS_REF_LOCATION_TYPE_UMTS_CELLID;
import static com.android.server.location.gnss.hal.GnssNative.AGPS_SETID_TYPE_IMSI;
import static com.android.server.location.gnss.hal.GnssNative.AGPS_SETID_TYPE_MSISDN;
import static com.android.server.location.gnss.hal.GnssNative.AGPS_SETID_TYPE_NONE;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_ALL;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_ALMANAC;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_CELLDB_INFO;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_EPHEMERIS;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_HEALTH;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_IONO;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_POSITION;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_RTI;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_SADATA;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_SVDIR;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_SVSTEER;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_TIME;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_AIDING_TYPE_UTC;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_POSITION_MODE_MS_ASSISTED;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_POSITION_MODE_MS_BASED;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_POSITION_MODE_STANDALONE;
import static com.android.server.location.gnss.hal.GnssNative.GNSS_POSITION_RECURRENCE_PERIODIC;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.GnssCapabilities;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.location.LocationResult;
import android.location.LocationResult.BadLocationException;
import android.location.flags.Flags;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.provider.Settings;
import android.provider.Telephony.Sms.Intents;
import android.telephony.CarrierConfigManager;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.location.GpsNetInitiatedHandler;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.HexDump;
import com.android.server.FgThread;
import com.android.server.location.gnss.GnssSatelliteBlocklistHelper.GnssSatelliteBlocklistCallback;
import com.android.server.location.gnss.NetworkTimeHelper.InjectTimeCallback;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.provider.AbstractLocationProvider;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A GNSS implementation of LocationProvider used by LocationManager.
 *
 * {@hide}
 */
public class GnssLocationProvider extends AbstractLocationProvider implements
        InjectTimeCallback, GnssSatelliteBlocklistCallback, GnssNative.BaseCallbacks,
        GnssNative.LocationCallbacks, GnssNative.SvStatusCallbacks, GnssNative.AGpsCallbacks,
        GnssNative.PsdsCallbacks, GnssNative.NotificationCallbacks,
        GnssNative.LocationRequestCallbacks, GnssNative.TimeCallbacks {

    private static final String TAG = "GnssLocationProvider";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private static final ProviderProperties PROPERTIES = new ProviderProperties.Builder()
                .setHasSatelliteRequirement(true)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(POWER_USAGE_HIGH)
                .setAccuracy(ACCURACY_FINE)
                .build();

    // The AGPS SUPL mode
    private static final int AGPS_SUPL_MODE_MSA = 0x02;
    private static final int AGPS_SUPL_MODE_MSB = 0x01;

    // TCP/IP constants.
    // Valid TCP/UDP port range is (0, 65535].
    private static final int TCP_MIN_PORT = 0;
    private static final int TCP_MAX_PORT = 0xffff;

    // 1 second, or 1 Hz frequency.
    private static final long LOCATION_UPDATE_MIN_TIME_INTERVAL_MILLIS = 1000;
    // Default update duration in milliseconds for REQUEST_LOCATION.
    private static final long LOCATION_UPDATE_DURATION_MILLIS = 10 * 1000;
    // Update duration extension multiplier for emergency REQUEST_LOCATION.
    private static final int EMERGENCY_LOCATION_UPDATE_DURATION_MULTIPLIER = 3;
    // maximum length gnss batching may go for (1 day)
    private static final int MIN_BATCH_INTERVAL_MS = (int) DateUtils.SECOND_IN_MILLIS;
    private static final long MAX_BATCH_LENGTH_MS = DateUtils.DAY_IN_MILLIS;
    private static final long MAX_BATCH_TIMESTAMP_DELTA_MS = 500;

    // Threadsafe class to hold stats reported in the Extras Bundle
    private static class LocationExtras {
        private int mSvCount;
        private int mMeanCn0;
        private int mMaxCn0;
        private final Bundle mBundle;

        LocationExtras() {
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

    // stop trying if we do not receive a fix within 60 seconds
    private static final int NO_FIX_TIMEOUT = 60 * 1000;

    // if the fix interval is below this we leave GPS on,
    // if above then we cycle the GPS driver.
    // Typical hot TTTF is ~5 seconds, so 10 seconds seems valid.
    private static final int GPS_POLLING_THRESHOLD_INTERVAL = 10 * 1000;

    // how long to wait if we have a network error in NTP or PSDS downloading
    // the initial value of the exponential backoff
    // current setting - 5 minutes
    private static final long RETRY_INTERVAL = 5 * 60 * 1000;
    // how long to wait if we have a network error in NTP or PSDS downloading
    // the max value of the exponential backoff
    // current setting - 4 hours
    private static final long MAX_RETRY_INTERVAL = 4 * 60 * 60 * 1000;

    // Timeout when holding wakelocks for downloading PSDS data.
    private static final long DOWNLOAD_PSDS_DATA_TIMEOUT_MS = 60 * 1000;
    private static final long WAKELOCK_TIMEOUT_MILLIS = 30 * 1000;

    // threshold for delay in GNSS engine turning off before warning & error
    private static final long LOCATION_OFF_DELAY_THRESHOLD_WARN_MILLIS = 2 * 1000;
    private static final long LOCATION_OFF_DELAY_THRESHOLD_ERROR_MILLIS = 15 * 1000;

    private final Object mLock = new Object();

    private final Context mContext;
    private final Handler mHandler;

    private final GnssNative mGnssNative;

    @GuardedBy("mLock")
    private final ExponentialBackOff mPsdsBackOff = new ExponentialBackOff(RETRY_INTERVAL,
            MAX_RETRY_INTERVAL);

    // True if we are enabled
    @GuardedBy("mLock")
    private boolean mGpsEnabled;

    @GuardedBy("mLock")
    private boolean mBatchingEnabled;

    @GuardedBy("mLock")
    private boolean mAutomotiveSuspend;

    private boolean mShutdown;
    private boolean mStarted;
    private boolean mBatchingStarted;
    private AlarmManager.OnAlarmListener mBatchingAlarm;
    private long mStartedChangedElapsedRealtime;
    private int mFixInterval = 1000;

    // True if handleInitialize() has finished;
    @GuardedBy("mLock")
    private boolean mInitialized;

    private ProviderRequest mProviderRequest;

    private int mPositionMode;
    private GnssPositionMode mLastPositionMode;

    // for calculating time to first fix
    private long mFixRequestTime = 0;
    // time to first fix for most recent session
    private int mTimeToFirstFix = 0;
    // time we received our last fix
    private long mLastFixTime;

    private final WorkSource mClientSource = new WorkSource();

    // true if PSDS is supported
    private boolean mSupportsPsds;
    private final Object mPsdsPeriodicDownloadToken = new Object();
    @GuardedBy("mLock")
    private final PowerManager.WakeLock mDownloadPsdsWakeLock;
    @GuardedBy("mLock")
    private final Set<Integer> mPendingDownloadPsdsTypes = new HashSet<>();
    @GuardedBy("mLock")
    private final Set<Integer> mDownloadInProgressPsdsTypes = new HashSet<>();

    /**
     * Properties loaded from PROPERTIES_FILE.
     * It must be accessed only inside {@link #mHandler}.
     */
    private final GnssConfiguration mGnssConfiguration;

    private String mSuplServerHost;
    private int mSuplServerPort = TCP_MIN_PORT;
    private String mC2KServerHost;
    private int mC2KServerPort;
    private boolean mSuplEsEnabled = false;
    private boolean mNiSuplMessageListenerRegistered = false;

    private final LocationExtras mLocationExtras = new LocationExtras();
    private final NetworkTimeHelper mNetworkTimeHelper;
    private final GnssSatelliteBlocklistHelper mGnssSatelliteBlocklistHelper;

    // Available only on GNSS HAL 2.0 implementations and later.
    private GnssVisibilityControl mGnssVisibilityControl;

    private final GnssNetworkConnectivityHandler mNetworkConnectivityHandler;
    private final GpsNetInitiatedHandler mNIHandler;

    // Wakelocks
    private final PowerManager.WakeLock mWakeLock;

    private final AlarmManager mAlarmManager;
    private final AlarmManager.OnAlarmListener mWakeupListener = this::startNavigating;
    private final AlarmManager.OnAlarmListener mTimeoutListener = this::hibernate;

    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStats;

    @GuardedBy("mLock")
    private final ArrayList<Runnable> mFlushListeners = new ArrayList<>(0);

    // GNSS Metrics
    private final GnssMetrics mGnssMetrics;

    /**
     * Implements {@link GnssSatelliteBlocklistCallback#onUpdateSatelliteBlocklist}.
     */
    @Override
    public void onUpdateSatelliteBlocklist(int[] constellations, int[] svids) {
        mHandler.post(() -> mGnssConfiguration.setSatelliteBlocklist(constellations, svids));
        mGnssMetrics.resetConstellationTypes();
    }

    private void subscriptionOrCarrierConfigChanged() {
        if (DEBUG) Log.d(TAG, "received SIM related action: ");
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        CarrierConfigManager configManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        int ddSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (SubscriptionManager.isValidSubscriptionId(ddSubId)) {
            phone = phone.createForSubscriptionId(ddSubId);
        }
        String mccMnc = phone.getSimOperator();
        boolean isKeepLppProfile = false;
        if (!TextUtils.isEmpty(mccMnc)) {
            if (DEBUG) Log.d(TAG, "SIM MCC/MNC is available: " + mccMnc);
            if (configManager != null) {
                PersistableBundle b = SubscriptionManager.isValidSubscriptionId(ddSubId)
                        ? configManager.getConfigForSubId(ddSubId) : null;
                if (b != null) {
                    isKeepLppProfile =
                            b.getBoolean(CarrierConfigManager.Gps.KEY_PERSIST_LPP_MODE_BOOL);
                }
            }
            if (isKeepLppProfile) {
                // load current properties for the carrier of ddSubId
                mGnssConfiguration.loadPropertiesFromCarrierConfig(/* inEmergency= */ false,
                        /* activeSubId= */ -1);
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
        } else {
            if (DEBUG) Log.d(TAG, "SIM MCC/MNC is still not available");
            // Reload gnss config for no SIM case
            mGnssConfiguration.reloadGpsProperties();
        }
        if (Flags.enableNiSuplMessageInjectionByCarrierConfigBugfix()) {
            updateNiSuplMessageListenerRegistration(
                    mGnssConfiguration.isNiSuplMessageInjectionEnabled());
        }
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
            mGnssVisibilityControl.onConfigurationUpdated(mGnssConfiguration);
        }
    }

    public GnssLocationProvider(Context context, GnssNative gnssNative,
            GnssMetrics gnssMetrics) {
        super(FgThread.getExecutor(), CallerIdentity.fromContext(context), PROPERTIES,
                Collections.emptySet());

        mContext = context;
        mGnssNative = gnssNative;
        mGnssMetrics = gnssMetrics;

        // Create a wake lock
        PowerManager powerManager = Objects.requireNonNull(
                mContext.getSystemService(PowerManager.class));
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*location*:" + TAG);
        mWakeLock.setReferenceCounted(true);

        // Create a separate wake lock for psds downloader as it may be released due to timeout.
        mDownloadPsdsWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "*location*:PsdsDownload");
        mDownloadPsdsWakeLock.setReferenceCounted(true);

        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        // App ops service to keep track of who is accessing the GPS
        mAppOps = mContext.getSystemService(AppOpsManager.class);

        // Battery statistics service to be notified when GPS turns on or off
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        // Construct internal handler
        mHandler = FgThread.getHandler();

        // Load GPS configuration and register listeners in the background:
        // some operations, such as opening files and registering broadcast receivers, can take a
        // relative long time, so the ctor() is kept to create objects needed by this instance,
        // while IO initialization and registration is delegated to our internal handler
        // this approach is just fine because events are posted to our handler anyway
        mGnssConfiguration = mGnssNative.getConfiguration();
        // Create a GPS net-initiated handler (also needed by handleInitialize)
        GpsNetInitiatedHandler.EmergencyCallCallback emergencyCallCallback =
                new GpsNetInitiatedHandler.EmergencyCallCallback() {

                    @Override
                    public void onEmergencyCallStart(int subId) {
                        if (!mGnssConfiguration.isActiveSimEmergencySuplEnabled()) {
                            return;
                        }
                        mHandler.post(() -> mGnssConfiguration.reloadGpsProperties(
                                mNIHandler.getInEmergency(), subId));
                    }

                    @Override
                    public void onEmergencyCallEnd() {
                        if (!mGnssConfiguration.isActiveSimEmergencySuplEnabled()) {
                            return;
                        }
                        mHandler.postDelayed(() -> mGnssConfiguration.reloadGpsProperties(
                                        /* inEmergency= */ false,
                                        SubscriptionManager.getDefaultDataSubscriptionId()),
                                TimeUnit.SECONDS.toMillis(mGnssConfiguration.getEsExtensionSec()));
                    }
                };
        mNIHandler = new GpsNetInitiatedHandler(context,
                emergencyCallCallback,
                mSuplEsEnabled);
        // Trigger PSDS data download when the network comes up after booting.
        mPendingDownloadPsdsTypes.add(GnssPsdsDownloader.LONG_TERM_PSDS_SERVER_INDEX);
        mNetworkConnectivityHandler = new GnssNetworkConnectivityHandler(context,
                GnssLocationProvider.this::onNetworkAvailable,
                mHandler.getLooper(), mNIHandler);

        mNetworkTimeHelper = NetworkTimeHelper.create(mContext, mHandler.getLooper(), this);
        mGnssSatelliteBlocklistHelper =
                new GnssSatelliteBlocklistHelper(mContext,
                        mHandler.getLooper(), this);

        setAllowed(true);

        mGnssNative.addBaseCallbacks(this);
        mGnssNative.addLocationCallbacks(this);
        mGnssNative.addSvStatusCallbacks(this);
        mGnssNative.setAGpsCallbacks(this);
        mGnssNative.setPsdsCallbacks(this);
        mGnssNative.setNotificationCallbacks(this);
        mGnssNative.setLocationRequestCallbacks(this);
        mGnssNative.setTimeCallbacks(this);
    }

    /** Called when system is ready. */
    public synchronized void onSystemReady() {
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

        mHandler.post(this::handleInitialize);
        mHandler.post(mGnssSatelliteBlocklistHelper::updateSatelliteBlocklist);
    }

    private void handleInitialize() {
        if (mGnssNative.isGnssVisibilityControlSupported()) {
            mGnssVisibilityControl = new GnssVisibilityControl(mContext, mHandler.getLooper(),
                    mNIHandler);
        }

        // load default GPS configuration
        // (this configuration might change in the future based on SIM changes)
        reloadGpsProperties();

        // listen for events
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        intentFilter.addAction(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        mContext.registerReceiver(mIntentReceiver, intentFilter, null, mHandler);

        if (!Flags.enableNiSuplMessageInjectionByCarrierConfigBugfix()) {
            updateNiSuplMessageListenerRegistration(
                    mGnssConfiguration.isNiSuplMessageInjectionEnabled());
        }

        mNetworkConnectivityHandler.registerNetworkCallbacks();

        // permanently passively listen to all network locations
        LocationManager locationManager = Objects.requireNonNull(
                mContext.getSystemService(LocationManager.class));
        if (locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    new LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL)
                            .setMinUpdateIntervalMillis(0)
                            .setHiddenFromAppOps(true)
                            .build(),
                    DIRECT_EXECUTOR,
                    this::injectLocation);
        }

        updateEnabled();
        synchronized (mLock) {
            mInitialized = true;
        }
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "receive broadcast intent, action: " + action);
            if (action == null) {
                return;
            }

            switch (action) {
                case CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED:
                case TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED:
                    subscriptionOrCarrierConfigChanged();
                    break;
            }
        }
    };

    private BroadcastReceiver mNiSuplIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "receive broadcast intent, action: " + action);
            if (action == null) {
                return;
            }

            switch (action) {
                case Intents.WAP_PUSH_RECEIVED_ACTION:
                case Intents.DATA_SMS_RECEIVED_ACTION:
                    injectSuplInit(intent);
                    break;
            }
        }
    };

    private void injectSuplInit(Intent intent) {
        if (!isNfwLocationAccessAllowed()) {
            Log.w(TAG, "Reject SUPL INIT as no NFW location access");
            return;
        }

        int slotIndex = intent.getIntExtra(SubscriptionManager.EXTRA_SLOT_INDEX,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        if (slotIndex == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            Log.e(TAG, "Invalid slot index");
            return;
        }

        byte[] suplInit = null;
        String action = intent.getAction();
        if (action.equals(Intents.DATA_SMS_RECEIVED_ACTION)) {
            SmsMessage[] messages = Intents.getMessagesFromIntent(intent);
            if (messages == null) {
                Log.e(TAG, "Message does not exist in the intent");
                return;
            }
            for (SmsMessage message : messages) {
                suplInit = message.getUserData();
                injectSuplInit(suplInit, slotIndex);
            }
        } else if (action.equals(Intents.WAP_PUSH_RECEIVED_ACTION)) {
            suplInit = intent.getByteArrayExtra("data");
            injectSuplInit(suplInit, slotIndex);
        }
    }

    private void injectSuplInit(byte[] suplInit, int slotIndex) {
        if (suplInit != null) {
            if (DEBUG) {
                Log.d(TAG, "suplInit = "
                        + HexDump.toHexString(suplInit) + " slotIndex = " + slotIndex);
            }
            mGnssNative.injectNiSuplMessageData(suplInit, suplInit.length , slotIndex);
        }
    }

    private boolean isNfwLocationAccessAllowed() {
        if (mGnssNative.isInEmergencySession()) {
            return true;
        }
        if (mGnssVisibilityControl != null
                && mGnssVisibilityControl.hasLocationPermissionEnabledProxyApps()) {
            return true;
        }
        return false;
    }

    /**
     * Implements {@link InjectTimeCallback#injectTime}
     */
    @Override
    public void injectTime(long unixEpochTimeMillis, long elapsedRealtimeMillis,
            int uncertaintyMillis) {
        mGnssNative.injectTime(unixEpochTimeMillis, elapsedRealtimeMillis, uncertaintyMillis);
    }

    /**
     * Implements {@link GnssNetworkConnectivityHandler.GnssNetworkListener#onNetworkAvailable()}
     */
    private void onNetworkAvailable() {
        mNetworkTimeHelper.onNetworkAvailable();
        // Download only if supported, (prevents an unnecessary on-boot download)
        if (mSupportsPsds) {
            synchronized (mLock) {
                for (int psdsType : mPendingDownloadPsdsTypes) {
                    postWithWakeLockHeld(() -> handleDownloadPsdsData(psdsType));
                }
                mPendingDownloadPsdsTypes.clear();
            }
        }
    }

    private void handleRequestLocation(boolean independentFromGnss, boolean isUserEmergency) {
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
        LocationListener locationListener;
        LocationRequest.Builder locationRequest = new LocationRequest.Builder(
                LOCATION_UPDATE_MIN_TIME_INTERVAL_MILLIS).setMaxUpdates(1);

        if (independentFromGnss) {
            // For fast GNSS TTFF - we use an empty listener because we will rely on the passive
            // network listener to actually inject the location. this prevents double injection
            provider = LocationManager.NETWORK_PROVIDER;
            locationListener = location -> { };
            locationRequest.setQuality(LocationRequest.QUALITY_LOW_POWER);
        } else {
            // For Device-Based Hybrid (E911)
            provider = LocationManager.FUSED_PROVIDER;
            locationListener = this::injectBestLocation;
            locationRequest.setQuality(LocationRequest.QUALITY_HIGH_ACCURACY);
        }

        // Ignore location settings if in emergency mode. This is only allowed for
        // isUserEmergency request (introduced in HAL v2.0), or HAL v1.1.
        if (mNIHandler.getInEmergency()) {
            GnssConfiguration.HalInterfaceVersion halVersion =
                    mGnssConfiguration.getHalInterfaceVersion();
            if (isUserEmergency || halVersion.mMajor < 2) {
                locationRequest.setLocationSettingsIgnored(true);
                durationMillis *= EMERGENCY_LOCATION_UPDATE_DURATION_MULTIPLIER;
            }
        }

        locationRequest.setDurationMillis(durationMillis);

        Log.i(TAG,
                String.format(
                        "GNSS HAL Requesting location updates from %s provider for %d millis.",
                        provider, durationMillis));

        if (locationManager.getProvider(provider) != null) {
            locationManager.requestLocationUpdates(provider, locationRequest.build(),
                    DIRECT_EXECUTOR, locationListener);
        }
    }

    private void injectBestLocation(Location location) {
        if (DEBUG) {
            Log.d(TAG, "injectBestLocation: " + location);
        }

        if (location.isMock()) {
            return;
        }

        mGnssNative.injectBestLocation(location);
    }

    /** Returns true if the location request is too frequent. */
    private boolean isRequestLocationRateLimited() {
        // TODO: implement exponential backoff.
        return false;
    }

    private void handleDownloadPsdsData(int psdsType) {
        if (!mSupportsPsds) {
            // native code reports psds not supported, don't try
            Log.d(TAG, "handleDownloadPsdsData() called when PSDS not supported");
            return;
        }
        if (!mNetworkConnectivityHandler.isDataNetworkConnected()) {
            // try again when network is up
            synchronized (mLock) {
                mPendingDownloadPsdsTypes.add(psdsType);
            }
            return;
        }
        synchronized (mLock) {
            if (mDownloadInProgressPsdsTypes.contains(psdsType)) {
                if (DEBUG) {
                    Log.d(TAG,
                            "PSDS type " + psdsType + " download in progress. Ignore the request.");
                }
                return;
            }
            // hold wake lock while task runs
            mDownloadPsdsWakeLock.acquire(DOWNLOAD_PSDS_DATA_TIMEOUT_MS);
            mDownloadInProgressPsdsTypes.add(psdsType);
        }
        Log.i(TAG, "WakeLock acquired by handleDownloadPsdsData()");
        Executors.newSingleThreadExecutor().execute(() -> {
            GnssPsdsDownloader psdsDownloader = new GnssPsdsDownloader(
                    mGnssConfiguration.getProperties());
            byte[] data = psdsDownloader.downloadPsdsData(psdsType);
            if (data != null) {
                mHandler.post(() -> {
                    FrameworkStatsLog.write(FrameworkStatsLog.GNSS_PSDS_DOWNLOAD_REPORTED,
                            psdsType);
                    if (DEBUG) Log.d(TAG, "calling native_inject_psds_data");
                    mGnssNative.injectPsdsData(data, data.length, psdsType);
                    synchronized (mLock) {
                        mPsdsBackOff.reset();
                    }
                });
                PackageManager pm = mContext.getPackageManager();
                if (pm != null && pm.hasSystemFeature(FEATURE_WATCH)
                        && psdsType == GnssPsdsDownloader.LONG_TERM_PSDS_SERVER_INDEX
                        && mGnssConfiguration.isPsdsPeriodicDownloadEnabled()) {
                    if (DEBUG) Log.d(TAG, "scheduling next long term Psds download");
                    mHandler.removeCallbacksAndMessages(mPsdsPeriodicDownloadToken);
                    mHandler.postDelayed(() -> handleDownloadPsdsData(psdsType),
                            mPsdsPeriodicDownloadToken,
                            GnssPsdsDownloader.PSDS_INTERVAL);
                }
            } else {
                // Try download PSDS data again later according to backoff time.
                // Since this is delayed and not urgent, we do not hold a wake lock here.
                // The arg2 below should not be 1 otherwise the wakelock will be under-locked.
                long backoffMillis;
                synchronized (mLock) {
                    backoffMillis = mPsdsBackOff.nextBackoffMillis();
                }
                mHandler.postDelayed(() -> handleDownloadPsdsData(psdsType), backoffMillis);
            }

            // Release wake lock held by task, synchronize on mLock in case multiple
            // download tasks overrun.
            synchronized (mLock) {
                if (mDownloadPsdsWakeLock.isHeld()) {
                    // This wakelock may have time-out, if a timeout was specified.
                    // Catch (and ignore) any timeout exceptions.
                    mDownloadPsdsWakeLock.release();
                    if (DEBUG) Log.d(TAG, "WakeLock released by handleDownloadPsdsData()");
                } else {
                    Log.e(TAG, "WakeLock expired before release in "
                            + "handleDownloadPsdsData()");
                }
                mDownloadInProgressPsdsTypes.remove(psdsType);
            }
        });
    }

    private void injectLocation(Location location) {
        if (!location.isMock()) {
            mGnssNative.injectLocation(location);
        }
    }

    private void setSuplHostPort() {
        mSuplServerHost = mGnssConfiguration.getSuplHost();
        mSuplServerPort = mGnssConfiguration.getSuplPort(TCP_MIN_PORT);
        if (mSuplServerHost != null
                && mSuplServerPort > TCP_MIN_PORT
                && mSuplServerPort <= TCP_MAX_PORT) {
            mGnssNative.setAgpsServer(GnssNetworkConnectivityHandler.AGPS_TYPE_SUPL,
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
                return GNSS_POSITION_MODE_STANDALONE;
            }

            // MS-Based is the preferred mode for Assisted-GPS position computation, so we favor
            // such mode when it is available
            if (mGnssNative.getCapabilities().hasMsb() && (suplMode & AGPS_SUPL_MODE_MSB) != 0) {
                return GNSS_POSITION_MODE_MS_BASED;
            }
        }
        return GNSS_POSITION_MODE_STANDALONE;
    }

    private void setGpsEnabled(boolean enabled) {
        synchronized (mLock) {
            mGpsEnabled = enabled;
        }
    }

    /**
     * Set whether the GnssLocationProvider is suspended. This method was added to help support
     * power management use cases on automotive devices.
     */
    public void setAutomotiveGnssSuspended(boolean suspended) {
        synchronized (mLock) {
            mAutomotiveSuspend = suspended;
        }
        mHandler.post(this::updateEnabled);
    }

    /**
     * Return whether the GnssLocationProvider is suspended or not. This method was added to help
     * support power management use cases on automotive devices.
     */
    public boolean isAutomotiveGnssSuspended() {
        synchronized (mLock) {
            return mAutomotiveSuspend && !mGpsEnabled;
        }
    }

    private void handleEnable() {
        if (DEBUG) Log.d(TAG, "handleEnable");

        boolean inited = mGnssNative.init();

        if (inited) {
            setGpsEnabled(true);
            mSupportsPsds = mGnssNative.isPsdsSupported();

            // TODO: remove the following native calls if we can make sure they are redundant.
            if (mSuplServerHost != null) {
                mGnssNative.setAgpsServer(GnssNetworkConnectivityHandler.AGPS_TYPE_SUPL,
                        mSuplServerHost, mSuplServerPort);
            }
            if (mC2KServerHost != null) {
                mGnssNative.setAgpsServer(GnssNetworkConnectivityHandler.AGPS_TYPE_C2K,
                        mC2KServerHost, mC2KServerPort);
            }

            mBatchingEnabled = mGnssNative.initBatching() && mGnssNative.getBatchSize() > 1;
            if (mGnssVisibilityControl != null) {
                mGnssVisibilityControl.onGpsEnabledChanged(/* isEnabled= */ true);
            }
        } else {
            setGpsEnabled(false);
            Log.w(TAG, "Failed to enable location provider");
        }
    }

    private void handleDisable() {
        if (DEBUG) Log.d(TAG, "handleDisable");

        setGpsEnabled(false);
        updateClientUids(new WorkSource());
        stopNavigating();
        stopBatching();

        if (mGnssVisibilityControl != null) {
            mGnssVisibilityControl.onGpsEnabledChanged(/* isEnabled= */ false);
        }
        // do this before releasing wakelock
        mGnssNative.cleanupBatching();
        mGnssNative.cleanup();
    }

    private void updateEnabled() {
        boolean enabled = false;

        // Generally follow location setting for visible users
        LocationManager locationManager = mContext.getSystemService(LocationManager.class);
        Set<UserHandle> visibleUserHandles =
                mContext.getSystemService(UserManager.class).getVisibleUsers();
        for (UserHandle visibleUserHandle : visibleUserHandles) {
            enabled |= locationManager.isLocationEnabledForUser(visibleUserHandle);
        }

        // .. but enable anyway, if there's an active bypass request (e.g. ELS or ADAS)
        enabled |= (mProviderRequest != null
                && mProviderRequest.isActive()
                && mProviderRequest.isBypass());

        // .. disable if automotive device needs to go into suspend
        synchronized (mLock) {
            enabled &= !mAutomotiveSuspend;
        }

        // ... and, finally, disable anyway, if device is being shut down
        enabled &= !mShutdown;

        if (enabled == isGpsEnabled()) {
            return;
        }

        if (enabled) {
            handleEnable();
        } else {
            handleDisable();
        }
    }

    private boolean isGpsEnabled() {
        synchronized (mLock) {
            return mGpsEnabled;
        }
    }

    /**
     * Returns the hardware batch size available in this hardware implementation. If the available
     * size is variable, for example, based on other operations consuming memory, this is the
     * minimum size guaranteed to be available for batching operations.
     */
    public int getBatchSize() {
        return mGnssNative.getBatchSize();
    }

    @Override
    protected void onFlush(Runnable listener) {
        boolean added = false;
        synchronized (mLock) {
            if (mBatchingEnabled) {
                added = mFlushListeners.add(listener);
            }
        }
        if (!added) {
            listener.run();
        } else {
            mGnssNative.flushBatch();
        }
    }

    @Override
    public void onSetRequest(ProviderRequest request) {
        mProviderRequest = request;
        updateEnabled();
        updateRequirements();
    }

    // Called when the requirements for GPS may have changed
    private void updateRequirements() {
        if (mProviderRequest == null || mProviderRequest.getWorkSource() == null) {
            return;
        }

        if (DEBUG) Log.d(TAG, "setRequest " + mProviderRequest);
        if (mProviderRequest.isActive() && isGpsEnabled()) {
            // update client uids
            updateClientUids(mProviderRequest.getWorkSource());

            if (mProviderRequest.getIntervalMillis() <= Integer.MAX_VALUE) {
                mFixInterval = (int) mProviderRequest.getIntervalMillis();
            } else {
                Log.w(TAG, "interval overflow: " + mProviderRequest.getIntervalMillis());
                mFixInterval = Integer.MAX_VALUE;
            }

            int batchIntervalMs = max(mFixInterval, MIN_BATCH_INTERVAL_MS);
            long batchLengthMs = Math.min(mProviderRequest.getMaxUpdateDelayMillis(),
                    MAX_BATCH_LENGTH_MS);

            // apply request to GPS engine
            if (mBatchingEnabled && batchLengthMs / 2 >= batchIntervalMs) {
                stopNavigating();
                mFixInterval = batchIntervalMs;
                startBatching(batchLengthMs);
            } else {
                stopBatching();

                if (mStarted && mGnssNative.getCapabilities().hasScheduling()) {
                    // change period and/or lowPowerMode
                    if (!setPositionMode(mPositionMode, GNSS_POSITION_RECURRENCE_PERIODIC,
                            mFixInterval, mProviderRequest.isLowPower())) {
                        Log.e(TAG, "set_position_mode failed in updateRequirements");
                    }
                } else if (!mStarted) {
                    // start GPS
                    startNavigating();
                } else {
                    // GNSS Engine is already ON, but no GPS_CAPABILITY_SCHEDULING
                    mAlarmManager.cancel(mTimeoutListener);
                    if (mFixInterval >= NO_FIX_TIMEOUT) {
                        // set timer to give up if we do not receive a fix within NO_FIX_TIMEOUT
                        // and our fix interval is not short
                        mAlarmManager.set(ELAPSED_REALTIME_WAKEUP,
                                SystemClock.elapsedRealtime() + NO_FIX_TIMEOUT, TAG,
                                mTimeoutListener, mHandler);
                    }
                }
            }
        } else {
            updateClientUids(new WorkSource());
            stopNavigating();
            stopBatching();
        }
    }

    private boolean setPositionMode(int mode, int recurrence, int minInterval,
            boolean lowPowerMode) {
        GnssPositionMode positionMode = new GnssPositionMode(mode, recurrence, minInterval,
                0, 0, lowPowerMode);
        if (mLastPositionMode != null && mLastPositionMode.equals(positionMode)) {
            return true;
        }

        boolean result = mGnssNative.setPositionMode(mode, recurrence, minInterval, 0, 0,
                lowPowerMode);
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

        // TODO: this doesn't seem correct, work chain attribution tag != package?
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
        // TODO: why is GnssLocationProvider the only component using these deprecated APIs?
        WorkSource[] changes = mClientSource.setReturningDiffs(source);
        if (changes != null) {
            WorkSource newWork = changes[0];
            WorkSource goneWork = changes[1];

            // Update sources that were not previously tracked.
            if (newWork != null) {
                for (int i = 0; i < newWork.size(); i++) {
                    mAppOps.startOpNoThrow(AppOpsManager.OP_GPS,
                            newWork.getUid(i), newWork.getPackageName(i));
                }
            }

            // Update sources that are no longer tracked.
            if (goneWork != null) {
                for (int i = 0; i < goneWork.size(); i++) {
                    mAppOps.finishOp(AppOpsManager.OP_GPS, goneWork.getUid(i),
                            goneWork.getPackageName(i));
                }
            }
        }
    }

    @Override
    public void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        if ("delete_aiding_data".equals(command)) {
            deleteAidingData(extras);
        } else if ("force_time_injection".equals(command)) {
            demandUtcTimeInjection();
        } else if ("force_psds_injection".equals(command)) {
            if (mSupportsPsds) {
                postWithWakeLockHeld(() -> handleDownloadPsdsData(
                        GnssPsdsDownloader.LONG_TERM_PSDS_SERVER_INDEX));
            }
        } else if ("request_power_stats".equals(command)) {
            mGnssNative.requestPowerStats(Runnable::run, powerStats -> {});
        } else {
            Log.w(TAG, "sendExtraCommand: unknown command " + command);
        }
    }

    private void deleteAidingData(Bundle extras) {
        int flags;

        if (extras == null) {
            flags = GNSS_AIDING_TYPE_ALL;
        } else {
            flags = 0;
            if (extras.getBoolean("ephemeris")) flags |= GNSS_AIDING_TYPE_EPHEMERIS;
            if (extras.getBoolean("almanac")) flags |= GNSS_AIDING_TYPE_ALMANAC;
            if (extras.getBoolean("position")) flags |= GNSS_AIDING_TYPE_POSITION;
            if (extras.getBoolean("time")) flags |= GNSS_AIDING_TYPE_TIME;
            if (extras.getBoolean("iono")) flags |= GNSS_AIDING_TYPE_IONO;
            if (extras.getBoolean("utc")) flags |= GNSS_AIDING_TYPE_UTC;
            if (extras.getBoolean("health")) flags |= GNSS_AIDING_TYPE_HEALTH;
            if (extras.getBoolean("svdir")) flags |= GNSS_AIDING_TYPE_SVDIR;
            if (extras.getBoolean("svsteer")) flags |= GNSS_AIDING_TYPE_SVSTEER;
            if (extras.getBoolean("sadata")) flags |= GNSS_AIDING_TYPE_SADATA;
            if (extras.getBoolean("rti")) flags |= GNSS_AIDING_TYPE_RTI;
            if (extras.getBoolean("celldb-info")) flags |= GNSS_AIDING_TYPE_CELLDB_INFO;
            if (extras.getBoolean("all")) flags |= GNSS_AIDING_TYPE_ALL;
        }

        if (flags != 0) {
            mGnssNative.deleteAidingData(flags);
        }
    }

    private void startNavigating() {
        if (!mStarted) {
            if (DEBUG) Log.d(TAG, "startNavigating");
            mTimeToFirstFix = 0;
            mLastFixTime = 0;
            setStarted(true);
            mPositionMode = GNSS_POSITION_MODE_STANDALONE;

            boolean agpsEnabled =
                    (Settings.Global.getInt(mContext.getContentResolver(),
                            Settings.Global.ASSISTED_GPS_ENABLED, 1) != 0);
            mPositionMode = getSuplMode(agpsEnabled);

            if (DEBUG) {
                String mode;

                switch (mPositionMode) {
                    case GNSS_POSITION_MODE_STANDALONE:
                        mode = "standalone";
                        break;
                    case GNSS_POSITION_MODE_MS_ASSISTED:
                        mode = "MS_ASSISTED";
                        break;
                    case GNSS_POSITION_MODE_MS_BASED:
                        mode = "MS_BASED";
                        break;
                    default:
                        mode = "unknown";
                        break;
                }
                Log.d(TAG, "setting position_mode to " + mode);
            }

            int interval = mGnssNative.getCapabilities().hasScheduling() ? mFixInterval : 1000;
            if (!setPositionMode(mPositionMode, GNSS_POSITION_RECURRENCE_PERIODIC,
                    interval, mProviderRequest.isLowPower())) {
                setStarted(false);
                Log.e(TAG, "set_position_mode failed in startNavigating()");
                return;
            }
            if (!mGnssNative.start()) {
                setStarted(false);
                Log.e(TAG, "native_start failed in startNavigating()");
                return;
            }

            // reset SV count to zero
            mLocationExtras.reset();
            mFixRequestTime = SystemClock.elapsedRealtime();
            if (!mGnssNative.getCapabilities().hasScheduling()) {
                // set timer to give up if we do not receive a fix within NO_FIX_TIMEOUT
                // and our fix interval is not short
                if (mFixInterval >= NO_FIX_TIMEOUT) {
                    mAlarmManager.set(ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + NO_FIX_TIMEOUT, TAG, mTimeoutListener,
                            mHandler);
                }
            }
        }
    }

    private void stopNavigating() {
        if (DEBUG) Log.d(TAG, "stopNavigating");
        if (mStarted) {
            setStarted(false);
            mGnssNative.stop();
            mLastFixTime = 0;
            // native_stop() may reset the position mode in hardware.
            mLastPositionMode = null;

            // reset SV count to zero
            mLocationExtras.reset();
        }
        mAlarmManager.cancel(mTimeoutListener);
        mAlarmManager.cancel(mWakeupListener);
    }

    private void startBatching(long batchLengthMs) {
        long batchSize = batchLengthMs / mFixInterval;

        if (DEBUG) {
            Log.d(TAG, "startBatching " + mFixInterval + " " + batchLengthMs);
        }
        if (mGnssNative.startBatch(MILLISECONDS.toNanos(mFixInterval), 0, true)) {
            mBatchingStarted = true;

            if (batchSize < getBatchSize()) {
                // if the batch size is smaller than the hardware batch size, use an alarm to flush
                // locations as appropriate
                mBatchingAlarm = () -> {
                    boolean flush = false;
                    synchronized (mLock) {
                        if (mBatchingAlarm != null) {
                            flush = true;
                            mAlarmManager.setExact(ELAPSED_REALTIME_WAKEUP,
                                    SystemClock.elapsedRealtime() + batchLengthMs, TAG,
                                    mBatchingAlarm, FgThread.getHandler());
                        }
                    }

                    if (flush) {
                        mGnssNative.flushBatch();
                    }
                };
                mAlarmManager.setExact(ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + batchLengthMs, TAG,
                        mBatchingAlarm, FgThread.getHandler());
            }
        } else {
            Log.e(TAG, "native_start_batch failed in startBatching()");
        }
    }

    private void stopBatching() {
        if (DEBUG) Log.d(TAG, "stopBatching");
        if (mBatchingStarted) {
            if (mBatchingAlarm != null) {
                mAlarmManager.cancel(mBatchingAlarm);
                mBatchingAlarm = null;
            }
            mGnssNative.flushBatch();
            mGnssNative.stopBatch();
            mBatchingStarted = false;
        }
    }

    private void setStarted(boolean started) {
        if (mStarted != started) {
            mStarted = started;
            mStartedChangedElapsedRealtime = SystemClock.elapsedRealtime();
        }
    }

    private void hibernate() {
        // stop GPS until our next fix interval arrives
        stopNavigating();
        long now = SystemClock.elapsedRealtime();
        mAlarmManager.set(ELAPSED_REALTIME_WAKEUP, now + mFixInterval, TAG,
                mWakeupListener, mHandler);
    }

    private void handleReportLocation(boolean hasLatLong, Location location) {
        if (VERBOSE) Log.v(TAG, "reportLocation " + location.toString());

        location.setExtras(mLocationExtras.getBundle());

        try {
            reportLocation(LocationResult.wrap(location).validate());
        } catch (BadLocationException e) {
            Log.e(TAG, "Dropping invalid location: " + e);
            return;
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
        } else {
            // Warn or error about long delayed GNSS engine shutdown as this generally wastes
            // power and sends location when not expected.
            long locationAfterStartedFalseMillis =
                    SystemClock.elapsedRealtime() - mStartedChangedElapsedRealtime;
            if (locationAfterStartedFalseMillis > LOCATION_OFF_DELAY_THRESHOLD_WARN_MILLIS) {
                String logMessage = "Unexpected GNSS Location report "
                        + TimeUtils.formatDuration(locationAfterStartedFalseMillis)
                        + " after location turned off";
                if (locationAfterStartedFalseMillis > LOCATION_OFF_DELAY_THRESHOLD_ERROR_MILLIS) {
                    Log.e(TAG, logMessage);
                } else {
                    Log.w(TAG, logMessage);
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
        }

        if (mStarted) {
            // For devices that use framework scheduling, a timer may be set to ensure we don't
            // spend too much power searching for a location, when the requested update rate is
            // slow.
            // As we just recievied a location, we'll cancel that timer.
            if (!mGnssNative.getCapabilities().hasScheduling() && mFixInterval < NO_FIX_TIMEOUT) {
                mAlarmManager.cancel(mTimeoutListener);
            }
        }

        if (!mGnssNative.getCapabilities().hasScheduling() && mStarted
                && mFixInterval > GPS_POLLING_THRESHOLD_INTERVAL) {
            if (DEBUG) Log.d(TAG, "got fix, hibernating");
            hibernate();
        }
    }

    private void handleReportSvStatus(GnssStatus gnssStatus) {
        // Log CN0 as part of GNSS metrics
        mGnssMetrics.logCn0(gnssStatus);

        if (VERBOSE) {
            Log.v(TAG, "SV count: " + gnssStatus.getSatelliteCount());
        }

        Set<Pair<Integer, Integer>> satellites = new HashSet<>();
        int usedInFixCount = 0;
        int maxCn0 = 0;
        int meanCn0 = 0;
        for (int i = 0; i < gnssStatus.getSatelliteCount(); i++) {
            if (gnssStatus.usedInFix(i)) {
                satellites.add(
                        new Pair<>(gnssStatus.getConstellationType(i), gnssStatus.getSvid(i)));
                ++usedInFixCount;
                if (gnssStatus.getCn0DbHz(i) > maxCn0) {
                    maxCn0 = (int) gnssStatus.getCn0DbHz(i);
                }
                meanCn0 += gnssStatus.getCn0DbHz(i);
                mGnssMetrics.logConstellationType(gnssStatus.getConstellationType(i));
            }
        }
        if (usedInFixCount > 0) {
            meanCn0 /= usedInFixCount;
        }
        // return number of sats used in fix instead of total reported
        mLocationExtras.set(satellites.size(), meanCn0, maxCn0);

        mGnssMetrics.logSvStatus(gnssStatus);
    }

    private void updateNiSuplMessageListenerRegistration(boolean shouldRegister) {
        if (!mNetworkConnectivityHandler.isNativeAgpsRilSupported()) {
            return;
        }
        if (mNiSuplMessageListenerRegistered == shouldRegister) {
            return;
        }

        // WAP PUSH NI SUPL message intent filter.
        // See User Plane Location Protocol Candidate Version 3.0,
        // OMA-TS-ULP-V3_0-20110920-C, Section 8.3 OMA Push.
        IntentFilter wapPushNiIntentFilter = new IntentFilter();
        wapPushNiIntentFilter.addAction(Intents.WAP_PUSH_RECEIVED_ACTION);
        try {
            wapPushNiIntentFilter
                .addDataType("application/vnd.omaloc-supl-init");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Log.w(TAG, "Malformed SUPL init mime type");
        }

        // MT SMS NI SUPL message intent filter.
        // See User Plane Location Protocol Candidate Version 3.0,
        // OMA-TS-ULP-V3_0-20110920-C, Section 8.4 MT SMS.
        IntentFilter mtSmsNiIntentFilter = new IntentFilter();
        mtSmsNiIntentFilter.addAction(Intents.DATA_SMS_RECEIVED_ACTION);
        mtSmsNiIntentFilter.addDataScheme("sms");
        mtSmsNiIntentFilter.addDataAuthority("localhost", "7275");

        if (shouldRegister) {
            mContext.registerReceiver(mNiSuplIntentReceiver,
                    wapPushNiIntentFilter, null, mHandler);
            mContext.registerReceiver(mNiSuplIntentReceiver,
                    mtSmsNiIntentFilter, null, mHandler);
            mNiSuplMessageListenerRegistered = true;
        } else {
            mContext.unregisterReceiver(mNiSuplIntentReceiver);
            mNiSuplMessageListenerRegistered = false;
        }
    }

    private void restartLocationRequest() {
        if (DEBUG) Log.d(TAG, "restartLocationRequest");
        setStarted(false);
        updateRequirements();
    }

    private void demandUtcTimeInjection() {
        if (DEBUG) Log.d(TAG, "demandUtcTimeInjection");
        postWithWakeLockHeld(mNetworkTimeHelper::demandUtcTimeInjection);
    }


    private static int getCellType(CellInfo ci) {
        if (ci instanceof CellInfoGsm) {
            return CellInfo.TYPE_GSM;
        } else if (ci instanceof CellInfoWcdma) {
            return CellInfo.TYPE_WCDMA;
        } else if (ci instanceof CellInfoLte) {
            return CellInfo.TYPE_LTE;
        } else if (ci instanceof CellInfoNr) {
            return CellInfo.TYPE_NR;
        }
        return CellInfo.TYPE_UNKNOWN;
    }

    /**
     * Extract the CID/CI for GSM/WCDMA/LTE/NR
     *
     * @return the cell ID or -1 if invalid
     */
    private static long getCidFromCellIdentity(CellIdentity id) {
        if (id == null) {
            return -1;
        }
        long cid = -1;
        switch(id.getType()) {
            case CellInfo.TYPE_GSM: cid = ((CellIdentityGsm) id).getCid(); break;
            case CellInfo.TYPE_WCDMA: cid = ((CellIdentityWcdma) id).getCid(); break;
            case CellInfo.TYPE_LTE: cid = ((CellIdentityLte) id).getCi(); break;
            case CellInfo.TYPE_NR: cid = ((CellIdentityNr) id).getNci(); break;
            default: break;
        }
        // If the CID is unreported
        if (cid == (id.getType() == CellInfo.TYPE_NR
                ? CellInfo.UNAVAILABLE_LONG : CellInfo.UNAVAILABLE)) {
            cid = -1;
        }

        return cid;
    }

    private void setRefLocation(int type, CellIdentity ci) {
        String mcc_str = ci.getMccString();
        String mnc_str = ci.getMncString();
        int mcc = mcc_str != null ? Integer.parseInt(mcc_str) : CellInfo.UNAVAILABLE;
        int mnc = mnc_str != null ? Integer.parseInt(mnc_str) : CellInfo.UNAVAILABLE;
        int lac = CellInfo.UNAVAILABLE;
        int tac = CellInfo.UNAVAILABLE;
        int pcid = CellInfo.UNAVAILABLE;
        int arfcn = CellInfo.UNAVAILABLE;
        long cid = CellInfo.UNAVAILABLE_LONG;

        switch (type) {
            case AGPS_REF_LOCATION_TYPE_GSM_CELLID:
                CellIdentityGsm cig = (CellIdentityGsm) ci;
                cid = cig.getCid();
                lac = cig.getLac();
                break;
            case AGPS_REF_LOCATION_TYPE_UMTS_CELLID:
                CellIdentityWcdma ciw = (CellIdentityWcdma) ci;
                cid = ciw.getCid();
                lac = ciw.getLac();
                break;
            case AGPS_REF_LOCATION_TYPE_LTE_CELLID:
                CellIdentityLte cil = (CellIdentityLte) ci;
                cid = cil.getCi();
                tac = cil.getTac();
                pcid = cil.getPci();
                break;
            case AGPS_REF_LOCATION_TYPE_NR_CELLID:
                CellIdentityNr cin = (CellIdentityNr) ci;
                cid = cin.getNci();
                tac = cin.getTac();
                pcid = cin.getPci();
                arfcn = cin.getNrarfcn();
                break;
            default:
        }

        mGnssNative.setAgpsReferenceLocationCellId(
                type, mcc, mnc, lac, cid, tac, pcid, arfcn);
    }

    private void requestRefLocation() {
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);

        final int phoneType = phone.getPhoneType();
        if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {

            List<CellInfo> cil = phone.getAllCellInfo();
            if (cil != null) {
                HashMap<Integer, CellIdentity> cellIdentityMap = new HashMap<>();
                cil.sort(Comparator.comparingInt(
                        (CellInfo ci) -> ci.getCellSignalStrength().getAsuLevel()).reversed());

                for (CellInfo ci : cil) {
                    int status = ci.getCellConnectionStatus();
                    if (ci.isRegistered()
                            || status == CellInfo.CONNECTION_PRIMARY_SERVING
                            || status == CellInfo.CONNECTION_SECONDARY_SERVING) {
                        CellIdentity c = ci.getCellIdentity();
                        int t = getCellType(ci);
                        if (getCidFromCellIdentity(c) != -1
                                && !cellIdentityMap.containsKey(t)) {
                            cellIdentityMap.put(t, c);
                        }
                    }
                }

                if (cellIdentityMap.containsKey(CellInfo.TYPE_GSM)) {
                    setRefLocation(AGPS_REF_LOCATION_TYPE_GSM_CELLID,
                            cellIdentityMap.get(CellInfo.TYPE_GSM));
                } else if (cellIdentityMap.containsKey(CellInfo.TYPE_WCDMA)) {
                    setRefLocation(AGPS_REF_LOCATION_TYPE_UMTS_CELLID,
                            cellIdentityMap.get(CellInfo.TYPE_WCDMA));
                } else if (cellIdentityMap.containsKey(CellInfo.TYPE_LTE)) {
                    setRefLocation(AGPS_REF_LOCATION_TYPE_LTE_CELLID,
                            cellIdentityMap.get(CellInfo.TYPE_LTE));
                } else if (cellIdentityMap.containsKey(CellInfo.TYPE_NR)) {
                    setRefLocation(AGPS_REF_LOCATION_TYPE_NR_CELLID,
                            cellIdentityMap.get(CellInfo.TYPE_NR));
                } else {
                    Log.e(TAG, "No available serving cell information.");
                }
            } else {
                Log.e(TAG, "Error getting cell location info.");
            }
        } else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            Log.e(TAG, "CDMA not supported.");
        }
    }

    private void postWithWakeLockHeld(Runnable runnable) {
        // hold a wake lock until this message is delivered
        // note that this assumes the message will not be removed from the queue before
        // it is handled (otherwise the wake lock would be leaked).
        mWakeLock.acquire(WAKELOCK_TIMEOUT_MILLIS);
        boolean success = mHandler.post(() -> {
            try {
                runnable.run();
            } finally {
                mWakeLock.release();
            }
        });
        if (!success) {
            mWakeLock.release();
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        boolean dumpAll = false;

        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-a".equals(opt)) {
                dumpAll = true;
                break;
            }
        }

        pw.print("mStarted=" + mStarted + "   (changed ");
        TimeUtils.formatDuration(SystemClock.elapsedRealtime()
                - mStartedChangedElapsedRealtime, pw);
        pw.println(" ago)");
        pw.println("mBatchingEnabled=" + mBatchingEnabled);
        pw.println("mBatchingStarted=" + mBatchingStarted);
        pw.println("mBatchSize=" + getBatchSize());
        pw.println("mFixInterval=" + mFixInterval);
        pw.print(mGnssMetrics.dumpGnssMetricsAsText());
        if (dumpAll) {
            mNetworkTimeHelper.dump(pw);
            pw.println("mSupportsPsds=" + mSupportsPsds);
            if (Flags.enableNiSuplMessageInjectionByCarrierConfigBugfix()) {
                pw.println("mNiSuplMessageListenerRegistered="
                        + mNiSuplMessageListenerRegistered);
            }
            pw.println(
                    "PsdsServerConfigured=" + mGnssConfiguration.isLongTermPsdsServerConfigured());
            pw.println("native internal state: ");
            pw.println("  " + mGnssNative.getInternalState());
        }
    }

    @Override
    public void onHalRestarted() {
        reloadGpsProperties();
        if (isGpsEnabled()) {
            setGpsEnabled(false);
            updateEnabled();
            restartLocationRequest();
        }

        // Re-register network callbacks to get an update of available networks right away.
        synchronized (mLock) {
            if (mInitialized) {
                mNetworkConnectivityHandler.unregisterNetworkCallbacks();
                mNetworkConnectivityHandler.registerNetworkCallbacks();
            }
        }
    }

    @Override
    public void onCapabilitiesChanged(GnssCapabilities oldCapabilities,
            GnssCapabilities newCapabilities) {
        mHandler.post(() -> {
            boolean useOnDemandTimeInjection = mGnssNative.getCapabilities().hasOnDemandTime();

            // b/73893222: There is a historic bug on Android, which means that the capability
            // "on demand time" is interpreted as "enable periodic injection" elsewhere but an
            // on-demand injection is done here. GNSS developers may have come to rely on the
            // periodic behavior, so it has been kept and all methods named to reflect what is
            // actually done. "On demand" requests are supported regardless of the capability.
            mNetworkTimeHelper.setPeriodicTimeInjectionMode(useOnDemandTimeInjection);
            if (useOnDemandTimeInjection) {
                demandUtcTimeInjection();
            }

            restartLocationRequest();
        });
    }

    @Override
    public void onReportLocation(boolean hasLatLong, Location location) {
        postWithWakeLockHeld(() -> handleReportLocation(hasLatLong, location));
    }

    @Override
    public void onReportLocations(Location[] locations) {
        if (DEBUG) {
            Log.d(TAG, "Location batch of size " + locations.length + " reported");
        }

        if (locations.length > 0) {
            // attempt to fix up timestamps if necessary
            if (locations.length > 1) {
                // check any realtimes outside of expected bounds
                boolean fixRealtime = false;
                for (int i = locations.length - 2; i >= 0; i--) {
                    long timeDeltaMs = locations[i + 1].getTime() - locations[i].getTime();
                    long realtimeDeltaMs = locations[i + 1].getElapsedRealtimeMillis()
                            - locations[i].getElapsedRealtimeMillis();
                    if (abs(timeDeltaMs - realtimeDeltaMs) > MAX_BATCH_TIMESTAMP_DELTA_MS) {
                        fixRealtime = true;
                        break;
                    }
                }

                if (fixRealtime) {
                    // sort for monotonically increasing time before fixing realtime - realtime will
                    // thus also be monotonically increasing
                    Arrays.sort(locations,
                            Comparator.comparingLong(Location::getTime));

                    long expectedDeltaMs =
                            locations[locations.length - 1].getTime()
                                    - locations[locations.length - 1].getElapsedRealtimeMillis();
                    for (int i = locations.length - 2; i >= 0; i--) {
                        locations[i].setElapsedRealtimeNanos(
                                MILLISECONDS.toNanos(
                                        max(locations[i].getTime() - expectedDeltaMs, 0)));
                    }
                } else {
                    // sort for monotonically increasing realttime
                    Arrays.sort(locations,
                            Comparator.comparingLong(Location::getElapsedRealtimeNanos));
                }
            }

            try {
                reportLocation(LocationResult.wrap(locations).validate());
            } catch (BadLocationException e) {
                Log.e(TAG, "Dropping invalid locations: " + e);
                return;
            }
        }

        Runnable[] listeners;
        synchronized (mLock) {
            listeners = mFlushListeners.toArray(new Runnable[0]);
            mFlushListeners.clear();
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    @Override
    public void onReportSvStatus(GnssStatus gnssStatus) {
        postWithWakeLockHeld(() -> handleReportSvStatus(gnssStatus));
    }

    @Override
    public void onReportAGpsStatus(int agpsType, int agpsStatus, byte[] suplIpAddr) {
        mNetworkConnectivityHandler.onReportAGpsStatus(agpsType, agpsStatus, suplIpAddr);
    }

    @Override
    public void onRequestPsdsDownload(int psdsType) {
        postWithWakeLockHeld(() -> handleDownloadPsdsData(psdsType));
    }

    @Override
    public void onRequestSetID(@GnssNative.AGpsCallbacks.AgpsSetIdFlags int flags) {
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        int type = AGPS_SETID_TYPE_NONE;
        String setId = null;

        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (mGnssConfiguration.isActiveSimEmergencySuplEnabled() && mNIHandler.getInEmergency()
                && mNetworkConnectivityHandler.getActiveSubId() >= 0) {
            subId = mNetworkConnectivityHandler.getActiveSubId();
        }
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            phone = phone.createForSubscriptionId(subId);
        }
        if ((flags & AGPS_REQUEST_SETID_IMSI) == AGPS_REQUEST_SETID_IMSI) {
            setId = phone.getSubscriberId();
            if (setId != null) {
                // This means the framework has the SIM card.
                type = AGPS_SETID_TYPE_IMSI;
            }
        } else if ((flags & AGPS_REQUEST_SETID_MSISDN) == AGPS_REQUEST_SETID_MSISDN) {
            setId = phone.getLine1Number();
            if (setId != null) {
                // This means the framework has the SIM card.
                type = AGPS_SETID_TYPE_MSISDN;
            }
        }

        mGnssNative.setAgpsSetId(type, (setId == null) ? "" : setId);
    }

    @Override
    public void onRequestLocation(boolean independentFromGnss, boolean isUserEmergency) {
        if (DEBUG) {
            Log.d(TAG, "requestLocation. independentFromGnss: " + independentFromGnss
                    + ", isUserEmergency: "
                    + isUserEmergency);
        }
        postWithWakeLockHeld(() -> handleRequestLocation(independentFromGnss, isUserEmergency));
    }

    @Override
    public void onRequestUtcTime() {
        demandUtcTimeInjection();
    }

    @Override
    public void onRequestRefLocation() {
        requestRefLocation();
    }

    @Override
    public void onReportNfwNotification(String proxyAppPackageName, byte protocolStack,
            String otherProtocolStackName, byte requestor, String requestorId,
            byte responseType, boolean inEmergencyMode, boolean isCachedLocation) {
        if (mGnssVisibilityControl == null) {
            Log.e(TAG, "reportNfwNotification: mGnssVisibilityControl uninitialized.");
            return;
        }

        mGnssVisibilityControl.reportNfwNotification(proxyAppPackageName, protocolStack,
                otherProtocolStackName, requestor, requestorId, responseType, inEmergencyMode,
                isCachedLocation);
    }
}
