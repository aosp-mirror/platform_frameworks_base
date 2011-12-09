/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.wifi;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DnsPinger;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * {@link WifiWatchdogStateMachine} monitors the initial connection to a Wi-Fi
 * network with multiple access points. After the framework successfully
 * connects to an access point, the watchdog verifies connectivity by 'pinging'
 * the configured DNS server using {@link DnsPinger}.
 * <p>
 * On DNS check failure, the BSSID is blacklisted if it is reasonably likely
 * that another AP might have internet access; otherwise the SSID is disabled.
 * <p>
 * On DNS success, the WatchdogService initiates a walled garden check via an
 * http get. A browser window is activated if a walled garden is detected.
 *
 * @hide
 */
public class WifiWatchdogStateMachine extends StateMachine {

    private static final boolean DBG = false;
    private static final String TAG = "WifiWatchdogStateMachine";
    private static final String DISABLED_NETWORK_NOTIFICATION_ID = "WifiWatchdog.networkdisabled";
    private static final String WALLED_GARDEN_NOTIFICATION_ID = "WifiWatchdog.walledgarden";

    private static final int WIFI_SIGNAL_LEVELS = 4;
    /**
     * Low signal is defined as less than or equal to cut off
     */
    private static final int LOW_SIGNAL_CUTOFF = 0;

    private static final long DEFAULT_DNS_CHECK_SHORT_INTERVAL_MS = 2 * 60 * 1000;
    private static final long DEFAULT_DNS_CHECK_LONG_INTERVAL_MS = 60 * 60 * 1000;
    private static final long DEFAULT_WALLED_GARDEN_INTERVAL_MS = 30 * 60 * 1000;

    private static final int DEFAULT_MAX_SSID_BLACKLISTS = 7;
    private static final int DEFAULT_NUM_DNS_PINGS = 5; // Multiple pings to detect setup issues
    private static final int DEFAULT_MIN_DNS_RESPONSES = 1;

    private static final int DEFAULT_DNS_PING_TIMEOUT_MS = 2000;

    private static final long DEFAULT_BLACKLIST_FOLLOWUP_INTERVAL_MS = 15 * 1000;

    // See http://go/clientsdns for usage approval
    private static final String DEFAULT_WALLED_GARDEN_URL =
            "http://clients3.google.com/generate_204";
    private static final int WALLED_GARDEN_SOCKET_TIMEOUT_MS = 10000;

    /* Some carrier apps might have support captive portal handling. Add some delay to allow
        app authentication to be done before our test.
       TODO: This should go away once we provide an API to apps to disable walled garden test
       for certain SSIDs
     */
    private static final int WALLED_GARDEN_START_DELAY_MS = 3000;

    private static final int DNS_INTRATEST_PING_INTERVAL_MS = 200;
    /* With some router setups, it takes a few hunder milli-seconds before connection is active */
    private static final int DNS_START_DELAY_MS = 1000;

    private static final int BASE = Protocol.BASE_WIFI_WATCHDOG;

    /**
     * Indicates the enable setting of WWS may have changed
     */
    private static final int EVENT_WATCHDOG_TOGGLED                 = BASE + 1;

    /**
     * Indicates the wifi network state has changed. Passed w/ original intent
     * which has a non-null networkInfo object
     */
    private static final int EVENT_NETWORK_STATE_CHANGE             = BASE + 2;
    /**
     * Indicates the signal has changed. Passed with arg1
     * {@link #mNetEventCounter} and arg2 [raw signal strength]
     */
    private static final int EVENT_RSSI_CHANGE                      = BASE + 3;
    private static final int EVENT_SCAN_RESULTS_AVAILABLE           = BASE + 4;
    private static final int EVENT_WIFI_RADIO_STATE_CHANGE          = BASE + 5;
    private static final int EVENT_WATCHDOG_SETTINGS_CHANGE         = BASE + 6;

    private static final int MESSAGE_HANDLE_WALLED_GARDEN           = BASE + 100;
    private static final int MESSAGE_HANDLE_BAD_AP                  = BASE + 101;
    /**
     * arg1 == mOnlineWatchState.checkCount
     */
    private static final int MESSAGE_SINGLE_DNS_CHECK               = BASE + 102;
    private static final int MESSAGE_NETWORK_FOLLOWUP               = BASE + 103;
    private static final int MESSAGE_DELAYED_WALLED_GARDEN_CHECK    = BASE + 104;

    private Context mContext;
    private ContentResolver mContentResolver;
    private WifiManager mWifiManager;
    private DnsPinger mDnsPinger;
    private IntentFilter mIntentFilter;
    private BroadcastReceiver mBroadcastReceiver;

