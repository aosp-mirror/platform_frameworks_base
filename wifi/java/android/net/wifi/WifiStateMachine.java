/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

/**
 * TODO:
 * Deprecate WIFI_STATE_UNKNOWN
 */
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.backup.IBackupManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.DhcpInfoInternal;
import android.net.DhcpStateMachine;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkUtils;
import android.net.wifi.RssiPacketCountInfo;
import android.net.wifi.WpsResult.Status;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pService;
import android.net.wifi.StateChangeResult;
import android.os.Binder;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.LruCache;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Track the state of Wifi connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * Wi-Fi now supports three modes of operation: Client, SoftAp and p2p
 * In the current implementation, we support concurrent wifi p2p and wifi operation.
 * The WifiStateMachine handles SoftAp and Client operations while WifiP2pService
 * handles p2p operation.
 *
 * @hide
 */
public class WifiStateMachine extends StateMachine {

    private static final String TAG = "WifiStateMachine";
    private static final String NETWORKTYPE = "WIFI";
    private static final boolean DBG = false;

    private WifiMonitor mWifiMonitor;
    private WifiNative mWifiNative;
    private WifiConfigStore mWifiConfigStore;
    private INetworkManagementService mNwService;
    private ConnectivityManager mCm;

    private final boolean mP2pSupported;
    private final AtomicBoolean mP2pConnected = new AtomicBoolean(false);
    private boolean mTemporarilyDisconnectWifi = false;
    private final String mPrimaryDeviceType;

    /* Scan results handling */
    private List<ScanResult> mScanResults = new ArrayList<ScanResult>();
    private static final Pattern scanResultPattern = Pattern.compile("\t+");
    private static final int SCAN_RESULT_CACHE_SIZE = 80;
    private final LruCache<String, ScanResult> mScanResultCache;

    /* Chipset supports background scan */
    private final boolean mBackgroundScanSupported;

    private String mInterfaceName;
    /* Tethering interface could be seperate from wlan interface */
    private String mTetherInterfaceName;

    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private int mLastNetworkId;
    private boolean mEnableRssiPolling = false;
    private boolean mEnableBackgroundScan = false;
    private int mRssiPollToken = 0;
    private int mReconnectCount = 0;
    private boolean mIsScanMode = false;
    private boolean mScanResultIsPending = false;
    /* Tracks if the current scan settings are active */
    private boolean mSetScanActive = false;
    /* Tracks if state machine has received any screen state change broadcast yet.
     * We can miss one of these at boot.
     */
    private AtomicBoolean mScreenBroadcastReceived = new AtomicBoolean(false);

    private boolean mBluetoothConnectionActive = false;

    private PowerManager.WakeLock mSuspendWakeLock;

    /**
     * Interval in milliseconds between polling for RSSI
     * and linkspeed information
     */
    private static final int POLL_RSSI_INTERVAL_MSECS = 3000;

    /**
     * Delay between supplicant restarts upon failure to establish connection
     */
    private static final int SUPPLICANT_RESTART_INTERVAL_MSECS = 5000;

    /**
     * Number of times we attempt to restart supplicant
     */
    private static final int SUPPLICANT_RESTART_TRIES = 5;

    private int mSupplicantRestartCount = 0;
    /* Tracks sequence number on stop failure message */
    private int mSupplicantStopFailureToken = 0;

    /**
     * Tether state change notification time out
     */
    private static final int TETHER_NOTIFICATION_TIME_OUT_MSECS = 5000;

    /* Tracks sequence number on a tether notification time out */
    private int mTetherToken = 0;

    /**
     * Driver start time out.
     */
    private static final int DRIVER_START_TIME_OUT_MSECS = 10000;

    /* Tracks sequence number on a driver time out */
    private int mDriverStartToken = 0;

    private LinkProperties mLinkProperties;

    /* Tracks sequence number on a periodic scan message */
    private int mPeriodicScanToken = 0;

    // Wakelock held during wifi start/stop and driver load/unload
    private PowerManager.WakeLock mWakeLock;

    private Context mContext;

    private DhcpInfoInternal mDhcpInfoInternal;
    private WifiInfo mWifiInfo;
    private NetworkInfo mNetworkInfo;
    private SupplicantStateTracker mSupplicantStateTracker;
    private DhcpStateMachine mDhcpStateMachine;

    private AlarmManager mAlarmManager;
    private PendingIntent mScanIntent;
    private PendingIntent mDriverStopIntent;

    /* Tracks current frequency mode */
    private AtomicInteger mFrequencyBand = new AtomicInteger(WifiManager.WIFI_FREQUENCY_BAND_AUTO);

    /* Tracks if we are filtering Multicast v4 packets. Default is to filter. */
    private AtomicBoolean mFilteringMulticastV4Packets = new AtomicBoolean(true);

    // Channel for sending replies.
    private AsyncChannel mReplyChannel = new AsyncChannel();

    private WifiP2pManager mWifiP2pManager;
    //Used to initiate a connection with WifiP2pService
    private AsyncChannel mWifiP2pChannel = new AsyncChannel();
    private AsyncChannel mWifiApConfigChannel = new AsyncChannel();

    // Event log tags (must be in sync with event-log-tags)
    private static final int EVENTLOG_WIFI_STATE_CHANGED        = 50021;
    private static final int EVENTLOG_WIFI_EVENT_HANDLED        = 50022;
    private static final int EVENTLOG_SUPPLICANT_STATE_CHANGED  = 50023;

    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI;
    /* Load the driver */
    static final int CMD_LOAD_DRIVER                      = BASE + 1;
    /* Unload the driver */
    static final int CMD_UNLOAD_DRIVER                    = BASE + 2;
    /* Indicates driver load succeeded */
    static final int CMD_LOAD_DRIVER_SUCCESS              = BASE + 3;
    /* Indicates driver load failed */
    static final int CMD_LOAD_DRIVER_FAILURE              = BASE + 4;
    /* Indicates driver unload succeeded */
    static final int CMD_UNLOAD_DRIVER_SUCCESS            = BASE + 5;
    /* Indicates driver unload failed */
    static final int CMD_UNLOAD_DRIVER_FAILURE            = BASE + 6;

    /* Start the supplicant */
    static final int CMD_START_SUPPLICANT                 = BASE + 11;
    /* Stop the supplicant */
    static final int CMD_STOP_SUPPLICANT                  = BASE + 12;
    /* Start the driver */
    static final int CMD_START_DRIVER                     = BASE + 13;
    /* Stop the driver */
    static final int CMD_STOP_DRIVER                      = BASE + 14;
    /* Indicates Static IP succeded */
    static final int CMD_STATIC_IP_SUCCESS                = BASE + 15;
    /* Indicates Static IP failed */
    static final int CMD_STATIC_IP_FAILURE                = BASE + 16;
    /* Indicates supplicant stop failed */
    static final int CMD_STOP_SUPPLICANT_FAILED           = BASE + 17;
    /* Delayed stop to avoid shutting down driver too quick*/
    static final int CMD_DELAYED_STOP_DRIVER              = BASE + 18;
    /* A delayed message sent to start driver when it fail to come up */
    static final int CMD_DRIVER_START_TIMED_OUT           = BASE + 19;
    /* Ready to switch to network as default */
    static final int CMD_CAPTIVE_CHECK_COMPLETE           = BASE + 20;

    /* Start the soft access point */
    static final int CMD_START_AP                         = BASE + 21;
    /* Indicates soft ap start succeded */
    static final int CMD_START_AP_SUCCESS                 = BASE + 22;
    /* Indicates soft ap start failed */
    static final int CMD_START_AP_FAILURE                 = BASE + 23;
    /* Stop the soft access point */
    static final int CMD_STOP_AP                          = BASE + 24;
    /* Set the soft access point configuration */
    static final int CMD_SET_AP_CONFIG                    = BASE + 25;
    /* Soft access point configuration set completed */
    static final int CMD_SET_AP_CONFIG_COMPLETED          = BASE + 26;
    /* Request the soft access point configuration */
    static final int CMD_REQUEST_AP_CONFIG                = BASE + 27;
    /* Response to access point configuration request */
    static final int CMD_RESPONSE_AP_CONFIG               = BASE + 28;
    /* Invoked when getting a tether state change notification */
    static final int CMD_TETHER_STATE_CHANGE              = BASE + 29;
    /* A delayed message sent to indicate tether state change failed to arrive */
    static final int CMD_TETHER_NOTIFICATION_TIMED_OUT    = BASE + 30;

    static final int CMD_BLUETOOTH_ADAPTER_STATE_CHANGE   = BASE + 31;

    /* Supplicant commands */
    /* Is supplicant alive ? */
    static final int CMD_PING_SUPPLICANT                  = BASE + 51;
    /* Add/update a network configuration */
    static final int CMD_ADD_OR_UPDATE_NETWORK            = BASE + 52;
    /* Delete a network */
    static final int CMD_REMOVE_NETWORK                   = BASE + 53;
    /* Enable a network. The device will attempt a connection to the given network. */
    static final int CMD_ENABLE_NETWORK                   = BASE + 54;
    /* Enable all networks */
    static final int CMD_ENABLE_ALL_NETWORKS              = BASE + 55;
    /* Blacklist network. De-prioritizes the given BSSID for connection. */
    static final int CMD_BLACKLIST_NETWORK                = BASE + 56;
    /* Clear the blacklist network list */
    static final int CMD_CLEAR_BLACKLIST                  = BASE + 57;
    /* Save configuration */
    static final int CMD_SAVE_CONFIG                      = BASE + 58;
    /* Get configured networks*/
    static final int CMD_GET_CONFIGURED_NETWORKS          = BASE + 59;

    /* Supplicant commands after driver start*/
    /* Initiate a scan */
    static final int CMD_START_SCAN                       = BASE + 71;
    /* Set scan mode. CONNECT_MODE or SCAN_ONLY_MODE */
    static final int CMD_SET_SCAN_MODE                    = BASE + 72;
    /* Set scan type. SCAN_ACTIVE or SCAN_PASSIVE */
    static final int CMD_SET_SCAN_TYPE                    = BASE + 73;
    /* Disconnect from a network */
    static final int CMD_DISCONNECT                       = BASE + 74;
    /* Reconnect to a network */
    static final int CMD_RECONNECT                        = BASE + 75;
    /* Reassociate to a network */
    static final int CMD_REASSOCIATE                      = BASE + 76;
    /* Controls suspend mode optimizations
     *
     * When high perf mode is enabled, suspend mode optimizations are disabled
     *
     * When high perf mode is disabled, suspend mode optimizations are enabled
     *
     * Suspend mode optimizations include:
     * - packet filtering
     * - turn off roaming
     * - DTIM wake up settings
     */
    static final int CMD_SET_HIGH_PERF_MODE               = BASE + 77;
    /* Set the country code */
    static final int CMD_SET_COUNTRY_CODE                 = BASE + 80;
    /* Enables RSSI poll */
    static final int CMD_ENABLE_RSSI_POLL                 = BASE + 82;
    /* RSSI poll */
    static final int CMD_RSSI_POLL                        = BASE + 83;
    /* Set up packet filtering */
    static final int CMD_START_PACKET_FILTERING           = BASE + 84;
    /* Clear packet filter */
    static final int CMD_STOP_PACKET_FILTERING            = BASE + 85;
    /* Enable suspend mode optimizations in the driver */
    static final int CMD_SET_SUSPEND_OPT_ENABLED          = BASE + 86;
    /* When there are no saved networks, we do a periodic scan to notify user of
     * an open network */
    static final int CMD_NO_NETWORKS_PERIODIC_SCAN        = BASE + 88;

    /* arg1 values to CMD_STOP_PACKET_FILTERING and CMD_START_PACKET_FILTERING */
    static final int MULTICAST_V6  = 1;
    static final int MULTICAST_V4  = 0;

   /* Set the frequency band */
    static final int CMD_SET_FREQUENCY_BAND               = BASE + 90;
    /* Enable background scan for configured networks */
    static final int CMD_ENABLE_BACKGROUND_SCAN           = BASE + 91;

    /* Commands from/to the SupplicantStateTracker */
    /* Reset the supplicant state tracker */
    static final int CMD_RESET_SUPPLICANT_STATE           = BASE + 111;

    /* P2p commands */
    /* We are ok with no response here since we wont do much with it anyway */
    public static final int CMD_ENABLE_P2P                = BASE + 131;
    /* In order to shut down supplicant cleanly, we wait till p2p has
     * been disabled */
    public static final int CMD_DISABLE_P2P_REQ           = BASE + 132;
    public static final int CMD_DISABLE_P2P_RSP           = BASE + 133;

    private static final int CONNECT_MODE   = 1;
    private static final int SCAN_ONLY_MODE = 2;

    private static final int SCAN_ACTIVE = 1;
    private static final int SCAN_PASSIVE = 2;

    private static final int SUCCESS = 1;
    private static final int FAILURE = -1;

    /* Phone in emergency call back mode */
    private static final int IN_ECM_STATE = 1;
    private static final int NOT_IN_ECM_STATE = 0;

    /**
     * The maximum number of times we will retry a connection to an access point
     * for which we have failed in acquiring an IP address from DHCP. A value of
     * N means that we will make N+1 connection attempts in all.
     * <p>
     * See {@link Settings.Secure#WIFI_MAX_DHCP_RETRY_COUNT}. This is the default
     * value if a Settings value is not present.
     */
    private static final int DEFAULT_MAX_DHCP_RETRIES = 9;

    /* Tracks if suspend optimizations need to be disabled by DHCP,
     * screen or due to high perf mode.
     * When any of them needs to disable it, we keep the suspend optimizations
     * disabled
     */
    private int mSuspendOptNeedsDisabled = 0;

    private static final int SUSPEND_DUE_TO_DHCP       = 1;
    private static final int SUSPEND_DUE_TO_HIGH_PERF  = 1<<1;
    private static final int SUSPEND_DUE_TO_SCREEN     = 1<<2;

    /* Tracks if user has enabled suspend optimizations through settings */
    private AtomicBoolean mUserWantsSuspendOpt = new AtomicBoolean(true);

    /**
     * Default framework scan interval in milliseconds. This is used in the scenario in which
     * wifi chipset does not support background scanning to set up a
     * periodic wake up scan so that the device can connect to a new access
     * point on the move. {@link Settings.Global#WIFI_FRAMEWORK_SCAN_INTERVAL_MS} can
     * override this.
     */
    private final int mDefaultFrameworkScanIntervalMs;

