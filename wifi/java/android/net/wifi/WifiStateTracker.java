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

import android.net.NetworkInfo;
import android.net.NetworkStateTracker;
import android.net.DhcpInfo;
import android.net.NetworkUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo.State;
import android.os.Message;
import android.os.Parcelable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Config;
import android.app.Notification;
import android.app.PendingIntent;
import android.bluetooth.BluetoothHeadset;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.Context;
import android.database.ContentObserver;

import java.util.List;
import java.util.ArrayList;
import java.net.UnknownHostException;

/**
 * Track the state of Wifi connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * {@hide}
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
    private static final int EVENT_DEFERRED_DISCONNECT               = 10;
    private static final int EVENT_DEFERRED_RECONNECT                = 11;
    /**
     * The driver is started or stopped. The object will be the state: true for
     * started, false for stopped.
     */ 
    private static final int EVENT_DRIVER_STATE_CHANGED              = 12;
    private static final int EVENT_PASSWORD_KEY_MAY_BE_INCORRECT     = 13;

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
     * The maximum number of times we will retry a connection to an access point
     * for which we have failed in acquiring an IP address from DHCP. A value of
     * N means that we will make N+1 connection attempts in all.
     * <p>
     * See {@link Settings.Secure#WIFI_MAX_DHCP_RETRY_COUNT}. This is the default
     * value if a Settings value is not present.
     */
    private static final int DEFAULT_MAX_DHCP_RETRIES = 2;

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
    private boolean mHaveIPAddress;
    private boolean mObtainingIPAddress;
    private boolean mTornDownByConnMgr;
    private boolean mDisconnectPending;
    private DhcpHandler mDhcpTarget;
    private DhcpInfo mDhcpInfo;
    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private String mLastSsid;
    private int mLastNetworkId = -1;
    private boolean mUseStaticIp = false;
    private int mReconnectCount;

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
    private boolean mIsScanModeSetDueToAHiddenNetwork;
    private boolean mDriverIsStopped;
    private String mInterfaceName;
    private static String LS = System.getProperty("line.separator");

    private Runnable mReleaseWakeLockCallback;

    private static String[] sDnsPropNames;

    /**
     * A structure for supplying information about a supplicant state
     * change in the STATE_CHANGE event message that comes from the
     * WifiMonitor
     * thread.
     */
    private static class SupplicantStateChangeResult {
        SupplicantStateChangeResult(int networkId, SupplicantState state) {
            this.state = state;
            this.networkId = networkId;
        }
        int networkId;
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
        mHaveIPAddress = false;
        mObtainingIPAddress = false;
        setTornDownByConnMgr(false);
        mDisconnectPending = false;
        mScanResults = new ArrayList<ScanResult>();
        // Allocate DHCP info object once, and fill it in on each request
        mDhcpInfo = new DhcpInfo();
        mIsScanModeSetDueToAHiddenNetwork = false;

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
    }

    /**
     * Helper method: sets the supplicant state and keeps the network
     * info updated.
     * @param state the new state
     */
    private void setSupplicantState(SupplicantState state) {
        mWifiInfo.setSupplicantState(state);
        updateNetworkInfo();
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
    public boolean isAvailable() {
        /*
         * TODO: Should we also look at scan results to see whether we're
         * in range of any access points?
         */
        SupplicantState suppState = mWifiInfo.getSupplicantState();
        return suppState != SupplicantState.UNINITIALIZED &&
                suppState != SupplicantState.INACTIVE &&
                (mTornDownByConnMgr || !mDriverIsStopped);
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
    void notifyStateChange(int networkId, SupplicantState newState) {
        Message msg = Message.obtain(
            this, EVENT_SUPPLICANT_STATE_CHANGED,
            new SupplicantStateChangeResult(networkId, newState));
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
        synchronized (this) {
            WifiNative.setScanResultHandlingCommand(SUPPL_SCAN_HANDLING_NORMAL);
        }
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
     * The notification is in the form of a synthesized DISCONNECTED event.
     */
    void notifyDriverStopped() {
        setDriverStopped(true);
        Message msg = Message.obtain(
                this, EVENT_SUPPLICANT_STATE_CHANGED,
                new SupplicantStateChangeResult(-1, SupplicantState.DISCONNECTED));
        msg.sendToTarget();
        
        // Send a driver stopped message to our handler
        Message.obtain(this, EVENT_DRIVER_STATE_CHANGED, 0, 0).sendToTarget();
    }

    /**
     * Send the tracker a notification that the Wi-Fi driver has been restarted after
     * having been stopped.
     */
    void notifyDriverStarted() {
        setDriverStopped(false);

        // Send a driver started message to our handler
        Message.obtain(this, EVENT_DRIVER_STATE_CHANGED, 1, 0).sendToTarget();
    }
    
    /**
     * Set the interval timer for polling connection information
     * that is not delivered asynchronously.
     */
    private synchronized void setPollTimer () {
        if (!hasMessages(EVENT_POLL_INTERVAL)) {
            sendEmptyMessageDelayed(EVENT_POLL_INTERVAL, POLL_STATUS_INTERVAL_MSECS);
        }
    }

    private synchronized void setDriverStopped(boolean isStopped) {
        mDriverIsStopped = isStopped;
    }

    private synchronized boolean isDriverStopped() {
        return mDriverIsStopped;
    }

    /**
     * Set the number of allowed radio frequency channels from the system
     * setting value, if any.
     * @return {@code true} if the operation succeeds, {@code false} otherwise, e.g.,
     * the number of channels is invalid.
     */
    public boolean setNumAllowedChannels() {
        try {
            return setNumAllowedChannels(
                    Settings.Secure.getInt(mContext.getContentResolver(),
                                           Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS));
        } catch (Settings.SettingNotFoundException e) {
            // if setting doesn't exist, stick with the driver default
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
        return WifiNative.setNumAllowedChannelsCommand(numChannels);
    }

    /**
     * Start the Wi-Fi driver, if it is in the stopped state. If
     * the driver has been stopped as a result of a teardown request
     * by the connectivity manager, then only the connectivity
     * manager can restart it.
     */
    public synchronized void startDriver() {
        if (mDriverIsStopped && !mTornDownByConnMgr) {
            WifiNative.startDriverCommand();
        }
    }

    /**
     * Stop the Wi-Fi driver, if it is not already in the stopped state.
     */
    public synchronized void stopDriver() {
        if (!mDriverIsStopped) {
            WifiNative.stopDriverCommand();
        }
    }

    @Override
    public void releaseWakeLock() {
        if (mReleaseWakeLockCallback != null) {
            mReleaseWakeLockCallback.run();
        }
    }
    
    public void setReleaseWakeLockCallback(Runnable callback) {
        mReleaseWakeLockCallback = callback;
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
                checkUseStaticIp();
                /*
                 * DHCP requests are blocking, so run them in a separate thread.
                 */
                HandlerThread dhcpThread = new HandlerThread("DHCP Handler Thread");
                dhcpThread.start();
                mDhcpTarget = new DhcpHandler(dhcpThread.getLooper(), this);
                mIsScanModeActive = true;
                mLastBssid = null;
                mLastSsid = null;
                requestConnectionInfo();
                SupplicantState supplState = mWifiInfo.getSupplicantState();
                /**
                 * The MAC address isn't going to change, so just request it
                 * once here.
                 */
                String macaddr;
                synchronized (this) {
                    macaddr = WifiNative.getMacAddressCommand();
                }
                if (macaddr != null) {
                    mWifiInfo.setMacAddress(macaddr);
                }
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
                intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
                intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, true);
                mContext.sendBroadcast(intent);
                if (supplState == SupplicantState.COMPLETED && mHaveIPAddress) {
                    setDetailedState(DetailedState.CONNECTED);
                } else {
                    setDetailedState(WifiInfo.getDetailedStateOf(supplState));
                }
                break;

            case EVENT_SUPPLICANT_DISCONNECT:
                int wifiState = mWM.getWifiState();
                boolean died = wifiState != WifiManager.WIFI_STATE_DISABLED &&
                        wifiState != WifiManager.WIFI_STATE_DISABLING;
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
                synchronized (this) {
                    WifiNative.closeSupplicantConnection();
                }
                if (died) {
                    resetInterface();
                }
                // When supplicant dies, kill the DHCP thread
                if (mDhcpTarget != null) {
                    mDhcpTarget.getLooper().quit();
                    mDhcpTarget = null;
                }
                mContext.removeStickyBroadcast(new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION));
                intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
                intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, false);
                mContext.sendBroadcast(intent);
                setDetailedState(DetailedState.DISCONNECTED);
                setSupplicantState(SupplicantState.UNINITIALIZED);
                mHaveIPAddress = false;
                mObtainingIPAddress = false;
                break;

            case EVENT_SUPPLICANT_STATE_CHANGED:
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
                        handleDisconnectedState(DetailedState.FAILED);
                        sendNetworkStateChangeBroadcast();
                        sendEmptyMessageDelayed(EVENT_DEFERRED_RECONNECT, RECONNECT_DELAY_MSECS);
                    } else if (newState == SupplicantState.DISCONNECTED) {
                        if (isDriverStopped()) {
                            handleDisconnectedState(DetailedState.DISCONNECTED);
                            sendNetworkStateChangeBroadcast();
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

                    intent = new Intent(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
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
                        (!mHaveIPAddress || mDisconnectPending))) {
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
                    if (!TextUtils.equals(mWifiInfo.getSSID(), mLastSsid)) {
                        /*
                         * The connection is fully configured as far as link-level
                         * connectivity is concerned, but we may still need to obtain
                         * an IP address. But do this only if we are connecting to
                         * a different access point than we were connected to previously.
                         */
                        if (wasDisconnectPending) {
                            DetailedState saveState = getNetworkInfo().getDetailedState();
                            handleDisconnectedState(DetailedState.DISCONNECTED);
                            setDetailedStateInternal(saveState);
                        }
                        configureInterface();
                    }
                    mLastBssid = result.BSSID;
                    mLastSsid = mWifiInfo.getSSID();
                    mLastNetworkId = result.networkId;
                    if (mHaveIPAddress) {
                        setDetailedState(DetailedState.CONNECTED);
                    } else {
                        setDetailedState(DetailedState.OBTAINING_IPADDR);
                    }
                }
                sendNetworkStateChangeBroadcast();
                break;

            case EVENT_SCAN_RESULTS_AVAILABLE:

                mContext.sendBroadcast(new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                sendScanResultsAvailable();
                /**
                 * On receiving the first scan results after connecting to
                 * the supplicant, switch scan mode over to passive.
                 */
                if (!mIsScanModeSetDueToAHiddenNetwork) {
                    // This is the only place at the moment where we set
                    // the scan mode NOT due to a hidden network. This is
                    // what the second parameter value (false) stands for.
                    setScanMode(false, false);
                }
                break;

            case EVENT_POLL_INTERVAL:
                if (mWifiInfo.getSupplicantState() != SupplicantState.UNINITIALIZED) {
                    requestPolledInfo(mWifiInfo);
                    if (mWifiInfo.getSupplicantState() == SupplicantState.COMPLETED) {
                        setPollTimer();
                    }
                }
                break;
            
            case EVENT_DEFERRED_DISCONNECT:
                if (mWifiInfo.getSupplicantState() != SupplicantState.UNINITIALIZED) {
                    handleDisconnectedState(DetailedState.DISCONNECTED);
                }
                break;

            case EVENT_DEFERRED_RECONNECT:
                /*
                 * If we've exceeded the maximum number of retries for reconnecting
                 * to a given network, disable the network so that the supplicant
                 * will try some other network, if any is available.
                 * TODO: network ID may have changed since we stored it.
                 */
                if (mWifiInfo.getSupplicantState() != SupplicantState.UNINITIALIZED) {
                    if (++mReconnectCount > getMaxDhcpRetries()) {
                        mWM.disableNetwork(mLastNetworkId);
                    }
                    WifiNative.reconnectCommand();
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
                mHaveIPAddress = true;
                mObtainingIPAddress = false;
                mWifiInfo.setIpAddress(mDhcpInfo.ipAddress);
                mLastSignalLevel = -1; // force update of signal strength
                if (mNetworkInfo.getDetailedState() != DetailedState.CONNECTED) {
                    setDetailedState(DetailedState.CONNECTED);
                    sendNetworkStateChangeBroadcast();
                } else {
                    mTarget.sendEmptyMessage(EVENT_CONFIGURATION_CHANGED);
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
                
                    mHaveIPAddress = false;
                    mWifiInfo.setIpAddress(0);
                    mObtainingIPAddress = false;
                    WifiNative.disconnectCommand();
                }
                break;
                
            case EVENT_DRIVER_STATE_CHANGED:
                boolean driverStarted = msg.arg1 != 0;
                
                // Wi-Fi driver state changed:
                // [31- 1] Reserved for future use
                // [ 0- 0] Driver start (1) or stopped (0)   
                eventLogParam = driverStarted ? 1 : 0;
                EventLog.writeEvent(EVENTLOG_DRIVER_STATE_CHANGED, eventLogParam);
                
                if (driverStarted) {
                    /**
                     * Set the number of allowed radio channels according
                     * to the system setting, since it gets reset by the
                     * driver upon changing to the STARTED state.
                     */
                    setNumAllowedChannels();
                    synchronized (this) {
                        // In some situations, supplicant needs to be kickstarted to
                        // start the background scanning
                        WifiNative.scanCommand();
                    }
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

    public synchronized void setScanMode(
        boolean isScanModeActive, boolean setDueToAHiddenNetwork) {
        mIsScanModeSetDueToAHiddenNetwork = setDueToAHiddenNetwork;
        if (mIsScanModeActive != isScanModeActive) {
            WifiNative.setScanModeCommand(mIsScanModeActive = isScanModeActive);
        }
    }

    private void configureInterface() {
        setPollTimer();
        mLastSignalLevel = -1;
        if (!mUseStaticIp) {
            if (!mHaveIPAddress && !mObtainingIPAddress) {
                mObtainingIPAddress = true;
                mDhcpTarget.sendEmptyMessage(EVENT_DHCP_START);
            }
        } else {
            int event;
            if (NetworkUtils.configureInterface(mInterfaceName, mDhcpInfo)) {
                mHaveIPAddress = true;
                event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                if (LOCAL_LOGD) Log.v(TAG, "Static IP configuration succeeded");
            } else {
                mHaveIPAddress = false;
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
     */
    private void handleDisconnectedState(DetailedState newState) {
        if (LOCAL_LOGD) Log.d(TAG, "Deconfiguring interface and stopping DHCP");
        if (mDisconnectPending) {
            cancelDisconnect();
        }
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, mNetworkInfo);
        if (mLastBssid != null)
            intent.putExtra(WifiManager.EXTRA_BSSID, mLastBssid);
        mWifiInfo.setBSSID(null);
        mLastBssid = null;
        mLastSsid = null;
        resetInterface();
        mContext.sendStickyBroadcast(intent);
        setDetailedState(newState);
        mDisconnectPending = false;
    }

    /**
     * Resets the Wi-Fi interface by clearing any state, resetting any sockets
     * using the interface, stopping DHCP, and disabling the interface.
     */
    public void resetInterface() {
        mHaveIPAddress = false;
        mObtainingIPAddress = false;
        mWifiInfo.setIpAddress(0);

        /*
         * Reset connection depends on both the interface and the IP assigned,
         * so it should be done before any chance of the IP being lost.
         */  
        NetworkUtils.resetConnections(mInterfaceName);
        
        // Stop DHCP
        if (mDhcpTarget != null) {
            mDhcpTarget.setCancelCallback(true);
        }
        if (!NetworkUtils.stopDhcp(mInterfaceName)) {
            Log.e(TAG, "Could not stop DHCP");
        }
        
        NetworkUtils.disableInterface(mInterfaceName);
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
        requestPolledInfo(mWifiInfo);
/*
        WifiInfo info = mWifiInfo;

        Log.v(TAG, "Status from supplicant: NID=" + info.getNetworkId() +
            " SSID=" + (info.getSSID() == null ? "<none>" : info.getSSID()) +
            " BSSID=" + (info.getBSSID() == null ? "<none>" : info.getBSSID()) +
            " WPA_STATE=" + (info.getSupplicantState() == null ? "<none>" : info.getSupplicantState()) +
            " RSSI=" + info.getRssi());
*/

        return mWifiInfo;
    }

    private void requestConnectionStatus(WifiInfo info) {
        String reply;
        synchronized (this) {
            reply = WifiNative.statusCommand();
        }
        if (reply == null) {
            return;
        }
        /*
         * Parse the reply from the supplicant to the status command, and update
         * local state accordingly. The reply is a series of lines of the form
         * "name=value".
         */
        String SSID = null;
        String BSSID = null;
        String suppState = null;
        int netId = -1;
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
    private synchronized void requestPolledInfo(WifiInfo info)
    {
        int newRssi = WifiNative.getRssiCommand();
        if (newRssi != -1 && -200 < newRssi && newRssi < 100) { // screen out invalid values
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
            // TODO: The "3" below needs to be a symbol somewhere, but
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
        int newLinkSpeed = WifiNative.getLinkSpeedCommand();
        if (newLinkSpeed != -1) {
            info.setLinkSpeed(newLinkSpeed);
        }
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
        mContext.sendBroadcast(intent);
    }

    private void sendNetworkStateChangeBroadcast() {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, mNetworkInfo);
        if (mWifiInfo.getBSSID() != null)
            intent.putExtra(WifiManager.EXTRA_BSSID, mWifiInfo.getBSSID());
        mContext.sendStickyBroadcast(intent);
    }

    /**
     * Disable Wi-Fi connectivity by stopping the driver.
     */
    public synchronized boolean teardown() {
        // Take down any open network notifications
        setNotificationVisible(false, 0, false, 0);
        
        if (!mTornDownByConnMgr) {
            boolean result = WifiNative.stopDriverCommand();
            setTornDownByConnMgr(result);
            return result;
        } else {
            return true;
        }
    }

    /**
     * Reenable Wi-Fi connectivity by restarting the driver.
     */
    public synchronized boolean reconnect() {
        if (mTornDownByConnMgr) {
            boolean result = WifiNative.startDriverCommand();
            setTornDownByConnMgr(!result);
            return result;
        } else {
            return true;
        }
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
    
    public synchronized boolean addToBlacklist(String bssid) {
        return WifiNative.addToBlacklistCommand(bssid);
    }
    
    public synchronized boolean clearBlacklist() {
        return WifiNative.clearBlacklistCommand();
    }
    
    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(mWifiInfo).append(LS);
        sb.append("interface ").append(mInterfaceName).append(LS);
        sb.append(mDhcpInfo).append(LS);
        sb.append("haveIpAddress=").append(mHaveIPAddress).
                append(", obtainingIpAddress=").append(mObtainingIPAddress).
                append(", scanModeActive=").append(mIsScanModeActive).append(LS).
                append("lastSignalLevel=").append(mLastSignalLevel).
                append(", explicitlyDisabled=").append(mTornDownByConnMgr);
        return sb.toString();
    }

    private class DhcpHandler extends Handler {

        private Handler mTarget;
        
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
            mTarget = target;
            
            mBluetoothHeadset = new BluetoothHeadset(mContext, null);
        }

        public void handleMessage(Message msg) {
            int event;

            switch (msg.what) {
                case EVENT_DHCP_START:
                    
                    boolean modifiedBluetoothCoexistenceMode = false;
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
                        synchronized (WifiStateTracker.this) {
                            WifiNative.setBluetoothCoexistenceModeCommand(
                                    WifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED);
                        }
                    }
                    
                    synchronized (WifiStateTracker.this) {
                        WifiNative.setPowerModeCommand(DRIVER_POWER_MODE_ACTIVE);
                    }
                    synchronized (this) {
                        // A new request is being made, so assume we will callback
                        mCancelCallback = false;
                    }
                    Log.d(TAG, "DhcpHandler: DHCP request started");
                    if (NetworkUtils.runDhcp(mInterfaceName, mDhcpInfo)) {
                        event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                        if (LOCAL_LOGD) Log.v(TAG, "DhcpHandler: DHCP request succeeded");
                    } else {
                        event = EVENT_INTERFACE_CONFIGURATION_FAILED;
                        Log.i(TAG, "DhcpHandler: DHCP request failed: " +
                            NetworkUtils.getDhcpError());
                    }
                    synchronized (WifiStateTracker.this) {
                        WifiNative.setPowerModeCommand(DRIVER_POWER_MODE_AUTO);
                    }
                    
                    if (modifiedBluetoothCoexistenceMode) {
                        // Set the coexistence mode back to its default value
                        synchronized (WifiStateTracker.this) {
                            WifiNative.setBluetoothCoexistenceModeCommand(
                                    WifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE);
                        }
                    }
                    
                    synchronized (this) {
                        if (!mCancelCallback) {
                            mTarget.sendEmptyMessage(event);
                        }
                    }
                    break;
            }
        }
        
        public synchronized void setCancelCallback(boolean cancelCallback) {
            mCancelCallback = cancelCallback;
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
            int state = mBluetoothHeadset.getState();
            return state == BluetoothHeadset.STATE_DISCONNECTED;
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
                resetInterface();
                configureInterface();
                if (mUseStaticIp) {
                    mTarget.sendEmptyMessage(EVENT_CONFIGURATION_CHANGED);
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