    private DefaultState mDefaultState = new DefaultState();
    private WatchdogDisabledState mWatchdogDisabledState = new WatchdogDisabledState();
    private WatchdogEnabledState mWatchdogEnabledState = new WatchdogEnabledState();
    private NotConnectedState mNotConnectedState = new NotConnectedState();
    private ConnectedState mConnectedState = new ConnectedState();
    private DnsCheckingState mDnsCheckingState = new DnsCheckingState();
    private OnlineWatchState mOnlineWatchState = new OnlineWatchState();
    private OnlineState mOnlineState = new OnlineState();
    private DnsCheckFailureState mDnsCheckFailureState = new DnsCheckFailureState();
    private DelayWalledGardenState mDelayWalledGardenState = new DelayWalledGardenState();
    private WalledGardenState mWalledGardenState = new WalledGardenState();
    private BlacklistedApState mBlacklistedApState = new BlacklistedApState();

    private long mDnsCheckShortIntervalMs;
    private long mDnsCheckLongIntervalMs;
    private long mWalledGardenIntervalMs;
    private int mMaxSsidBlacklists;
    private int mNumDnsPings;
    private int mMinDnsResponses;
    private int mDnsPingTimeoutMs;
    private long mBlacklistFollowupIntervalMs;
    private boolean mPoorNetworkDetectionEnabled;
    private boolean mWalledGardenTestEnabled;
    private String mWalledGardenUrl;

    private boolean mShowDisabledNotification;
    /**
     * The {@link WifiInfo} object passed to WWSM on network broadcasts
     */
    private WifiInfo mConnectionInfo;
    private int mNetEventCounter = 0;

    /**
     * Currently maintained but not used, TODO
     */
    private HashSet<String> mBssids = new HashSet<String>();
    private int mNumCheckFailures = 0;

    private Long mLastWalledGardenCheckTime = null;

    /**
     * This is set by the blacklisted state and reset when connected to a new AP.
     * It triggers a disableNetwork call if a DNS check fails.
     */
    public boolean mDisableAPNextFailure = false;
    private static boolean sWifiOnly = false;
    private boolean mDisabledNotificationShown;
    private boolean mWalledGardenNotificationShown;
    public boolean mHasConnectedWifiManager = false;

    /**
     * STATE MAP
     *          Default
     *         /       \
     * Disabled     Enabled
     *             /       \
     * NotConnected      Connected
     *                  /---------\
     *               (all other states)
     */
    private WifiWatchdogStateMachine(Context context) {
        super(TAG);
        mContext = context;
        mContentResolver = context.getContentResolver();
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mDnsPinger = new DnsPinger(mContext, "WifiWatchdogStateMachine.DnsPinger",
                                this.getHandler().getLooper(), this.getHandler(),
                                ConnectivityManager.TYPE_WIFI);

        setupNetworkReceiver();

        // The content observer to listen needs a handler
        registerForSettingsChanges();
        registerForWatchdogToggle();
        addState(mDefaultState);
            addState(mWatchdogDisabledState, mDefaultState);
            addState(mWatchdogEnabledState, mDefaultState);
                addState(mNotConnectedState, mWatchdogEnabledState);
                addState(mConnectedState, mWatchdogEnabledState);
                    addState(mDnsCheckingState, mConnectedState);
                    addState(mDnsCheckFailureState, mConnectedState);
                    addState(mDelayWalledGardenState, mConnectedState);
                    addState(mWalledGardenState, mConnectedState);
                    addState(mBlacklistedApState, mConnectedState);
                    addState(mOnlineWatchState, mConnectedState);
                    addState(mOnlineState, mConnectedState);

        setInitialState(mWatchdogDisabledState);
        updateSettings();
    }

    public static WifiWatchdogStateMachine makeWifiWatchdogStateMachine(Context context) {
        ContentResolver contentResolver = context.getContentResolver();

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        sWifiOnly = (cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) == false);

