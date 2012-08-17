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
import android.net.arp.ArpPeer;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URL;

/**
 * WifiWatchdogStateMachine monitors the connection to a Wi-Fi
 * network. After the framework notifies that it has connected to an
 * acccess point and is waiting for link to be verified, the watchdog
 * takes over and verifies if the link is good by doing ARP pings to
 * the gateway using {@link ArpPeer}.
 *
 * Upon successful verification, the watchdog notifies and continues
 * to monitor the link afterwards when the RSSI level falls below
 * a certain threshold.

 * When Wi-fi connects at L2 layer, the beacons from access point reach
 * the device and it can maintain a connection, but the application
 * connectivity can be flaky (due to bigger packet size exchange).
 *
 * We now monitor the quality of the last hop on
 * Wi-Fi using signal strength and ARP connectivity as indicators
 * to decide if the link is good enough to switch to Wi-Fi as the uplink.
 *
 * ARP pings are useful for link validation but can still get through
 * when the application traffic fails to go through and are thus not
 * the best indicator of real packet loss since they are tiny packets
 * (28 bytes) and have a much low chance of packet corruption than the
 * regular data packets.
 *
 * When signal strength and ARP are used together, it ends up working well in tests.
 * The goal is to switch to Wi-Fi after validating ARP transfer
 * and RSSI and then switching out of Wi-Fi when we hit a low
 * signal strength threshold and then waiting until the signal strength
 * improves and validating ARP transfer.
 *
 * @hide
 */
public class WifiWatchdogStateMachine extends StateMachine {

    /* STOPSHIP: Keep this configurable for debugging until ship */
    private static boolean DBG = false;
    private static final String TAG = "WifiWatchdogStateMachine";
    private static final String WALLED_GARDEN_NOTIFICATION_ID = "WifiWatchdog.walledgarden";

    /* RSSI Levels as used by notification icon
       Level 4  -55 <= RSSI
       Level 3  -66 <= RSSI < -55
       Level 2  -77 <= RSSI < -67
       Level 1  -88 <= RSSI < -78
       Level 0         RSSI < -88 */

    /* Wi-fi connection is monitored actively below this
       threshold */
    private static final int RSSI_LEVEL_MONITOR = 0;
    /* Rssi threshold is at level 0 (-88dBm) */
    private static final int RSSI_MONITOR_THRESHOLD = -88;
    /* Number of times RSSI is measured to be low before being avoided */
    private static final int RSSI_MONITOR_COUNT = 5;
    private int mRssiMonitorCount = 0;

    /* Avoid flapping. The interval is changed over time as long as we continue to avoid
     * under the max interval after which we reset the interval again */
    private static final int MIN_INTERVAL_AVOID_BSSID_MS[] = {0, 30 * 1000, 60 * 1000,
            5 * 60 * 1000, 30 * 60 * 1000};
    /* Index into the interval array MIN_INTERVAL_AVOID_BSSID_MS */
    private int mMinIntervalArrayIndex = 0;

    private long mLastBssidAvoidedTime;

    private int mCurrentSignalLevel;

    private static final long DEFAULT_ARP_CHECK_INTERVAL_MS = 2 * 60 * 1000;
    private static final long DEFAULT_RSSI_FETCH_INTERVAL_MS = 1000;
    private static final long DEFAULT_WALLED_GARDEN_INTERVAL_MS = 30 * 60 * 1000;

    private static final int DEFAULT_NUM_ARP_PINGS = 5;
    private static final int DEFAULT_MIN_ARP_RESPONSES = 1;

    private static final int DEFAULT_ARP_PING_TIMEOUT_MS = 100;

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
    /* Passed with RSSI information */
    private static final int EVENT_RSSI_CHANGE                      = BASE + 3;
    private static final int EVENT_WIFI_RADIO_STATE_CHANGE          = BASE + 5;
    private static final int EVENT_WATCHDOG_SETTINGS_CHANGE         = BASE + 6;

    /* Internal messages */
    private static final int CMD_ARP_CHECK                          = BASE + 11;
    private static final int CMD_DELAYED_WALLED_GARDEN_CHECK        = BASE + 12;
    private static final int CMD_RSSI_FETCH                         = BASE + 13;