    /**
     * Supplicant scan interval in milliseconds.
     * Comes from {@link Settings.Global#WIFI_SUPPLICANT_SCAN_INTERVAL_MS} or
     * from the default config if the setting is not set
     */
    private long mSupplicantScanIntervalMs;

    /**
     * Minimum time interval between enabling all networks.
     * A device can end up repeatedly connecting to a bad network on screen on/off toggle
     * due to enabling every time. We add a threshold to avoid this.
     */
    private static final int MIN_INTERVAL_ENABLE_ALL_NETWORKS_MS = 10 * 60 * 1000; /* 10 minutes */
    private long mLastEnableAllNetworksTime;

    /**
     * Starting and shutting down driver too quick causes problems leading to driver
     * being in a bad state. Delay driver stop.
     */
    private final int mDriverStopDelayMs;
    private int mDelayedStopCounter;
    private boolean mInDelayedStop = false;

    private static final int MIN_RSSI = -200;
    private static final int MAX_RSSI = 256;

    /* Default parent state */
    private State mDefaultState = new DefaultState();
    /* Temporary initial state */
    private State mInitialState = new InitialState();
    /* Unloading the driver */
    private State mDriverUnloadingState = new DriverUnloadingState();
    /* Loading the driver */
    private State mDriverUnloadedState = new DriverUnloadedState();
    /* Driver load/unload failed */
    private State mDriverFailedState = new DriverFailedState();
    /* Driver loading */
    private State mDriverLoadingState = new DriverLoadingState();
    /* Driver loaded */
    private State mDriverLoadedState = new DriverLoadedState();
    /* Driver loaded, waiting for supplicant to start */
    private State mSupplicantStartingState = new SupplicantStartingState();
    /* Driver loaded and supplicant ready */
    private State mSupplicantStartedState = new SupplicantStartedState();
    /* Waiting for supplicant to stop and monitor to exit */
    private State mSupplicantStoppingState = new SupplicantStoppingState();
    /* Driver start issued, waiting for completed event */
    private State mDriverStartingState = new DriverStartingState();
    /* Driver started */
    private State mDriverStartedState = new DriverStartedState();
    /* Wait until p2p is disabled
     * This is a special state which is entered right after we exit out of DriverStartedState
     * before transitioning to another state.
     */
    private State mWaitForP2pDisableState = new WaitForP2pDisableState();
    /* Driver stopping */
    private State mDriverStoppingState = new DriverStoppingState();
    /* Driver stopped */
    private State mDriverStoppedState = new DriverStoppedState();
    /* Scan for networks, no connection will be established */
    private State mScanModeState = new ScanModeState();
    /* Connecting to an access point */
    private State mConnectModeState = new ConnectModeState();
    /* Connected at 802.11 (L2) level */
    private State mL2ConnectedState = new L2ConnectedState();
    /* fetching IP after connection to access point (assoc+auth complete) */
    private State mObtainingIpState = new ObtainingIpState();
    /* Waiting for link quality verification to be complete */
    private State mVerifyingLinkState = new VerifyingLinkState();
    /* Waiting for captive portal check to be complete */
    private State mCaptivePortalCheckState = new CaptivePortalCheckState();
    /* Connected with IP addr */
    private State mConnectedState = new ConnectedState();
    /* disconnect issued, waiting for network disconnect confirmation */
    private State mDisconnectingState = new DisconnectingState();
    /* Network is not connected, supplicant assoc+auth is not complete */
    private State mDisconnectedState = new DisconnectedState();
    /* Waiting for WPS to be completed*/
    private State mWpsRunningState = new WpsRunningState();

    /* Soft ap is starting up */
    private State mSoftApStartingState = new SoftApStartingState();
    /* Soft ap is running */
    private State mSoftApStartedState = new SoftApStartedState();
    /* Soft ap is running and we are waiting for tether notification */
    private State mTetheringState = new TetheringState();
    /* Soft ap is running and we are tethered through connectivity service */
    private State mTetheredState = new TetheredState();
    /* Waiting for untether confirmation to stop soft Ap */
    private State mSoftApStoppingState = new SoftApStoppingState();

    private class TetherStateChange {
        ArrayList<String> available;
        ArrayList<String> active;
        TetherStateChange(ArrayList<String> av, ArrayList<String> ac) {
            available = av;
            active = ac;
        }
    }


    /**
     * One of  {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     *
     */
    private final AtomicInteger mWifiState = new AtomicInteger(WIFI_STATE_DISABLED);

    /**
     * One of  {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *         {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *         {@link WifiManager#WIFI_AP_STATE_FAILED}
     *
     */
    private final AtomicInteger mWifiApState = new AtomicInteger(WIFI_AP_STATE_DISABLED);

    private final AtomicInteger mLastEnableUid = new AtomicInteger(Process.myUid());
    private final AtomicInteger mLastApEnableUid = new AtomicInteger(Process.myUid());

    private static final int SCAN_REQUEST = 0;
    private static final String ACTION_START_SCAN =
        "com.android.server.WifiManager.action.START_SCAN";

    private static final String DELAYED_STOP_COUNTER = "DelayedStopCounter";
    private static final int DRIVER_STOP_REQUEST = 0;
    private static final String ACTION_DELAYED_DRIVER_STOP =
        "com.android.server.WifiManager.action.DELAYED_DRIVER_STOP";

    /**
     * Keep track of whether WIFI is running.
     */
    private boolean mIsRunning = false;

    /**
     * Keep track of whether we last told the battery stats we had started.
     */
    private boolean mReportedRunning = false;

    /**
     * Most recently set source of starting WIFI.
     */
    private final WorkSource mRunningWifiUids = new WorkSource();

    /**
     * The last reported UIDs that were responsible for starting WIFI.
     */
    private final WorkSource mLastRunningWifiUids = new WorkSource();

    private final IBatteryStats mBatteryStats;

    public WifiStateMachine(Context context, String wlanInterface) {
        super(TAG);

        mContext = context;
        mInterfaceName = wlanInterface;

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, NETWORKTYPE, "");
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batteryinfo"));

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mWifiNative = new WifiNative(mInterfaceName);
        mWifiConfigStore = new WifiConfigStore(context, mWifiNative);
        mWifiMonitor = new WifiMonitor(this, mWifiNative);
        mDhcpInfoInternal = new DhcpInfoInternal();
        mWifiInfo = new WifiInfo();
        mSupplicantStateTracker = new SupplicantStateTracker(context, this, mWifiConfigStore,
                getHandler());
        mLinkProperties = new LinkProperties();

        WifiApConfigStore wifiApConfigStore = WifiApConfigStore.makeWifiApConfigStore(
                context, getHandler());
        wifiApConfigStore.loadApConfiguration();
        mWifiApConfigChannel.connectSync(mContext, getHandler(), wifiApConfigStore.getMessenger());

        mNetworkInfo.setIsAvailable(false);
        mLinkProperties.clear();
        mLastBssid = null;
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSignalLevel = -1;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent scanIntent = new Intent(ACTION_START_SCAN, null);
        mScanIntent = PendingIntent.getBroadcast(mContext, SCAN_REQUEST, scanIntent, 0);