        // Disable for wifi only devices.
        if (Settings.Secure.getString(contentResolver, Settings.Secure.WIFI_WATCHDOG_ON) == null &&
                sWifiOnly) {
            putSettingsBoolean(contentResolver, Settings.Secure.WIFI_WATCHDOG_ON, false);
        }
        WifiWatchdogStateMachine wwsm = new WifiWatchdogStateMachine(context);
        wwsm.start();
        wwsm.sendMessage(EVENT_WATCHDOG_TOGGLED);
        return wwsm;
    }

    /**
   *
   */
    private void setupNetworkReceiver() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    sendMessage(EVENT_NETWORK_STATE_CHANGE, intent);
                } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
                    obtainMessage(EVENT_RSSI_CHANGE, mNetEventCounter,
                            intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200)).sendToTarget();
                } else if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                    sendMessage(EVENT_SCAN_RESULTS_AVAILABLE);
                } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                    sendMessage(EVENT_WIFI_RADIO_STATE_CHANGE,
                            intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                    WifiManager.WIFI_STATE_UNKNOWN));
                }
            }
        };

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
    }

    /**
     * Observes the watchdog on/off setting, and takes action when changed.
     */
    private void registerForWatchdogToggle() {
        ContentObserver contentObserver = new ContentObserver(this.getHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                sendMessage(EVENT_WATCHDOG_TOGGLED);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_ON),
                false, contentObserver);
    }

    /**
     * Observes watchdogs secure setting changes.
     */
    private void registerForSettingsChanges() {
        ContentObserver contentObserver = new ContentObserver(this.getHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                sendMessage(EVENT_WATCHDOG_SETTINGS_CHANGE);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(
                        Settings.Secure.WIFI_WATCHDOG_DNS_CHECK_SHORT_INTERVAL_MS),
                        false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_DNS_CHECK_LONG_INTERVAL_MS),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_INTERVAL_MS),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_MAX_SSID_BLACKLISTS),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_NUM_DNS_PINGS),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_MIN_DNS_RESPONSES),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_DNS_PING_TIMEOUT_MS),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(
                        Settings.Secure.WIFI_WATCHDOG_BLACKLIST_FOLLOWUP_INTERVAL_MS),
                        false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_TEST_ENABLED),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_URL),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_SHOW_DISABLED_NETWORK_POPUP)
                , false, contentObserver);
    }

    /**
     * DNS based detection techniques do not work at all hotspots. The one sure
     * way to check a walled garden is to see if a URL fetch on a known address
     * fetches the data we expect
     */
    private boolean isWalledGardenConnection() {
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(mWalledGardenUrl);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setInstanceFollowRedirects(false);
            urlConnection.setConnectTimeout(WALLED_GARDEN_SOCKET_TIMEOUT_MS);
            urlConnection.setReadTimeout(WALLED_GARDEN_SOCKET_TIMEOUT_MS);
            urlConnection.setUseCaches(false);
            urlConnection.getInputStream();
            // We got a valid response, but not from the real google
            return urlConnection.getResponseCode() != 204;
        } catch (IOException e) {
            if (DBG) {
                log("Walled garden check - probably not a portal: exception " + e);
            }
            return false;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private boolean rssiStrengthAboveCutoff(int rssi) {
        return WifiManager.calculateSignalLevel(rssi, WIFI_SIGNAL_LEVELS) > LOW_SIGNAL_CUTOFF;
    }

    public void dump(PrintWriter pw) {
        pw.print("WatchdogStatus: ");
        pw.print("State " + getCurrentState());
        pw.println(", network [" + mConnectionInfo + "]");
        pw.print("checkFailures   " + mNumCheckFailures);
        pw.println(", bssids: " + mBssids);
        pw.println("lastSingleCheck: " + mOnlineWatchState.lastCheckTime);
    }

    private boolean isWatchdogEnabled() {
        return getSettingsBoolean(mContentResolver, Settings.Secure.WIFI_WATCHDOG_ON, true);
    }

    private void updateSettings() {
        mDnsCheckShortIntervalMs = Secure.getLong(mContentResolver,
                Secure.WIFI_WATCHDOG_DNS_CHECK_SHORT_INTERVAL_MS,
                DEFAULT_DNS_CHECK_SHORT_INTERVAL_MS);
        mDnsCheckLongIntervalMs = Secure.getLong(mContentResolver,
                Secure.WIFI_WATCHDOG_DNS_CHECK_LONG_INTERVAL_MS,
                DEFAULT_DNS_CHECK_LONG_INTERVAL_MS);
        mMaxSsidBlacklists = Secure.getInt(mContentResolver,
                Secure.WIFI_WATCHDOG_MAX_SSID_BLACKLISTS,
                DEFAULT_MAX_SSID_BLACKLISTS);
        mNumDnsPings = Secure.getInt(mContentResolver,
                Secure.WIFI_WATCHDOG_NUM_DNS_PINGS,
                DEFAULT_NUM_DNS_PINGS);
        mMinDnsResponses = Secure.getInt(mContentResolver,
                Secure.WIFI_WATCHDOG_MIN_DNS_RESPONSES,
                DEFAULT_MIN_DNS_RESPONSES);
        mDnsPingTimeoutMs = Secure.getInt(mContentResolver,
                Secure.WIFI_WATCHDOG_DNS_PING_TIMEOUT_MS,
                DEFAULT_DNS_PING_TIMEOUT_MS);
        mBlacklistFollowupIntervalMs = Secure.getLong(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_BLACKLIST_FOLLOWUP_INTERVAL_MS,
                DEFAULT_BLACKLIST_FOLLOWUP_INTERVAL_MS);
        //TODO: enable this by default after changing watchdog behavior
        //Also, update settings description
        mPoorNetworkDetectionEnabled = getSettingsBoolean(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED, false);
        mWalledGardenTestEnabled = getSettingsBoolean(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_TEST_ENABLED, true);
        mWalledGardenUrl = getSettingsStr(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_URL,
                DEFAULT_WALLED_GARDEN_URL);
        mWalledGardenIntervalMs = Secure.getLong(mContentResolver,
                Secure.WIFI_WATCHDOG_WALLED_GARDEN_INTERVAL_MS,
                DEFAULT_WALLED_GARDEN_INTERVAL_MS);
        mShowDisabledNotification = getSettingsBoolean(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_SHOW_DISABLED_NETWORK_POPUP, true);
    }

    /**
     * Helper to return wait time left given a min interval and last run
     *
     * @param interval minimum wait interval
     * @param lastTime last time action was performed in
     *            SystemClock.elapsedRealtime(). Null if never.
     * @return non negative time to wait
     */
    private static long waitTime(long interval, Long lastTime) {
        if (lastTime == null)
            return 0;
        long wait = interval + lastTime - SystemClock.elapsedRealtime();
        return wait > 0 ? wait : 0;
    }

    private static String wifiInfoToStr(WifiInfo wifiInfo) {
        if (wifiInfo == null)
            return "null";
        return "(" + wifiInfo.getSSID() + ", " + wifiInfo.getBSSID() + ")";
    }

    /**
     * Uses {@link #mConnectionInfo}.
     */
    private void updateBssids() {
        String curSsid = mConnectionInfo.getSSID();
        List<ScanResult> results = mWifiManager.getScanResults();
        int oldNumBssids = mBssids.size();

        if (results == null) {
            if (DBG) {
                log("updateBssids: Got null scan results!");
            }
            return;
        }

        for (ScanResult result : results) {
            if (result == null || result.SSID == null) {
                if (DBG) {
                    log("Received invalid scan result: " + result);
                }
                continue;
            }
            if (curSsid.equals(result.SSID))
                mBssids.add(result.BSSID);
        }
    }

    private void resetWatchdogState() {
        if (DBG) {
            log("Resetting watchdog state...");
        }
        mConnectionInfo = null;
        mDisableAPNextFailure = false;
        mLastWalledGardenCheckTime = null;
        mNumCheckFailures = 0;
        mBssids.clear();
        setDisabledNetworkNotificationVisible(false);
        setWalledGardenNotificationVisible(false);
    }

    private void setWalledGardenNotificationVisible(boolean visible) {
        // If it should be hidden and it is already hidden, then noop
        if (!visible && !mWalledGardenNotificationShown) {
            return;
        }

        Resources r = Resources.getSystem();
        NotificationManager notificationManager = (NotificationManager) mContext
            .getSystemService(Context.NOTIFICATION_SERVICE);

        if (visible) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mWalledGardenUrl));
            intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);

            CharSequence title = r.getString(R.string.wifi_available_sign_in, 0);
            CharSequence details = r.getString(R.string.wifi_available_sign_in_detailed,
                    mConnectionInfo.getSSID());

            Notification notification = new Notification();
            notification.when = 0;
            notification.icon = com.android.internal.R.drawable.stat_notify_wifi_in_range;
            notification.flags = Notification.FLAG_AUTO_CANCEL;
            notification.contentIntent = PendingIntent.getActivity(mContext, 0, intent, 0);
            notification.tickerText = title;
            notification.setLatestEventInfo(mContext, title, details, notification.contentIntent);

            notificationManager.notify(WALLED_GARDEN_NOTIFICATION_ID, 1, notification);
        } else {
            notificationManager.cancel(WALLED_GARDEN_NOTIFICATION_ID, 1);
        }
        mWalledGardenNotificationShown = visible;
    }

    private void setDisabledNetworkNotificationVisible(boolean visible) {
        // If it should be hidden and it is already hidden, then noop
        if (!visible && !mDisabledNotificationShown) {
            return;
        }

        Resources r = Resources.getSystem();
        NotificationManager notificationManager = (NotificationManager) mContext
            .getSystemService(Context.NOTIFICATION_SERVICE);

        if (visible) {
            CharSequence title = r.getText(R.string.wifi_watchdog_network_disabled);
            String msg = mConnectionInfo.getSSID() +
                r.getText(R.string.wifi_watchdog_network_disabled_detailed);

            Notification wifiDisabledWarning = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.stat_sys_warning)
                .setDefaults(Notification.DEFAULT_ALL)
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(msg)
                .setContentIntent(PendingIntent.getActivity(mContext, 0,
                            new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0))
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .getNotification();

            notificationManager.notify(DISABLED_NETWORK_NOTIFICATION_ID, 1, wifiDisabledWarning);
        } else {
            notificationManager.cancel(DISABLED_NETWORK_NOTIFICATION_ID, 1);
        }
        mDisabledNotificationShown = visible;
    }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_SETTINGS_CHANGE:
                    updateSettings();
                    if (DBG) {
                        log("Updating wifi-watchdog secure settings");
                    }
                    return HANDLED;
            }
            if (DBG) {
                log("Caught message " + msg.what + " in state " +
                        getCurrentState().getName());
            }
            return HANDLED;
        }
    }

    class WatchdogDisabledState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_TOGGLED:
                    if (isWatchdogEnabled())
                        transitionTo(mNotConnectedState);
                    return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    class WatchdogEnabledState extends State {
        @Override
        public void enter() {
            resetWatchdogState();
            mContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
            if (DBG) log("WifiWatchdogService enabled");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_TOGGLED:
                    if (!isWatchdogEnabled())
                        transitionTo(mWatchdogDisabledState);
                    return HANDLED;
                case EVENT_NETWORK_STATE_CHANGE:
                    Intent stateChangeIntent = (Intent) msg.obj;
                    NetworkInfo networkInfo = (NetworkInfo)
                            stateChangeIntent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

                    setDisabledNetworkNotificationVisible(false);
                    setWalledGardenNotificationVisible(false);
                    switch (networkInfo.getState()) {
                        case CONNECTED:
                            WifiInfo wifiInfo = (WifiInfo)
                                stateChangeIntent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                            if (wifiInfo == null) {
                                loge("Connected --> WifiInfo object null!");
                                return HANDLED;
                            }

                            if (wifiInfo.getSSID() == null || wifiInfo.getBSSID() == null) {
                                loge("Received wifiInfo object with null elts: "
                                        + wifiInfoToStr(wifiInfo));
                                return HANDLED;
                            }

                            initConnection(wifiInfo);
                            mConnectionInfo = wifiInfo;
                            mNetEventCounter++;
                            if (mPoorNetworkDetectionEnabled) {
                                updateBssids();
                                transitionTo(mDnsCheckingState);
                            } else {
                                transitionTo(mDelayWalledGardenState);
                            }
                            break;
                        default:
                            mNetEventCounter++;
                            transitionTo(mNotConnectedState);
                            break;
                    }
                    return HANDLED;
                case EVENT_WIFI_RADIO_STATE_CHANGE:
                    if ((Integer) msg.obj == WifiManager.WIFI_STATE_DISABLING) {
                        if (DBG) log("WifiStateDisabling -- Resetting WatchdogState");
                        resetWatchdogState();
                        mNetEventCounter++;
                        transitionTo(mNotConnectedState);
                    }
                    return HANDLED;
            }

            return NOT_HANDLED;
        }

        /**
         * @param wifiInfo Info object with non-null ssid and bssid
         */
        private void initConnection(WifiInfo wifiInfo) {
            if (DBG) {
                log("Connected:: old " + wifiInfoToStr(mConnectionInfo) +
                        " ==> new " + wifiInfoToStr(wifiInfo));
            }

            if (mConnectionInfo == null || !wifiInfo.getSSID().equals(mConnectionInfo.getSSID())) {
                resetWatchdogState();
            } else if (!wifiInfo.getBSSID().equals(mConnectionInfo.getBSSID())) {
                mDisableAPNextFailure = false;
            }
        }

        @Override
        public void exit() {
            mContext.unregisterReceiver(mBroadcastReceiver);
            if (DBG) log("WifiWatchdogService disabled");
        }
    }

    class NotConnectedState extends State {
    }

    class ConnectedState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SCAN_RESULTS_AVAILABLE:
                    if (mPoorNetworkDetectionEnabled) {
                        updateBssids();
                    }
                    return HANDLED;
                case EVENT_WATCHDOG_SETTINGS_CHANGE:
                    updateSettings();
                    if (mPoorNetworkDetectionEnabled) {
                        transitionTo(mOnlineWatchState);
                    } else {
                        transitionTo(mOnlineState);
                    }
                    return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    class DnsCheckingState extends State {
        List<InetAddress> mDnsList;
        int[] dnsCheckSuccesses;
        String dnsCheckLogStr;
        String[] dnsResponseStrs;
        /** Keeps track of active dns pings.  Map is from pingID to index in mDnsList */
        HashMap<Integer, Integer> idDnsMap = new HashMap<Integer, Integer>();

        @Override
        public void enter() {
            mDnsList = mDnsPinger.getDnsList();
            int numDnses = mDnsList.size();
            dnsCheckSuccesses = new int[numDnses];
            dnsResponseStrs = new String[numDnses];
            for (int i = 0; i < numDnses; i++)
                dnsResponseStrs[i] = "";

            if (DBG) {
                dnsCheckLogStr = String.format("Pinging %s on ssid [%s]: ",
                        mDnsList, mConnectionInfo.getSSID());
                log(dnsCheckLogStr);
            }

            idDnsMap.clear();
            for (int i=0; i < mNumDnsPings; i++) {
                for (int j = 0; j < numDnses; j++) {
                    idDnsMap.put(mDnsPinger.pingDnsAsync(mDnsList.get(j), mDnsPingTimeoutMs,
                            DNS_START_DELAY_MS + DNS_INTRATEST_PING_INTERVAL_MS * i), j);
                }
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what != DnsPinger.DNS_PING_RESULT) {
                return NOT_HANDLED;
            }

            int pingID = msg.arg1;
            int pingResponseTime = msg.arg2;

            Integer dnsServerId = idDnsMap.get(pingID);
            if (dnsServerId == null) {
                loge("Received a Dns response with unknown ID!");
                return HANDLED;
            }

            idDnsMap.remove(pingID);
            if (pingResponseTime >= 0)
                dnsCheckSuccesses[dnsServerId]++;

            if (DBG) {
                if (pingResponseTime >= 0) {
                    dnsResponseStrs[dnsServerId] += "|" + pingResponseTime;
                } else {
                    dnsResponseStrs[dnsServerId] += "|x";
                }
            }

            /**
             * After a full ping count, if we have more responses than this
             * cutoff, the outcome is success; else it is 'failure'.
             */

            /**
             * Our final success count will be at least this big, so we're
             * guaranteed to succeed.
             */
            if (dnsCheckSuccesses[dnsServerId] >= mMinDnsResponses) {
                // DNS CHECKS OK, NOW WALLED GARDEN
                if (DBG) {
                    log(makeLogString() + "  SUCCESS");
                }

                if (!shouldCheckWalledGarden()) {
                    transitionTo(mOnlineWatchState);
                    return HANDLED;
                }

                transitionTo(mDelayWalledGardenState);
                return HANDLED;
            }

            if (idDnsMap.isEmpty()) {
                if (DBG) {
                    log(makeLogString() + "  FAILURE");
                }
                transitionTo(mDnsCheckFailureState);
                return HANDLED;
            }

            return HANDLED;
        }

        private String makeLogString() {
            String logStr = dnsCheckLogStr;
            for (String respStr : dnsResponseStrs)
                logStr += " [" + respStr + "]";
            return logStr;
        }

        @Override
        public void exit() {
            mDnsPinger.cancelPings();
        }

        private boolean shouldCheckWalledGarden() {
            if (!mWalledGardenTestEnabled) {
                if (DBG)
                    log("Skipping walled garden check - disabled");
                return false;
            }
            long waitTime = waitTime(mWalledGardenIntervalMs,
                    mLastWalledGardenCheckTime);
            if (waitTime > 0) {
                if (DBG) {
                    log("Skipping walled garden check - wait " +
                            waitTime + " ms.");
                }
                return false;
            }
            return true;
        }
    }

    class DelayWalledGardenState extends State {
        @Override
        public void enter() {
            sendMessageDelayed(MESSAGE_DELAYED_WALLED_GARDEN_CHECK, WALLED_GARDEN_START_DELAY_MS);
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_DELAYED_WALLED_GARDEN_CHECK:
                    mLastWalledGardenCheckTime = SystemClock.elapsedRealtime();
                    if (isWalledGardenConnection()) {
                        if (DBG) log("Walled garden test complete - walled garden detected");
                        transitionTo(mWalledGardenState);
                    } else {
                        if (DBG) log("Walled garden test complete - online");
                        if (mPoorNetworkDetectionEnabled) {
                            transitionTo(mOnlineWatchState);
                        } else {
                            transitionTo(mOnlineState);
                        }
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    class OnlineWatchState extends State {
        /**
         * Signals a short-wait message is enqueued for the current 'guard' counter
         */
        boolean unstableSignalChecks = false;

        /**
         * The signal is unstable.  We should enqueue a short-wait check, if one is enqueued
         * already
         */
        boolean signalUnstable = false;

        /**
         * A monotonic counter to ensure that at most one check message will be processed from any
         * set of check messages currently enqueued.  Avoids duplicate checks when a low-signal
         * event is observed.
         */
        int checkGuard = 0;
        Long lastCheckTime = null;

        /** Keeps track of dns pings.  Map is from pingID to InetAddress used for ping */
        HashMap<Integer, InetAddress> pingInfoMap = new HashMap<Integer, InetAddress>();

        @Override
        public void enter() {
            lastCheckTime = SystemClock.elapsedRealtime();
            signalUnstable = false;
            checkGuard++;
            unstableSignalChecks = false;
            pingInfoMap.clear();
            triggerSingleDnsCheck();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_RSSI_CHANGE:
                    if (msg.arg1 != mNetEventCounter) {
                        if (DBG) {
                            log("Rssi change message out of sync, ignoring");
                        }
                        return HANDLED;
                    }
                    int newRssi = msg.arg2;
                    signalUnstable = !rssiStrengthAboveCutoff(newRssi);
                    if (DBG) {
                        log("OnlineWatchState:: new rssi " + newRssi + " --> level " +
                                WifiManager.calculateSignalLevel(newRssi, WIFI_SIGNAL_LEVELS));
                    }

                    if (signalUnstable && !unstableSignalChecks) {
                        if (DBG) {
                            log("Sending triggered check msg");
                        }
                        triggerSingleDnsCheck();
                    }
                    return HANDLED;
                case MESSAGE_SINGLE_DNS_CHECK:
                    if (msg.arg1 != checkGuard) {
                        if (DBG) {
                            log("Single check msg out of sync, ignoring.");
                        }
                        return HANDLED;
                    }
                    lastCheckTime = SystemClock.elapsedRealtime();
                    pingInfoMap.clear();
                    for (InetAddress curDns: mDnsPinger.getDnsList()) {
                        pingInfoMap.put(mDnsPinger.pingDnsAsync(curDns, mDnsPingTimeoutMs, 0),
                                curDns);
                    }
                    return HANDLED;
                case DnsPinger.DNS_PING_RESULT:
                    InetAddress curDnsServer = pingInfoMap.get(msg.arg1);
                    if (curDnsServer == null) {
                        return HANDLED;
                    }
                    pingInfoMap.remove(msg.arg1);
                    int responseTime = msg.arg2;
                    if (responseTime >= 0) {
                        if (DBG) {
                            log("Single DNS ping OK. Response time: "
                                    + responseTime + " from DNS " + curDnsServer);
                        }
                        pingInfoMap.clear();

                        checkGuard++;
                        unstableSignalChecks = false;
                        triggerSingleDnsCheck();
                    } else {
                        if (pingInfoMap.isEmpty()) {
                            if (DBG) {
                                log("Single dns ping failure. All dns servers failed, "
                                        + "starting full checks.");
                            }
                            transitionTo(mDnsCheckingState);
                        }
                    }
                    return HANDLED;
            }
            return NOT_HANDLED;
        }

        @Override
        public void exit() {
            mDnsPinger.cancelPings();
        }

        /**
         * Times a dns check with an interval based on {@link #signalUnstable}
         */
        private void triggerSingleDnsCheck() {
            long waitInterval;
            if (signalUnstable) {
                waitInterval = mDnsCheckShortIntervalMs;
                unstableSignalChecks = true;
            } else {
                waitInterval = mDnsCheckLongIntervalMs;
            }
            sendMessageDelayed(obtainMessage(MESSAGE_SINGLE_DNS_CHECK, checkGuard, 0),
                    waitTime(waitInterval, lastCheckTime));
        }
    }


    /* Child state of ConnectedState indicating that we are online
     * and there is nothing to do
     */
    class OnlineState extends State {
    }

    class DnsCheckFailureState extends State {

        @Override
        public void enter() {
            mNumCheckFailures++;
            obtainMessage(MESSAGE_HANDLE_BAD_AP, mNetEventCounter, 0).sendToTarget();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what != MESSAGE_HANDLE_BAD_AP) {
                return NOT_HANDLED;
            }

            if (msg.arg1 != mNetEventCounter) {
                if (DBG) {
                    log("Msg out of sync, ignoring...");
                }
                return HANDLED;
            }

            if (mDisableAPNextFailure || mNumCheckFailures >= mBssids.size()
                    || mNumCheckFailures >= mMaxSsidBlacklists) {
                if (sWifiOnly) {
                    log("Would disable bad network, but device has no mobile data!" +
                            "  Going idle...");
                    // This state should be called idle -- will be changing flow.
                    transitionTo(mNotConnectedState);
                    return HANDLED;
                }

                // TODO : Unban networks if they had low signal ?
                log("Disabling current SSID " + wifiInfoToStr(mConnectionInfo)
                        + ".  " + "numCheckFailures " + mNumCheckFailures
                        + ", numAPs " + mBssids.size());
                int networkId = mConnectionInfo.getNetworkId();
                if (!mHasConnectedWifiManager) {
                    mWifiManager.asyncConnect(mContext, getHandler());
                    mHasConnectedWifiManager = true;
                }
                mWifiManager.disableNetwork(networkId, WifiConfiguration.DISABLED_DNS_FAILURE);
                if (mShowDisabledNotification) {
                    setDisabledNetworkNotificationVisible(true);
                }
                transitionTo(mNotConnectedState);
            } else {
                log("Blacklisting current BSSID.  " + wifiInfoToStr(mConnectionInfo)
                       + "numCheckFailures " + mNumCheckFailures + ", numAPs " + mBssids.size());

                mWifiManager.addToBlacklist(mConnectionInfo.getBSSID());
                mWifiManager.reassociate();
                transitionTo(mBlacklistedApState);
            }
            return HANDLED;
        }
    }

    class WalledGardenState extends State {
        @Override
        public void enter() {
            obtainMessage(MESSAGE_HANDLE_WALLED_GARDEN, mNetEventCounter, 0).sendToTarget();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what != MESSAGE_HANDLE_WALLED_GARDEN) {
                return NOT_HANDLED;
            }

            if (msg.arg1 != mNetEventCounter) {
                if (DBG) {
                    log("WalledGardenState::Msg out of sync, ignoring...");
                }
                return HANDLED;
            }
            setWalledGardenNotificationVisible(true);
            if (mPoorNetworkDetectionEnabled) {
                transitionTo(mOnlineWatchState);
            } else {
                transitionTo(mOnlineState);
            }
            return HANDLED;
        }
    }

    class BlacklistedApState extends State {
        @Override
        public void enter() {
            mDisableAPNextFailure = true;
            sendMessageDelayed(obtainMessage(MESSAGE_NETWORK_FOLLOWUP, mNetEventCounter, 0),
                    mBlacklistFollowupIntervalMs);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what != MESSAGE_NETWORK_FOLLOWUP) {
                return NOT_HANDLED;
            }

            if (msg.arg1 != mNetEventCounter) {
                if (DBG) {
                    log("BlacklistedApState::Msg out of sync, ignoring...");
                }
                return HANDLED;
            }

            transitionTo(mDnsCheckingState);
            return HANDLED;
        }
    }


    /**
     * Convenience function for retrieving a single secure settings value
     * as a string with a default value.
     *
     * @param cr The ContentResolver to access.
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     *
     * @return The setting's current value, or 'def' if it is not defined
     */
    private static String getSettingsStr(ContentResolver cr, String name, String def) {
        String v = Settings.Secure.getString(cr, name);
        return v != null ? v : def;
    }

    /**
     * Convenience function for retrieving a single secure settings value
     * as a boolean.  Note that internally setting values are always
     * stored as strings; this function converts the string to a boolean
     * for you.  The default value will be returned if the setting is
     * not defined or not a valid boolean.
     *
     * @param cr The ContentResolver to access.
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     *
     * @return The setting's current value, or 'def' if it is not defined
     * or not a valid boolean.
     */
    private static boolean getSettingsBoolean(ContentResolver cr, String name, boolean def) {
        return Settings.Secure.getInt(cr, name, def ? 1 : 0) == 1;
    }

    /**
     * Convenience function for updating a single settings value as an
     * integer. This will either create a new entry in the table if the
     * given name does not exist, or modify the value of the existing row
     * with that name.  Note that internally setting values are always
     * stored as strings, so this function converts the given value to a
     * string before storing it.
     *
     * @param cr The ContentResolver to access.
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    private static boolean putSettingsBoolean(ContentResolver cr, String name, boolean value) {
        return Settings.Secure.putInt(cr, name, value ? 1 : 0);
    }

    private void log(String s) {
        Log.d(TAG, s);
    }

    private void loge(String s) {
        Log.e(TAG, s);
    }
}