    /* Notifications to WifiStateMachine */
    static final int POOR_LINK_DETECTED                             = BASE + 21;
    static final int GOOD_LINK_DETECTED                             = BASE + 22;
    static final int RSSI_FETCH                                     = BASE + 23;
    static final int RSSI_FETCH_SUCCEEDED                           = BASE + 24;
    static final int RSSI_FETCH_FAILED                              = BASE + 25;

    private static final int SINGLE_ARP_CHECK = 0;
    private static final int FULL_ARP_CHECK   = 1;

    private Context mContext;
    private ContentResolver mContentResolver;
    private WifiManager mWifiManager;
    private IntentFilter mIntentFilter;
    private BroadcastReceiver mBroadcastReceiver;
    private AsyncChannel mWsmChannel = new AsyncChannel();;

    private DefaultState mDefaultState = new DefaultState();
    private WatchdogDisabledState mWatchdogDisabledState = new WatchdogDisabledState();
    private WatchdogEnabledState mWatchdogEnabledState = new WatchdogEnabledState();
    private NotConnectedState mNotConnectedState = new NotConnectedState();
    private VerifyingLinkState mVerifyingLinkState = new VerifyingLinkState();
    private ConnectedState mConnectedState = new ConnectedState();
    private WalledGardenCheckState mWalledGardenCheckState = new WalledGardenCheckState();
    /* Online and watching link connectivity */
    private OnlineWatchState mOnlineWatchState = new OnlineWatchState();
    /* RSSI level is below RSSI_LEVEL_MONITOR and needs close monitoring */
    private RssiMonitoringState mRssiMonitoringState = new RssiMonitoringState();
    /* Online and doing nothing */
    private OnlineState mOnlineState = new OnlineState();

    private int mArpToken = 0;
    private long mArpCheckIntervalMs;
    private int mRssiFetchToken = 0;
    private long mRssiFetchIntervalMs;
    private long mWalledGardenIntervalMs;
    private int mNumArpPings;
    private int mMinArpResponses;
    private int mArpPingTimeoutMs;
    private boolean mPoorNetworkDetectionEnabled;
    private boolean mWalledGardenTestEnabled;
    private String mWalledGardenUrl;

    private WifiInfo mWifiInfo;
    private LinkProperties mLinkProperties;

    private long mLastWalledGardenCheckTime = 0;

    private static boolean sWifiOnly = false;
    private boolean mWalledGardenNotificationShown;

    /**
     * STATE MAP
     *          Default
     *         /       \
     * Disabled      Enabled
     *             /     \     \
     * NotConnected  Verifying  Connected
     *                         /---------\
     *                       (all other states)
     */
    private WifiWatchdogStateMachine(Context context) {
        super(TAG);
        mContext = context;
        mContentResolver = context.getContentResolver();
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mWsmChannel.connectSync(mContext, getHandler(),
                mWifiManager.getWifiStateMachineMessenger());

        setupNetworkReceiver();

        // The content observer to listen needs a handler
        registerForSettingsChanges();
        registerForWatchdogToggle();
        addState(mDefaultState);
            addState(mWatchdogDisabledState, mDefaultState);
            addState(mWatchdogEnabledState, mDefaultState);
                addState(mNotConnectedState, mWatchdogEnabledState);
                addState(mVerifyingLinkState, mWatchdogEnabledState);
                addState(mConnectedState, mWatchdogEnabledState);
                    addState(mWalledGardenCheckState, mConnectedState);
                    addState(mOnlineWatchState, mConnectedState);
                    addState(mRssiMonitoringState, mOnlineWatchState);
                    addState(mOnlineState, mConnectedState);

        if (isWatchdogEnabled()) {
            setInitialState(mNotConnectedState);
        } else {
            setInitialState(mWatchdogDisabledState);
        }
        updateSettings();
    }

    public static WifiWatchdogStateMachine makeWifiWatchdogStateMachine(Context context) {
        ContentResolver contentResolver = context.getContentResolver();

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        sWifiOnly = (cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) == false);

        // Watchdog is always enabled. Poor network detection & walled garden detection
        // can individually be turned on/off
        // TODO: Remove this setting & clean up state machine since we always have
        // watchdog in an enabled state
        putSettingsBoolean(contentResolver, Settings.Secure.WIFI_WATCHDOG_ON, true);

