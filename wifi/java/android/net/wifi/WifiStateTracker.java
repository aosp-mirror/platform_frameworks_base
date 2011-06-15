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

package android.net.wifi;

import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.NetworkInfo;
import android.net.NetworkStateTracker;
import android.net.DhcpInfo;
import android.net.NetworkUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo.State;
import android.os.Message;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Config;
import com.android.internal.app.IBatteryStats;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Track the state of Wifi connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * @hide
 */
public class WifiStateTracker extends NetworkStateTracker {

    private static final boolean LOCAL_LOGD = Config.LOGD || false;
    
    private static final String TAG = "WifiStateTracker";

    // Event log tags (must be in sync with event-log-tags)
    private static final int EVENTLOG_NETWORK_STATE_CHANGED = 50021;
    private static final int EVENTLOG_SUPPLICANT_STATE_CHANGED = 50022;
    private static final int EVENTLOG_DRIVER_STATE_CHANGED = 50023;
    private static final int EVENTLOG_INTERFACE_CONFIGURATION_STATE_CHANGED = 50024;
    private static final int EVENTLOG_SUPPLICANT_CONNECTION_STATE_CHANGED = 50025;

    // Event codes
    private static final int EVENT_SUPPLICANT_CONNECTION             = 1;
    private static final int EVENT_SUPPLICANT_DISCONNECT             = 2;
    private static final int EVENT_SUPPLICANT_STATE_CHANGED          = 3;
    private static final int EVENT_NETWORK_STATE_CHANGED             = 4;
    private static final int EVENT_SCAN_RESULTS_AVAILABLE            = 5;
    private static final int EVENT_INTERFACE_CONFIGURATION_SUCCEEDED = 6;
    private static final int EVENT_INTERFACE_CONFIGURATION_FAILED    = 7;
    private static final int EVENT_POLL_INTERVAL                     = 8;
    private static final int EVENT_DHCP_START                        = 9;
    private static final int EVENT_DHCP_RENEW                        = 10;
    private static final int EVENT_DEFERRED_DISCONNECT               = 11;
    private static final int EVENT_DEFERRED_RECONNECT                = 12;
    /**
     * The driver is started or stopped. The object will be the state: true for
     * started, false for stopped.
     */
    private static final int EVENT_DRIVER_STATE_CHANGED              = 13;
    private static final int EVENT_PASSWORD_KEY_MAY_BE_INCORRECT     = 14;
    private static final int EVENT_MAYBE_START_SCAN_POST_DISCONNECT  = 15;

    /**
     * The driver state indication.
     */
    private static final int DRIVER_STARTED                          = 0;
    private static final int DRIVER_STOPPED                          = 1;
    private static final int DRIVER_HUNG                             = 2;

    /**
     * Interval in milliseconds between polling for connection
     * status items that are not sent via asynchronous events.
     * An example is RSSI (signal strength).
     */
    private static final int POLL_STATUS_INTERVAL_MSECS = 3000;

    /**
     * The max number of the WPA supplicant loop iterations before we
     * decide that the loop should be terminated:
     */
    private static final int MAX_SUPPLICANT_LOOP_ITERATIONS = 4;

    /**
     * When a DISCONNECT event is received, we defer handling it to
     * allow for the possibility that the DISCONNECT is about to
     * be followed shortly by a CONNECT to the same network we were
     * just connected to. In such a case, we don't want to report
     * the network as down, nor do we want to reconfigure the network
     * interface, etc. If we get a CONNECT event for another network
     * within the delay window, we immediately handle the pending
     * disconnect before processing the CONNECT.<p/>
     * The five second delay is chosen somewhat arbitrarily, but is
     * meant to cover most of the cases where a DISCONNECT/CONNECT
     * happens to a network.
     */
    private static final int DISCONNECT_DELAY_MSECS = 5000;
    /**
     * When the supplicant goes idle after we do an explicit disconnect
     * following a DHCP failure, we need to kick the supplicant into
     * trying to associate with access points.
     */
    private static final int RECONNECT_DELAY_MSECS = 2000;

    /**
     * When the supplicant disconnects from an AP it sometimes forgets
     * to restart scanning.  Wait this delay before asking it to start
     * scanning (in case it forgot).  15 sec is the standard delay between
     * scans.
     */
    private static final int KICKSTART_SCANNING_DELAY_MSECS = 15000;

    /**
     * The maximum number of times we will retry a connection to an access point
     * for which we have failed in acquiring an IP address from DHCP. A value of
     * N means that we will make N+1 connection attempts in all.
     * <p>
     * See {@link Settings.Secure#WIFI_MAX_DHCP_RETRY_COUNT}. This is the default
     * value if a Settings value is not present.
     */
    private static final int DEFAULT_MAX_DHCP_RETRIES = 9;

    //Minimum dhcp lease duration for renewal
    private static final int MIN_RENEWAL_TIME_SECS = 5 * 60; //5 minutes

    private static final int DRIVER_POWER_MODE_AUTO = 0;
    private static final int DRIVER_POWER_MODE_ACTIVE = 1;

    /**
     * The current WPA supplicant loop state (used to detect looping behavior):
     */
    private SupplicantState mSupplicantLoopState = SupplicantState.DISCONNECTED;

    /**
     * The current number of WPA supplicant loop iterations:
     */
    private int mNumSupplicantLoopIterations = 0;

    /**
     * The current number of supplicant state changes.  This is used to determine
     * if we've received any new info since we found out it was DISCONNECTED or
     * INACTIVE.  If we haven't for X ms, we then request a scan - it should have
     * done that automatically, but sometimes some firmware does not.
     */
    private int mNumSupplicantStateChanges = 0;

    /**
     * True if we received an event that that a password-key may be incorrect.
     * If the next incoming supplicant state change event is DISCONNECT,
     * broadcast a message that we have a possible password error and disable
     * the network.
     */
    private boolean mPasswordKeyMayBeIncorrect = false;

    public static final int SUPPL_SCAN_HANDLING_NORMAL = 1;
    public static final int SUPPL_SCAN_HANDLING_LIST_ONLY = 2;

    private WifiMonitor mWifiMonitor;
    private WifiInfo mWifiInfo;
    private List<ScanResult> mScanResults;
    private WifiManager mWM;
    private boolean mHaveIpAddress;
    private boolean mObtainingIpAddress;
    private boolean mTornDownByConnMgr;
    /**
     * A DISCONNECT event has been received, but processing it
     * is being deferred.
     */
    private boolean mDisconnectPending;
    /**
     * An operation has been performed as a result of which we expect the next event
     * will be a DISCONNECT.
     */
    private boolean mDisconnectExpected;
    private DhcpHandler mDhcpTarget;
    private DhcpInfo mDhcpInfo;
    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private String mLastSsid;
    private int mLastNetworkId = -1;
    private boolean mUseStaticIp = false;
    private int mReconnectCount;

    private AlarmManager mAlarmManager;
    private PendingIntent mDhcpRenewalIntent;
    private PowerManager.WakeLock mDhcpRenewWakeLock;
    private static final String WAKELOCK_TAG = "*wifi*";

    private static final int DHCP_RENEW = 0;
    private static final String ACTION_DHCP_RENEW = "android.net.wifi.DHCP_RENEW";


    /* Tracks if any network in the configuration is disabled */
    private AtomicBoolean mIsAnyNetworkDisabled = new AtomicBoolean(false);

    // used to store the (non-persisted) num determined during device boot 
    // (from mcc or other phone info) before the driver is started.
    private int mNumAllowedChannels = 0;

    // Variables relating to the 'available networks' notification
    
    /**
     * The icon to show in the 'available networks' notification. This will also
     * be the ID of the Notification given to the NotificationManager.
     */
    private static final int ICON_NETWORKS_AVAILABLE =
            com.android.internal.R.drawable.stat_notify_wifi_in_range;
    /**
     * When a notification is shown, we wait this amount before possibly showing it again.
     */
    private final long NOTIFICATION_REPEAT_DELAY_MS;
    /**
     * Whether the user has set the setting to show the 'available networks' notification.
     */
    private boolean mNotificationEnabled;
    /**
     * Observes the user setting to keep {@link #mNotificationEnabled} in sync.
     */
    private NotificationEnabledSettingObserver mNotificationEnabledSettingObserver;
    /**
     * The {@link System#currentTimeMillis()} must be at least this value for us
     * to show the notification again.
     */
    private long mNotificationRepeatTime;
    /**
     * The Notification object given to the NotificationManager.
     */
    private Notification mNotification;
    /**
     * Whether the notification is being shown, as set by us. That is, if the
     * user cancels the notification, we will not receive the callback so this
     * will still be true. We only guarantee if this is false, then the
     * notification is not showing.
     */
    private boolean mNotificationShown;
    /**
     * The number of continuous scans that must occur before consider the
     * supplicant in a scanning state. This allows supplicant to associate with
     * remembered networks that are in the scan results.
     */
    private static final int NUM_SCANS_BEFORE_ACTUALLY_SCANNING = 3;
    /**
     * The number of scans since the last network state change. When this
     * exceeds {@link #NUM_SCANS_BEFORE_ACTUALLY_SCANNING}, we consider the
     * supplicant to actually be scanning. When the network state changes to
     * something other than scanning, we reset this to 0.
     */
    private int mNumScansSinceNetworkStateChange;
    /**
     * Observes the static IP address settings.
     */
    private SettingsObserver mSettingsObserver;
    
    private boolean mIsScanModeActive;
    private boolean mEnableRssiPolling;
    private boolean mIsHighPerfEnabled;
    private int mPowerModeRefCount = 0;
    private int mOptimizationsDisabledRefCount = 0;

    /**
     * One of  {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     *
     * getWifiState() is not synchronized to make sure it's always fast,
     * even when the instance lock is held on other slow operations.
     * Use a atomic variable for state.
     */
    private final AtomicInteger mWifiState = new AtomicInteger(WIFI_STATE_UNKNOWN);

    // Wi-Fi run states:
    private static final int RUN_STATE_STARTING = 1;
    private static final int RUN_STATE_RUNNING  = 2;
    private static final int RUN_STATE_STOPPING = 3;
    private static final int RUN_STATE_STOPPED  = 4;