        mDefaultFrameworkScanIntervalMs = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_scan_interval);

        mDriverStopDelayMs = mContext.getResources().getInteger(
                R.integer.config_wifi_driver_stop_delay);

        mBackgroundScanSupported = mContext.getResources().getBoolean(
                R.bool.config_wifi_background_scan_support);

        mPrimaryDeviceType = mContext.getResources().getString(
                R.string.config_wifi_p2p_device_type);

        mUserWantsSuspendOpt.set(Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);

        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    ArrayList<String> available = intent.getStringArrayListExtra(
                            ConnectivityManager.EXTRA_AVAILABLE_TETHER);
                    ArrayList<String> active = intent.getStringArrayListExtra(
                            ConnectivityManager.EXTRA_ACTIVE_TETHER);
                    sendMessage(CMD_TETHER_STATE_CHANGE, new TetherStateChange(available, active));
                }
            },new IntentFilter(ConnectivityManager.ACTION_TETHER_STATE_CHANGED));

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        startScan(false);
                    }
                },
                new IntentFilter(ACTION_START_SCAN));

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if (action.equals(Intent.ACTION_SCREEN_ON)) {
                    handleScreenStateChanged(true);
                } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    handleScreenStateChanged(false);
                }
            }
        };
        mContext.registerReceiver(screenReceiver, screenFilter);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                       int counter = intent.getIntExtra(DELAYED_STOP_COUNTER, 0);
                       sendMessage(obtainMessage(CMD_DELAYED_STOP_DRIVER, counter, 0));
                    }
                },
                new IntentFilter(ACTION_DELAYED_DRIVER_STOP));

        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED), false,
                new ContentObserver(getHandler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mUserWantsSuspendOpt.set(Settings.Global.getInt(mContext.getContentResolver(),
                                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);
                    }
                });

        mScanResultCache = new LruCache<String, ScanResult>(SCAN_RESULT_CACHE_SIZE);

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mSuspendWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiSuspend");
        mSuspendWakeLock.setReferenceCounted(false);

        addState(mDefaultState);
            addState(mInitialState, mDefaultState);
            addState(mDriverUnloadingState, mDefaultState);
            addState(mDriverUnloadedState, mDefaultState);
                addState(mDriverFailedState, mDriverUnloadedState);
            addState(mDriverLoadingState, mDefaultState);
            addState(mDriverLoadedState, mDefaultState);
            addState(mSupplicantStartingState, mDefaultState);
            addState(mSupplicantStartedState, mDefaultState);
                addState(mDriverStartingState, mSupplicantStartedState);
                addState(mDriverStartedState, mSupplicantStartedState);
                    addState(mScanModeState, mDriverStartedState);
                    addState(mConnectModeState, mDriverStartedState);
                        addState(mL2ConnectedState, mConnectModeState);
                            addState(mObtainingIpState, mL2ConnectedState);
                            addState(mVerifyingLinkState, mL2ConnectedState);
                            addState(mCaptivePortalCheckState, mL2ConnectedState);
                            addState(mConnectedState, mL2ConnectedState);
                        addState(mDisconnectingState, mConnectModeState);
                        addState(mDisconnectedState, mConnectModeState);
                        addState(mWpsRunningState, mConnectModeState);
                addState(mWaitForP2pDisableState, mSupplicantStartedState);
                addState(mDriverStoppingState, mSupplicantStartedState);
                addState(mDriverStoppedState, mSupplicantStartedState);
            addState(mSupplicantStoppingState, mDefaultState);
            addState(mSoftApStartingState, mDefaultState);
            addState(mSoftApStartedState, mDefaultState);
                addState(mTetheringState, mSoftApStartedState);
                addState(mTetheredState, mSoftApStartedState);
            addState(mSoftApStoppingState, mDefaultState);

        setInitialState(mInitialState);

        setLogRecSize(100);
        if (DBG) setDbg(true);

        //start the state machine
        start();
    }

    /*********************************************************
     * Methods exposed for public use
     ********************************************************/

    public Messenger getMessenger() {
        return new Messenger(getHandler());
    }
    /**
     * TODO: doc
     */
    public boolean syncPingSupplicant(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_PING_SUPPLICANT);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * TODO: doc
     */
    public void startScan(boolean forceActive) {
        sendMessage(obtainMessage(CMD_START_SCAN, forceActive ?
                SCAN_ACTIVE : SCAN_PASSIVE, 0));
    }

    /**
     * TODO: doc
     */
    public void setWifiEnabled(boolean enable) {
        mLastEnableUid.set(Binder.getCallingUid());
        if (enable) {
            /* Argument is the state that is entered prior to load */
            sendMessage(obtainMessage(CMD_LOAD_DRIVER, WIFI_STATE_ENABLING, 0));
            sendMessage(CMD_START_SUPPLICANT);
        } else {
            sendMessage(CMD_STOP_SUPPLICANT);
            /* Argument is the state that is entered upon success */
            sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_STATE_DISABLED, 0));
        }
    }

    /**
     * TODO: doc
     */
    public void setWifiApEnabled(WifiConfiguration wifiConfig, boolean enable) {
        mLastApEnableUid.set(Binder.getCallingUid());
        if (enable) {
            /* Argument is the state that is entered prior to load */
            sendMessage(obtainMessage(CMD_LOAD_DRIVER, WIFI_AP_STATE_ENABLING, 0));
            sendMessage(obtainMessage(CMD_START_AP, wifiConfig));
        } else {
            sendMessage(CMD_STOP_AP);
            /* Argument is the state that is entered upon success */
            sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_AP_STATE_DISABLED, 0));
        }
    }

    public void setWifiApConfiguration(WifiConfiguration config) {
        mWifiApConfigChannel.sendMessage(CMD_SET_AP_CONFIG, config);
    }

    public WifiConfiguration syncGetWifiApConfiguration() {
        Message resultMsg = mWifiApConfigChannel.sendMessageSynchronously(CMD_REQUEST_AP_CONFIG);
        WifiConfiguration ret = (WifiConfiguration) resultMsg.obj;
        resultMsg.recycle();
        return ret;
    }

    /**
     * TODO: doc
     */
    public int syncGetWifiState() {
        return mWifiState.get();
    }

    /**
     * TODO: doc
     */
    public String syncGetWifiStateByName() {
        switch (mWifiState.get()) {
            case WIFI_STATE_DISABLING:
                return "disabling";
            case WIFI_STATE_DISABLED:
                return "disabled";
            case WIFI_STATE_ENABLING:
                return "enabling";
            case WIFI_STATE_ENABLED:
                return "enabled";
            case WIFI_STATE_UNKNOWN:
                return "unknown state";
            default:
                return "[invalid state]";
        }
    }

    /**
     * TODO: doc
     */
    public int syncGetWifiApState() {
        return mWifiApState.get();
    }

    /**
     * TODO: doc
     */
    public String syncGetWifiApStateByName() {
        switch (mWifiApState.get()) {
            case WIFI_AP_STATE_DISABLING:
                return "disabling";
            case WIFI_AP_STATE_DISABLED:
                return "disabled";
            case WIFI_AP_STATE_ENABLING:
                return "enabling";
            case WIFI_AP_STATE_ENABLED:
                return "enabled";
            case WIFI_AP_STATE_FAILED:
                return "failed";
            default:
                return "[invalid state]";
        }
    }

    /**
     * Get status information for the current connection, if any.
     * @return a {@link WifiInfo} object containing information about the current connection
     *
     */
    public WifiInfo syncRequestConnectionInfo() {
        return mWifiInfo;
    }

    public DhcpInfo syncGetDhcpInfo() {
        synchronized (mDhcpInfoInternal) {
            return mDhcpInfoInternal.makeDhcpInfo();
        }
    }

    /**
     * TODO: doc
     */
    public void setDriverStart(boolean enable, boolean ecm) {
        if (enable) {
            sendMessage(CMD_START_DRIVER);
        } else {
            sendMessage(obtainMessage(CMD_STOP_DRIVER, ecm ? IN_ECM_STATE : NOT_IN_ECM_STATE, 0));
        }
    }

    public void captivePortalCheckComplete() {
        sendMessage(obtainMessage(CMD_CAPTIVE_CHECK_COMPLETE));
    }

    /**
     * TODO: doc
     */
    public void setScanOnlyMode(boolean enable) {
      if (enable) {
          sendMessage(obtainMessage(CMD_SET_SCAN_MODE, SCAN_ONLY_MODE, 0));
      } else {
          sendMessage(obtainMessage(CMD_SET_SCAN_MODE, CONNECT_MODE, 0));
      }
    }

    /**
     * TODO: doc
     */
    public void setScanType(boolean active) {
      if (active) {
          sendMessage(obtainMessage(CMD_SET_SCAN_TYPE, SCAN_ACTIVE, 0));
      } else {
          sendMessage(obtainMessage(CMD_SET_SCAN_TYPE, SCAN_PASSIVE, 0));
      }
    }

    /**
     * TODO: doc
     */
    public List<ScanResult> syncGetScanResultsList() {
        synchronized (mScanResultCache) {
            List<ScanResult> scanList = new ArrayList<ScanResult>();
            for(ScanResult result: mScanResults) {
                scanList.add(new ScanResult(result));
            }
            return scanList;
        }
    }

    /**
     * Disconnect from Access Point
     */
    public void disconnectCommand() {
        sendMessage(CMD_DISCONNECT);
    }

    /**
     * Initiate a reconnection to AP
     */
    public void reconnectCommand() {
        sendMessage(CMD_RECONNECT);
    }

    /**
     * Initiate a re-association to AP
     */
    public void reassociateCommand() {
        sendMessage(CMD_REASSOCIATE);
    }

    /**
     * Add a network synchronously
     *
     * @return network id of the new network
     */
    public int syncAddOrUpdateNetwork(AsyncChannel channel, WifiConfiguration config) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ADD_OR_UPDATE_NETWORK, config);
        int result = resultMsg.arg1;
        resultMsg.recycle();
        return result;
    }

    public List<WifiConfiguration> syncGetConfiguredNetworks(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CONFIGURED_NETWORKS);
        List<WifiConfiguration> result = (List<WifiConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Delete a network
     *
     * @param networkId id of the network to be removed
     */
    public boolean syncRemoveNetwork(AsyncChannel channel, int networkId) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_REMOVE_NETWORK, networkId);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Enable a network
     *
     * @param netId network id of the network
     * @param disableOthers true, if all other networks have to be disabled
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncEnableNetwork(AsyncChannel channel, int netId, boolean disableOthers) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ENABLE_NETWORK, netId,
                disableOthers ? 1 : 0);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Disable a network
     *
     * @param netId network id of the network
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncDisableNetwork(AsyncChannel channel, int netId) {
        Message resultMsg = channel.sendMessageSynchronously(WifiManager.DISABLE_NETWORK, netId);
        boolean result = (resultMsg.arg1 != WifiManager.DISABLE_NETWORK_FAILED);
        resultMsg.recycle();
        return result;
    }

    /**
     * Blacklist a BSSID. This will avoid the AP if there are
     * alternate APs to connect
     *
     * @param bssid BSSID of the network
     */
    public void addToBlacklist(String bssid) {
        sendMessage(obtainMessage(CMD_BLACKLIST_NETWORK, bssid));
    }

    /**
     * Clear the blacklist list
     *
     */
    public void clearBlacklist() {
        sendMessage(obtainMessage(CMD_CLEAR_BLACKLIST));
    }

    public void enableRssiPolling(boolean enabled) {
       sendMessage(obtainMessage(CMD_ENABLE_RSSI_POLL, enabled ? 1 : 0, 0));
    }

    public void enableBackgroundScanCommand(boolean enabled) {
       sendMessage(obtainMessage(CMD_ENABLE_BACKGROUND_SCAN, enabled ? 1 : 0, 0));
    }

    public void enableAllNetworks() {
        sendMessage(CMD_ENABLE_ALL_NETWORKS);
    }

    /**
     * Start filtering Multicast v4 packets
     */
    public void startFilteringMulticastV4Packets() {
        mFilteringMulticastV4Packets.set(true);
        sendMessage(obtainMessage(CMD_START_PACKET_FILTERING, MULTICAST_V4, 0));
    }

    /**
     * Stop filtering Multicast v4 packets
     */
    public void stopFilteringMulticastV4Packets() {
        mFilteringMulticastV4Packets.set(false);
        sendMessage(obtainMessage(CMD_STOP_PACKET_FILTERING, MULTICAST_V4, 0));
    }

    /**
     * Start filtering Multicast v4 packets
     */
    public void startFilteringMulticastV6Packets() {
        sendMessage(obtainMessage(CMD_START_PACKET_FILTERING, MULTICAST_V6, 0));
    }

    /**
     * Stop filtering Multicast v4 packets
     */
    public void stopFilteringMulticastV6Packets() {
        sendMessage(obtainMessage(CMD_STOP_PACKET_FILTERING, MULTICAST_V6, 0));
    }

    /**
     * Set high performance mode of operation.
     * Enabling would set active power mode and disable suspend optimizations;
     * disabling would set auto power mode and enable suspend optimizations
     * @param enable true if enable, false otherwise
     */
    public void setHighPerfModeEnabled(boolean enable) {
        sendMessage(obtainMessage(CMD_SET_HIGH_PERF_MODE, enable ? 1 : 0, 0));
    }

    /**
     * Set the country code
     * @param countryCode following ISO 3166 format
     * @param persist {@code true} if the setting should be remembered.
     */
    public void setCountryCode(String countryCode, boolean persist) {
        if (persist) {
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.WIFI_COUNTRY_CODE,
                    countryCode);
        }
        sendMessage(obtainMessage(CMD_SET_COUNTRY_CODE, countryCode));
    }

    /**
     * Set the operational frequency band
     * @param band
     * @param persist {@code true} if the setting should be remembered.
     */
    public void setFrequencyBand(int band, boolean persist) {
        if (persist) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.WIFI_FREQUENCY_BAND,
                    band);
        }
        sendMessage(obtainMessage(CMD_SET_FREQUENCY_BAND, band, 0));
    }

    /**
     * Returns the operational frequency band
     */
    public int getFrequencyBand() {
        return mFrequencyBand.get();
    }

    /**
     * Returns the wifi configuration file
     */
    public String getConfigFile() {
        return mWifiConfigStore.getConfigFile();
    }

    /**
     * Send a message indicating bluetooth adapter connection state changed
     */
    public void sendBluetoothAdapterStateChange(int state) {
        sendMessage(obtainMessage(CMD_BLUETOOTH_ADAPTER_STATE_CHANGE, state, 0));
    }

    /**
     * Save configuration on supplicant
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * TODO: deprecate this
     */
    public boolean syncSaveConfig(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_SAVE_CONFIG);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public void updateBatteryWorkSource(WorkSource newSource) {
        synchronized (mRunningWifiUids) {
            try {
                if (newSource != null) {
                    mRunningWifiUids.set(newSource);
                }
                if (mIsRunning) {
                    if (mReportedRunning) {
                        // If the work source has changed since last time, need
                        // to remove old work from battery stats.
                        if (mLastRunningWifiUids.diff(mRunningWifiUids)) {
                            mBatteryStats.noteWifiRunningChanged(mLastRunningWifiUids,
                                    mRunningWifiUids);
                            mLastRunningWifiUids.set(mRunningWifiUids);
                        }
                    } else {
                        // Now being started, report it.
                        mBatteryStats.noteWifiRunning(mRunningWifiUids);
                        mLastRunningWifiUids.set(mRunningWifiUids);
                        mReportedRunning = true;
                    }
                } else {
                    if (mReportedRunning) {
                        // Last reported we were running, time to stop.
                        mBatteryStats.noteWifiStopped(mLastRunningWifiUids);
                        mLastRunningWifiUids.clear();
                        mReportedRunning = false;
                    }
                }
                mWakeLock.setWorkSource(newSource);
            } catch (RemoteException ignore) {
            }
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String LS = System.getProperty("line.separator");
        sb.append("current HSM state: ").append(getCurrentState().getName()).append(LS);
        sb.append("mLinkProperties ").append(mLinkProperties).append(LS);
        sb.append("mWifiInfo ").append(mWifiInfo).append(LS);
        sb.append("mDhcpInfoInternal ").append(mDhcpInfoInternal).append(LS);
        sb.append("mNetworkInfo ").append(mNetworkInfo).append(LS);
        sb.append("mLastSignalLevel ").append(mLastSignalLevel).append(LS);
        sb.append("mLastBssid ").append(mLastBssid).append(LS);
        sb.append("mLastNetworkId ").append(mLastNetworkId).append(LS);
        sb.append("mReconnectCount ").append(mReconnectCount).append(LS);
        sb.append("mIsScanMode ").append(mIsScanMode).append(LS);
        sb.append("mUserWantsSuspendOpt ").append(mUserWantsSuspendOpt).append(LS);
        sb.append("mSuspendOptNeedsDisabled ").append(mSuspendOptNeedsDisabled).append(LS);
        sb.append("Supplicant status").append(LS)
                .append(mWifiNative.status()).append(LS).append(LS);

        sb.append(mWifiConfigStore.dump());
        return sb.toString();
    }

    @Override
    protected boolean recordLogRec(Message msg) {
        //Ignore screen on/off & common messages when driver has started
        if (getCurrentState() == mConnectedState || getCurrentState() == mDisconnectedState) {
            switch (msg.what) {
                case CMD_LOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_START_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_HIGH_PERF_MODE:
                case CMD_SET_SUSPEND_OPT_ENABLED:
                case CMD_ENABLE_BACKGROUND_SCAN:
                case CMD_ENABLE_ALL_NETWORKS:
                return false;
            }
        }

        switch (msg.what) {
            case CMD_START_SCAN:
            case CMD_ENABLE_RSSI_POLL:
            case CMD_RSSI_POLL:
            case CMD_DELAYED_STOP_DRIVER:
            case WifiMonitor.SCAN_RESULTS_EVENT:
            case WifiManager.RSSI_PKTCNT_FETCH:
                return false;
            default:
                return true;
        }
    }

    /*********************************************************
     * Internal private functions
     ********************************************************/

    private void handleScreenStateChanged(boolean screenOn) {
        if (DBG) log("handleScreenStateChanged: " + screenOn);
        enableRssiPolling(screenOn);
        if (mBackgroundScanSupported) {
            enableBackgroundScanCommand(screenOn == false);
        }

        if (screenOn) enableAllNetworks();
        if (mUserWantsSuspendOpt.get()) {
            if (screenOn) {
                sendMessage(obtainMessage(CMD_SET_SUSPEND_OPT_ENABLED, 0, 0));
            } else {
                //Allow 2s for suspend optimizations to be set
                mSuspendWakeLock.acquire(2000);
                sendMessage(obtainMessage(CMD_SET_SUSPEND_OPT_ENABLED, 1, 0));
            }
        }
        mScreenBroadcastReceived.set(true);
    }

    private void checkAndSetConnectivityInstance() {
        if (mCm == null) {
            mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
    }

    private boolean startTethering(ArrayList<String> available) {

        boolean wifiAvailable = false;

        checkAndSetConnectivityInstance();

        String[] wifiRegexs = mCm.getTetherableWifiRegexs();

        for (String intf : available) {
            for (String regex : wifiRegexs) {
                if (intf.matches(regex)) {

                    InterfaceConfiguration ifcg = null;
                    try {
                        ifcg = mNwService.getInterfaceConfig(intf);
                        if (ifcg != null) {
                            /* IP/netmask: 192.168.43.1/255.255.255.0 */
                            ifcg.setLinkAddress(new LinkAddress(
                                    NetworkUtils.numericToInetAddress("192.168.43.1"), 24));
                            ifcg.setInterfaceUp();

                            mNwService.setInterfaceConfig(intf, ifcg);
                        }
                    } catch (Exception e) {
                        loge("Error configuring interface " + intf + ", :" + e);
                        return false;
                    }

                    if(mCm.tether(intf) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                        loge("Error tethering on " + intf);
                        return false;
                    }
                    mTetherInterfaceName = intf;
                    return true;
                }
            }
        }
        // We found no interfaces to tether
        return false;
    }

    private void stopTethering() {

        checkAndSetConnectivityInstance();

        /* Clear the interface config to allow dhcp correctly configure new
           ip settings */
        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNwService.getInterfaceConfig(mTetherInterfaceName);
            if (ifcg != null) {
                ifcg.setLinkAddress(
                        new LinkAddress(NetworkUtils.numericToInetAddress("0.0.0.0"), 0));
                mNwService.setInterfaceConfig(mTetherInterfaceName, ifcg);
            }
        } catch (Exception e) {
            loge("Error resetting interface " + mTetherInterfaceName + ", :" + e);
        }

        if (mCm.untether(mTetherInterfaceName) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            loge("Untether initiate failed!");
        }
    }

    private boolean isWifiTethered(ArrayList<String> active) {

        checkAndSetConnectivityInstance();

        String[] wifiRegexs = mCm.getTetherableWifiRegexs();
        for (String intf : active) {
            for (String regex : wifiRegexs) {
                if (intf.matches(regex)) {
                    return true;
                }
            }
        }
        // We found no interfaces that are tethered
        return false;
    }

    /**
     * Set the country code from the system setting value, if any.
     */
    private void setCountryCode() {
        String countryCode = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.WIFI_COUNTRY_CODE);
        if (countryCode != null && !countryCode.isEmpty()) {
            setCountryCode(countryCode, false);
        } else {
            //use driver default
        }
    }

    /**
     * Set the frequency band from the system setting value, if any.
     */
    private void setFrequencyBand() {
        int band = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_FREQUENCY_BAND, WifiManager.WIFI_FREQUENCY_BAND_AUTO);
        setFrequencyBand(band, false);
    }

    private void setSuspendOptimizationsNative(int reason, boolean enabled) {
        if (DBG) log("setSuspendOptimizationsNative: " + reason + " " + enabled);
        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
            /* None of dhcp, screen or highperf need it disabled and user wants it enabled */
            if (mSuspendOptNeedsDisabled == 0 && mUserWantsSuspendOpt.get()) {
                mWifiNative.setSuspendOptimizations(true);
            }
        } else {
            mSuspendOptNeedsDisabled |= reason;
            mWifiNative.setSuspendOptimizations(false);
        }
    }

    private void setSuspendOptimizations(int reason, boolean enabled) {
        if (DBG) log("setSuspendOptimizations: " + reason + " " + enabled);
        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
        } else {
            mSuspendOptNeedsDisabled |= reason;
        }
        if (DBG) log("mSuspendOptNeedsDisabled " + mSuspendOptNeedsDisabled);
    }

    private void setWifiState(int wifiState) {
        final int previousWifiState = mWifiState.get();

        try {
            if (wifiState == WIFI_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiState == WIFI_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            loge("Failed to note battery stats in wifi");
        }

        mWifiState.set(wifiState);

        if (DBG) log("setWifiState: " + syncGetWifiStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, wifiState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, previousWifiState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void setWifiApState(int wifiApState) {
        final int previousWifiApState = mWifiApState.get();

        try {
            if (wifiApState == WIFI_AP_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiApState == WIFI_AP_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            loge("Failed to note battery stats in wifi");
        }

        // Update state
        mWifiApState.set(wifiApState);

        if (DBG) log("setWifiApState: " + syncGetWifiApStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, wifiApState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, previousWifiApState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private static final String BSSID_STR = "bssid=";
    private static final String FREQ_STR = "freq=";
    private static final String LEVEL_STR = "level=";
    private static final String TSF_STR = "tsf=";
    private static final String FLAGS_STR = "flags=";
    private static final String SSID_STR = "ssid=";
    private static final String DELIMITER_STR = "====";
    /**
     * Format:
     * bssid=68:7f:76:d7:1a:6e
     * freq=2412
     * level=-44
     * tsf=1344626243700342
     * flags=[WPA2-PSK-CCMP][WPS][ESS]
     * ssid=zfdy
     * ====
     * bssid=68:5f:74:d7:1a:6f
     * freq=5180
     * level=-73
     * tsf=1344626243700373
     * flags=[WPA2-PSK-CCMP][WPS][ESS]
     * ssid=zuby
     * ====
     */
    private void setScanResults(String scanResults) {
        String bssid = "";
        int level = 0;
        int freq = 0;
        long tsf = 0;
        String flags = "";
        WifiSsid wifiSsid = null;

        if (scanResults == null) {
            return;
        }

        synchronized(mScanResultCache) {
            mScanResults = new ArrayList<ScanResult>();
            String[] lines = scanResults.split("\n");

            for (String line : lines) {
                if (line.startsWith(BSSID_STR)) {
                    bssid = line.substring(BSSID_STR.length());
                } else if (line.startsWith(FREQ_STR)) {
                    try {
                        freq = Integer.parseInt(line.substring(FREQ_STR.length()));
                    } catch (NumberFormatException e) {
                        freq = 0;
                    }
                } else if (line.startsWith(LEVEL_STR)) {
                    try {
                        level = Integer.parseInt(line.substring(LEVEL_STR.length()));
                        /* some implementations avoid negative values by adding 256
                         * so we need to adjust for that here.
                         */
                        if (level > 0) level -= 256;
                    } catch(NumberFormatException e) {
                        level = 0;
                    }
                } else if (line.startsWith(TSF_STR)) {
                    try {
                        tsf = Long.parseLong(line.substring(TSF_STR.length()));
                    } catch (NumberFormatException e) {
                        tsf = 0;
                    }
                } else if (line.startsWith(FLAGS_STR)) {
                    flags = line.substring(FLAGS_STR.length());
                } else if (line.startsWith(SSID_STR)) {
                    wifiSsid = WifiSsid.createFromAsciiEncoded(
                            line.substring(SSID_STR.length()));
                } else if (line.startsWith(DELIMITER_STR)) {
                    if (bssid != null) {
                        String ssid = (wifiSsid != null) ? wifiSsid.toString() : WifiSsid.NONE;
                        String key = bssid + ssid;
                        ScanResult scanResult = mScanResultCache.get(key);
                        if (scanResult != null) {
                            scanResult.level = level;
                            scanResult.wifiSsid = wifiSsid;
                            // Keep existing API
                            scanResult.SSID = (wifiSsid != null) ? wifiSsid.toString() :
                                    WifiSsid.NONE;
                            scanResult.capabilities = flags;
                            scanResult.frequency = freq;
                            scanResult.timestamp = tsf;
                        } else {
                            scanResult =
                                new ScanResult(
                                        wifiSsid, bssid, flags, level, freq, tsf);
                            mScanResultCache.put(key, scanResult);
                        }
                        mScanResults.add(scanResult);
                    }
                    bssid = null;
                    level = 0;
                    freq = 0;
                    tsf = 0;
                    flags = "";
                    wifiSsid = null;
                }
            }
        }
    }

    /*
     * Fetch RSSI and linkspeed on current connection
     */
    private void fetchRssiAndLinkSpeedNative() {
        int newRssi = -1;
        int newLinkSpeed = -1;

        String signalPoll = mWifiNative.signalPoll();

        if (signalPoll != null) {
            String[] lines = signalPoll.split("\n");
            for (String line : lines) {
                String[] prop = line.split("=");
                if (prop.length < 2) continue;
                try {
                    if (prop[0].equals("RSSI")) {
                        newRssi = Integer.parseInt(prop[1]);
                    } else if (prop[0].equals("LINKSPEED")) {
                        newLinkSpeed = Integer.parseInt(prop[1]);
                    }
                } catch (NumberFormatException e) {
                    //Ignore, defaults on rssi and linkspeed are assigned
                }
            }
        }

        if (newRssi != -1 && MIN_RSSI < newRssi && newRssi < MAX_RSSI) { // screen out invalid values
            /* some implementations avoid negative values by adding 256
             * so we need to adjust for that here.
             */
            if (newRssi > 0) newRssi -= 256;
            mWifiInfo.setRssi(newRssi);
            /*
             * Rather then sending the raw RSSI out every time it
             * changes, we precalculate the signal level that would
             * be displayed in the status bar, and only send the
             * broadcast if that much more coarse-grained number
             * changes. This cuts down greatly on the number of
             * broadcasts, at the cost of not informing others
             * interested in RSSI of all the changes in signal
             * level.
             */
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi, WifiManager.RSSI_LEVELS);
            if (newSignalLevel != mLastSignalLevel) {
                sendRssiChangeBroadcast(newRssi);
            }
            mLastSignalLevel = newSignalLevel;
        } else {
            mWifiInfo.setRssi(MIN_RSSI);
        }

        if (newLinkSpeed != -1) {
            mWifiInfo.setLinkSpeed(newLinkSpeed);
        }
    }

    /*
     * Fetch TX packet counters on current connection
     */
    private void fetchPktcntNative(RssiPacketCountInfo info) {
        String pktcntPoll = mWifiNative.pktcntPoll();

        if (pktcntPoll != null) {
            String[] lines = pktcntPoll.split("\n");
            for (String line : lines) {
                String[] prop = line.split("=");
                if (prop.length < 2) continue;
                try {
                    if (prop[0].equals("TXGOOD")) {
                        info.txgood = Integer.parseInt(prop[1]);
                    } else if (prop[0].equals("TXBAD")) {
                        info.txbad = Integer.parseInt(prop[1]);
                    }
                } catch (NumberFormatException e) {
                    //Ignore
                }
            }
        }
    }

    private void configureLinkProperties() {
        if (mWifiConfigStore.isUsingStaticIp(mLastNetworkId)) {
            mLinkProperties = mWifiConfigStore.getLinkProperties(mLastNetworkId);
        } else {
            synchronized (mDhcpInfoInternal) {
                mLinkProperties = mDhcpInfoInternal.makeLinkProperties();
            }
            mLinkProperties.setHttpProxy(mWifiConfigStore.getProxyProperties(mLastNetworkId));
        }
        mLinkProperties.setInterfaceName(mInterfaceName);
        if (DBG) {
            log("netId=" + mLastNetworkId  + " Link configured: " +
                    mLinkProperties.toString());
        }
    }

    private int getMaxDhcpRetries() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                                      Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT,
                                      DEFAULT_MAX_DHCP_RETRIES);
    }

    private void sendScanResultsAvailableBroadcast() {
        Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendNetworkStateChangeBroadcast(String bssid) {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, new LinkProperties (mLinkProperties));
        if (bssid != null)
            intent.putExtra(WifiManager.EXTRA_BSSID, bssid);
        if (mNetworkInfo.getDetailedState() == DetailedState.VERIFYING_POOR_LINK ||
                mNetworkInfo.getDetailedState() == DetailedState.CONNECTED) {
            intent.putExtra(WifiManager.EXTRA_WIFI_INFO, new WifiInfo(mWifiInfo));
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendLinkConfigurationChangedBroadcast() {
        Intent intent = new Intent(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, new LinkProperties(mLinkProperties));
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendSupplicantConnectionChangedBroadcast(boolean connected) {
        Intent intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, connected);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Record the detailed state of a network.
     * @param state the new {@code DetailedState}
     */
    private void setNetworkDetailedState(NetworkInfo.DetailedState state) {
        if (DBG) {
            log("setDetailed state, old ="
                    + mNetworkInfo.getDetailedState() + " and new state=" + state);
        }

        if (state != mNetworkInfo.getDetailedState()) {
            mNetworkInfo.setDetailedState(state, null, mWifiInfo.getSSID());
        }
    }

    private DetailedState getNetworkDetailedState() {
        return mNetworkInfo.getDetailedState();
    }


    private SupplicantState handleSupplicantStateChange(Message message) {
        StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
        SupplicantState state = stateChangeResult.state;
        // Supplicant state change
        // [31-13] Reserved for future use
        // [8 - 0] Supplicant state (as defined in SupplicantState.java)
        // 50023 supplicant_state_changed (custom|1|5)
        EventLog.writeEvent(EVENTLOG_SUPPLICANT_STATE_CHANGED, state.ordinal());
        mWifiInfo.setSupplicantState(state);
        // Network id is only valid when we start connecting
        if (SupplicantState.isConnecting(state)) {
            mWifiInfo.setNetworkId(stateChangeResult.networkId);
        } else {
            mWifiInfo.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
        }

        mWifiInfo.setBSSID(stateChangeResult.BSSID);
        mWifiInfo.setSSID(stateChangeResult.wifiSsid);

        mSupplicantStateTracker.sendMessage(Message.obtain(message));

        return state;
    }

    /**
     * Resets the Wi-Fi Connections by clearing any state, resetting any sockets
     * using the interface, stopping DHCP & disabling interface
     */
    private void handleNetworkDisconnect() {
        if (DBG) log("Stopping DHCP and clearing IP");

        /*
         * stop DHCP
         */
        if (mDhcpStateMachine != null) {
            /* In case we were in middle of DHCP operation
               restore back powermode */
            handlePostDhcpSetup();
            mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
        }

        try {
            mNwService.clearInterfaceAddresses(mInterfaceName);
            mNwService.disableIpv6(mInterfaceName);
        } catch (Exception e) {
            loge("Failed to clear addresses or disable ipv6" + e);
        }

        /* Reset data structures */
        mWifiInfo.setInetAddress(null);
        mWifiInfo.setBSSID(null);
        mWifiInfo.setSSID(null);
        mWifiInfo.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
        mWifiInfo.setRssi(MIN_RSSI);
        mWifiInfo.setLinkSpeed(-1);
        mWifiInfo.setMeteredHint(false);

        setNetworkDetailedState(DetailedState.DISCONNECTED);
        mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.DISCONNECTED);

        /* send event to CM & network change broadcast */
        sendNetworkStateChangeBroadcast(mLastBssid);

        /* Clear network properties */
        mLinkProperties.clear();
        /* Clear IP settings if the network used DHCP */
        if (!mWifiConfigStore.isUsingStaticIp(mLastNetworkId)) {
            mWifiConfigStore.clearIpConfiguration(mLastNetworkId);
        }

        mLastBssid= null;
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    }

    void handlePreDhcpSetup() {
        if (!mBluetoothConnectionActive) {
            /*
             * There are problems setting the Wi-Fi driver's power
             * mode to active when bluetooth coexistence mode is
             * enabled or sense.
             * <p>
             * We set Wi-Fi to active mode when
             * obtaining an IP address because we've found
             * compatibility issues with some routers with low power
             * mode.
             * <p>
             * In order for this active power mode to properly be set,
             * we disable coexistence mode until we're done with
             * obtaining an IP address.  One exception is if we
             * are currently connected to a headset, since disabling
             * coexistence would interrupt that connection.
             */
            // Disable the coexistence mode
            mWifiNative.setBluetoothCoexistenceMode(
                    mWifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED);
        }

        /* Disable power save and suspend optimizations during DHCP */
        // Note: The order here is important for now. Brcm driver changes
        // power settings when we control suspend mode optimizations.
        // TODO: Remove this comment when the driver is fixed.
        setSuspendOptimizationsNative(SUSPEND_DUE_TO_DHCP, false);
        mWifiNative.setPowerSave(false);
    }


    void handlePostDhcpSetup() {
        /* Restore power save and suspend optimizations */
        setSuspendOptimizationsNative(SUSPEND_DUE_TO_DHCP, true);
        mWifiNative.setPowerSave(true);

        // Set the coexistence mode back to its default value
        mWifiNative.setBluetoothCoexistenceMode(
                mWifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE);
    }

    private void handleSuccessfulIpConfiguration(DhcpInfoInternal dhcpInfoInternal) {
        synchronized (mDhcpInfoInternal) {
            mDhcpInfoInternal = dhcpInfoInternal;
        }
        mLastSignalLevel = -1; // force update of signal strength
        mReconnectCount = 0; //Reset IP failure tracking
        mWifiConfigStore.setIpConfiguration(mLastNetworkId, dhcpInfoInternal);
        InetAddress addr = NetworkUtils.numericToInetAddress(dhcpInfoInternal.ipAddress);
        mWifiInfo.setInetAddress(addr);
        mWifiInfo.setMeteredHint(dhcpInfoInternal.hasMeteredHint());
        if (getNetworkDetailedState() == DetailedState.CONNECTED) {
            //DHCP renewal in connected state
            LinkProperties linkProperties = dhcpInfoInternal.makeLinkProperties();
            linkProperties.setHttpProxy(mWifiConfigStore.getProxyProperties(mLastNetworkId));
            linkProperties.setInterfaceName(mInterfaceName);
            if (!linkProperties.equals(mLinkProperties)) {
                if (DBG) {
                    log("Link configuration changed for netId: " + mLastNetworkId
                            + " old: " + mLinkProperties + "new: " + linkProperties);
                }
                mLinkProperties = linkProperties;
                sendLinkConfigurationChangedBroadcast();
            }
        } else {
            configureLinkProperties();
        }
    }

    private void handleFailedIpConfiguration() {
        loge("IP configuration failed");

        mWifiInfo.setInetAddress(null);
        mWifiInfo.setMeteredHint(false);
        /**
         * If we've exceeded the maximum number of retries for DHCP
         * to a given network, disable the network
         */
        int maxRetries = getMaxDhcpRetries();
        // maxRetries == 0 means keep trying forever
        if (maxRetries > 0 && ++mReconnectCount > maxRetries) {
            loge("Failed " +
                    mReconnectCount + " times, Disabling " + mLastNetworkId);
            mWifiConfigStore.disableNetwork(mLastNetworkId,
                    WifiConfiguration.DISABLED_DHCP_FAILURE);
            mReconnectCount = 0;
        }

        /* DHCP times out after about 30 seconds, we do a
         * disconnect and an immediate reconnect to try again
         */
        mWifiNative.disconnect();
        mWifiNative.reconnect();
    }

    /* Current design is to not set the config on a running hostapd but instead
     * stop and start tethering when user changes config on a running access point
     *
     * TODO: Add control channel setup through hostapd that allows changing config
     * on a running daemon
     */
    private void startSoftApWithConfig(final WifiConfiguration config) {
        // start hostapd on a seperate thread
        new Thread(new Runnable() {
            public void run() {
                try {
                    mNwService.startAccessPoint(config, mInterfaceName);
                } catch (Exception e) {
                    loge("Exception in softap start " + e);
                    try {
                        mNwService.stopAccessPoint(mInterfaceName);
                        mNwService.startAccessPoint(config, mInterfaceName);
                    } catch (Exception e1) {
                        loge("Exception in softap re-start " + e1);
                        sendMessage(CMD_START_AP_FAILURE);
                        return;
                    }
                }
                if (DBG) log("Soft AP start successful");
                sendMessage(CMD_START_AP_SUCCESS);
            }
        }).start();
    }

    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiP2pChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                    } else {
                        loge("WifiP2pService connection failure, error=" + message.arg1);
                    }
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    loge("WifiP2pService channel lost, message.arg1 =" + message.arg1);
                    //TODO: Re-establish connection to state machine after a delay
                    //mWifiP2pChannel.connect(mContext, getHandler(), mWifiP2pManager.getMessenger());
                    break;
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1 !=
                            BluetoothAdapter.STATE_DISCONNECTED);
                    break;
                    /* Synchronous call returns */
                case CMD_PING_SUPPLICANT:
                case CMD_ENABLE_NETWORK:
                case CMD_ADD_OR_UPDATE_NETWORK:
                case CMD_REMOVE_NETWORK:
                case CMD_SAVE_CONFIG:
                    replyToMessage(message, message.what, FAILURE);
                    break;
                case CMD_GET_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what, (List<WifiConfiguration>) null);
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    mEnableRssiPolling = (message.arg1 == 1);
                    break;
                case CMD_ENABLE_BACKGROUND_SCAN:
                    mEnableBackgroundScan = (message.arg1 == 1);
                    break;
                case CMD_SET_HIGH_PERF_MODE:
                    if (message.arg1 == 1) {
                        setSuspendOptimizations(SUSPEND_DUE_TO_HIGH_PERF, false);
                    } else {
                        setSuspendOptimizations(SUSPEND_DUE_TO_HIGH_PERF, true);
                    }
                    break;
                    /* Discard */
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_STOP_SUPPLICANT_FAILED:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_DELAYED_STOP_DRIVER:
                case CMD_DRIVER_START_TIMED_OUT:
                case CMD_START_AP:
                case CMD_START_AP_SUCCESS:
                case CMD_START_AP_FAILURE:
                case CMD_STOP_AP:
                case CMD_TETHER_STATE_CHANGE:
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case WifiMonitor.SUP_CONNECTION_EVENT:
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.SCAN_RESULTS_EVENT:
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                case WifiMonitor.WPS_OVERLAP_EVENT:
                case CMD_BLACKLIST_NETWORK:
                case CMD_CLEAR_BLACKLIST:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_RSSI_POLL:
                case CMD_ENABLE_ALL_NETWORKS:
                case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                /* Handled by WifiApConfigStore */
                case CMD_SET_AP_CONFIG:
                case CMD_SET_AP_CONFIG_COMPLETED:
                case CMD_REQUEST_AP_CONFIG:
                case CMD_RESPONSE_AP_CONFIG:
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                case WifiWatchdogStateMachine.GOOD_LINK_DETECTED:
                case CMD_NO_NETWORKS_PERIODIC_SCAN:
                    break;
                case DhcpStateMachine.CMD_ON_QUIT:
                    mDhcpStateMachine = null;
                    break;
                case CMD_SET_SUSPEND_OPT_ENABLED:
                    if (message.arg1 == 1) {
                        mSuspendWakeLock.release();
                        setSuspendOptimizations(SUSPEND_DUE_TO_SCREEN, true);
                    } else {
                        setSuspendOptimizations(SUSPEND_DUE_TO_SCREEN, false);
                    }
                    break;
                case WifiMonitor.DRIVER_HUNG_EVENT:
                    setWifiEnabled(false);
                    setWifiEnabled(true);
                    break;
                case WifiManager.CONNECT_NETWORK:
                    replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.FORGET_NETWORK:
                    replyToMessage(message, WifiManager.FORGET_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.SAVE_NETWORK:
                    replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.START_WPS:
                    replyToMessage(message, WifiManager.WPS_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.CANCEL_WPS:
                    replyToMessage(message, WifiManager.CANCEL_WPS_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.DISABLE_NETWORK:
                    replyToMessage(message, WifiManager.DISABLE_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH:
                    replyToMessage(message, WifiManager.RSSI_PKTCNT_FETCH_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiP2pService.P2P_CONNECTION_CHANGED:
                    NetworkInfo info = (NetworkInfo) message.obj;
                    mP2pConnected.set(info.isConnected());
                    break;
                case WifiP2pService.DISCONNECT_WIFI_REQUEST:
                    mTemporarilyDisconnectWifi = (message.arg1 == 1);
                    replyToMessage(message, WifiP2pService.DISCONNECT_WIFI_RESPONSE);
                    break;
                default:
                    loge("Error! unhandled message" + message);
                    break;
            }
            return HANDLED;
        }
    }

    class InitialState extends State {
        @Override
        //TODO: could move logging into a common class
        public void enter() {
            if (DBG) log(getName() + "\n");
            // [31-8] Reserved for future use
            // [7 - 0] HSM state change
            // 50021 wifi_state_changed (custom|1|5)
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            if (mWifiNative.isDriverLoaded()) {
                transitionTo(mDriverLoadedState);
            }
            else {
                transitionTo(mDriverUnloadedState);
            }

            //Connect to WifiP2pService
            mWifiP2pManager = (WifiP2pManager) mContext.getSystemService(Context.WIFI_P2P_SERVICE);
            mWifiP2pChannel.connect(mContext, getHandler(), mWifiP2pManager.getMessenger());

            /* IPv6 is disabled at boot time and is controlled by framework
             * to be enabled only as long as we are connected to an access point
             *
             * This fixes issues, a few being:
             * - IPv6 addresses and routes stick around after disconnection
             * - When connected, the kernel is unaware and can fail to start IPv6 negotiation
             * - The kernel sometimes starts autoconfiguration when 802.1x is not complete
             */
            try {
                mNwService.disableIpv6(mInterfaceName);
            } catch (RemoteException re) {
                loge("Failed to disable IPv6: " + re);
            } catch (IllegalStateException e) {
                loge("Failed to disable IPv6: " + e);
            }
        }
    }

    class DriverLoadingState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            final Message message = new Message();
            message.copyFrom(getCurrentMessage());
            /* TODO: add a timeout to fail when driver load is hung.
             * Similarly for driver unload.
             */
            new Thread(new Runnable() {
                public void run() {
                    mWakeLock.acquire();
                    //enabling state
                    switch(message.arg1) {
                        case WIFI_STATE_ENABLING:
                            setWifiState(WIFI_STATE_ENABLING);
                            break;
                        case WIFI_AP_STATE_ENABLING:
                            setWifiApState(WIFI_AP_STATE_ENABLING);
                            break;
                    }

                    if(mWifiNative.loadDriver()) {
                        if (DBG) log("Driver load successful");
                        sendMessage(CMD_LOAD_DRIVER_SUCCESS);
                    } else {
                        loge("Failed to load driver!");
                        switch(message.arg1) {
                            case WIFI_STATE_ENABLING:
                                setWifiState(WIFI_STATE_UNKNOWN);
                                break;
                            case WIFI_AP_STATE_ENABLING:
                                setWifiApState(WIFI_AP_STATE_FAILED);
                                break;
                        }
                        sendMessage(CMD_LOAD_DRIVER_FAILURE);
                    }
                    mWakeLock.release();
                }
            }).start();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_LOAD_DRIVER_SUCCESS:
                    transitionTo(mDriverLoadedState);
                    break;
                case CMD_LOAD_DRIVER_FAILURE:
                    transitionTo(mDriverFailedState);
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverLoadedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_UNLOAD_DRIVER:
                    transitionTo(mDriverUnloadingState);
                    break;
                case CMD_START_SUPPLICANT:
                    try {
                        mNwService.wifiFirmwareReload(mInterfaceName, "STA");
                    } catch (Exception e) {
                        loge("Failed to reload STA firmware " + e);
                        // continue
                    }
                   try {
                       //A runtime crash can leave the interface up and
                       //this affects connectivity when supplicant starts up.
                       //Ensure interface is down before a supplicant start.
                        mNwService.setInterfaceDown(mInterfaceName);
                        //Set privacy extensions
                        mNwService.setInterfaceIpv6PrivacyExtensions(mInterfaceName, true);
                    } catch (RemoteException re) {
                        loge("Unable to change interface settings: " + re);
                    } catch (IllegalStateException ie) {
                        loge("Unable to change interface settings: " + ie);
                    }

                    if(mWifiNative.startSupplicant(mP2pSupported)) {
                        if (DBG) log("Supplicant start successful");
                        mWifiMonitor.startMonitoring();
                        transitionTo(mSupplicantStartingState);
                    } else {
                        loge("Failed to start supplicant!");
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_STATE_UNKNOWN, 0));
                    }
                    break;
                case CMD_START_AP:
                    transitionTo(mSoftApStartingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverUnloadingState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            final Message message = new Message();
            message.copyFrom(getCurrentMessage());
            new Thread(new Runnable() {
                public void run() {
                    if (DBG) log(getName() + message.toString() + "\n");
                    mWakeLock.acquire();
                    if(mWifiNative.unloadDriver()) {
                        if (DBG) log("Driver unload successful");
                        sendMessage(CMD_UNLOAD_DRIVER_SUCCESS);

                        switch(message.arg1) {
                            case WIFI_STATE_DISABLED:
                            case WIFI_STATE_UNKNOWN:
                                setWifiState(message.arg1);
                                break;
                            case WIFI_AP_STATE_DISABLED:
                            case WIFI_AP_STATE_FAILED:
                                setWifiApState(message.arg1);
                                break;
                        }
                    } else {
                        loge("Failed to unload driver!");
                        sendMessage(CMD_UNLOAD_DRIVER_FAILURE);

                        switch(message.arg1) {
                            case WIFI_STATE_DISABLED:
                            case WIFI_STATE_UNKNOWN:
                                setWifiState(WIFI_STATE_UNKNOWN);
                                break;
                            case WIFI_AP_STATE_DISABLED:
                            case WIFI_AP_STATE_FAILED:
                                setWifiApState(WIFI_AP_STATE_FAILED);
                                break;
                        }
                    }
                    mWakeLock.release();
                }
            }).start();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_UNLOAD_DRIVER_SUCCESS:
                    transitionTo(mDriverUnloadedState);
                    break;
                case CMD_UNLOAD_DRIVER_FAILURE:
                    transitionTo(mDriverFailedState);
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverUnloadedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_LOAD_DRIVER:
                    transitionTo(mDriverLoadingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverFailedState extends State {
        @Override
        public void enter() {
            loge(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            return NOT_HANDLED;
        }
    }


    class SupplicantStartingState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }

        private void initializeWpsDetails() {
            String detail;
            detail = SystemProperties.get("ro.product.name", "");
            if (!mWifiNative.setDeviceName(detail)) {
                loge("Failed to set device name " +  detail);
            }
            detail = SystemProperties.get("ro.product.manufacturer", "");
            if (!mWifiNative.setManufacturer(detail)) {
                loge("Failed to set manufacturer " + detail);
            }
            detail = SystemProperties.get("ro.product.model", "");
            if (!mWifiNative.setModelName(detail)) {
                loge("Failed to set model name " + detail);
            }
            detail = SystemProperties.get("ro.product.model", "");
            if (!mWifiNative.setModelNumber(detail)) {
                loge("Failed to set model number " + detail);
            }
            detail = SystemProperties.get("ro.serialno", "");
            if (!mWifiNative.setSerialNumber(detail)) {
                loge("Failed to set serial number " + detail);
            }
            if (!mWifiNative.setConfigMethods("physical_display virtual_push_button keypad")) {
                loge("Failed to set WPS config methods");
            }
            if (!mWifiNative.setDeviceType(mPrimaryDeviceType)) {
                loge("Failed to set primary device type " + mPrimaryDeviceType);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    if (DBG) log("Supplicant connection established");
                    setWifiState(WIFI_STATE_ENABLED);
                    mSupplicantRestartCount = 0;
                    /* Reset the supplicant state to indicate the supplicant
                     * state is not known at this time */
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    /* Initialize data structures */
                    mLastBssid = null;
                    mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
                    mLastSignalLevel = -1;

                    mWifiInfo.setMacAddress(mWifiNative.getMacAddress());
                    mWifiConfigStore.initialize();
                    initializeWpsDetails();

                    sendSupplicantConnectionChangedBroadcast(true);
                    transitionTo(mDriverStartedState);
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (++mSupplicantRestartCount <= SUPPLICANT_RESTART_TRIES) {
                        loge("Failed to setup control channel, restart supplicant");
                        mWifiNative.killSupplicant();
                        transitionTo(mDriverLoadedState);
                        sendMessageDelayed(CMD_START_SUPPLICANT, SUPPLICANT_RESTART_INTERVAL_MSECS);
                    } else {
                        loge("Failed " + mSupplicantRestartCount +
                                " times to start supplicant, unload driver");
                        mSupplicantRestartCount = 0;
                        transitionTo(mDriverLoadedState);
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_STATE_UNKNOWN, 0));
                    }
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SupplicantStartedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            /* Initialize for connect mode operation at start */
            mIsScanMode = false;
            /* Wifi is available as long as we have a connection to supplicant */
            mNetworkInfo.setIsAvailable(true);

            int defaultInterval = mContext.getResources().getInteger(
                    R.integer.config_wifi_supplicant_scan_interval);

            mSupplicantScanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                    Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                    defaultInterval);

            mWifiNative.setScanInterval((int)mSupplicantScanIntervalMs / 1000);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            WifiConfiguration config;
            switch(message.what) {
                case CMD_STOP_SUPPLICANT:   /* Supplicant stopped by user */
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mSupplicantStoppingState);
                    }
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:  /* Supplicant connection lost */
                    loge("Connection lost, restart supplicant");
                    mWifiNative.killSupplicant();
                    mWifiNative.closeSupplicantConnection();
                    mNetworkInfo.setIsAvailable(false);
                    handleNetworkDisconnect();
                    sendSupplicantConnectionChangedBroadcast(false);
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mDriverLoadedState);
                    }
                    sendMessageDelayed(CMD_START_SUPPLICANT, SUPPLICANT_RESTART_INTERVAL_MSECS);
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                    setScanResults(mWifiNative.scanResults());
                    sendScanResultsAvailableBroadcast();
                    mScanResultIsPending = false;
                    break;
                case CMD_PING_SUPPLICANT:
                    boolean ok = mWifiNative.ping();
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ADD_OR_UPDATE_NETWORK:
                    config = (WifiConfiguration) message.obj;
                    replyToMessage(message, CMD_ADD_OR_UPDATE_NETWORK,
                            mWifiConfigStore.addOrUpdateNetwork(config));
                    break;
                case CMD_REMOVE_NETWORK:
                    ok = mWifiConfigStore.removeNetwork(message.arg1);
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ENABLE_NETWORK:
                    ok = mWifiConfigStore.enableNetwork(message.arg1, message.arg2 == 1);
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ENABLE_ALL_NETWORKS:
                    long time =  android.os.SystemClock.elapsedRealtime();
                    if (time - mLastEnableAllNetworksTime > MIN_INTERVAL_ENABLE_ALL_NETWORKS_MS) {
                        mWifiConfigStore.enableAllNetworks();
                        mLastEnableAllNetworksTime = time;
                    }
                    break;
                case WifiManager.DISABLE_NETWORK:
                    if (mWifiConfigStore.disableNetwork(message.arg1,
                            WifiConfiguration.DISABLED_UNKNOWN_REASON) == true) {
                        replyToMessage(message, WifiManager.DISABLE_NETWORK_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiManager.DISABLE_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case CMD_BLACKLIST_NETWORK:
                    mWifiNative.addToBlacklist((String)message.obj);
                    break;
                case CMD_CLEAR_BLACKLIST:
                    mWifiNative.clearBlacklist();
                    break;
                case CMD_SAVE_CONFIG:
                    ok = mWifiConfigStore.saveConfig();
                    replyToMessage(message, CMD_SAVE_CONFIG, ok ? SUCCESS : FAILURE);

                    // Inform the backup manager about a data change
                    IBackupManager ibm = IBackupManager.Stub.asInterface(
                            ServiceManager.getService(Context.BACKUP_SERVICE));
                    if (ibm != null) {
                        try {
                            ibm.dataChanged("com.android.providers.settings");
                        } catch (Exception e) {
                            // Try again later
                        }
                    }
                    break;
                case CMD_GET_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what,
                            mWifiConfigStore.getConfiguredNetworks());
                    break;
                    /* Cannot start soft AP while in client mode */
                case CMD_START_AP:
                    loge("Failed to start soft AP with a running supplicant");
                    setWifiApState(WIFI_AP_STATE_FAILED);
                    break;
                case CMD_SET_SCAN_MODE:
                    mIsScanMode = (message.arg1 == SCAN_ONLY_MODE);
                    break;
                case WifiManager.SAVE_NETWORK:
                    config = (WifiConfiguration) message.obj;
                    NetworkUpdateResult result = mWifiConfigStore.saveNetwork(config);
                    if (result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
                        replyToMessage(message, WifiManager.SAVE_NETWORK_SUCCEEDED);
                    } else {
                        loge("Failed to save network");
                        replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case WifiManager.FORGET_NETWORK:
                    if (mWifiConfigStore.forgetNetwork(message.arg1)) {
                        replyToMessage(message, WifiManager.FORGET_NETWORK_SUCCEEDED);
                    } else {
                        loge("Failed to forget network");
                        replyToMessage(message, WifiManager.FORGET_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mNetworkInfo.setIsAvailable(false);
        }
    }

    class SupplicantStoppingState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            /* Send any reset commands to supplicant before shutting it down */
            handleNetworkDisconnect();
            if (mDhcpStateMachine != null) {
                mDhcpStateMachine.doQuit();
            }

            if (DBG) log("stopping supplicant");
            if (!mWifiNative.stopSupplicant()) {
                loge("Failed to stop supplicant");
            }

            /* Send ourselves a delayed message to indicate failure after a wait time */
            sendMessageDelayed(obtainMessage(CMD_STOP_SUPPLICANT_FAILED,
                    ++mSupplicantStopFailureToken, 0), SUPPLICANT_RESTART_INTERVAL_MSECS);

            mNetworkInfo.setIsAvailable(false);
            setWifiState(WIFI_STATE_DISABLING);
            sendSupplicantConnectionChangedBroadcast(false);
            mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    loge("Supplicant connection received while stopping");
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (DBG) log("Supplicant connection lost");
                    /* Socket connection can be lost when we do a graceful shutdown
                     * or when the driver is hung. Ensure supplicant is stopped here.
                     */
                    mWifiNative.killSupplicant();
                    mWifiNative.closeSupplicantConnection();
                    transitionTo(mDriverLoadedState);
                    break;
                case CMD_STOP_SUPPLICANT_FAILED:
                    if (message.arg1 == mSupplicantStopFailureToken) {
                        loge("Timed out on a supplicant stop, kill and proceed");
                        mWifiNative.killSupplicant();
                        mWifiNative.closeSupplicantConnection();
                        transitionTo(mDriverLoadedState);
                    }
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStartingState extends State {
        private int mTries;
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            mTries = 1;
            /* Send ourselves a delayed message to start driver a second time */
            sendMessageDelayed(obtainMessage(CMD_DRIVER_START_TIMED_OUT,
                        ++mDriverStartToken, 0), DRIVER_START_TIME_OUT_MSECS);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
               case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    /* If suplicant is exiting out of INTERFACE_DISABLED state into
                     * a state that indicates driver has started, it is ready to
                     * receive driver commands
                     */
                    if (SupplicantState.isDriverActive(state)) {
                        transitionTo(mDriverStartedState);
                    }
                    break;
                case CMD_DRIVER_START_TIMED_OUT:
                    if (message.arg1 == mDriverStartToken) {
                        if (mTries >= 2) {
                            loge("Failed to start driver after " + mTries);
                            transitionTo(mDriverStoppedState);
                        } else {
                            loge("Driver start failed, retrying");
                            mWakeLock.acquire();
                            mWifiNative.startDriver();
                            mWakeLock.release();

                            ++mTries;
                            /* Send ourselves a delayed message to start driver again */
                            sendMessageDelayed(obtainMessage(CMD_DRIVER_START_TIMED_OUT,
                                        ++mDriverStartToken, 0), DRIVER_START_TIME_OUT_MSECS);
                        }
                    }
                    break;
                    /* Queue driver commands & connection events */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                case WifiMonitor.WPS_OVERLAP_EVENT:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStartedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            mIsRunning = true;
            mInDelayedStop = false;
            updateBatteryWorkSource(null);

            /**
             * Enable bluetooth coexistence scan mode when bluetooth connection is active.
             * When this mode is on, some of the low-level scan parameters used by the
             * driver are changed to reduce interference with bluetooth
             */
            mWifiNative.setBluetoothCoexistenceScanMode(mBluetoothConnectionActive);
            /* set country code */
            setCountryCode();
            /* set frequency band of operation */
            setFrequencyBand();
            /* initialize network state */
            setNetworkDetailedState(DetailedState.DISCONNECTED);

            /* Remove any filtering on Multicast v6 at start */
            mWifiNative.stopFilteringMulticastV6Packets();

            /* Reset Multicast v4 filtering state */
            if (mFilteringMulticastV4Packets.get()) {
                mWifiNative.startFilteringMulticastV4Packets();
            } else {
                mWifiNative.stopFilteringMulticastV4Packets();
            }

            if (mIsScanMode) {
                mWifiNative.setScanResultHandling(SCAN_ONLY_MODE);
                mWifiNative.disconnect();
                transitionTo(mScanModeState);
            } else {
                mWifiNative.setScanResultHandling(CONNECT_MODE);
                mWifiNative.reconnect();
                // Status pulls in the current supplicant state and network connection state
                // events over the monitor connection. This helps framework sync up with
                // current supplicant state
                mWifiNative.status();
                transitionTo(mDisconnectedState);
            }

            // We may have missed screen update at boot
            if (mScreenBroadcastReceived.get() == false) {
                PowerManager powerManager = (PowerManager)mContext.getSystemService(
                        Context.POWER_SERVICE);
                handleScreenStateChanged(powerManager.isScreenOn());
            } else {
                // Set the right suspend mode settings
                mWifiNative.setSuspendOptimizations(mSuspendOptNeedsDisabled == 0
                        && mUserWantsSuspendOpt.get());
            }
            mWifiNative.setPowerSave(true);

            if (mP2pSupported) mWifiP2pChannel.sendMessage(WifiStateMachine.CMD_ENABLE_P2P);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
               case CMD_SET_SCAN_TYPE:
                    mSetScanActive = (message.arg1 == SCAN_ACTIVE);
                    mWifiNative.setScanMode(mSetScanActive);
                    break;
                case CMD_START_SCAN:
                    boolean forceActive = (message.arg1 == SCAN_ACTIVE);
                    if (forceActive && !mSetScanActive) {
                        mWifiNative.setScanMode(forceActive);
                    }
                    mWifiNative.scan();
                    if (forceActive && !mSetScanActive) {
                        mWifiNative.setScanMode(mSetScanActive);
                    }
                    mScanResultIsPending = true;
                    break;
                case CMD_SET_COUNTRY_CODE:
                    String country = (String) message.obj;
                    if (DBG) log("set country code " + country);
                    if (!mWifiNative.setCountryCode(country.toUpperCase())) {
                        loge("Failed to set country code " + country);
                    }
                    break;
                case CMD_SET_FREQUENCY_BAND:
                    int band =  message.arg1;
                    if (DBG) log("set frequency band " + band);
                    if (mWifiNative.setBand(band)) {
                        mFrequencyBand.set(band);
                        //Fetch the latest scan results when frequency band is set
                        startScan(true);
                    } else {
                        loge("Failed to set frequency band " + band);
                    }
                    break;
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1 !=
                            BluetoothAdapter.STATE_DISCONNECTED);
                    mWifiNative.setBluetoothCoexistenceScanMode(mBluetoothConnectionActive);
                    break;
                case CMD_STOP_DRIVER:
                    int mode = message.arg1;

                    /* Already doing a delayed stop && not in ecm state */
                    if (mInDelayedStop && mode != IN_ECM_STATE) {
                        if (DBG) log("Already in delayed stop");
                        break;
                    }
                    mInDelayedStop = true;
                    mDelayedStopCounter++;
                    if (DBG) log("Delayed stop message " + mDelayedStopCounter);

                    if (mode == IN_ECM_STATE) {
                        /* send a shut down immediately */
                        sendMessage(obtainMessage(CMD_DELAYED_STOP_DRIVER, mDelayedStopCounter, 0));
                    } else {
                        /* send regular delayed shut down */
                        Intent driverStopIntent = new Intent(ACTION_DELAYED_DRIVER_STOP, null);
                        driverStopIntent.putExtra(DELAYED_STOP_COUNTER, mDelayedStopCounter);
                        mDriverStopIntent = PendingIntent.getBroadcast(mContext,
                                DRIVER_STOP_REQUEST, driverStopIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT);

                        mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                                + mDriverStopDelayMs, mDriverStopIntent);
                    }
                    break;
                case CMD_START_DRIVER:
                    if (mInDelayedStop) {
                        mInDelayedStop = false;
                        mDelayedStopCounter++;
                        mAlarmManager.cancel(mDriverStopIntent);
                        if (DBG) log("Delayed stop ignored due to start");
                    }
                    break;
                case CMD_DELAYED_STOP_DRIVER:
                    if (DBG) log("delayed stop " + message.arg1 + " " + mDelayedStopCounter);
                    if (message.arg1 != mDelayedStopCounter) break;
                    if (getCurrentState() != mDisconnectedState) {
                        mWifiNative.disconnect();
                        handleNetworkDisconnect();
                    }
                    mWakeLock.acquire();
                    mWifiNative.stopDriver();
                    mWakeLock.release();
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mDriverStoppingState);
                    }
                    break;
                case CMD_START_PACKET_FILTERING:
                    if (message.arg1 == MULTICAST_V6) {
                        mWifiNative.startFilteringMulticastV6Packets();
                    } else if (message.arg1 == MULTICAST_V4) {
                        mWifiNative.startFilteringMulticastV4Packets();
                    } else {
                        loge("Illegal arugments to CMD_START_PACKET_FILTERING");
                    }
                    break;
                case CMD_STOP_PACKET_FILTERING:
                    if (message.arg1 == MULTICAST_V6) {
                        mWifiNative.stopFilteringMulticastV6Packets();
                    } else if (message.arg1 == MULTICAST_V4) {
                        mWifiNative.stopFilteringMulticastV4Packets();
                    } else {
                        loge("Illegal arugments to CMD_STOP_PACKET_FILTERING");
                    }
                    break;
                case CMD_SET_SUSPEND_OPT_ENABLED:
                    if (message.arg1 == 1) {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_SCREEN, true);
                        mSuspendWakeLock.release();
                    } else {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_SCREEN, false);
                    }
                    break;
                case CMD_SET_HIGH_PERF_MODE:
                    if (message.arg1 == 1) {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_HIGH_PERF, false);
                    } else {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_HIGH_PERF, true);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
        @Override
        public void exit() {
            if (DBG) log(getName() + "\n");
            mIsRunning = false;
            updateBatteryWorkSource(null);
            mScanResults = new ArrayList<ScanResult>();
        }
    }

    class WaitForP2pDisableState extends State {
        private State mTransitionToState;
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            switch (getCurrentMessage().what) {
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    mTransitionToState = mDriverLoadedState;
                    break;
                case CMD_DELAYED_STOP_DRIVER:
                    mTransitionToState = mDriverStoppingState;
                    break;
                case CMD_STOP_SUPPLICANT:
                    mTransitionToState = mSupplicantStoppingState;
                    break;
                default:
                    mTransitionToState = mDriverStoppingState;
                    break;
            }
            mWifiP2pChannel.sendMessage(WifiStateMachine.CMD_DISABLE_P2P_REQ);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case WifiStateMachine.CMD_DISABLE_P2P_RSP:
                    transitionTo(mTransitionToState);
                    break;
                /* Defer wifi start/shut and driver commands */
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStoppingState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    if (state == SupplicantState.INTERFACE_DISABLED) {
                        transitionTo(mDriverStoppedState);
                    }
                    break;
                    /* Queue driver commands */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStoppedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    SupplicantState state = stateChangeResult.state;
                    // A WEXT bug means that we can be back to driver started state
                    // unexpectedly
                    if (SupplicantState.isDriverActive(state)) {
                        transitionTo(mDriverStartedState);
                    }
                    break;
                case CMD_START_DRIVER:
                    mWakeLock.acquire();
                    mWifiNative.startDriver();
                    mWakeLock.release();
                    transitionTo(mDriverStartingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class ScanModeState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        /* Ignore */
                        return HANDLED;
                    } else {
                        mWifiNative.setScanResultHandling(message.arg1);
                        mWifiNative.reconnect();
                        mIsScanMode = false;
                        transitionTo(mDisconnectedState);
                    }
                    break;
                    /* Ignore */
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class ConnectModeState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            StateChangeResult stateChangeResult;
            switch(message.what) {
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                    mSupplicantStateTracker.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT);
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    // A driver/firmware hang can now put the interface in a down state.
                    // We detect the interface going down and recover from it
                    if (!SupplicantState.isDriverActive(state)) {
                        if (mNetworkInfo.getState() != NetworkInfo.State.DISCONNECTED) {
                            handleNetworkDisconnect();
                        }
                        log("Detected an interface down, restart driver");
                        transitionTo(mDriverStoppedState);
                        sendMessage(CMD_START_DRIVER);
                        break;
                    }

                    // Supplicant can fail to report a NETWORK_DISCONNECTION_EVENT
                    // when authentication times out after a successful connection,
                    // we can figure this from the supplicant state. If supplicant
                    // state is DISCONNECTED, but the mNetworkInfo says we are not
                    // disconnected, we need to handle a disconnection
                    if (state == SupplicantState.DISCONNECTED &&
                            mNetworkInfo.getState() != NetworkInfo.State.DISCONNECTED) {
                        if (DBG) log("Missed CTRL-EVENT-DISCONNECTED, disconnect");
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case WifiP2pService.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiNative.disconnect();
                        mTemporarilyDisconnectWifi = true;
                    } else {
                        mWifiNative.reconnect();
                        mTemporarilyDisconnectWifi = false;
                    }
                    break;
                    /* Do a redundant disconnect without transition */
                case CMD_DISCONNECT:
                    mWifiNative.disconnect();
                    break;
                case CMD_RECONNECT:
                    mWifiNative.reconnect();
                    break;
                case CMD_REASSOCIATE:
                    mWifiNative.reassociate();
                    break;
                case WifiManager.CONNECT_NETWORK:
                    /* The connect message can contain a network id passed as arg1 on message or
                     * or a config passed as obj on message.
                     * For a new network, a config is passed to create and connect.
                     * For an existing network, a network id is passed
                     */
                    int netId = message.arg1;
                    WifiConfiguration config = (WifiConfiguration) message.obj;

                    /* Save the network config */
                    if (config != null) {
                        NetworkUpdateResult result = mWifiConfigStore.saveNetwork(config);
                        netId = result.getNetworkId();
                    }

                    if (mWifiConfigStore.selectNetwork(netId) &&
                            mWifiNative.reconnect()) {
                        /* The state tracker handles enabling networks upon completion/failure */
                        mSupplicantStateTracker.sendMessage(WifiManager.CONNECT_NETWORK);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_SUCCEEDED);
                        /* Expect a disconnection from the old connection */
                        transitionTo(mDisconnectingState);
                    } else {
                        loge("Failed to connect config: " + config + " netId: " + netId);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    }
                    break;
                case WifiManager.START_WPS:
                    WpsInfo wpsInfo = (WpsInfo) message.obj;
                    WpsResult result;
                    switch (wpsInfo.setup) {
                        case WpsInfo.PBC:
                            result = mWifiConfigStore.startWpsPbc(wpsInfo);
                            break;
                        case WpsInfo.KEYPAD:
                            result = mWifiConfigStore.startWpsWithPinFromAccessPoint(wpsInfo);
                            break;
                        case WpsInfo.DISPLAY:
                            result = mWifiConfigStore.startWpsWithPinFromDevice(wpsInfo);
                            break;
                        default:
                            result = new WpsResult(Status.FAILURE);
                            Log.e(TAG, "Invalid setup for WPS");
                            break;
                    }
                    if (result.status == Status.SUCCESS) {
                        replyToMessage(message, WifiManager.START_WPS_SUCCEEDED, result);
                        transitionTo(mWpsRunningState);
                    } else {
                        Log.e(TAG, "Failed to start WPS with config " + wpsInfo.toString());
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                    /* Set the scan setting back to "connect" mode */
                    mWifiNative.setScanResultHandling(CONNECT_MODE);
                    /* Handle scan results */
                    return NOT_HANDLED;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    if (DBG) log("Network connection established");
                    mLastNetworkId = message.arg1;
                    mLastBssid = (String) message.obj;

                    mWifiInfo.setBSSID(mLastBssid);
                    mWifiInfo.setNetworkId(mLastNetworkId);
                    /* send event to CM & network change broadcast */
                    setNetworkDetailedState(DetailedState.OBTAINING_IPADDR);
                    sendNetworkStateChangeBroadcast(mLastBssid);
                    transitionTo(mObtainingIpState);
                    break;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    if (DBG) log("Network connection lost");
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class L2ConnectedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            mRssiPollToken++;
            if (mEnableRssiPolling) {
                sendMessage(obtainMessage(CMD_RSSI_POLL, mRssiPollToken, 0));
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
              case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                  handlePreDhcpSetup();
                  mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE);
                  break;
              case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                  handlePostDhcpSetup();
                  if (message.arg1 == DhcpStateMachine.DHCP_SUCCESS) {
                      if (DBG) log("DHCP successful");
                      handleSuccessfulIpConfiguration((DhcpInfoInternal) message.obj);
                      transitionTo(mVerifyingLinkState);
                  } else if (message.arg1 == DhcpStateMachine.DHCP_FAILURE) {
                      if (DBG) log("DHCP failed");
                      handleFailedIpConfiguration();
                      transitionTo(mDisconnectingState);
                  }
                  break;
                case CMD_DISCONNECT:
                    mWifiNative.disconnect();
                    transitionTo(mDisconnectingState);
                    break;
                case WifiP2pService.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiNative.disconnect();
                        mTemporarilyDisconnectWifi = true;
                        transitionTo(mDisconnectingState);
                    }
                    break;
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        sendMessage(CMD_DISCONNECT);
                        deferMessage(message);
                    }
                    break;
                case CMD_START_SCAN:
                    /* When the network is connected, re-scanning can trigger
                     * a reconnection. Put it in scan-only mode during scan.
                     * When scan results are received, the mode is switched
                     * back to CONNECT_MODE.
                     */
                    mWifiNative.setScanResultHandling(SCAN_ONLY_MODE);
                    /* Have the parent state handle the rest */
                    return NOT_HANDLED;
                    /* Ignore connection to same network */
                case WifiManager.CONNECT_NETWORK:
                    int netId = message.arg1;
                    if (mWifiInfo.getNetworkId() == netId) {
                        break;
                    }
                    return NOT_HANDLED;
                case WifiManager.SAVE_NETWORK:
                    WifiConfiguration config = (WifiConfiguration) message.obj;
                    NetworkUpdateResult result = mWifiConfigStore.saveNetwork(config);
                    if (mWifiInfo.getNetworkId() == result.getNetworkId()) {
                        if (result.hasIpChanged()) {
                            log("Reconfiguring IP on connection");
                            transitionTo(mObtainingIpState);
                        }
                        if (result.hasProxyChanged()) {
                            log("Reconfiguring proxy on connection");
                            configureLinkProperties();
                            sendLinkConfigurationChangedBroadcast();
                        }
                    }

                    if (result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
                        replyToMessage(message, WifiManager.SAVE_NETWORK_SUCCEEDED);
                    } else {
                        loge("Failed to save network");
                        replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                    /* Ignore */
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    break;
                case CMD_RSSI_POLL:
                    if (message.arg1 == mRssiPollToken) {
                        // Get Info and continue polling
                        fetchRssiAndLinkSpeedNative();
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL,
                                mRssiPollToken, 0), POLL_RSSI_INTERVAL_MSECS);
                    } else {
                        // Polling has completed
                    }
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    mEnableRssiPolling = (message.arg1 == 1);
                    mRssiPollToken++;
                    if (mEnableRssiPolling) {
                        // first poll
                        fetchRssiAndLinkSpeedNative();
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL,
                                mRssiPollToken, 0), POLL_RSSI_INTERVAL_MSECS);
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH:
                    RssiPacketCountInfo info = new RssiPacketCountInfo();
                    fetchRssiAndLinkSpeedNative();
                    info.rssi = mWifiInfo.getRssi();
                    fetchPktcntNative(info);
                    replyToMessage(message, WifiManager.RSSI_PKTCNT_FETCH_SUCCEEDED, info);
                    break;
                default:
                    return NOT_HANDLED;
            }

            return HANDLED;
        }

        @Override
        public void exit() {
            /* If a scan result is pending in connected state, the supplicant
             * is in SCAN_ONLY_MODE. Restore CONNECT_MODE on exit
             */
            if (mScanResultIsPending) {
                mWifiNative.setScanResultHandling(CONNECT_MODE);
            }
        }
    }

    class ObtainingIpState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            if (!mWifiConfigStore.isUsingStaticIp(mLastNetworkId)) {
                //start DHCP
                if (mDhcpStateMachine == null) {
                    mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(
                            mContext, WifiStateMachine.this, mInterfaceName);

                }
                mDhcpStateMachine.registerForPreDhcpNotification();
                mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
            } else {
                DhcpInfoInternal dhcpInfoInternal = mWifiConfigStore.getIpConfiguration(
                        mLastNetworkId);
                InterfaceConfiguration ifcg = new InterfaceConfiguration();
                ifcg.setLinkAddress(dhcpInfoInternal.makeLinkAddress());
                ifcg.setInterfaceUp();
                try {
                    mNwService.setInterfaceConfig(mInterfaceName, ifcg);
                    if (DBG) log("Static IP configuration succeeded");
                    sendMessage(CMD_STATIC_IP_SUCCESS, dhcpInfoInternal);
                } catch (RemoteException re) {
                    loge("Static IP configuration failed: " + re);
                    sendMessage(CMD_STATIC_IP_FAILURE);
                } catch (IllegalStateException e) {
                    loge("Static IP configuration failed: " + e);
                    sendMessage(CMD_STATIC_IP_FAILURE);
                }
            }
        }
      @Override
      public boolean processMessage(Message message) {
          if (DBG) log(getName() + message.toString() + "\n");
          switch(message.what) {
            case CMD_STATIC_IP_SUCCESS:
                  handleSuccessfulIpConfiguration((DhcpInfoInternal) message.obj);
                  transitionTo(mVerifyingLinkState);
                  break;
              case CMD_STATIC_IP_FAILURE:
                  handleFailedIpConfiguration();
                  transitionTo(mDisconnectingState);
                  break;
             case WifiManager.SAVE_NETWORK:
                  deferMessage(message);
                  break;
                  /* Defer any power mode changes since we must keep active power mode at DHCP */
              case CMD_SET_HIGH_PERF_MODE:
                  deferMessage(message);
                  break;
              default:
                  return NOT_HANDLED;
          }
          return HANDLED;
      }
    }

    class VerifyingLinkState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            setNetworkDetailedState(DetailedState.VERIFYING_POOR_LINK);
            mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.VERIFYING_POOR_LINK);
            sendNetworkStateChangeBroadcast(mLastBssid);
        }
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                    //stay here
                    break;
                case WifiWatchdogStateMachine.GOOD_LINK_DETECTED:
                    transitionTo(mCaptivePortalCheckState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class CaptivePortalCheckState extends State {
        @Override
        public void enter() {
            setNetworkDetailedState(DetailedState.CAPTIVE_PORTAL_CHECK);
            mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.CAPTIVE_PORTAL_CHECK);
            sendNetworkStateChangeBroadcast(mLastBssid);
        }
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_CAPTIVE_CHECK_COMPLETE:
                    try {
                        mNwService.enableIpv6(mInterfaceName);
                    } catch (RemoteException re) {
                        loge("Failed to enable IPv6: " + re);
                    } catch (IllegalStateException e) {
                        loge("Failed to enable IPv6: " + e);
                    }
                    setNetworkDetailedState(DetailedState.CONNECTED);
                    mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.CONNECTED);
                    sendNetworkStateChangeBroadcast(mLastBssid);
                    transitionTo(mConnectedState);
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
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
       }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
               case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                    if (DBG) log("Watchdog reports poor link");
                    try {
                        mNwService.disableIpv6(mInterfaceName);
                    } catch (RemoteException re) {
                        loge("Failed to disable IPv6: " + re);
                    } catch (IllegalStateException e) {
                        loge("Failed to disable IPv6: " + e);
                    }
                    /* Report a disconnect */
                    setNetworkDetailedState(DetailedState.DISCONNECTED);
                    mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.DISCONNECTED);
                    sendNetworkStateChangeBroadcast(mLastBssid);

                    transitionTo(mVerifyingLinkState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
        @Override
        public void exit() {
            /* Request a CS wakelock during transition to mobile */
            checkAndSetConnectivityInstance();
            mCm.requestNetworkTransitionWakelock(TAG);
        }
    }

    class DisconnectingState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        deferMessage(message);
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    /* If we get a SUPPLICANT_STATE_CHANGE_EVENT before NETWORK_DISCONNECTION_EVENT
                     * we have missed the network disconnection, transition to mDisconnectedState
                     * and handle the rest of the events there
                     */
                    deferMessage(message);
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DisconnectedState extends State {
        private boolean mAlarmEnabled = false;
        /* This is set from the overlay config file or from a secure setting.
         * A value of 0 disables scanning in the framework.
         */
        private long mFrameworkScanIntervalMs;

        private void setScanAlarm(boolean enabled) {
            if (enabled == mAlarmEnabled) return;
            if (enabled) {
                if (mFrameworkScanIntervalMs > 0) {
                    mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + mFrameworkScanIntervalMs,
                            mFrameworkScanIntervalMs,
                            mScanIntent);
                    mAlarmEnabled = true;
                }
            } else {
                mAlarmManager.cancel(mScanIntent);
                mAlarmEnabled = false;
            }
        }

        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            // We dont scan frequently if this is a temporary disconnect
            // due to p2p
            if (mTemporarilyDisconnectWifi) {
                mWifiP2pChannel.sendMessage(WifiP2pService.DISCONNECT_WIFI_RESPONSE);
                return;
            }

            mFrameworkScanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                    Settings.Global.WIFI_FRAMEWORK_SCAN_INTERVAL_MS,
                    mDefaultFrameworkScanIntervalMs);
            /*
             * We initiate background scanning if it is enabled, otherwise we
             * initiate an infrequent scan that wakes up the device to ensure
             * a user connects to an access point on the move
             */
            if (mEnableBackgroundScan) {
                /* If a regular scan result is pending, do not initiate background
                 * scan until the scan results are returned. This is needed because
                 * initiating a background scan will cancel the regular scan and
                 * scan results will not be returned until background scanning is
                 * cleared
                 */
                if (!mScanResultIsPending) {
                    mWifiNative.enableBackgroundScan(true);
                }
            } else {
                setScanAlarm(true);
            }

            /**
             * If we have no networks saved, the supplicant stops doing the periodic scan.
             * The scans are useful to notify the user of the presence of an open network.
             * Note that these are not wake up scans.
             */
            if (!mP2pConnected.get() && mWifiConfigStore.getConfiguredNetworks().size() == 0) {
                sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                            ++mPeriodicScanToken, 0), mSupplicantScanIntervalMs);
            }
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            boolean ret = HANDLED;
            switch (message.what) {
                case CMD_NO_NETWORKS_PERIODIC_SCAN:
                    if (mP2pConnected.get()) break;
                    if (message.arg1 == mPeriodicScanToken &&
                            mWifiConfigStore.getConfiguredNetworks().size() == 0) {
                        sendMessage(CMD_START_SCAN);
                        sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                    ++mPeriodicScanToken, 0), mSupplicantScanIntervalMs);
                    }
                    break;
                case WifiManager.FORGET_NETWORK:
                case CMD_REMOVE_NETWORK:
                    // Set up a delayed message here. After the forget/remove is handled
                    // the handled delayed message will determine if there is a need to
                    // scan and continue
                    sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                ++mPeriodicScanToken, 0), mSupplicantScanIntervalMs);
                    ret = NOT_HANDLED;
                    break;
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        mWifiNative.setScanResultHandling(message.arg1);
                        //Supplicant disconnect to prevent further connects
                        mWifiNative.disconnect();
                        mIsScanMode = true;
                        transitionTo(mScanModeState);
                    }
                    break;
                case CMD_ENABLE_BACKGROUND_SCAN:
                    mEnableBackgroundScan = (message.arg1 == 1);
                    if (mEnableBackgroundScan) {
                        mWifiNative.enableBackgroundScan(true);
                        setScanAlarm(false);
                    } else {
                        mWifiNative.enableBackgroundScan(false);
                        setScanAlarm(true);
                    }
                    break;
                    /* Ignore network disconnect */
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    setNetworkDetailedState(WifiInfo.getDetailedStateOf(stateChangeResult.state));
                    /* ConnectModeState does the rest of the handling */
                    ret = NOT_HANDLED;
                    break;
                case CMD_START_SCAN:
                    /* Disable background scan temporarily during a regular scan */
                    if (mEnableBackgroundScan) {
                        mWifiNative.enableBackgroundScan(false);
                    }
                    /* Handled in parent state */
                    ret = NOT_HANDLED;
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                    /* Re-enable background scan when a pending scan result is received */
                    if (mEnableBackgroundScan && mScanResultIsPending) {
                        mWifiNative.enableBackgroundScan(true);
                    }
                    /* Handled in parent state */
                    ret = NOT_HANDLED;
                    break;
                case WifiP2pService.P2P_CONNECTION_CHANGED:
                    NetworkInfo info = (NetworkInfo) message.obj;
                    mP2pConnected.set(info.isConnected());
                    if (mP2pConnected.get()) {
                        int defaultInterval = mContext.getResources().getInteger(
                                R.integer.config_wifi_scan_interval_p2p_connected);
                        long scanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                                Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                                defaultInterval);
                        mWifiNative.setScanInterval((int) scanIntervalMs/1000);
                    } else if (mWifiConfigStore.getConfiguredNetworks().size() == 0) {
                        if (DBG) log("Turn on scanning after p2p disconnected");
                        sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                    ++mPeriodicScanToken, 0), mSupplicantScanIntervalMs);
                    }
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                    // Drop a third party reconnect/reassociate if we are
                    // tempoarily disconnected for p2p
                    if (mTemporarilyDisconnectWifi) ret = NOT_HANDLED;
                    break;
                default:
                    ret = NOT_HANDLED;
            }
            return ret;
        }

        @Override
        public void exit() {
            /* No need for a background scan upon exit from a disconnected state */
            if (mEnableBackgroundScan) {
                mWifiNative.enableBackgroundScan(false);
            }
            setScanAlarm(false);
        }
    }

    class WpsRunningState extends State {
        //Tracks the source to provide a reply
        private Message mSourceMessage;
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            mSourceMessage = Message.obtain(getCurrentMessage());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch (message.what) {
                case WifiMonitor.WPS_SUCCESS_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_COMPLETED);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    transitionTo(mDisconnectedState);
                    break;
                case WifiMonitor.WPS_OVERLAP_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_FAILED,
                            WifiManager.WPS_OVERLAP_ERROR);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    transitionTo(mDisconnectedState);
                    break;
                case WifiMonitor.WPS_FAIL_EVENT:
                    //arg1 has the reason for the failure
                    replyToMessage(mSourceMessage, WifiManager.WPS_FAILED, message.arg1);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    transitionTo(mDisconnectedState);
                    break;
                case WifiMonitor.WPS_TIMEOUT_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_FAILED,
                            WifiManager.WPS_TIMED_OUT);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    transitionTo(mDisconnectedState);
                    break;
                case WifiManager.START_WPS:
                    replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.IN_PROGRESS);
                    break;
                case WifiManager.CANCEL_WPS:
                    if (mWifiNative.cancelWps()) {
                        replyToMessage(message, WifiManager.CANCEL_WPS_SUCCEDED);
                    } else {
                        replyToMessage(message, WifiManager.CANCEL_WPS_FAILED, WifiManager.ERROR);
                    }
                    transitionTo(mDisconnectedState);
                    break;
                /* Defer all commands that can cause connections to a different network
                 * or put the state machine out of connect mode
                 */
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case WifiManager.CONNECT_NETWORK:
                case CMD_ENABLE_NETWORK:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case WifiMonitor.NETWORK_CONNECTION_EVENT: /* Handled after exiting WPS state */
                    deferMessage(message);
                    break;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    if (DBG) log("Network connection lost");
                    handleNetworkDisconnect();
                    break;
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                    // Disregard auth failure events during WPS connection. The
                    // EAP sequence is retried several times, and there might be
                    // failures (especially for wps pin). We will get a WPS_XXX
                    // event at the end of the sequence anyway.
                    if (DBG) log("Ignore auth failure during WPS connection");
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    //Throw away supplicant state changes when WPS is running.
                    //We will start getting supplicant state changes once we get
                    //a WPS success or failure
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mWifiConfigStore.enableAllNetworks();
            mWifiConfigStore.loadConfiguredNetworks();
        }
    }

    class SoftApStartingState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            final Message message = getCurrentMessage();
            if (message.what == CMD_START_AP) {
                final WifiConfiguration config = (WifiConfiguration) message.obj;

                if (config == null) {
                    mWifiApConfigChannel.sendMessage(CMD_REQUEST_AP_CONFIG);
                } else {
                    mWifiApConfigChannel.sendMessage(CMD_SET_AP_CONFIG, config);
                    startSoftApWithConfig(config);
                }
            } else {
                throw new RuntimeException("Illegal transition to SoftApStartingState: " + message);
            }
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_TETHER_STATE_CHANGE:
                    deferMessage(message);
                    break;
                case WifiStateMachine.CMD_RESPONSE_AP_CONFIG:
                    WifiConfiguration config = (WifiConfiguration) message.obj;
                    if (config != null) {
                        startSoftApWithConfig(config);
                    } else {
                        loge("Softap config is null!");
                        sendMessage(CMD_START_AP_FAILURE);
                    }
                    break;
                case CMD_START_AP_SUCCESS:
                    setWifiApState(WIFI_AP_STATE_ENABLED);
                    transitionTo(mSoftApStartedState);
                    break;
                case CMD_START_AP_FAILURE:
                    // initiate driver unload
                    sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_AP_STATE_FAILED, 0));
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SoftApStartedState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_STOP_AP:
                    if (DBG) log("Stopping Soft AP");
                    setWifiApState(WIFI_AP_STATE_DISABLING);

                    /* We have not tethered at this point, so we just shutdown soft Ap */
                    try {
                        mNwService.stopAccessPoint(mInterfaceName);
                    } catch(Exception e) {
                        loge("Exception in stopAccessPoint()");
                    }
                    transitionTo(mDriverLoadedState);
                    break;
                case CMD_START_AP:
                    // Ignore a start on a running access point
                    break;
                    /* Fail client mode operation when soft AP is enabled */
                case CMD_START_SUPPLICANT:
                   loge("Cannot start supplicant with a running soft AP");
                    setWifiState(WIFI_STATE_UNKNOWN);
                    break;
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (startTethering(stateChange.available)) {
                        transitionTo(mTetheringState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class TetheringState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            /* Send ourselves a delayed message to shut down if tethering fails to notify */
            sendMessageDelayed(obtainMessage(CMD_TETHER_NOTIFICATION_TIMED_OUT,
                    ++mTetherToken, 0), TETHER_NOTIFICATION_TIME_OUT_MSECS);
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (isWifiTethered(stateChange.active)) {
                        transitionTo(mTetheredState);
                    }
                    return HANDLED;
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                    if (message.arg1 == mTetherToken) {
                        loge("Failed to get tether update, shutdown soft access point");
                        setWifiApEnabled(null, false);
                    }
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class TetheredState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (!isWifiTethered(stateChange.active)) {
                        loge("Tethering reports wifi as untethered!, shut down soft Ap");
                        setWifiApEnabled(null, false);
                    }
                    return HANDLED;
                case CMD_STOP_AP:
                    if (DBG) log("Untethering before stopping AP");
                    setWifiApState(WIFI_AP_STATE_DISABLING);
                    stopTethering();
                    transitionTo(mSoftApStoppingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SoftApStoppingState extends State {
        @Override
        public void enter() {
            if (DBG) log(getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            /* Send ourselves a delayed message to shut down if tethering fails to notify */
            sendMessageDelayed(obtainMessage(CMD_TETHER_NOTIFICATION_TIMED_OUT,
                    ++mTetherToken, 0), TETHER_NOTIFICATION_TIME_OUT_MSECS);

        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) log(getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;

                    /* Wait till wifi is untethered */
                    if (isWifiTethered(stateChange.active)) break;

                    try {
                        mNwService.stopAccessPoint(mInterfaceName);
                    } catch(Exception e) {
                        loge("Exception in stopAccessPoint()");
                    }
                    transitionTo(mDriverLoadedState);
                    break;
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                    if (message.arg1 == mTetherToken) {
                        loge("Failed to get tether update, force stop access point");
                        try {
                            mNwService.stopAccessPoint(mInterfaceName);
                        } catch(Exception e) {
                            loge("Exception in stopAccessPoint()");
                        }
                        transitionTo(mDriverLoadedState);
                    }
                    break;
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    //State machine initiated requests can have replyTo set to null indicating
    //there are no recepients, we ignore those reply actions
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithArg2(msg);
        dstMsg.what = what;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithArg2(msg);
        dstMsg.what = what;
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithArg2(msg);
        dstMsg.what = what;
        dstMsg.obj = obj;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    /**
     * arg2 on the source message has a unique id that needs to be retained in replies
     * to match the request
     *
     * see WifiManager for details
     */
    private Message obtainMessageWithArg2(Message srcMsg) {
        Message msg = Message.obtain();
        msg.arg2 = srcMsg.arg2;
        return msg;
    }

    private void log(String s) {
        Log.d(TAG, s);
    }

    private void loge(String s) {
        Log.e(TAG, s);
    }
}