        // Disable poor network avoidance, but keep watchdog active for walled garden detection
        if (sWifiOnly) {
            log("Disabling poor network avoidance for wi-fi only device");
            putSettingsBoolean(contentResolver,
                    Settings.Secure.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED, false);
        }

        WifiWatchdogStateMachine wwsm = new WifiWatchdogStateMachine(context);
        wwsm.start();
        return wwsm;
    }

    private void setupNetworkReceiver() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    sendMessage(EVENT_NETWORK_STATE_CHANGE, intent);
                } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
                    obtainMessage(EVENT_RSSI_CHANGE,
                            intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200), 0).sendToTarget();
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
        mContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
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
                        Settings.Secure.WIFI_WATCHDOG_ARP_CHECK_INTERVAL_MS),
                        false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_INTERVAL_MS),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_NUM_ARP_PINGS),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_MIN_ARP_RESPONSES),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_ARP_PING_TIMEOUT_MS),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_TEST_ENABLED),
                false, contentObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_URL),
                false, contentObserver);
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

    public void dump(PrintWriter pw) {
        pw.print("WatchdogStatus: ");
        pw.print("State: " + getCurrentState());
        pw.println("mWifiInfo: [" + mWifiInfo + "]");
        pw.println("mLinkProperties: [" + mLinkProperties + "]");
        pw.println("mCurrentSignalLevel: [" + mCurrentSignalLevel + "]");
        pw.println("mArpCheckIntervalMs: [" + mArpCheckIntervalMs+ "]");
        pw.println("mRssiFetchIntervalMs: [" + mRssiFetchIntervalMs + "]");
        pw.println("mWalledGardenIntervalMs: [" + mWalledGardenIntervalMs + "]");
        pw.println("mNumArpPings: [" + mNumArpPings + "]");
        pw.println("mMinArpResponses: [" + mMinArpResponses + "]");
        pw.println("mArpPingTimeoutMs: [" + mArpPingTimeoutMs + "]");
        pw.println("mPoorNetworkDetectionEnabled: [" + mPoorNetworkDetectionEnabled + "]");
        pw.println("mWalledGardenTestEnabled: [" + mWalledGardenTestEnabled + "]");
        pw.println("mWalledGardenUrl: [" + mWalledGardenUrl + "]");
    }

    private boolean isWatchdogEnabled() {
        boolean ret = getSettingsBoolean(mContentResolver, Settings.Secure.WIFI_WATCHDOG_ON, true);
        if (DBG) log("watchdog enabled " + ret);
        return ret;
    }

    private void updateSettings() {
        if (DBG) log("Updating secure settings");

        mArpCheckIntervalMs = Secure.getLong(mContentResolver,
                Secure.WIFI_WATCHDOG_ARP_CHECK_INTERVAL_MS,
                DEFAULT_ARP_CHECK_INTERVAL_MS);
        mRssiFetchIntervalMs = Secure.getLong(mContentResolver,
                Secure.WIFI_WATCHDOG_RSSI_FETCH_INTERVAL_MS,
                DEFAULT_RSSI_FETCH_INTERVAL_MS);
        mNumArpPings = Secure.getInt(mContentResolver,
                Secure.WIFI_WATCHDOG_NUM_ARP_PINGS,
                DEFAULT_NUM_ARP_PINGS);
        mMinArpResponses = Secure.getInt(mContentResolver,
                Secure.WIFI_WATCHDOG_MIN_ARP_RESPONSES,
                DEFAULT_MIN_ARP_RESPONSES);
        mArpPingTimeoutMs = Secure.getInt(mContentResolver,
                Secure.WIFI_WATCHDOG_ARP_PING_TIMEOUT_MS,
                DEFAULT_ARP_PING_TIMEOUT_MS);
        mPoorNetworkDetectionEnabled = getSettingsBoolean(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_POOR_NETWORK_TEST_ENABLED, true);
        mWalledGardenTestEnabled = getSettingsBoolean(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_TEST_ENABLED, true);
        mWalledGardenUrl = getSettingsStr(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_URL,
                DEFAULT_WALLED_GARDEN_URL);
        mWalledGardenIntervalMs = Secure.getLong(mContentResolver,
                Secure.WIFI_WATCHDOG_WALLED_GARDEN_INTERVAL_MS,
                DEFAULT_WALLED_GARDEN_INTERVAL_MS);
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
                    mWifiInfo.getSSID());

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

    class DefaultState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_SETTINGS_CHANGE:
                    updateSettings();
                    if (DBG) {
                        log("Updating wifi-watchdog secure settings");
                    }
                    break;
                case EVENT_RSSI_CHANGE:
                    mCurrentSignalLevel = calculateSignalLevel(msg.arg1);
                    break;
                case EVENT_WIFI_RADIO_STATE_CHANGE:
                case EVENT_NETWORK_STATE_CHANGE:
                case CMD_ARP_CHECK:
                case CMD_DELAYED_WALLED_GARDEN_CHECK:
                case CMD_RSSI_FETCH:
                case RSSI_FETCH_SUCCEEDED:
                case RSSI_FETCH_FAILED:
                    //ignore
                    break;
                default:
                    log("Unhandled message " + msg + " in state " + getCurrentState().getName());
                    break;
            }
            return HANDLED;
        }
    }

    class WatchdogDisabledState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_TOGGLED:
                    if (isWatchdogEnabled())
                        transitionTo(mNotConnectedState);
                    return HANDLED;
                case EVENT_NETWORK_STATE_CHANGE:
                    Intent intent = (Intent) msg.obj;
                    NetworkInfo networkInfo = (NetworkInfo)
                            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

                    switch (networkInfo.getDetailedState()) {
                        case VERIFYING_POOR_LINK:
                            if (DBG) log("Watchdog disabled, verify link");
                            mWsmChannel.sendMessage(GOOD_LINK_DETECTED);
                            break;
                        default:
                            break;
                    }
                    break;
            }
            return NOT_HANDLED;
        }
    }

    class WatchdogEnabledState extends State {
        @Override
        public void enter() {
            if (DBG) log("WifiWatchdogService enabled");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_TOGGLED:
                    if (!isWatchdogEnabled())
                        transitionTo(mWatchdogDisabledState);
                    break;
                case EVENT_NETWORK_STATE_CHANGE:
                    Intent intent = (Intent) msg.obj;
                    NetworkInfo networkInfo = (NetworkInfo)
                            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

                    if (DBG) log("network state change " + networkInfo.getDetailedState());

                    switch (networkInfo.getDetailedState()) {
                        case VERIFYING_POOR_LINK:
                            mLinkProperties = (LinkProperties) intent.getParcelableExtra(
                                    WifiManager.EXTRA_LINK_PROPERTIES);
                            mWifiInfo = (WifiInfo) intent.getParcelableExtra(
                                    WifiManager.EXTRA_WIFI_INFO);
                            if (mPoorNetworkDetectionEnabled) {
                                if (mWifiInfo == null) {
                                    log("Ignoring link verification, mWifiInfo is NULL");
                                    mWsmChannel.sendMessage(GOOD_LINK_DETECTED);
                                } else {
                                    transitionTo(mVerifyingLinkState);
                                }
                            } else {
                                mWsmChannel.sendMessage(GOOD_LINK_DETECTED);
                            }
                            break;
                        case CONNECTED:
                            if (shouldCheckWalledGarden()) {
                                transitionTo(mWalledGardenCheckState);
                            } else {
                                transitionTo(mOnlineWatchState);
                            }
                            break;
                        default:
                            transitionTo(mNotConnectedState);
                            break;
                    }
                    break;
                case EVENT_WIFI_RADIO_STATE_CHANGE:
                    if ((Integer) msg.obj == WifiManager.WIFI_STATE_DISABLING) {
                        if (DBG) log("WifiStateDisabling -- Resetting WatchdogState");
                        transitionTo(mNotConnectedState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }

            setWalledGardenNotificationVisible(false);
            return HANDLED;
        }

        @Override
        public void exit() {
            if (DBG) log("WifiWatchdogService disabled");
        }
    }

    class NotConnectedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
        }
    }

    class VerifyingLinkState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            //Treat entry as an rssi change
            handleRssiChange();
        }

        private void handleRssiChange() {
            if (mCurrentSignalLevel <= RSSI_LEVEL_MONITOR) {
                //stay here
                if (DBG) log("enter VerifyingLinkState, stay level: " + mCurrentSignalLevel);
            } else {
                if (DBG) log("enter VerifyingLinkState, arp check level: " + mCurrentSignalLevel);
                sendMessage(obtainMessage(CMD_ARP_CHECK, ++mArpToken, 0));
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_SETTINGS_CHANGE:
                    updateSettings();
                    if (!mPoorNetworkDetectionEnabled) {
                        mWsmChannel.sendMessage(GOOD_LINK_DETECTED);
                    }
                    break;
                case EVENT_RSSI_CHANGE:
                    mCurrentSignalLevel = calculateSignalLevel(msg.arg1);
                    handleRssiChange();
                    break;
                case CMD_ARP_CHECK:
                    if (msg.arg1 == mArpToken) {
                        if (doArpTest(FULL_ARP_CHECK) == true) {
                            if (DBG) log("Notify link is good " + mCurrentSignalLevel);
                            mWsmChannel.sendMessage(GOOD_LINK_DETECTED);
                        } else {
                            if (DBG) log("Continue ARP check, rssi level: " + mCurrentSignalLevel);
                            sendMessageDelayed(obtainMessage(CMD_ARP_CHECK, ++mArpToken, 0),
                                    mArpCheckIntervalMs);
                        }
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class ConnectedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
        }
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_SETTINGS_CHANGE:
                    updateSettings();
                    //STOPSHIP: Remove this at ship
                    DBG = true;
                    if (DBG) log("Updated secure settings and turned debug on");

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

    class WalledGardenCheckState extends State {
        private int mWalledGardenToken = 0;
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            sendMessageDelayed(obtainMessage(CMD_DELAYED_WALLED_GARDEN_CHECK,
                    ++mWalledGardenToken, 0), WALLED_GARDEN_START_DELAY_MS);
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_DELAYED_WALLED_GARDEN_CHECK:
                    if (msg.arg1 == mWalledGardenToken) {
                        mLastWalledGardenCheckTime = SystemClock.elapsedRealtime();
                        if (isWalledGardenConnection()) {
                            if (DBG) log("Walled garden detected");
                            setWalledGardenNotificationVisible(true);
                        }
                        transitionTo(mOnlineWatchState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class OnlineWatchState extends State {
        public void enter() {
            if (DBG) log(getName() + "\n");
            if (mPoorNetworkDetectionEnabled) {
                //Treat entry as an rssi change
                handleRssiChange();
            } else {
                transitionTo(mOnlineState);
            }
        }

        private void handleRssiChange() {
            if (mCurrentSignalLevel <= RSSI_LEVEL_MONITOR) {
                transitionTo(mRssiMonitoringState);
            } else {
                //stay here
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_RSSI_CHANGE:
                    mCurrentSignalLevel = calculateSignalLevel(msg.arg1);
                    //Ready to avoid bssid again ?
                    long time = android.os.SystemClock.elapsedRealtime();
                    if (time - mLastBssidAvoidedTime  > MIN_INTERVAL_AVOID_BSSID_MS[
                            mMinIntervalArrayIndex]) {
                        handleRssiChange();
                    } else {
                        if (DBG) log("Early to avoid " + mWifiInfo + " time: " + time +
                                " last avoided: " + mLastBssidAvoidedTime +
                                " mMinIntervalArrayIndex: " + mMinIntervalArrayIndex);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class RssiMonitoringState extends State {
        public void enter() {
            if (DBG) log(getName() + "\n");
            sendMessage(obtainMessage(CMD_RSSI_FETCH, ++mRssiFetchToken, 0));
        }

        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_RSSI_CHANGE:
                    mCurrentSignalLevel = calculateSignalLevel(msg.arg1);
                    if (mCurrentSignalLevel <= RSSI_LEVEL_MONITOR) {
                        //stay here;
                    } else {
                        //We dont need frequent RSSI monitoring any more
                        transitionTo(mOnlineWatchState);
                    }
                    break;
                case CMD_RSSI_FETCH:
                    if (msg.arg1 == mRssiFetchToken) {
                        mWsmChannel.sendMessage(RSSI_FETCH);
                        sendMessageDelayed(obtainMessage(CMD_RSSI_FETCH, ++mRssiFetchToken, 0),
                                mRssiFetchIntervalMs);
                    }
                    break;
                case RSSI_FETCH_SUCCEEDED:
                    int rssi = msg.arg1;
                    if (DBG) log("RSSI_FETCH_SUCCEEDED: " + rssi);
                    if (msg.arg1 < RSSI_MONITOR_THRESHOLD) {
                        mRssiMonitorCount++;
                    } else {
                        mRssiMonitorCount = 0;
                    }

                    if (mRssiMonitorCount > RSSI_MONITOR_COUNT) {
                        sendPoorLinkDetected();
                        ++mRssiFetchToken;
                    }
                    break;
                case RSSI_FETCH_FAILED:
                    //can happen if we are waiting to get a disconnect notification
                    if (DBG) log("RSSI_FETCH_FAILED");
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
   }

    /* Child state of ConnectedState indicating that we are online
     * and there is nothing to do
     */
    class OnlineState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
        }
    }

    private boolean shouldCheckWalledGarden() {
        if (!mWalledGardenTestEnabled) {
            if (DBG) log("Skipping walled garden check - disabled");
            return false;
        }

        long waitTime = (mWalledGardenIntervalMs + mLastWalledGardenCheckTime)
            - SystemClock.elapsedRealtime();

        if (mLastWalledGardenCheckTime != 0 && waitTime > 0) {
            if (DBG) {
                log("Skipping walled garden check - wait " +
                        waitTime + " ms.");
            }
            return false;
        }
        return true;
    }

    private boolean doArpTest(int type) {
        boolean success;

        String iface = mLinkProperties.getInterfaceName();
        String mac = mWifiInfo.getMacAddress();
        InetAddress inetAddress = null;
        InetAddress gateway = null;

        for (LinkAddress la : mLinkProperties.getLinkAddresses()) {
            inetAddress = la.getAddress();
            break;
        }

        for (RouteInfo route : mLinkProperties.getRoutes()) {
            gateway = route.getGateway();
            break;
        }

        if (DBG) log("ARP " + iface + "addr: " + inetAddress + "mac: " + mac + "gw: " + gateway);

        try {
            ArpPeer peer = new ArpPeer(iface, inetAddress, mac, gateway);
            if (type == SINGLE_ARP_CHECK) {
                success = (peer.doArp(mArpPingTimeoutMs) != null);
                if (DBG) log("single ARP test result: " + success);
            } else {
                int responses = 0;
                for (int i=0; i < mNumArpPings; i++) {
                    if(peer.doArp(mArpPingTimeoutMs) != null) responses++;
                }
                if (DBG) log("full ARP test result: " + responses + "/" + mNumArpPings);
                success = (responses >= mMinArpResponses);
            }
            peer.close();
        } catch (SocketException se) {
            //Consider an Arp socket creation issue as a successful Arp
            //test to avoid any wifi connectivity issues
            loge("ARP test initiation failure: " + se);
            success = true;
        } catch (IllegalArgumentException e) {
            // ArpPeer throws exception for IPv6 address
            success = true;
        }

        return success;
    }

    private int calculateSignalLevel(int rssi) {
        int signalLevel = WifiManager.calculateSignalLevel(rssi,
                WifiManager.RSSI_LEVELS);
        if (DBG) log("RSSI current: " + mCurrentSignalLevel + "new: " + rssi + ", " + signalLevel);
        return signalLevel;
    }

    private void sendPoorLinkDetected() {
        if (DBG) log("send POOR_LINK_DETECTED " + mWifiInfo);
        mWsmChannel.sendMessage(POOR_LINK_DETECTED);

        long time = android.os.SystemClock.elapsedRealtime();
        if (time - mLastBssidAvoidedTime  > MIN_INTERVAL_AVOID_BSSID_MS[
                MIN_INTERVAL_AVOID_BSSID_MS.length - 1]) {
            mMinIntervalArrayIndex = 1;
            if (DBG) log("set mMinIntervalArrayIndex to 1");
        } else {

            if (mMinIntervalArrayIndex < MIN_INTERVAL_AVOID_BSSID_MS.length - 1) {
                mMinIntervalArrayIndex++;
            }
            if (DBG) log("mMinIntervalArrayIndex: " + mMinIntervalArrayIndex);
        }

        mLastBssidAvoidedTime = android.os.SystemClock.elapsedRealtime();
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

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