    private static final String mRunStateNames[] = {
            "Starting",
            "Running",
            "Stopping",
            "Stopped"
    };
    private int mRunState;

    private final IBatteryStats mBatteryStats;

    private boolean mIsScanOnly;

    private BluetoothA2dp mBluetoothA2dp;

    private String mInterfaceName;
    private static String LS = System.getProperty("line.separator");

    private static String[] sDnsPropNames;

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

    /**
     * A structure for supplying information about a supplicant state
     * change in the STATE_CHANGE event message that comes from the
     * WifiMonitor
     * thread.
     */
    private static class SupplicantStateChangeResult {
        SupplicantStateChangeResult(int networkId, String BSSID, SupplicantState state) {
            this.state = state;
            this.BSSID = BSSID;
            this.networkId = networkId;
        }
        int networkId;
        String BSSID;
        SupplicantState state;
    }

    /**
     * A structure for supplying information about a connection in
     * the CONNECTED event message that comes from the WifiMonitor
     * thread.
     */
    private static class NetworkStateChangeResult {
        NetworkStateChangeResult(DetailedState state, String BSSID, int networkId) {
            this.state = state;
            this.BSSID = BSSID;
            this.networkId = networkId;
        }
        DetailedState state;
        String BSSID;
        int networkId;
    }

    public WifiStateTracker(Context context, Handler target) {
        super(context, target, ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");
        
        mWifiInfo = new WifiInfo();
        mWifiMonitor = new WifiMonitor(this);
        mHaveIpAddress = false;
        mObtainingIpAddress = false;
        setTornDownByConnMgr(false);
        mDisconnectPending = false;
        mScanResults = new ArrayList<ScanResult>();
        // Allocate DHCP info object once, and fill it in on each request
        mDhcpInfo = new DhcpInfo();
        mRunState = RUN_STATE_STARTING;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent dhcpRenewalIntent = new Intent(ACTION_DHCP_RENEW, null);
        mDhcpRenewalIntent = PendingIntent.getBroadcast(mContext, DHCP_RENEW, dhcpRenewalIntent, 0);

        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    //DHCP renew
                    if (mDhcpTarget != null) {
                        Log.d(TAG, "Sending a DHCP renewal");
                        //acquire a 40s wakelock to finish DHCP renewal
                        mDhcpRenewWakeLock.acquire(40000);
                        mDhcpTarget.sendEmptyMessage(EVENT_DHCP_RENEW);
                    }
                }
            },new IntentFilter(ACTION_DHCP_RENEW));

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mDhcpRenewWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        // Setting is in seconds
        NOTIFICATION_REPEAT_DELAY_MS = Settings.Secure.getInt(context.getContentResolver(), 
                Settings.Secure.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY, 900) * 1000l;
        mNotificationEnabledSettingObserver = new NotificationEnabledSettingObserver(new Handler());
        mNotificationEnabledSettingObserver.register();

        mSettingsObserver = new SettingsObserver(new Handler());

        mInterfaceName = SystemProperties.get("wifi.interface", "tiwlan0");
        sDnsPropNames = new String[] {
            "dhcp." + mInterfaceName + ".dns1",
            "dhcp." + mInterfaceName + ".dns2"
        };
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batteryinfo"));

    }

    /**
     * Helper method: sets the supplicant state and keeps the network
     * info updated.
     * @param state the new state
     */
    private void setSupplicantState(SupplicantState state) {
        mWifiInfo.setSupplicantState(state);
        updateNetworkInfo();
        checkPollTimer();
    }

    public SupplicantState getSupplicantState() {
        return mWifiInfo.getSupplicantState();
    }

    /**
     * Helper method: sets the supplicant state and keeps the network
     * info updated (string version).
     * @param stateName the string name of the new state
     */
    private void setSupplicantState(String stateName) {
        mWifiInfo.setSupplicantState(stateName);
        updateNetworkInfo();
        checkPollTimer();
    }

    /**
     * Helper method: sets the boolean indicating that the connection
     * manager asked the network to be torn down (and so only the connection
     * manager can set it up again).
     * network info updated.
     * @param flag {@code true} if explicitly disabled.
     */
    private void setTornDownByConnMgr(boolean flag) {
        mTornDownByConnMgr = flag;
        updateNetworkInfo();
    }

    /**
     * Return the IP addresses of the DNS servers available for the WLAN
     * network interface.
     * @return a list of DNS addresses, with no holes.
     */
    public String[] getNameServers() {
        return getNameServerList(sDnsPropNames);
    }

    /**
     * Return the name of our WLAN network interface.
     * @return the name of our interface.
     */
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.wifi";
    }

    public void startMonitoring() {
        /*
         * Get a handle on the WifiManager. This cannot be done in our
         * constructor, because the Wifi service is not yet registered.
         */
        mWM = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
    }

    public void startEventLoop() {
        mWifiMonitor.startMonitoring();
    }

    /**
     * Wi-Fi is considered available as long as we have a connection to the
     * supplicant daemon and there is at least one enabled network. If a teardown
     * was explicitly requested, then Wi-Fi can be restarted with a reconnect
     * request, so it is considered available. If the driver has been stopped
     * for any reason other than a teardown request, Wi-Fi is considered
     * unavailable.
     * @return {@code true} if Wi-Fi connections are possible
     */
    public synchronized boolean isAvailable() {
        /*
         * TODO: Need to also look at scan results to see whether we're
         * in range of any access points. If we have scan results that
         * are no more than N seconds old, use those, otherwise, initiate
         * a scan and wait for the results. This only matters if we
         * allow mobile to be the preferred network.
         */
        SupplicantState suppState = mWifiInfo.getSupplicantState();
        return suppState != SupplicantState.UNINITIALIZED &&
                suppState != SupplicantState.INACTIVE &&
                (mTornDownByConnMgr || !isDriverStopped());
    }

    /**
     * {@inheritDoc}
     * There are currently no defined Wi-Fi subtypes.
     */
    public int getNetworkSubtype() {
        return 0;
    }

    /**
     * Helper method: updates the network info object to keep it in sync with
     * the Wi-Fi state tracker.
     */
    private void updateNetworkInfo() {
        mNetworkInfo.setIsAvailable(isAvailable());
    }

    /**
     * Report whether the Wi-Fi connection is fully configured for data.
     * @return {@code true} if the {@link SupplicantState} is
     * {@link android.net.wifi.SupplicantState#COMPLETED COMPLETED}.
     */
    public boolean isConnectionCompleted() {
        return mWifiInfo.getSupplicantState() == SupplicantState.COMPLETED;
    }

    /**
     * Report whether the Wi-Fi connection has successfully acquired an IP address.
     * @return {@code true} if the Wi-Fi connection has been assigned an IP address.
     */
    public boolean hasIpAddress() {
        return mHaveIpAddress;
    }

    /**
     * Send the tracker a notification that a user-entered password key
     * may be incorrect (i.e., caused authentication to fail).
     */
    void notifyPasswordKeyMayBeIncorrect() {
        sendEmptyMessage(EVENT_PASSWORD_KEY_MAY_BE_INCORRECT);
    }

    /**
     * Send the tracker a notification that a connection to the supplicant
     * daemon has been established.
     */
    void notifySupplicantConnection() {
        sendEmptyMessage(EVENT_SUPPLICANT_CONNECTION);
    }

    /**
     * Send the tracker a notification that the state of the supplicant
     * has changed.
     * @param networkId the configured network on which the state change occurred
     * @param newState the new {@code SupplicantState}
     */
    void notifyStateChange(int networkId, String BSSID, SupplicantState newState) {
        Message msg = Message.obtain(
            this, EVENT_SUPPLICANT_STATE_CHANGED,
            new SupplicantStateChangeResult(networkId, BSSID, newState));
        msg.sendToTarget();
    }

    /**
     * Send the tracker a notification that the state of Wifi connectivity
     * has changed.
     * @param networkId the configured network on which the state change occurred
     * @param newState the new network state
     * @param BSSID when the new state is {@link DetailedState#CONNECTED
     * NetworkInfo.DetailedState.CONNECTED},
     * this is the MAC address of the access point. Otherwise, it
     * is {@code null}.
     */
    void notifyStateChange(DetailedState newState, String BSSID, int networkId) {
        Message msg = Message.obtain(
            this, EVENT_NETWORK_STATE_CHANGED,
            new NetworkStateChangeResult(newState, BSSID, networkId));
        msg.sendToTarget();
    }

    /**
     * Send the tracker a notification that a scan has completed, and results
     * are available.
     */
    void notifyScanResultsAvailable() {
        // reset the supplicant's handling of scan results to "normal" mode
        setScanResultHandling(SUPPL_SCAN_HANDLING_NORMAL);
        sendEmptyMessage(EVENT_SCAN_RESULTS_AVAILABLE);
    }

    /**
     * Send the tracker a notification that we can no longer communicate with
     * the supplicant daemon.
     */
    void notifySupplicantLost() {
        sendEmptyMessage(EVENT_SUPPLICANT_DISCONNECT);
    }

    /**
     * Send the tracker a notification that the Wi-Fi driver has been stopped.
     */
    void notifyDriverStopped() {
        // Send a driver stopped message to our handler
        Message.obtain(this, EVENT_DRIVER_STATE_CHANGED, DRIVER_STOPPED, 0).sendToTarget();
    }

    /**
     * Send the tracker a notification that the Wi-Fi driver has been restarted after
     * having been stopped.
     */
    void notifyDriverStarted() {
        // Send a driver started message to our handler
        Message.obtain(this, EVENT_DRIVER_STATE_CHANGED, DRIVER_STARTED, 0).sendToTarget();
    }

    /**
     * Send the tracker a notification that the Wi-Fi driver has hung and needs restarting.
     */
    void notifyDriverHung() {
        // Send a driver hanged message to our handler
        Message.obtain(this, EVENT_DRIVER_STATE_CHANGED, DRIVER_HUNG, 0).sendToTarget();
    }

    /**
     * Set the interval timer for polling connection information
     * that is not delivered asynchronously.
     */
    private synchronized void checkPollTimer() {
        if (mEnableRssiPolling &&
                mWifiInfo.getSupplicantState() == SupplicantState.COMPLETED &&
                !hasMessages(EVENT_POLL_INTERVAL)) {
            sendEmptyMessageDelayed(EVENT_POLL_INTERVAL, POLL_STATUS_INTERVAL_MSECS);
        }
    }

    /**
     * TODO: mRunState is not synchronized in some places
     * address this as part of re-architect.
     *
     * TODO: We are exposing an additional public synchronized call
     * for a wakelock optimization in WifiService. Remove it
     * when we handle the wakelock in ConnectivityService.
     */
    public synchronized boolean isDriverStopped() {
        return mRunState == RUN_STATE_STOPPED || mRunState == RUN_STATE_STOPPING;
    }

    public void updateBatteryWorkSourceLocked(WorkSource newSource) {
        try {
            if (newSource != null) {
                mRunningWifiUids.set(newSource);
            }
            if (mRunState == RUN_STATE_RUNNING) {
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
            } else if (mRunState == RUN_STATE_STOPPED) {
                if (mReportedRunning) {
                    // Last reported we were running, time to stop.
                    mBatteryStats.noteWifiStopped(mLastRunningWifiUids);
                    mLastRunningWifiUids.clear();
                    mReportedRunning = false;
                }
            } else {
                // State in transition -- nothing to update yet.
            }
        } catch (RemoteException ignore) {
        }
    }

    /**
     * Set the run state to either "normal" or "scan-only".
     * @param scanOnlyMode true if the new mode should be scan-only.
     */
    public synchronized void setScanOnlyMode(boolean scanOnlyMode) {
        // do nothing unless scan-only mode is changing
        if (mIsScanOnly != scanOnlyMode) {
            int scanType = (scanOnlyMode ?
                    SUPPL_SCAN_HANDLING_LIST_ONLY : SUPPL_SCAN_HANDLING_NORMAL);
            if (LOCAL_LOGD) Log.v(TAG, "Scan-only mode changing to " + scanOnlyMode + " scanType=" + scanType);
            if (setScanResultHandling(scanType)) {
                mIsScanOnly = scanOnlyMode;
                if (!isDriverStopped()) {
                    if (scanOnlyMode) {
                        disconnect();
                    } else {
                        reconnectCommand();
                    }
                }
            }
        }
    }

    /**
     * Set suspend mode optimizations. These include:
     * - packet filtering
     * - turn off roaming
     * - DTIM settings
     *
     * Uses reference counting to keep the suspend optimizations disabled
     * as long as one entity wants optimizations disabled.
     *
     * For example, WifiLock can keep suspend optimizations disabled
     * or the user setting (wifi never sleeps) can keep suspend optimizations
     * disabled. As long as one entity wants it disabled, it should stay
     * that way
     *
     * @param enabled true if optimizations need enabled, false otherwise
     */
    public synchronized void setSuspendModeOptimizations(boolean enabled) {

        /* It is good to plumb suspend optimization enable
         * or disable even if ref count indicates already done
         * since we could have a case of previous failure.
         */
        if (!enabled) {
            mOptimizationsDisabledRefCount++;
        } else {
            mOptimizationsDisabledRefCount--;
            if (mOptimizationsDisabledRefCount > 0) {
                return;
            } else {
                /* Keep refcount from becoming negative */
                mOptimizationsDisabledRefCount = 0;
            }
        }

        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return;
        }

        WifiNative.setSuspendOptimizationsCommand(enabled);
    }


    /**
     * Set high performance mode of operation. This would mean
     * use active power mode and disable suspend optimizations
     * @param enabled true if enabled, false otherwise
     */
    public synchronized void setHighPerfMode(boolean enabled) {
        if (mIsHighPerfEnabled != enabled) {
            if (enabled) {
                setPowerMode(DRIVER_POWER_MODE_ACTIVE);
                setSuspendModeOptimizations(false);
            } else {
                setPowerMode(DRIVER_POWER_MODE_AUTO);
                setSuspendModeOptimizations(true);
            }
            mIsHighPerfEnabled = enabled;
            Log.d(TAG,"high performance mode: " + enabled);
        }
    }


    private void checkIsBluetoothPlaying() {
        boolean isBluetoothPlaying = false;
        Set<BluetoothDevice> connected = mBluetoothA2dp.getConnectedSinks();

        for (BluetoothDevice device : connected) {
            if (mBluetoothA2dp.getSinkState(device) == BluetoothA2dp.STATE_PLAYING) {
                isBluetoothPlaying = true;
                break;
            }
        }
        setBluetoothScanMode(isBluetoothPlaying);
    }

    public void enableRssiPolling(boolean enable) {
        if (mEnableRssiPolling != enable) {
            mEnableRssiPolling = enable;
            checkPollTimer();
        }
    }

    /**
     * We release the wakelock in WifiService
     * using a timer.
     *
     * TODO:
     * Releasing wakelock using both timer and
     * a call from ConnectivityService requires
     * a rethink. We had problems where WifiService
     * could keep a wakelock forever if we delete
     * messages in the asynchronous call
     * from ConnectivityService
     */
    @Override
    public void releaseWakeLock() {
    }

    /**
     * Tracks the WPA supplicant states to detect "loop" situations.
     * @param newSupplicantState The new WPA supplicant state.
     * @return {@code true} if the supplicant loop should be stopped
     * and {@code false} if it should continue.
     */
    private boolean isSupplicantLooping(SupplicantState newSupplicantState) {
        if (SupplicantState.ASSOCIATING.ordinal() <= newSupplicantState.ordinal()
            && newSupplicantState.ordinal() < SupplicantState.COMPLETED.ordinal()) {
            if (mSupplicantLoopState != newSupplicantState) {
                if (newSupplicantState.ordinal() < mSupplicantLoopState.ordinal()) {
                    ++mNumSupplicantLoopIterations;
                }

                mSupplicantLoopState = newSupplicantState;
            }
        } else if (newSupplicantState == SupplicantState.COMPLETED) {
            resetSupplicantLoopState();
        }

        return mNumSupplicantLoopIterations >= MAX_SUPPLICANT_LOOP_ITERATIONS;
    }

    /**
     * Resets the WPA supplicant loop state.
     */
    private void resetSupplicantLoopState() {
        mNumSupplicantLoopIterations = 0;
    }

    @Override
    public void handleMessage(Message msg) {
        Intent intent;

        switch (msg.what) {
            case EVENT_SUPPLICANT_CONNECTION:
                mRunState = RUN_STATE_RUNNING;
                String macaddr;
                synchronized (this) {
                    updateBatteryWorkSourceLocked(null);
                    macaddr = WifiNative.getMacAddressCommand();
                }
                if (macaddr != null) {
                    mWifiInfo.setMacAddress(macaddr);
                }

                checkUseStaticIp();
                /* Reset notification state on new connection */
                resetNotificationTimer();
                /*
                 * DHCP requests are blocking, so run them in a separate thread.
                 */
                HandlerThread dhcpThread = new HandlerThread("DHCP Handler Thread");
                dhcpThread.start();
                mDhcpTarget = new DhcpHandler(dhcpThread.getLooper(), this);
                mIsScanModeActive = true;
                mIsHighPerfEnabled = false;
                mOptimizationsDisabledRefCount = 0;
                mPowerModeRefCount = 0;
                mTornDownByConnMgr = false;
                mLastBssid = null;
                mLastSsid = null;
                mIsAnyNetworkDisabled.set(false);
                requestConnectionInfo();
                SupplicantState supplState = mWifiInfo.getSupplicantState();

                if (LOCAL_LOGD) Log.v(TAG, "Connection to supplicant established, state=" +
                    supplState);
                // Wi-Fi supplicant connection state changed:
                // [31- 2] Reserved for future use
                // [ 1- 0] Connected to supplicant (1), disconnected from supplicant (0) ,
                //         or supplicant died (2)
                EventLog.writeEvent(EVENTLOG_SUPPLICANT_CONNECTION_STATE_CHANGED, 1);
                /*
                 * The COMPLETED state change from the supplicant may have occurred
                 * in between polling for supplicant availability, in which case
                 * we didn't perform a DHCP request to get an IP address.
                 */
                if (supplState == SupplicantState.COMPLETED) {
                    mLastBssid = mWifiInfo.getBSSID();
                    mLastSsid = mWifiInfo.getSSID();
                    configureInterface();
                }
                if (ActivityManagerNative.isSystemReady()) {
                    intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
                    intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, true);
                    mContext.sendBroadcast(intent);
                }
                if (supplState == SupplicantState.COMPLETED && mHaveIpAddress) {
                    setDetailedState(DetailedState.CONNECTED);
                } else {
                    setDetailedState(WifiInfo.getDetailedStateOf(supplState));
                }
                /*
                 * Filter out multicast packets. This saves battery power, since
                 * the CPU doesn't have to spend time processing packets that
                 * are going to end up being thrown away.
                 */
                mWM.initializeMulticastFiltering();

                if (mBluetoothA2dp == null) {
                    mBluetoothA2dp = new BluetoothA2dp(mContext);
                }
                checkIsBluetoothPlaying();

                // initialize this after the supplicant is alive
                setNumAllowedChannels();
                break;

            case EVENT_SUPPLICANT_DISCONNECT:
                mRunState = RUN_STATE_STOPPED;
                synchronized (this) {
                    updateBatteryWorkSourceLocked(null);
                }
                boolean died = mWifiState.get() != WIFI_STATE_DISABLED &&
                               mWifiState.get() != WIFI_STATE_DISABLING;
                if (died) {
                    if (LOCAL_LOGD) Log.v(TAG, "Supplicant died unexpectedly");
                } else {
                    if (LOCAL_LOGD) Log.v(TAG, "Connection to supplicant lost");
                }
                // Wi-Fi supplicant connection state changed:
                // [31- 2] Reserved for future use
                // [ 1- 0] Connected to supplicant (1), disconnected from supplicant (0) ,
                //         or supplicant died (2)
                EventLog.writeEvent(EVENTLOG_SUPPLICANT_CONNECTION_STATE_CHANGED, died ? 2 : 0);
                closeSupplicantConnection();

                if (died) {
                    resetConnections(true);
                }
                // When supplicant dies, kill the DHCP thread
                mDhcpTarget.getLooper().quit();

                mContext.removeStickyBroadcast(new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION));
                if (ActivityManagerNative.isSystemReady()) {
                    intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
                    intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, false);
                    mContext.sendBroadcast(intent);
                }
                setDetailedState(DetailedState.DISCONNECTED);
                setSupplicantState(SupplicantState.UNINITIALIZED);
                mHaveIpAddress = false;
                mObtainingIpAddress = false;
                if (died) {
                    mWM.setWifiEnabled(false);
                }
                break;

            case EVENT_MAYBE_START_SCAN_POST_DISCONNECT:
                // Only do this if we haven't gotten a new supplicant status since the timer
                // started
                if (mNumSupplicantStateChanges == msg.arg1) {
                    scan(false); // do a passive scan
                }
                break;

            case EVENT_SUPPLICANT_STATE_CHANGED:
                mNumSupplicantStateChanges++;
                SupplicantStateChangeResult supplicantStateResult =
                    (SupplicantStateChangeResult) msg.obj;
                SupplicantState newState = supplicantStateResult.state;
                SupplicantState currentState = mWifiInfo.getSupplicantState();

                // Wi-Fi supplicant state changed:
                // [31- 6] Reserved for future use
                // [ 5- 0] Supplicant state ordinal (as defined by SupplicantState)
                int eventLogParam = (newState.ordinal() & 0x3f);
                EventLog.writeEvent(EVENTLOG_SUPPLICANT_STATE_CHANGED, eventLogParam);

                if (LOCAL_LOGD) Log.v(TAG, "Changing supplicant state: "
                                      + currentState +
                                      " ==> " + newState);

                int networkId = supplicantStateResult.networkId;

                /**
                 * The SupplicantState BSSID value is valid in ASSOCIATING state only.
                 * The NetworkState BSSID value comes upon a successful connection.
                 */
                if (supplicantStateResult.state == SupplicantState.ASSOCIATING) {
                    mLastBssid = supplicantStateResult.BSSID;
                }
                /*
                 * If we get disconnect or inactive we need to start our
                 * watchdog timer to start a scan
                 */
                if (newState == SupplicantState.DISCONNECTED ||
                        newState == SupplicantState.INACTIVE) {
                    sendMessageDelayed(obtainMessage(EVENT_MAYBE_START_SCAN_POST_DISCONNECT,
                            mNumSupplicantStateChanges, 0), KICKSTART_SCANNING_DELAY_MSECS);
                }


                /*
                 * Did we get to DISCONNECTED state due to an
                 * authentication (password) failure?
                 */
                boolean failedToAuthenticate = false;
                if (newState == SupplicantState.DISCONNECTED) {
                    failedToAuthenticate = mPasswordKeyMayBeIncorrect;
                }
                mPasswordKeyMayBeIncorrect = false;

                /*
                 * Keep track of the supplicant state and check if we should
                 * disable the network
                 */
                boolean disabledNetwork = false;
                if (isSupplicantLooping(newState)) {
                    if (LOCAL_LOGD) {
                        Log.v(TAG,
                              "Stop WPA supplicant loop and disable network");
                    }
                    disabledNetwork = wifiManagerDisableNetwork(networkId);
                }

                if (disabledNetwork) {
                    /*
                     * Reset the loop state if we disabled the network
                     */
                    resetSupplicantLoopState();
                } else if (newState != currentState ||
                        (newState == SupplicantState.DISCONNECTED && isDriverStopped())) {
                    setSupplicantState(newState);
                    if (newState == SupplicantState.DORMANT) {
                        DetailedState newDetailedState;
                        Message reconnectMsg = obtainMessage(EVENT_DEFERRED_RECONNECT, mLastBssid);
                        if (mIsScanOnly || mRunState == RUN_STATE_STOPPING) {
                            newDetailedState = DetailedState.IDLE;
                        } else {
                            newDetailedState = DetailedState.FAILED;
                        }
                        handleDisconnectedState(newDetailedState, true);
                        /**
                         * We should never let the supplicant stay in DORMANT state
                         * as long as we are in connect mode and driver is started
                         *
                         * We should normally hit a DORMANT state due to a disconnect
                         * issued after an IP configuration failure. We issue a reconnect
                         * after RECONNECT_DELAY_MSECS in such a case.
                         *
                         * After multiple failures, the network gets disabled and the
                         * supplicant should reach an INACTIVE state.
                         *
                         */
                        if (mRunState == RUN_STATE_RUNNING && !mIsScanOnly) {
                            sendMessageDelayed(reconnectMsg, RECONNECT_DELAY_MSECS);
                        } else if (mRunState == RUN_STATE_STOPPING) {
                            stopDriver();
                        } else if (mRunState == RUN_STATE_STARTING && !mIsScanOnly) {
                            reconnectCommand();
                        }
                    } else if (newState == SupplicantState.DISCONNECTED) {
                        mHaveIpAddress = false;
                        if (isDriverStopped() || mDisconnectExpected) {
                            handleDisconnectedState(DetailedState.DISCONNECTED, true);
                        } else {
                            scheduleDisconnect();
                        }
                    } else if (newState != SupplicantState.COMPLETED && !mDisconnectPending) {
                        /**
                         * Ignore events that don't change the connectivity state,
                         * such as WPA rekeying operations.
                         */
                        if (!(currentState == SupplicantState.COMPLETED &&
                               (newState == SupplicantState.ASSOCIATING ||
                                newState == SupplicantState.ASSOCIATED ||
                                newState == SupplicantState.FOUR_WAY_HANDSHAKE ||
                                newState == SupplicantState.GROUP_HANDSHAKE))) {
                            setDetailedState(WifiInfo.getDetailedStateOf(newState));
                        }
                    }

                    mDisconnectExpected = false;
                    intent = new Intent(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                            | Intent.FLAG_RECEIVER_REPLACE_PENDING);
                    intent.putExtra(WifiManager.EXTRA_NEW_STATE, (Parcelable)newState);
                    if (failedToAuthenticate) {
                        if (LOCAL_LOGD) Log.d(TAG, "Failed to authenticate, disabling network " + networkId);
                        wifiManagerDisableNetwork(networkId);
                        intent.putExtra(
                            WifiManager.EXTRA_SUPPLICANT_ERROR,
                            WifiManager.ERROR_AUTHENTICATING);
                    }
                    mContext.sendStickyBroadcast(intent);
                }
                break;

            case EVENT_NETWORK_STATE_CHANGED:
                /*
                 * Each CONNECT or DISCONNECT generates a pair of events.
                 * One is a supplicant state change event, and the other
                 * is a network state change event. For connects, the
                 * supplicant event always arrives first, followed by
                 * the network state change event. Only the latter event
                 * has the BSSID, which we are interested in capturing.
                 * For disconnects, the order is the opposite -- the
                 * network state change event comes first, followed by
                 * the supplicant state change event.
                 */
                NetworkStateChangeResult result =
                    (NetworkStateChangeResult) msg.obj;

                // Wi-Fi network state changed:
                // [31- 6] Reserved for future use
                // [ 5- 0] Detailed state ordinal (as defined by NetworkInfo.DetailedState)   
                eventLogParam = (result.state.ordinal() & 0x3f);
                EventLog.writeEvent(EVENTLOG_NETWORK_STATE_CHANGED, eventLogParam);
                
                if (LOCAL_LOGD) Log.v(TAG, "New network state is " + result.state);
                /*
                 * If we're in scan-only mode, don't advance the state machine, and
                 * don't report the state change to clients.
                 */
                if (mIsScanOnly) {
                    if (LOCAL_LOGD) Log.v(TAG, "Dropping event in scan-only mode");
                    break;
                }
                if (result.state != DetailedState.SCANNING) {
                    /*
                     * Reset the scan count since there was a network state
                     * change. This could be from supplicant trying to associate
                     * with a network.
                     */
                    mNumScansSinceNetworkStateChange = 0;
                }
                /*
                 * If the supplicant sent us a CONNECTED event, we don't
                 * want to send out an indication of overall network
                 * connectivity until we have our IP address. If the
                 * supplicant sent us a DISCONNECTED event, we delay
                 * sending a notification in case a reconnection to
                 * the same access point occurs within a short time.
                 */
                if (result.state == DetailedState.DISCONNECTED) {
                    if (mWifiInfo.getSupplicantState() != SupplicantState.DORMANT) {
                        scheduleDisconnect();
                    }
                    break;
                }
                requestConnectionStatus(mWifiInfo);
                if (!(result.state == DetailedState.CONNECTED &&
                        (!mHaveIpAddress || mDisconnectPending))) {
                    setDetailedState(result.state);
                }

                if (result.state == DetailedState.CONNECTED) {
                    /*
                     * Remove the 'available networks' notification when we
                     * successfully connect to a network.
                     */
                    setNotificationVisible(false, 0, false, 0);
                    boolean wasDisconnectPending = mDisconnectPending;
                    cancelDisconnect();
                    /*
                     * The connection is fully configured as far as link-level
                     * connectivity is concerned, but we may still need to obtain
                     * an IP address.
                     */
                    if (wasDisconnectPending) {
                        DetailedState saveState = getNetworkInfo().getDetailedState();
                        handleDisconnectedState(DetailedState.DISCONNECTED, false);
                        setDetailedStateInternal(saveState);
                    }

                    configureInterface();
                    mLastBssid = result.BSSID;
                    mLastSsid = mWifiInfo.getSSID();
                    mLastNetworkId = result.networkId;
                    if (mHaveIpAddress) {
                        setDetailedState(DetailedState.CONNECTED);
                    } else {
                        setDetailedState(DetailedState.OBTAINING_IPADDR);
                    }
                }
                sendNetworkStateChangeBroadcast(mWifiInfo.getBSSID());
                break;

            case EVENT_SCAN_RESULTS_AVAILABLE:
                if (ActivityManagerNative.isSystemReady()) {
                    mContext.sendBroadcast(new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                }
                sendScanResultsAvailable();
                /**
                 * On receiving the first scan results after connecting to
                 * the supplicant, switch scan mode over to passive.
                 */
                setScanMode(false);
                break;

            case EVENT_POLL_INTERVAL:
                if (mWifiInfo.getSupplicantState() != SupplicantState.UNINITIALIZED) {
                    requestPolledInfo(mWifiInfo, true);
                    checkPollTimer();
                }
                break;
            
            case EVENT_DEFERRED_DISCONNECT:
                if (mWifiInfo.getSupplicantState() != SupplicantState.UNINITIALIZED) {
                    handleDisconnectedState(DetailedState.DISCONNECTED, true);
                }
                break;

            case EVENT_DEFERRED_RECONNECT:
                /**
                 * mLastBssid can be null when there is a reconnect
                 * request on the first BSSID we connect to
                 */
                String BSSID = (msg.obj != null) ? msg.obj.toString() : null;
                /**
                 * If we've exceeded the maximum number of retries for reconnecting
                 * to a given network, disable the network
                 */
                if (mWifiInfo.getSupplicantState() != SupplicantState.UNINITIALIZED) {
                    if (++mReconnectCount > getMaxDhcpRetries()) {
                        if (LOCAL_LOGD) {
                            Log.d(TAG, "Failed reconnect count: " +
                                    mReconnectCount + " Disabling " + BSSID);
                        }
                        mWM.disableNetwork(mLastNetworkId);
                    }
                    reconnectCommand();
                }
                break;

            case EVENT_INTERFACE_CONFIGURATION_SUCCEEDED:
                /**
                 * Since this event is sent from another thread, it might have been
                 * sent after we closed our connection to the supplicant in the course
                 * of disabling Wi-Fi. In that case, we should just ignore the event.
                 */
                if (mWifiInfo.getSupplicantState() == SupplicantState.UNINITIALIZED) {
                    break;
                }
                mReconnectCount = 0;
                mHaveIpAddress = true;
                mObtainingIpAddress = false;
                mWifiInfo.setIpAddress(mDhcpInfo.ipAddress);
                mLastSignalLevel = -1; // force update of signal strength
                if (mNetworkInfo.getDetailedState() != DetailedState.CONNECTED) {
                    setDetailedState(DetailedState.CONNECTED);
                    sendNetworkStateChangeBroadcast(mWifiInfo.getBSSID());
                } else {
                    msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
                    msg.sendToTarget();
                }
                if (LOCAL_LOGD) Log.v(TAG, "IP configuration: " + mDhcpInfo);
                // Wi-Fi interface configuration state changed:
                // [31- 1] Reserved for future use
                // [ 0- 0] Interface configuration succeeded (1) or failed (0)   
                EventLog.writeEvent(EVENTLOG_INTERFACE_CONFIGURATION_STATE_CHANGED, 1);

                // We've connected successfully, so allow the notification again in the future
                resetNotificationTimer();
                break;

            case EVENT_INTERFACE_CONFIGURATION_FAILED:
                if (mWifiInfo.getSupplicantState() != SupplicantState.UNINITIALIZED) {
                    // Wi-Fi interface configuration state changed:
                    // [31- 1] Reserved for future use
                    // [ 0- 0] Interface configuration succeeded (1) or failed (0)
                    EventLog.writeEvent(EVENTLOG_INTERFACE_CONFIGURATION_STATE_CHANGED, 0);
                    mHaveIpAddress = false;
                    mWifiInfo.setIpAddress(0);
                    mObtainingIpAddress = false;
                    disconnect();
                }
                break;

            case EVENT_DRIVER_STATE_CHANGED:
                // Wi-Fi driver state changed:
                // 0 STARTED
                // 1 STOPPED
                // 2 HUNG
                EventLog.writeEvent(EVENTLOG_DRIVER_STATE_CHANGED, msg.arg1);

                switch (msg.arg1) {
                case DRIVER_STARTED:
                    /**
                     * Set the number of allowed radio channels according
                     * to the system setting, since it gets reset by the
                     * driver upon changing to the STARTED state.
                     */
                    setNumAllowedChannels();
                    synchronized (this) {
                        macaddr = WifiNative.getMacAddressCommand();
                        if (macaddr != null) {
                            mWifiInfo.setMacAddress(macaddr);
                        }
                        mRunState = RUN_STATE_RUNNING;
                        if (!mIsScanOnly) {
                            reconnectCommand();
                        } else {
                            // In some situations, supplicant needs to be kickstarted to
                            // start the background scanning
                            scan(true);
                        }
                    }
                    break;
                case DRIVER_STOPPED:
                    mRunState = RUN_STATE_STOPPED;
                    break;
                case DRIVER_HUNG:
                    Log.e(TAG, "Wifi Driver reports HUNG - reloading.");
                    /**
                     * restart the driver - toggle off and on
                     */
                    mWM.setWifiEnabled(false);
                    mWM.setWifiEnabled(true);
                    break;
                }
                synchronized (this) {
                    updateBatteryWorkSourceLocked(null);
                }
                break;

            case EVENT_PASSWORD_KEY_MAY_BE_INCORRECT:
                mPasswordKeyMayBeIncorrect = true;
                break;
        }
    }

    private boolean wifiManagerDisableNetwork(int networkId) {
        boolean disabledNetwork = false;
        if (0 <= networkId) {
            disabledNetwork = mWM.disableNetwork(networkId);
            if (LOCAL_LOGD) {
                if (disabledNetwork) {
                    Log.v(TAG, "Disabled network: " + networkId);
                }
            }
        }
        if (LOCAL_LOGD) {
            if (!disabledNetwork) {
                Log.e(TAG, "Failed to disable network:" +
                      " invalid network id: " + networkId);
            }
        }
        return disabledNetwork;
    }

    private void configureInterface() {
        checkPollTimer();
        mLastSignalLevel = -1;
        if (!mUseStaticIp) {
            if (!mHaveIpAddress && !mObtainingIpAddress) {
                mObtainingIpAddress = true;
                mDhcpTarget.sendEmptyMessage(EVENT_DHCP_START);
            }
        } else {
            int event;
            if (NetworkUtils.configureInterface(mInterfaceName, mDhcpInfo)) {
                mHaveIpAddress = true;
                event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                if (LOCAL_LOGD) Log.v(TAG, "Static IP configuration succeeded");
            } else {
                mHaveIpAddress = false;
                event = EVENT_INTERFACE_CONFIGURATION_FAILED;
                if (LOCAL_LOGD) Log.v(TAG, "Static IP configuration failed");
            }
            sendEmptyMessage(event);
        }
    }

    /**
     * Reset our IP state and send out broadcasts following a disconnect.
     * @param newState the {@code DetailedState} to set. Should be either
     * {@code DISCONNECTED} or {@code FAILED}.
     * @param disableInterface indicates whether the interface should
     * be disabled
     */
    private void handleDisconnectedState(DetailedState newState, boolean disableInterface) {
        if (mDisconnectPending) {
            cancelDisconnect();
        }
        mDisconnectExpected = false;
        resetConnections(disableInterface);
        setDetailedState(newState);
        sendNetworkStateChangeBroadcast(mLastBssid);
        mWifiInfo.setBSSID(null);
        mLastBssid = null;
        mLastSsid = null;
        mDisconnectPending = false;
    }

    /**
     * Resets the Wi-Fi Connections by clearing any state, resetting any sockets
     * using the interface, stopping DHCP, and disabling the interface.
     */
    public void resetConnections(boolean disableInterface) {
        if (LOCAL_LOGD) Log.d(TAG, "Reset connections and stopping DHCP");
        mHaveIpAddress = false;
        mObtainingIpAddress = false;
        mWifiInfo.setIpAddress(0);

        /*
         * Reset connection depends on both the interface and the IP assigned,
         * so it should be done before any chance of the IP being lost.
         */
        NetworkUtils.resetConnections(mInterfaceName);

        // Stop DHCP
        mDhcpTarget.setCancelCallback(true);
        mDhcpTarget.removeMessages(EVENT_DHCP_START);

        if (!NetworkUtils.stopDhcp(mInterfaceName)) {
            Log.e(TAG, "Could not stop DHCP");
        }

        /**
         * Interface is re-enabled in the supplicant
         * when moving out of ASSOCIATING state
         */
        if(disableInterface) {
            if (LOCAL_LOGD) Log.d(TAG, "Disabling interface");
            NetworkUtils.disableInterface(mInterfaceName);
        }
    }

    /**
     * The supplicant is reporting that we are disconnected from the current
     * access point. Often, however, a disconnect will be followed very shortly
     * by a reconnect to the same access point. Therefore, we delay resetting
     * the connection's IP state for a bit.
     */
    private void scheduleDisconnect() {
        mDisconnectPending = true;
        if (!hasMessages(EVENT_DEFERRED_DISCONNECT)) {
            sendEmptyMessageDelayed(EVENT_DEFERRED_DISCONNECT, DISCONNECT_DELAY_MSECS);
        }
    }

    private void cancelDisconnect() {
        mDisconnectPending = false;
        removeMessages(EVENT_DEFERRED_DISCONNECT);
    }

    public DhcpInfo getDhcpInfo() {
        return mDhcpInfo;
    }

    public synchronized List<ScanResult> getScanResultsList() {
        return mScanResults;
    }

    public synchronized void setScanResultsList(List<ScanResult> scanList) {
        mScanResults = scanList;
    }

    /**
     * Get status information for the current connection, if any.
     * @return a {@link WifiInfo} object containing information about the current connection
     */
    public WifiInfo requestConnectionInfo() {
        requestConnectionStatus(mWifiInfo);
        requestPolledInfo(mWifiInfo, false);
        return mWifiInfo;
    }

    private void requestConnectionStatus(WifiInfo info) {
        String SSID = null;
        String BSSID = null;
        String suppState = null;
        int netId = -1;
        String reply = status();
        if (reply != null) {
            /*
             * Parse the reply from the supplicant to the status command, and update
             * local state accordingly. The reply is a series of lines of the form
             * "name=value".
             */

            String[] lines = reply.split("\n");
            for (String line : lines) {
                String[] prop = line.split(" *= *");
                if (prop.length < 2)
                    continue;
                String name = prop[0];
                String value = prop[1];
                if (name.equalsIgnoreCase("id"))
                    netId = Integer.parseInt(value);
                else if (name.equalsIgnoreCase("ssid"))
                    SSID = value;
                else if (name.equalsIgnoreCase("bssid"))
                    BSSID = value;
                else if (name.equalsIgnoreCase("wpa_state"))
                    suppState = value;
            }
        }
        info.setNetworkId(netId);
        info.setSSID(SSID);
        info.setBSSID(BSSID);
        /*
         * We only set the supplicant state if the previous state was
         * UNINITIALIZED. This should only happen when we first connect to
         * the supplicant. Once we're connected, we should always receive
         * an event upon any state change, but in this case, we want to
         * make sure any listeners are made aware of the state change.
         */
        if (mWifiInfo.getSupplicantState() == SupplicantState.UNINITIALIZED && suppState != null)
            setSupplicantState(suppState);
    }

    /**
     * Get the dynamic information that is not reported via events.
     * @param info the object into which the information should be captured.
     */
    private synchronized void requestPolledInfo(WifiInfo info, boolean polling)
    {
        int newRssi = (polling ? getRssiApprox() : getRssi());
        if (newRssi != -1 && -200 < newRssi && newRssi < 256) { // screen out invalid values
            /* some implementations avoid negative values by adding 256
             * so we need to adjust for that here.
             */
            if (newRssi > 0) newRssi -= 256;
            info.setRssi(newRssi);
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
            // TODO: The second arg to the call below needs to be a symbol somewhere, but
            // it's actually the size of an array of icons that's private
            // to StatusBar Policy.
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi, 4);
            if (newSignalLevel != mLastSignalLevel) {
                sendRssiChangeBroadcast(newRssi);
            }
            mLastSignalLevel = newSignalLevel;
        } else {
            info.setRssi(-200);
        }
        int newLinkSpeed = getLinkSpeed();
        if (newLinkSpeed != -1) {
            info.setLinkSpeed(newLinkSpeed);
        }
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        if (ActivityManagerNative.isSystemReady()) {
            Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
            intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
            mContext.sendBroadcast(intent);
        }
    }

    private void sendNetworkStateChangeBroadcast(String bssid) {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, mNetworkInfo);
        if (bssid != null)
            intent.putExtra(WifiManager.EXTRA_BSSID, bssid);
        mContext.sendStickyBroadcast(intent);
    }

    /**
     * Disable Wi-Fi connectivity by stopping the driver.
     */
    public boolean teardown() {
        if (!mTornDownByConnMgr) {
            if (disconnectAndStop()) {
                setTornDownByConnMgr(true);
                return true;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    /**
     * Reenable Wi-Fi connectivity by restarting the driver.
     */
    public boolean reconnect() {
        if (mTornDownByConnMgr) {
            if (restart()) {
                setTornDownByConnMgr(false);
                return true;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    /**
     * We want to stop the driver, but if we're connected to a network,
     * we first want to disconnect, so that the supplicant is always in
     * a known state (DISCONNECTED) when the driver is stopped.
     * @return {@code true} if the operation succeeds, which means that the
     * disconnect or stop command was initiated.
     */
    public synchronized boolean disconnectAndStop() {
        boolean ret = true;;
        if (mRunState != RUN_STATE_STOPPING && mRunState != RUN_STATE_STOPPED) {
            // Take down any open network notifications
            setNotificationVisible(false, 0, false, 0);

            if (mWifiInfo.getSupplicantState() == SupplicantState.DORMANT) {
                ret = stopDriver();
            } else {
                ret = disconnect();
            }
            mRunState = RUN_STATE_STOPPING;
        }
        return ret;
    }

    public synchronized boolean restart() {
        if (isDriverStopped()) {
            mRunState = RUN_STATE_STARTING;
            resetConnections(true);
            return startDriver();
        }
        return true;
    }

    public int getWifiState() {
        return mWifiState.get();
    }

    public void setWifiState(int wifiState) {
        mWifiState.set(wifiState);
    }

    public boolean isAnyNetworkDisabled() {
        return mIsAnyNetworkDisabled.get();
    }

   /**
     * The WifiNative interface functions are listed below.
     * The only native call that is not synchronized on
     * WifiStateTracker is waitForEvent() which waits on a
     * seperate monitor channel.
     *
     * All supplicant commands need the wifi to be in an
     * enabled state. This can be done by checking the
     * mWifiState to be WIFI_STATE_ENABLED.
     *
     * All commands that can cause commands to driver
     * initiated need the driver state to be started.
     * This is done by checking isDriverStopped() to
     * be false.
     */

    /**
     * Load the driver and firmware
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean loadDriver() {
        return WifiNative.loadDriver();
    }

    /**
     * Unload the driver and firmware
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean unloadDriver() {
        return WifiNative.unloadDriver();
    }

    /**
     * Check the supplicant config and
     * start the supplicant daemon
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean startSupplicant() {
        return WifiNative.startSupplicant();
    }

    /**
     * Stop the supplicant daemon
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean stopSupplicant() {
        return WifiNative.stopSupplicant();
    }

    /**
     * Establishes two channels - control channel for commands
     * and monitor channel for notifying WifiMonitor
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean connectToSupplicant() {
        return WifiNative.connectToSupplicant();
    }

    /**
     * Close the control/monitor channels to supplicant
     */
    public synchronized void closeSupplicantConnection() {
        WifiNative.closeSupplicantConnection();
    }

    /**
     * Check if the supplicant is alive
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean ping() {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return WifiNative.pingCommand();
    }

    /**
     * initiate an active or passive scan
     *
     * @param forceActive true if it is a active scan
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean scan(boolean forceActive) {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        return WifiNative.scanCommand(forceActive);
    }

    /**
     * Specifies whether the supplicant or driver
     * take care of initiating scan and doing AP selection
     *
     * @param mode
     *    SUPPL_SCAN_HANDLING_NORMAL
     *    SUPPL_SCAN_HANDLING_LIST_ONLY
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean setScanResultHandling(int mode) {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return WifiNative.setScanResultHandlingCommand(mode);
    }

    /**
     * Fetch the scan results from the supplicant
     *
     * @return example result string
     * 00:bb:cc:dd:cc:ee       2427    166     [WPA-EAP-TKIP][WPA2-EAP-CCMP]   Net1
     * 00:bb:cc:dd:cc:ff       2412    165     [WPA-EAP-TKIP][WPA2-EAP-CCMP]   Net2
     */
    public synchronized String scanResults() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return null;
        }
        return WifiNative.scanResultsCommand();
    }

    /**
     * Set the scan mode - active or passive
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean setScanMode(boolean isScanModeActive) {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        if (mIsScanModeActive != isScanModeActive) {
            return WifiNative.setScanModeCommand(mIsScanModeActive = isScanModeActive);
        }
        return true;
    }

    /**
     * Disconnect from Access Point
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean disconnect() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        return WifiNative.disconnectCommand();
    }

    /**
     * Initiate a reconnection to AP
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean reconnectCommand() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        return WifiNative.reconnectCommand();
    }

    /**
     * Add a network
     *
     * @return network id of the new network
     */
    public synchronized int addNetwork() {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return -1;
        }
        return WifiNative.addNetworkCommand();
    }

    /**
     * Delete a network
     *
     * @param networkId id of the network to be removed
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean removeNetwork(int networkId) {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return mDisconnectExpected = WifiNative.removeNetworkCommand(networkId);
    }

    /**
     * Enable a network
     *
     * @param netId network id of the network
     * @param disableOthers true, if all other networks have to be disabled
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean enableNetwork(int netId, boolean disableOthers) {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        if (disableOthers) mIsAnyNetworkDisabled.set(true);
        return WifiNative.enableNetworkCommand(netId, disableOthers);
    }

    /**
     * Enable all networks
     *
     * @param networks list of configured networks
     */
    public synchronized void enableAllNetworks(List<WifiConfiguration> networks) {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return;
        }
        mIsAnyNetworkDisabled.set(false);
        for (WifiConfiguration config : networks) {
            if (config.status == WifiConfiguration.Status.DISABLED) {
                WifiNative.enableNetworkCommand(config.networkId, false);
            }
        }
    }

    /**
     * Disable a network
     *
     * @param netId network id of the network
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean disableNetwork(int netId) {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        mIsAnyNetworkDisabled.set(true);
        return WifiNative.disableNetworkCommand(netId);
    }

    /**
     * Initiate a re-association in supplicant
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean reassociate() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        return WifiNative.reassociateCommand();
    }

    /**
     * Blacklist a BSSID. This will avoid the AP if there are
     * alternate APs to connect
     *
     * @param bssid BSSID of the network
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean addToBlacklist(String bssid) {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return WifiNative.addToBlacklistCommand(bssid);
    }

    /**
     * Clear the blacklist list
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean clearBlacklist() {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return WifiNative.clearBlacklistCommand();
    }

    /**
     * List all configured networks
     *
     * @return list of networks or null on failure
     */
    public synchronized String listNetworks() {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return null;
        }
        return WifiNative.listNetworksCommand();
    }

    /**
     * Get network setting by name
     *
     * @param netId network id of the network
     * @param name network variable key
     * @return value corresponding to key
     */
    public synchronized String getNetworkVariable(int netId, String name) {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return null;
        }
        return WifiNative.getNetworkVariableCommand(netId, name);
    }

    /**
     * Set network setting by name
     *
     * @param netId network id of the network
     * @param name network variable key
     * @param value network variable value
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean setNetworkVariable(int netId, String name, String value) {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return WifiNative.setNetworkVariableCommand(netId, name, value);
    }

    /**
     * Get detailed status of the connection
     *
     * @return Example status result
     *  bssid=aa:bb:cc:dd:ee:ff
     *  ssid=TestNet
     *  id=3
     *  pairwise_cipher=NONE
     *  group_cipher=NONE
     *  key_mgmt=NONE
     *  wpa_state=COMPLETED
     *  ip_address=X.X.X.X
     */
    public synchronized String status() {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return null;
        }
        return WifiNative.statusCommand();
    }

    /**
     * Get RSSI to currently connected network
     *
     * @return RSSI value, -1 on failure
     */
    public synchronized int getRssi() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return -1;
        }
        return WifiNative.getRssiApproxCommand();
    }

    /**
     * Get approx RSSI to currently connected network
     *
     * @return RSSI value, -1 on failure
     */
    public synchronized int getRssiApprox() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return -1;
        }
        return WifiNative.getRssiApproxCommand();
    }

    /**
     * Get link speed to currently connected network
     *
     * @return link speed, -1 on failure
     */
    public synchronized int getLinkSpeed() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return -1;
        }
        return WifiNative.getLinkSpeedCommand();
    }

    /**
     * Start driver
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean startDriver() {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return WifiNative.startDriverCommand();
    }

    /**
     * Stop driver
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean stopDriver() {
        /* Driver stop should not happen only when supplicant event
         * DRIVER_STOPPED has already been handled */
        if (mWifiState.get() != WIFI_STATE_ENABLED || mRunState == RUN_STATE_STOPPED) {
            return false;
        }
        return WifiNative.stopDriverCommand();
    }

    /**
     * Start packet filtering
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean startPacketFiltering() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        return WifiNative.startPacketFiltering();
    }

    /**
     * Stop packet filtering
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean stopPacketFiltering() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        return WifiNative.stopPacketFiltering();
    }

    /**
     * Get power mode
     * @return power mode
     */
    public synchronized int getPowerMode() {
        if (mWifiState.get() != WIFI_STATE_ENABLED && !isDriverStopped()) {
            return -1;
        }
        return WifiNative.getPowerModeCommand();
    }

    /**
     * Set power mode
     * @param mode
     *     DRIVER_POWER_MODE_AUTO
     *     DRIVER_POWER_MODE_ACTIVE
     *
     * Uses reference counting to keep power mode active
     * as long as one entity wants power mode to be active.
     *
     * For example, WifiLock high perf mode can keep power mode active
     * or a DHCP session can keep it active. As long as one entity wants
     * it enabled, it should stay that way
     *
     */
    private synchronized void setPowerMode(int mode) {

        /* It is good to plumb power mode change
         * even if ref count indicates already done
         * since we could have a case of previous failure.
         */
        switch(mode) {
            case DRIVER_POWER_MODE_ACTIVE:
                mPowerModeRefCount++;
                break;
            case DRIVER_POWER_MODE_AUTO:
                mPowerModeRefCount--;
                if (mPowerModeRefCount > 0) {
                    return;
                } else {
                    /* Keep refcount from becoming negative */
                    mPowerModeRefCount = 0;
                }
                break;
        }

        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return;
        }

        WifiNative.setPowerModeCommand(mode);
    }

    /**
     * Set the number of allowed radio frequency channels from the system
     * setting value, if any.
     * @return {@code true} if the operation succeeds, {@code false} otherwise, e.g.,
     * the number of channels is invalid.
     */
    public synchronized boolean setNumAllowedChannels() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        try {
            return setNumAllowedChannels(
                    Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS));
        } catch (Settings.SettingNotFoundException e) {
            if (mNumAllowedChannels != 0) {
                WifiNative.setNumAllowedChannelsCommand(mNumAllowedChannels);
            }
            // otherwise, use the driver default
        }
        return true;
    }

    /**
     * Set the number of radio frequency channels that are allowed to be used
     * in the current regulatory domain.
     * @param numChannels the number of allowed channels. Must be greater than 0
     * and less than or equal to 16.
     * @return {@code true} if the operation succeeds, {@code false} otherwise, e.g.,
     * {@code numChannels} is outside the valid range.
     */
    public synchronized boolean setNumAllowedChannels(int numChannels) {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        mNumAllowedChannels = numChannels;
        return WifiNative.setNumAllowedChannelsCommand(numChannels);
    }

    /**
     * Get number of allowed channels
     *
     * @return channel count, -1 on failure
     */
    public synchronized int getNumAllowedChannels() {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return -1;
        }
        return WifiNative.getNumAllowedChannelsCommand();
    }

    /**
     * Set bluetooth coex mode:
     *
     * @param mode
     *  BLUETOOTH_COEXISTENCE_MODE_ENABLED
     *  BLUETOOTH_COEXISTENCE_MODE_DISABLED
     *  BLUETOOTH_COEXISTENCE_MODE_SENSE
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean setBluetoothCoexistenceMode(int mode) {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return false;
        }
        return WifiNative.setBluetoothCoexistenceModeCommand(mode);
    }

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param isBluetoothPlaying whether to enable or disable this mode
     */
    public synchronized void setBluetoothScanMode(boolean isBluetoothPlaying) {
        if (mWifiState.get() != WIFI_STATE_ENABLED || isDriverStopped()) {
            return;
        }
        WifiNative.setBluetoothCoexistenceScanModeCommand(isBluetoothPlaying);
    }

    /**
     * Save configuration on supplicant
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean saveConfig() {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return WifiNative.saveConfigCommand();
    }

    /**
     * Reload the configuration from file
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public synchronized boolean reloadConfig() {
        if (mWifiState.get() != WIFI_STATE_ENABLED) {
            return false;
        }
        return WifiNative.reloadConfigCommand();
    }

    public boolean setRadio(boolean turnOn) {
        return mWM.setWifiEnabled(turnOn);
    }

    /**
     * {@inheritDoc}
     * There are currently no Wi-Fi-specific features supported.
     * @param feature the name of the feature
     * @return {@code -1} indicating failure, always
     */
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * {@inheritDoc}
     * There are currently no Wi-Fi-specific features supported.
     * @param feature the name of the feature
     * @return {@code -1} indicating failure, always
     */
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    @Override
    public void interpretScanResultsAvailable() {

        // If we shouldn't place a notification on available networks, then
        // don't bother doing any of the following
        if (!mNotificationEnabled) return;

        NetworkInfo networkInfo = getNetworkInfo();

        State state = networkInfo.getState();
        if ((state == NetworkInfo.State.DISCONNECTED)
                || (state == NetworkInfo.State.UNKNOWN)) {

            // Look for an open network
            List<ScanResult> scanResults = getScanResultsList();
            if (scanResults != null) {
                int numOpenNetworks = 0;
                for (int i = scanResults.size() - 1; i >= 0; i--) {
                    ScanResult scanResult = scanResults.get(i);

                    if (TextUtils.isEmpty(scanResult.capabilities)) {
                        numOpenNetworks++;
                    }
                }
            
                if (numOpenNetworks > 0) {
                    if (++mNumScansSinceNetworkStateChange >= NUM_SCANS_BEFORE_ACTUALLY_SCANNING) {
                        /*
                         * We've scanned continuously at least
                         * NUM_SCANS_BEFORE_NOTIFICATION times. The user
                         * probably does not have a remembered network in range,
                         * since otherwise supplicant would have tried to
                         * associate and thus resetting this counter.
                         */
                        setNotificationVisible(true, numOpenNetworks, false, 0);
                    }
                    return;
                }
            }
        }
        
        // No open networks in range, remove the notification
        setNotificationVisible(false, 0, false, 0);
    }

    /**
     * Display or don't display a notification that there are open Wi-Fi networks.
     * @param visible {@code true} if notification should be visible, {@code false} otherwise
     * @param numNetworks the number networks seen
     * @param force {@code true} to force notification to be shown/not-shown,
     * even if it is already shown/not-shown.
     * @param delay time in milliseconds after which the notification should be made
     * visible or invisible.
     */
    public void setNotificationVisible(boolean visible, int numNetworks, boolean force, int delay) {
        
        // Since we use auto cancel on the notification, when the
        // mNetworksAvailableNotificationShown is true, the notification may
        // have actually been canceled.  However, when it is false we know
        // for sure that it is not being shown (it will not be shown any other
        // place than here)
        
        // If it should be hidden and it is already hidden, then noop
        if (!visible && !mNotificationShown && !force) {
            return;
        }

        Message message;
        if (visible) {
            
            // Not enough time has passed to show the notification again
            if (System.currentTimeMillis() < mNotificationRepeatTime) {
                return;
            }
            
            if (mNotification == null) {
                // Cache the Notification mainly so we can remove the
                // EVENT_NOTIFICATION_CHANGED message with this Notification from
                // the queue later
                mNotification = new Notification();
                mNotification.when = 0;
                mNotification.icon = ICON_NETWORKS_AVAILABLE;
                mNotification.flags = Notification.FLAG_AUTO_CANCEL;
                mNotification.contentIntent = PendingIntent.getActivity(mContext, 0,
                        new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK), 0);
            }

            CharSequence title = mContext.getResources().getQuantityText(
                    com.android.internal.R.plurals.wifi_available, numNetworks);
            CharSequence details = mContext.getResources().getQuantityText(
                    com.android.internal.R.plurals.wifi_available_detailed, numNetworks);
            mNotification.tickerText = title;
            mNotification.setLatestEventInfo(mContext, title, details, mNotification.contentIntent);
            
            mNotificationRepeatTime = System.currentTimeMillis() + NOTIFICATION_REPEAT_DELAY_MS;

            message = mTarget.obtainMessage(EVENT_NOTIFICATION_CHANGED, 1,
                    ICON_NETWORKS_AVAILABLE, mNotification);
            
        } else {

            // Remove any pending messages to show the notification
            mTarget.removeMessages(EVENT_NOTIFICATION_CHANGED, mNotification);
            
            message = mTarget.obtainMessage(EVENT_NOTIFICATION_CHANGED, 0, ICON_NETWORKS_AVAILABLE);
        }

        mTarget.sendMessageDelayed(message, delay);
        
        mNotificationShown = visible;
    }

    /**
     * Clears variables related to tracking whether a notification has been
     * shown recently.
     * <p>
     * After calling this method, the timer that prevents notifications from
     * being shown too often will be cleared.
     */
    private void resetNotificationTimer() {
        mNotificationRepeatTime = 0;
        mNumScansSinceNetworkStateChange = 0;
    }
    
    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("interface ").append(mInterfaceName);
        sb.append(" runState=");
        if (mRunState >= 1 && mRunState <= mRunStateNames.length) {
            sb.append(mRunStateNames[mRunState-1]);
        } else {
            sb.append(mRunState);
        }
        sb.append(LS).append(mWifiInfo).append(LS);
        sb.append(mDhcpInfo).append(LS);
        sb.append("haveIpAddress=").append(mHaveIpAddress).
                append(", obtainingIpAddress=").append(mObtainingIpAddress).
                append(", scanModeActive=").append(mIsScanModeActive).append(LS).
                append("lastSignalLevel=").append(mLastSignalLevel).
                append(", explicitlyDisabled=").append(mTornDownByConnMgr);
        return sb.toString();
    }

    private class DhcpHandler extends Handler {

        private Handler mWifiStateTrackerHandler;
        
        /**
         * Whether to skip the DHCP result callback to the target. For example,
         * this could be set if the network we were requesting an IP for has
         * since been disconnected.
         * <p>
         * Note: There is still a chance where the client's intended DHCP
         * request not being canceled. For example, we are request for IP on
         * A, and he queues request for IP on B, and then cancels the request on
         * B while we're still requesting from A.
         */
        private boolean mCancelCallback;

        /**
         * Instance of the bluetooth headset helper. This needs to be created
         * early because there is a delay before it actually 'connects', as
         * noted by its javadoc. If we check before it is connected, it will be
         * in an error state and we will not disable coexistence.
         */
        private BluetoothHeadset mBluetoothHeadset;

        public DhcpHandler(Looper looper, Handler target) {
            super(looper);
            mWifiStateTrackerHandler = target;
            
            mBluetoothHeadset = new BluetoothHeadset(mContext, null);
        }

        public void handleMessage(Message msg) {
            int event;

            switch (msg.what) {
                case EVENT_DHCP_START:
                case EVENT_DHCP_RENEW:
                    boolean modifiedBluetoothCoexistenceMode = false;
                    int powerMode = DRIVER_POWER_MODE_AUTO;

                    if (shouldDisableCoexistenceMode()) {
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
                        modifiedBluetoothCoexistenceMode = true;

                        // Disable the coexistence mode
                        setBluetoothCoexistenceMode(
                                WifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED);
                    }

                    powerMode = getPowerMode();
                    if (powerMode < 0) {
                        // Handle the case where supplicant driver does not support
                        // getPowerModeCommand.
                        powerMode = DRIVER_POWER_MODE_AUTO;
                    }
                    if (powerMode != DRIVER_POWER_MODE_ACTIVE) {
                        setPowerMode(DRIVER_POWER_MODE_ACTIVE);
                    }

                    synchronized (this) {
                        // A new request is being made, so assume we will callback
                        mCancelCallback = false;
                    }

                    if (msg.what == EVENT_DHCP_START) {
                        Log.d(TAG, "DHCP request started");
                        if (NetworkUtils.runDhcp(mInterfaceName, mDhcpInfo)) {
                            event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                            Log.d(TAG, "DHCP succeeded with lease: " + mDhcpInfo.leaseDuration);
                            setDhcpRenewalAlarm(mDhcpInfo.leaseDuration);
                       } else {
                            event = EVENT_INTERFACE_CONFIGURATION_FAILED;
                            Log.e(TAG, "DHCP request failed: " + NetworkUtils.getDhcpError());
                        }
                        synchronized (this) {
                            if (!mCancelCallback) {
                                mWifiStateTrackerHandler.sendEmptyMessage(event);
                            }
                        }

                    } else if (msg.what == EVENT_DHCP_RENEW) {
                        Log.d(TAG, "DHCP renewal started");
                        int oIp = mDhcpInfo.ipAddress;
                        int oGw = mDhcpInfo.gateway;
                        int oMsk = mDhcpInfo.netmask;
                        int oDns1 = mDhcpInfo.dns1;
                        int oDns2 = mDhcpInfo.dns2;

                        if (NetworkUtils.runDhcpRenew(mInterfaceName, mDhcpInfo)) {
                            Log.d(TAG, "DHCP renewal with lease: " + mDhcpInfo.leaseDuration);

                            boolean changed =
                                (oIp   != mDhcpInfo.ipAddress ||
                                 oGw   != mDhcpInfo.gateway ||
                                 oMsk  != mDhcpInfo.netmask ||
                                 oDns1 != mDhcpInfo.dns1 ||
                                 oDns2 != mDhcpInfo.dns2);

                            if (changed) {
                                Log.d(TAG, "IP config change on renewal");
                                mWifiInfo.setIpAddress(mDhcpInfo.ipAddress);
                                NetworkUtils.resetConnections(mInterfaceName);
                                msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED,
                                        mNetworkInfo);
                                msg.sendToTarget();
                            }

                            setDhcpRenewalAlarm(mDhcpInfo.leaseDuration);
                        } else {
                            event = EVENT_INTERFACE_CONFIGURATION_FAILED;
                            Log.d(TAG, "DHCP renewal failed: " + NetworkUtils.getDhcpError());

                            synchronized (this) {
                                if (!mCancelCallback) {
                                    mWifiStateTrackerHandler.sendEmptyMessage(event);
                                }
                            }
                        }
                    }

                    if (powerMode != DRIVER_POWER_MODE_ACTIVE) {
                        setPowerMode(powerMode);
                    }

                    if (modifiedBluetoothCoexistenceMode) {
                        // Set the coexistence mode back to its default value
                        setBluetoothCoexistenceMode(
                                WifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE);
                    }

                    break;
            }
        }

        public synchronized void setCancelCallback(boolean cancelCallback) {
            mCancelCallback = cancelCallback;
            if (cancelCallback) {
                mAlarmManager.cancel(mDhcpRenewalIntent);
            }
        }

        /**
         * Whether to disable coexistence mode while obtaining IP address. This
         * logic will return true only if the current bluetooth
         * headset/handsfree state is disconnected. This means if it is in an
         * error state, we will NOT disable coexistence mode to err on the side
         * of safety.
         * 
         * @return Whether to disable coexistence mode.
         */
        private boolean shouldDisableCoexistenceMode() {
            int state = mBluetoothHeadset.getState(mBluetoothHeadset.getCurrentHeadset());
            return state == BluetoothHeadset.STATE_DISCONNECTED;
        }

        private void setDhcpRenewalAlarm(long leaseDuration) {
            //Do it a bit earlier than half the lease duration time
            //to beat the native DHCP client and avoid extra packets
            //48% for one hour lease time = 29 minutes
            if (leaseDuration < MIN_RENEWAL_TIME_SECS) {
                leaseDuration = MIN_RENEWAL_TIME_SECS;
            }
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() +
                    leaseDuration * 480, //in milliseconds
                    mDhcpRenewalIntent);
        }

    }
    
    private void checkUseStaticIp() {
        mUseStaticIp = false;
        final ContentResolver cr = mContext.getContentResolver();
        try {
            if (Settings.System.getInt(cr, Settings.System.WIFI_USE_STATIC_IP) == 0) {
                return;
            }
        } catch (Settings.SettingNotFoundException e) {
            return;
        }

        try {
            String addr = Settings.System.getString(cr, Settings.System.WIFI_STATIC_IP);
            if (addr != null) {
                mDhcpInfo.ipAddress = stringToIpAddr(addr);
            } else {
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.WIFI_STATIC_GATEWAY);
            if (addr != null) {
                mDhcpInfo.gateway = stringToIpAddr(addr);
            } else {
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.WIFI_STATIC_NETMASK);
            if (addr != null) {
                mDhcpInfo.netmask = stringToIpAddr(addr);
            } else {
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.WIFI_STATIC_DNS1);
            if (addr != null) {
                mDhcpInfo.dns1 = stringToIpAddr(addr);
            } else {
                return;
            }
            addr = Settings.System.getString(cr, Settings.System.WIFI_STATIC_DNS2);
            if (addr != null) {
                mDhcpInfo.dns2 = stringToIpAddr(addr);
            } else {
                mDhcpInfo.dns2 = 0;
            }
        } catch (UnknownHostException e) {
            return;
        }
        mUseStaticIp = true;
    }

    private static int stringToIpAddr(String addrString) throws UnknownHostException {
        try {
            String[] parts = addrString.split("\\.");
            if (parts.length != 4) {
                throw new UnknownHostException(addrString);
            }

            int a = Integer.parseInt(parts[0])      ;
            int b = Integer.parseInt(parts[1]) <<  8;
            int c = Integer.parseInt(parts[2]) << 16;
            int d = Integer.parseInt(parts[3]) << 24;

            return a | b | c | d;
        } catch (NumberFormatException ex) {
            throw new UnknownHostException(addrString);
        }
    }

    private int getMaxDhcpRetries() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                                      Settings.Secure.WIFI_MAX_DHCP_RETRY_COUNT,
                                      DEFAULT_MAX_DHCP_RETRIES);
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.WIFI_USE_STATIC_IP), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.WIFI_STATIC_IP), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.WIFI_STATIC_GATEWAY), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.WIFI_STATIC_NETMASK), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.WIFI_STATIC_DNS1), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                Settings.System.WIFI_STATIC_DNS2), false, this);
        }

        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            boolean wasStaticIp = mUseStaticIp;
            int oIp, oGw, oMsk, oDns1, oDns2;
            oIp = oGw = oMsk = oDns1 = oDns2 = 0;
            if (wasStaticIp) {
                oIp = mDhcpInfo.ipAddress;
                oGw = mDhcpInfo.gateway;
                oMsk = mDhcpInfo.netmask;
                oDns1 = mDhcpInfo.dns1;
                oDns2 = mDhcpInfo.dns2;
            }
            checkUseStaticIp();

            if (mWifiInfo.getSupplicantState() == SupplicantState.UNINITIALIZED) {
                return;
            }

            boolean changed =
                (wasStaticIp != mUseStaticIp) ||
                    (wasStaticIp && (
                        oIp   != mDhcpInfo.ipAddress ||
                        oGw   != mDhcpInfo.gateway ||
                        oMsk  != mDhcpInfo.netmask ||
                        oDns1 != mDhcpInfo.dns1 ||
                        oDns2 != mDhcpInfo.dns2));

            if (changed) {
                resetConnections(true);
                configureInterface();
                if (mUseStaticIp) {
                    Message msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
                    msg.sendToTarget();
                }
            }
        }
    }

    private class NotificationEnabledSettingObserver extends ContentObserver {

        public NotificationEnabledSettingObserver(Handler handler) {
            super(handler);
        }

        public void register() {
            ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON), true, this);
            mNotificationEnabled = getValue();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            mNotificationEnabled = getValue();
            if (!mNotificationEnabled) {
                // Remove any notification that may be showing
                setNotificationVisible(false, 0, true, 0);
            }

            resetNotificationTimer();
        }

        private boolean getValue() {
            return Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1) == 1;
        }
    }
}
