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
 * TODO: Add soft AP states as part of WIFI_STATE_XXX
 * Retain WIFI_STATE_ENABLING that indicates driver is loading
 * Add WIFI_STATE_AP_ENABLED to indicate soft AP has started
 * and WIFI_STATE_FAILED for failure
 * Deprecate WIFI_STATE_UNKNOWN
 *
 * Doing this will simplify the logic for sending broadcasts
 */
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import android.app.ActivityManagerNative;
import android.net.NetworkInfo;
import android.net.DhcpInfo;
import android.net.NetworkUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkProperties;
import android.net.wifi.WifiConfiguration.Status;
import android.os.Binder;
import android.os.Message;
import android.os.Parcelable;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.app.backup.IBackupManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothA2dp;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.Context;
import android.database.ContentObserver;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.HierarchicalState;
import com.android.internal.util.HierarchicalStateMachine;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Track the state of Wifi connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * @hide
 */
//TODO: we still need frequent scanning for the case when
// we issue disconnect but need scan results for open network notification
public class WifiStateMachine extends HierarchicalStateMachine {

    private static final String TAG = "WifiStateMachine";
    private static final String NETWORKTYPE = "WIFI";
    private static final boolean DBG = false;

    /* TODO: fetch a configurable interface */
    private static final String SOFTAP_IFACE = "wl0.1";

    private WifiMonitor mWifiMonitor;
    private INetworkManagementService nwService;
    private ConnectivityManager mCm;

    /* Scan results handling */
    private List<ScanResult> mScanResults;
    private static final Pattern scanResultPattern = Pattern.compile("\t+");
    private static final int SCAN_RESULT_CACHE_SIZE = 80;
    private final LinkedHashMap<String, ScanResult> mScanResultCache;

    private String mInterfaceName;

    private int mNumAllowedChannels = 0;
    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private int mLastNetworkId;
    private boolean mEnableRssiPolling = false;
    private boolean mPasswordKeyMayBeIncorrect = false;
    private boolean mUseStaticIp = false;
    private int mReconnectCount = 0;
    private boolean mIsScanMode = false;
    private boolean mConfigChanged = false;
    private List<WifiConfiguration> mConfiguredNetworks = new ArrayList<WifiConfiguration>();

    /**
     * Instance of the bluetooth headset helper. This needs to be created
     * early because there is a delay before it actually 'connects', as
     * noted by its javadoc. If we check before it is connected, it will be
     * in an error state and we will not disable coexistence.
     */
    private BluetoothHeadset mBluetoothHeadset;

    private BluetoothA2dp mBluetoothA2dp;

    /**
     * Observes the static IP address settings.
     */
    private SettingsObserver mSettingsObserver;
    private NetworkProperties mNetworkProperties;

    // Held during driver load and unload
    private static PowerManager.WakeLock sWakeLock;

    private Context mContext;

    private DhcpInfo mDhcpInfo;
    private WifiInfo mWifiInfo;
    private NetworkInfo mNetworkInfo;
    private SupplicantStateTracker mSupplicantStateTracker;
    /* Tracks the highest priority of configured networks */
    private int mLastPriority = -1;
    /* Tracks if all networks need to be enabled */
    private boolean mEnableAllNetworks = false;

    // Event log tags (must be in sync with event-log-tags)
    private static final int EVENTLOG_WIFI_STATE_CHANGED        = 50021;
    private static final int EVENTLOG_WIFI_EVENT_HANDLED        = 50022;
    private static final int EVENTLOG_SUPPLICANT_STATE_CHANGED  = 50023;

    /* Load the driver */
    private static final int CMD_LOAD_DRIVER                      = 1;
    /* Unload the driver */
    private static final int CMD_UNLOAD_DRIVER                    = 2;
    /* Indicates driver load succeeded */
    private static final int CMD_LOAD_DRIVER_SUCCESS              = 3;
    /* Indicates driver load failed */
    private static final int CMD_LOAD_DRIVER_FAILURE              = 4;
    /* Indicates driver unload succeeded */
    private static final int CMD_UNLOAD_DRIVER_SUCCESS            = 5;
    /* Indicates driver unload failed */
    private static final int CMD_UNLOAD_DRIVER_FAILURE            = 6;

    /* Start the supplicant */
    private static final int CMD_START_SUPPLICANT                 = 11;
    /* Stop the supplicant */
    private static final int CMD_STOP_SUPPLICANT                  = 12;
    /* Start the driver */
    private static final int CMD_START_DRIVER                     = 13;
    /* Start the driver */
    private static final int CMD_STOP_DRIVER                      = 14;
    /* Indicates DHCP succeded */
    private static final int CMD_IP_CONFIG_SUCCESS                = 15;
    /* Indicates DHCP failed */
    private static final int CMD_IP_CONFIG_FAILURE                = 16;
    /* Re-configure interface */
    private static final int CMD_RECONFIGURE_IP                   = 17;


    /* Start the soft access point */
    private static final int CMD_START_AP                         = 21;
    /* Stop the soft access point */
    private static final int CMD_STOP_AP                          = 22;


    /* Supplicant events */
    /* Connection to supplicant established */
    private static final int SUP_CONNECTION_EVENT                 = 31;
    /* Connection to supplicant lost */
    private static final int SUP_DISCONNECTION_EVENT              = 32;
    /* Driver start completed */
    private static final int DRIVER_START_EVENT                   = 33;
    /* Driver stop completed */
    private static final int DRIVER_STOP_EVENT                    = 34;
    /* Network connection completed */
    private static final int NETWORK_CONNECTION_EVENT             = 36;
    /* Network disconnection completed */
    private static final int NETWORK_DISCONNECTION_EVENT          = 37;
    /* Scan results are available */
    private static final int SCAN_RESULTS_EVENT                   = 38;
    /* Supplicate state changed */
    private static final int SUPPLICANT_STATE_CHANGE_EVENT        = 39;
    /* Password may be incorrect */
    private static final int PASSWORD_MAY_BE_INCORRECT_EVENT      = 40;

    /* Supplicant commands */
    /* Is supplicant alive ? */
    private static final int CMD_PING_SUPPLICANT                  = 51;
    /* Add/update a network configuration */
    private static final int CMD_ADD_OR_UPDATE_NETWORK            = 52;
    /* Delete a network */
    private static final int CMD_REMOVE_NETWORK                   = 53;
    /* Enable a network. The device will attempt a connection to the given network. */
    private static final int CMD_ENABLE_NETWORK                   = 54;
    /* Disable a network. The device does not attempt a connection to the given network. */
    private static final int CMD_DISABLE_NETWORK                  = 55;
    /* Blacklist network. De-prioritizes the given BSSID for connection. */
    private static final int CMD_BLACKLIST_NETWORK                = 56;
    /* Clear the blacklist network list */
    private static final int CMD_CLEAR_BLACKLIST                  = 57;
    /* Save configuration */
    private static final int CMD_SAVE_CONFIG                      = 58;

    /* Supplicant commands after driver start*/
    /* Initiate a scan */
    private static final int CMD_START_SCAN                       = 71;
    /* Set scan mode. CONNECT_MODE or SCAN_ONLY_MODE */
    private static final int CMD_SET_SCAN_MODE                    = 72;
    /* Set scan type. SCAN_ACTIVE or SCAN_PASSIVE */
    private static final int CMD_SET_SCAN_TYPE                    = 73;
    /* Disconnect from a network */
    private static final int CMD_DISCONNECT                       = 74;
    /* Reconnect to a network */
    private static final int CMD_RECONNECT                        = 75;
    /* Reassociate to a network */
    private static final int CMD_REASSOCIATE                      = 76;
    /* Set power mode
     * POWER_MODE_ACTIVE
     * POWER_MODE_AUTO
     */
    private static final int CMD_SET_POWER_MODE                   = 77;
    /* Set bluetooth co-existence
     * BLUETOOTH_COEXISTENCE_MODE_ENABLED
     * BLUETOOTH_COEXISTENCE_MODE_DISABLED
     * BLUETOOTH_COEXISTENCE_MODE_SENSE
     */
    private static final int CMD_SET_BLUETOOTH_COEXISTENCE        = 78;
    /* Enable/disable bluetooth scan mode
     * true(1)
     * false(0)
     */
    private static final int CMD_SET_BLUETOOTH_SCAN_MODE          = 79;
    /* Set number of allowed channels */
    private static final int CMD_SET_NUM_ALLOWED_CHANNELS         = 80;
    /* Request connectivity manager wake lock before driver stop */
    private static final int CMD_REQUEST_CM_WAKELOCK              = 81;
    /* Enables RSSI poll */
    private static final int CMD_ENABLE_RSSI_POLL                 = 82;
    /* RSSI poll */
    private static final int CMD_RSSI_POLL                        = 83;
    /* Get current RSSI */
    private static final int CMD_GET_RSSI                         = 84;
    /* Get approx current RSSI */
    private static final int CMD_GET_RSSI_APPROX                  = 85;
    /* Get link speed on connection */
    private static final int CMD_GET_LINK_SPEED                   = 86;
    /* Radio mac address */
    private static final int CMD_GET_MAC_ADDR                     = 87;
    /* Set up packet filtering */
    private static final int CMD_START_PACKET_FILTERING           = 88;
    /* Clear packet filter */
    private static final int CMD_STOP_PACKET_FILTERING            = 89;
    /* Connect to a specified network (network id
     * or WifiConfiguration) This involves increasing
     * the priority of the network, enabling the network
     * (while disabling others) and issuing a reconnect.
     * Note that CMD_RECONNECT just does a reconnect to
     * an existing network. All the networks get enabled
     * upon a successful connection or a failure.
     */
    private static final int CMD_CONNECT_NETWORK                  = 90;
    /* Save the specified network. This involves adding
     * an enabled network (if new) and updating the
     * config and issuing a save on supplicant config.
     */
    private static final int CMD_SAVE_NETWORK                     = 91;
    /* Delete the specified network. This involves
     * removing the network and issuing a save on
     * supplicant config.
     */
    private static final int CMD_FORGET_NETWORK                   = 92;



    /**
     * Interval in milliseconds between polling for connection
     * status items that are not sent via asynchronous events.
     * An example is RSSI (signal strength).
     */
    private static final int POLL_RSSI_INTERVAL_MSECS = 3000;

    private static final int CONNECT_MODE   = 1;
    private static final int SCAN_ONLY_MODE = 2;

    private static final int SCAN_ACTIVE = 1;
    private static final int SCAN_PASSIVE = 2;

    /**
     * The maximum number of times we will retry a connection to an access point
     * for which we have failed in acquiring an IP address from DHCP. A value of
     * N means that we will make N+1 connection attempts in all.
     * <p>
     * See {@link Settings.Secure#WIFI_MAX_DHCP_RETRY_COUNT}. This is the default
     * value if a Settings value is not present.
     */
    private static final int DEFAULT_MAX_DHCP_RETRIES = 9;

    private static final int DRIVER_POWER_MODE_ACTIVE = 1;
    private static final int DRIVER_POWER_MODE_AUTO = 0;

    /* Default parent state */
    private HierarchicalState mDefaultState = new DefaultState();
    /* Temporary initial state */
    private HierarchicalState mInitialState = new InitialState();
    /* Unloading the driver */
    private HierarchicalState mDriverUnloadingState = new DriverUnloadingState();
    /* Loading the driver */
    private HierarchicalState mDriverUnloadedState = new DriverUnloadedState();
    /* Driver load/unload failed */
    private HierarchicalState mDriverFailedState = new DriverFailedState();
    /* Driver loading */
    private HierarchicalState mDriverLoadingState = new DriverLoadingState();
    /* Driver loaded */
    private HierarchicalState mDriverLoadedState = new DriverLoadedState();
    /* Driver loaded, waiting for supplicant to start */
    private HierarchicalState mWaitForSupState = new WaitForSupState();

    /* Driver loaded and supplicant ready */
    private HierarchicalState mDriverSupReadyState = new DriverSupReadyState();
    /* Driver start issued, waiting for completed event */
    private HierarchicalState mDriverStartingState = new DriverStartingState();
    /* Driver started */
    private HierarchicalState mDriverStartedState = new DriverStartedState();
    /* Driver stopping */
    private HierarchicalState mDriverStoppingState = new DriverStoppingState();
    /* Driver stopped */
    private HierarchicalState mDriverStoppedState = new DriverStoppedState();
    /* Scan for networks, no connection will be established */
    private HierarchicalState mScanModeState = new ScanModeState();
    /* Connecting to an access point */
    private HierarchicalState mConnectModeState = new ConnectModeState();
    /* Fetching IP after network connection (assoc+auth complete) */
    private HierarchicalState mConnectingState = new ConnectingState();
    /* Connected with IP addr */
    private HierarchicalState mConnectedState = new ConnectedState();
    /* disconnect issued, waiting for network disconnect confirmation */
    private HierarchicalState mDisconnectingState = new DisconnectingState();
    /* Network is not connected, supplicant assoc+auth is not complete */
    private HierarchicalState mDisconnectedState = new DisconnectedState();

    /* Soft Ap is running */
    private HierarchicalState mSoftApStartedState = new SoftApStartedState();


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

    private final IBatteryStats mBatteryStats;

    public WifiStateMachine(Context context) {
        super(TAG);

        mContext = context;

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, NETWORKTYPE, "");
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batteryinfo"));

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        nwService = INetworkManagementService.Stub.asInterface(b);

        mWifiMonitor = new WifiMonitor(this);
        mDhcpInfo = new DhcpInfo();
        mWifiInfo = new WifiInfo();
        mInterfaceName = SystemProperties.get("wifi.interface", "tiwlan0");
        mSupplicantStateTracker = new SupplicantStateTracker(context, getHandler());

        mBluetoothHeadset = new BluetoothHeadset(mContext, null);
        mNetworkProperties = new NetworkProperties();

        mNetworkInfo.setIsAvailable(false);
        mNetworkProperties.clear();
        mLastBssid = null;
        mLastNetworkId = -1;
        mLastSignalLevel = -1;

        mScanResultCache = new LinkedHashMap<String, ScanResult>(
            SCAN_RESULT_CACHE_SIZE, 0.75f, true) {
                /*
                 * Limit the cache size by SCAN_RESULT_CACHE_SIZE
                 * elements
                 */
                @Override
                public boolean removeEldestEntry(Map.Entry eldest) {
                    return SCAN_RESULT_CACHE_SIZE < this.size();
                }
        };

        mSettingsObserver = new SettingsObserver(new Handler());

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        sWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        addState(mDefaultState);
            addState(mInitialState, mDefaultState);
            addState(mDriverUnloadingState, mDefaultState);
            addState(mDriverUnloadedState, mDefaultState);
                addState(mDriverFailedState, mDriverUnloadedState);
            addState(mDriverLoadingState, mDefaultState);
            addState(mDriverLoadedState, mDefaultState);
                addState(mWaitForSupState, mDriverLoadedState);
            addState(mDriverSupReadyState, mDefaultState);
                addState(mDriverStartingState, mDriverSupReadyState);
                addState(mDriverStartedState, mDriverSupReadyState);
                    addState(mScanModeState, mDriverStartedState);
                    addState(mConnectModeState, mDriverStartedState);
                        addState(mConnectingState, mConnectModeState);
                        addState(mConnectedState, mConnectModeState);
                        addState(mDisconnectingState, mConnectModeState);
                        addState(mDisconnectedState, mConnectModeState);
                addState(mDriverStoppingState, mDriverSupReadyState);
                addState(mDriverStoppedState, mDriverSupReadyState);
            addState(mSoftApStartedState, mDefaultState);

        setInitialState(mInitialState);

        if (DBG) setDbg(true);

        //start the state machine
        start();
    }

    /*********************************************************
     * Methods exposed for public use
     ********************************************************/

    /**
     * TODO: doc
     */
    public boolean syncPingSupplicant() {
        return sendSyncMessage(CMD_PING_SUPPLICANT).boolValue;
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
        return mDhcpInfo;
    }

    /**
     * TODO: doc
     */
    public void setDriverStart(boolean enable) {
      if (enable) {
          sendMessage(CMD_START_DRIVER);
      } else {
          sendMessage(CMD_STOP_DRIVER);
      }
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
        return mScanResults;
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
    public int syncAddOrUpdateNetwork(WifiConfiguration config) {
        return sendSyncMessage(CMD_ADD_OR_UPDATE_NETWORK, config).intValue;
    }

    public List<WifiConfiguration> syncGetConfiguredNetworks() {
        List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        synchronized (mConfiguredNetworks) {
            Iterator<WifiConfiguration> iterator = mConfiguredNetworks.iterator();
            while(iterator.hasNext()) {
                networks.add(iterator.next().clone());
            }
        }
        return networks;
    }

    /**
     * Delete a network
     *
     * @param networkId id of the network to be removed
     */
    public boolean syncRemoveNetwork(int networkId) {
        return sendSyncMessage(obtainMessage(CMD_REMOVE_NETWORK, networkId, 0)).boolValue;
    }

    private class EnableNetParams {
        private int netId;
        private boolean disableOthers;
        EnableNetParams(int n, boolean b) {
            netId = n;
            disableOthers = b;
        }
    }
    /**
     * Enable a network
     *
     * @param netId network id of the network
     * @param disableOthers true, if all other networks have to be disabled
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncEnableNetwork(int netId, boolean disableOthers) {
        return sendSyncMessage(CMD_ENABLE_NETWORK,
                new EnableNetParams(netId, disableOthers)).boolValue;
    }

    /**
     * Disable a network
     *
     * @param netId network id of the network
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncDisableNetwork(int netId) {
        return sendSyncMessage(obtainMessage(CMD_DISABLE_NETWORK, netId, 0)).boolValue;
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

    public void connectNetwork(int netId) {
        sendMessage(obtainMessage(CMD_CONNECT_NETWORK, netId, 0));
    }

    public void connectNetwork(WifiConfiguration wifiConfig) {
        /* arg1 is used to indicate netId, force a netId value of -1 when
         * we are passing a configuration since the default value of
         * 0 is a valid netId
         */
        sendMessage(obtainMessage(CMD_CONNECT_NETWORK, -1, 0, wifiConfig));
    }

    public void saveNetwork(WifiConfiguration wifiConfig) {
        sendMessage(obtainMessage(CMD_SAVE_NETWORK, wifiConfig));
    }

    public void forgetNetwork(int netId) {
        sendMessage(obtainMessage(CMD_FORGET_NETWORK, netId, 0));
    }

    public void enableRssiPolling(boolean enabled) {
       sendMessage(obtainMessage(CMD_ENABLE_RSSI_POLL, enabled ? 1 : 0, 0));
    }
    /**
     * Get RSSI to currently connected network
     *
     * @return RSSI value, -1 on failure
     */
    public int syncGetRssi() {
        return sendSyncMessage(CMD_GET_RSSI).intValue;
    }

    /**
     * Get approx RSSI to currently connected network
     *
     * @return RSSI value, -1 on failure
     */
    public int syncGetRssiApprox() {
        return sendSyncMessage(CMD_GET_RSSI_APPROX).intValue;
    }

    /**
     * Get link speed to currently connected network
     *
     * @return link speed, -1 on failure
     */
    public int syncGetLinkSpeed() {
        return sendSyncMessage(CMD_GET_LINK_SPEED).intValue;
    }

    /**
     * Get MAC address of radio
     *
     * @return MAC address, null on failure
     */
    public String syncGetMacAddress() {
        return sendSyncMessage(CMD_GET_MAC_ADDR).stringValue;
    }

    /**
     * Start packet filtering
     */
    public void startPacketFiltering() {
        sendMessage(CMD_START_PACKET_FILTERING);
    }

    /**
     * Stop packet filtering
     */
    public void stopPacketFiltering() {
        sendMessage(CMD_STOP_PACKET_FILTERING);
    }

    /**
     * Set power mode
     * @param mode
     *     DRIVER_POWER_MODE_AUTO
     *     DRIVER_POWER_MODE_ACTIVE
     */
    public void setPowerMode(int mode) {
        sendMessage(obtainMessage(CMD_SET_POWER_MODE, mode, 0));
    }

    /**
     * Set the number of allowed radio frequency channels from the system
     * setting value, if any.
     */
    public void setNumAllowedChannels() {
        try {
            setNumAllowedChannels(
                    Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS));
        } catch (Settings.SettingNotFoundException e) {
            if (mNumAllowedChannels != 0) {
                setNumAllowedChannels(mNumAllowedChannels);
            }
            // otherwise, use the driver default
        }
    }

    /**
     * Set the number of radio frequency channels that are allowed to be used
     * in the current regulatory domain.
     * @param numChannels the number of allowed channels. Must be greater than 0
     * and less than or equal to 16.
     */
    public void setNumAllowedChannels(int numChannels) {
        sendMessage(obtainMessage(CMD_SET_NUM_ALLOWED_CHANNELS, numChannels, 0));
    }

    /**
     * Get number of allowed channels
     *
     * @return channel count, -1 on failure
     *
     * TODO: this is not a public API and needs to be removed in favor
     * of asynchronous reporting. unused for now.
     */
    public int getNumAllowedChannels() {
        return -1;
    }

    /**
     * Set bluetooth coex mode:
     *
     * @param mode
     *  BLUETOOTH_COEXISTENCE_MODE_ENABLED
     *  BLUETOOTH_COEXISTENCE_MODE_DISABLED
     *  BLUETOOTH_COEXISTENCE_MODE_SENSE
     */
    public void setBluetoothCoexistenceMode(int mode) {
        sendMessage(obtainMessage(CMD_SET_BLUETOOTH_COEXISTENCE, mode, 0));
    }

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param isBluetoothPlaying whether to enable or disable this mode
     */
    public void setBluetoothScanMode(boolean isBluetoothPlaying) {
        sendMessage(obtainMessage(CMD_SET_BLUETOOTH_SCAN_MODE, isBluetoothPlaying ? 1 : 0, 0));
    }

    /**
     * Save configuration on supplicant
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * TODO: deprecate this
     */
    public boolean syncSaveConfig() {
        return sendSyncMessage(CMD_SAVE_CONFIG).boolValue;
    }

    /**
     * TODO: doc
     */
    public void requestCmWakeLock() {
        sendMessage(CMD_REQUEST_CM_WAKELOCK);
    }

    /*********************************************************
     * Internal private functions
     ********************************************************/

    class SyncReturn {
        boolean boolValue;
        int intValue;
        String stringValue;
        Object objValue;
    }

    class SyncParams {
        Object mParameter;
        SyncReturn mSyncReturn;
        SyncParams() {
            mSyncReturn = new SyncReturn();
        }
        SyncParams(Object p) {
            mParameter = p;
            mSyncReturn = new SyncReturn();
        }
    }

    /**
     * message.obj is used to store SyncParams
     */
    private SyncReturn syncedSend(Message msg) {
        SyncParams syncParams = (SyncParams) msg.obj;
        synchronized(syncParams) {
            if (DBG) Log.d(TAG, "syncedSend " + msg);
            sendMessage(msg);
            try {
                syncParams.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "sendSyncMessage: unexpected interruption of wait()");
                return null;
            }
        }
        return syncParams.mSyncReturn;
    }

    private SyncReturn sendSyncMessage(Message msg) {
        SyncParams syncParams = new SyncParams();
        msg.obj = syncParams;
        return syncedSend(msg);
    }

    private SyncReturn sendSyncMessage(int what, Object param) {
        SyncParams syncParams = new SyncParams(param);
        Message msg = obtainMessage(what, syncParams);
        return syncedSend(msg);
    }


    private SyncReturn sendSyncMessage(int what) {
        return sendSyncMessage(obtainMessage(what));
    }

    private void notifyOnMsgObject(Message msg) {
        SyncParams syncParams = (SyncParams) msg.obj;
        if (syncParams != null) {
            synchronized(syncParams) {
                if (DBG) Log.d(TAG, "notifyOnMsgObject " + msg);
                syncParams.notify();
            }
        }
        else {
            Log.e(TAG, "Error! syncParams in notifyOnMsgObject is null");
        }
    }

    private void setWifiState(int wifiState) {
        final int previousWifiState = mWifiState.get();

        try {
            if (wifiState == WIFI_STATE_ENABLED) {
                mBatteryStats.noteWifiOn(mLastEnableUid.get());
            } else if (wifiState == WIFI_STATE_DISABLED) {
                mBatteryStats.noteWifiOff(mLastEnableUid.get());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to note battery stats in wifi");
        }

        mWifiState.set(wifiState);

        if (DBG) Log.d(TAG, "setWifiState: " + syncGetWifiStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, wifiState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, previousWifiState);
        mContext.sendStickyBroadcast(intent);
    }

    private void setWifiApState(int wifiApState) {
        final int previousWifiApState = mWifiApState.get();

        try {
            if (wifiApState == WIFI_AP_STATE_ENABLED) {
                mBatteryStats.noteWifiOn(mLastApEnableUid.get());
            } else if (wifiApState == WIFI_AP_STATE_DISABLED) {
                mBatteryStats.noteWifiOff(mLastApEnableUid.get());
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Failed to note battery stats in wifi");
        }

        // Update state
        mWifiApState.set(wifiApState);

        if (DBG) Log.d(TAG, "setWifiApState: " + syncGetWifiApStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, wifiApState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, previousWifiApState);
        mContext.sendStickyBroadcast(intent);
    }

    /**
     * Parse the scan result line passed to us by wpa_supplicant (helper).
     * @param line the line to parse
     * @return the {@link ScanResult} object
     */
    private ScanResult parseScanResult(String line) {
        ScanResult scanResult = null;
        if (line != null) {
            /*
             * Cache implementation (LinkedHashMap) is not synchronized, thus,
             * must synchronized here!
             */
            synchronized (mScanResultCache) {
                String[] result = scanResultPattern.split(line);
                if (3 <= result.length && result.length <= 5) {
                    String bssid = result[0];
                    // bssid | frequency | level | flags | ssid
                    int frequency;
                    int level;
                    try {
                        frequency = Integer.parseInt(result[1]);
                        level = Integer.parseInt(result[2]);
                        /* some implementations avoid negative values by adding 256
                         * so we need to adjust for that here.
                         */
                        if (level > 0) level -= 256;
                    } catch (NumberFormatException e) {
                        frequency = 0;
                        level = 0;
                    }

                    /*
                     * The formatting of the results returned by
                     * wpa_supplicant is intended to make the fields
                     * line up nicely when printed,
                     * not to make them easy to parse. So we have to
                     * apply some heuristics to figure out which field
                     * is the SSID and which field is the flags.
                     */
                    String ssid;
                    String flags;
                    if (result.length == 4) {
                        if (result[3].charAt(0) == '[') {
                            flags = result[3];
                            ssid = "";
                        } else {
                            flags = "";
                            ssid = result[3];
                        }
                    } else if (result.length == 5) {
                        flags = result[3];
                        ssid = result[4];
                    } else {
                        // Here, we must have 3 fields: no flags and ssid
                        // set
                        flags = "";
                        ssid = "";
                    }

                    // bssid + ssid is the hash key
                    String key = bssid + ssid;
                    scanResult = mScanResultCache.get(key);
                    if (scanResult != null) {
                        scanResult.level = level;
                        scanResult.SSID = ssid;
                        scanResult.capabilities = flags;
                        scanResult.frequency = frequency;
                    } else {
                        // Do not add scan results that have no SSID set
                        if (0 < ssid.trim().length()) {
                            scanResult =
                                new ScanResult(
                                    ssid, bssid, flags, level, frequency);
                            mScanResultCache.put(key, scanResult);
                        }
                    }
                } else {
                    Log.w(TAG, "Misformatted scan result text with " +
                          result.length + " fields: " + line);
                }
            }
        }

        return scanResult;
    }

    /**
     * scanResults input format
     * 00:bb:cc:dd:cc:ee       2427    166     [WPA-EAP-TKIP][WPA2-EAP-CCMP]   Net1
     * 00:bb:cc:dd:cc:ff       2412    165     [WPA-EAP-TKIP][WPA2-EAP-CCMP]   Net2
     */
    private void setScanResults(String scanResults) {
        if (scanResults == null) {
            return;
        }

        List<ScanResult> scanList = new ArrayList<ScanResult>();

        int lineCount = 0;

        int scanResultsLen = scanResults.length();
        // Parse the result string, keeping in mind that the last line does
        // not end with a newline.
        for (int lineBeg = 0, lineEnd = 0; lineEnd <= scanResultsLen; ++lineEnd) {
            if (lineEnd == scanResultsLen || scanResults.charAt(lineEnd) == '\n') {
                ++lineCount;

                if (lineCount == 1) {
                    lineBeg = lineEnd + 1;
                    continue;
                }
                if (lineEnd > lineBeg) {
                    String line = scanResults.substring(lineBeg, lineEnd);
                    ScanResult scanResult = parseScanResult(line);
                    if (scanResult != null) {
                        scanList.add(scanResult);
                    } else {
                        Log.w(TAG, "misformatted scan result for: " + line);
                    }
                }
                lineBeg = lineEnd + 1;
            }
        }

        mScanResults = scanList;
    }

    private String fetchSSID() {
        String status = WifiNative.statusCommand();
        if (status == null) {
            return null;
        }
        // extract ssid from a series of "name=value"
        String[] lines = status.split("\n");
        for (String line : lines) {
            String[] prop = line.split(" *= *");
            if (prop.length < 2) continue;
            String name = prop[0];
            String value = prop[1];
            if (name.equalsIgnoreCase("ssid")) return value;
        }
        return null;
    }

    private void configureNetworkProperties() {
        try {
            mNetworkProperties.setInterface(NetworkInterface.getByName(mInterfaceName));
        } catch (SocketException e) {
            Log.e(TAG, "SocketException creating NetworkInterface from " + mInterfaceName +
                    ". e=" + e);
            return;
        } catch (NullPointerException e) {
            Log.e(TAG, "NPE creating NetworkInterface. e=" + e);
            return;
        }
        // TODO - fix this for v6
        try {
            mNetworkProperties.addAddress(InetAddress.getByAddress(
                    NetworkUtils.v4IntToArray(mDhcpInfo.ipAddress)));
        } catch (UnknownHostException e) {
            Log.e(TAG, "Exception setting IpAddress using " + mDhcpInfo + ", e=" + e);
        }

        try {
            mNetworkProperties.setGateway(InetAddress.getByAddress(NetworkUtils.v4IntToArray(
                    mDhcpInfo.gateway)));
        } catch (UnknownHostException e) {
            Log.e(TAG, "Exception setting Gateway using " + mDhcpInfo + ", e=" + e);
        }

        try {
            mNetworkProperties.addDns(InetAddress.getByAddress(
                    NetworkUtils.v4IntToArray(mDhcpInfo.dns1)));
        } catch (UnknownHostException e) {
            Log.e(TAG, "Exception setting Dns1 using " + mDhcpInfo + ", e=" + e);
        }
        try {
            mNetworkProperties.addDns(InetAddress.getByAddress(
                    NetworkUtils.v4IntToArray(mDhcpInfo.dns2)));

        } catch (UnknownHostException e) {
            Log.e(TAG, "Exception setting Dns2 using " + mDhcpInfo + ", e=" + e);
        }
        // TODO - add proxy info
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

        @Override
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
                sendMessage(CMD_RECONFIGURE_IP);
                mConfigChanged = true;
            }
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

    private void sendScanResultsAvailableBroadcast() {
        if (!ActivityManagerNative.isSystemReady()) return;

        mContext.sendBroadcast(new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        if (!ActivityManagerNative.isSystemReady()) return;

        Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
        mContext.sendBroadcast(intent);
    }

    private void sendNetworkStateChangeBroadcast(String bssid) {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, mNetworkInfo);
        intent.putExtra(WifiManager.EXTRA_NETWORK_PROPERTIES, mNetworkProperties);
        if (bssid != null)
            intent.putExtra(WifiManager.EXTRA_BSSID, bssid);
        mContext.sendStickyBroadcast(intent);
    }

    private void sendConfigChangeBroadcast() {
        if (!ActivityManagerNative.isSystemReady()) return;
        Intent intent = new Intent(WifiManager.CONFIG_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_PROPERTIES, mNetworkProperties);
        mContext.sendBroadcast(intent);
    }

    private void sendSupplicantStateChangedBroadcast(StateChangeResult sc, boolean failedAuth) {
        Intent intent = new Intent(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiManager.EXTRA_NEW_STATE, (Parcelable)sc.state);
        if (failedAuth) {
            intent.putExtra(
                WifiManager.EXTRA_SUPPLICANT_ERROR,
                WifiManager.ERROR_AUTHENTICATING);
        }
        mContext.sendStickyBroadcast(intent);
    }

    private void updateConfigAndSendChangeBroadcast() {
        updateConfiguredNetworks();
        if (!ActivityManagerNative.isSystemReady()) return;
        Intent intent = new Intent(WifiManager.SUPPLICANT_CONFIG_CHANGED_ACTION);
        mContext.sendBroadcast(intent);
    }

    private void sendSupplicantConnectionChangedBroadcast(boolean connected) {
        if (!ActivityManagerNative.isSystemReady()) return;

        Intent intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, connected);
        mContext.sendBroadcast(intent);
    }

    /**
     * Record the detailed state of a network.
     * @param state the new @{code DetailedState}
     */
    private void setDetailedState(NetworkInfo.DetailedState state) {
        Log.d(TAG, "setDetailed state, old ="
                + mNetworkInfo.getDetailedState() + " and new state=" + state);
        if (state != mNetworkInfo.getDetailedState()) {
            mNetworkInfo.setDetailedState(state, null, null);
        }
    }

    private static String removeDoubleQuotes(String string) {
      if (string.length() <= 2) return "";
      return string.substring(1, string.length() - 1);
    }

    private static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    private static String makeString(BitSet set, String[] strings) {
        StringBuffer buf = new StringBuffer();
        int nextSetBit = -1;

        /* Make sure all set bits are in [0, strings.length) to avoid
         * going out of bounds on strings.  (Shouldn't happen, but...) */
        set = set.get(0, strings.length);

        while ((nextSetBit = set.nextSetBit(nextSetBit + 1)) != -1) {
            buf.append(strings[nextSetBit].replace('_', '-')).append(' ');
        }

        // remove trailing space
        if (set.cardinality() > 0) {
            buf.setLength(buf.length() - 1);
        }

        return buf.toString();
    }

    private static int lookupString(String string, String[] strings) {
        int size = strings.length;

        string = string.replace('-', '_');

        for (int i = 0; i < size; i++)
            if (string.equals(strings[i]))
                return i;

        // if we ever get here, we should probably add the
        // value to WifiConfiguration to reflect that it's
        // supported by the WPA supplicant
        Log.w(TAG, "Failed to look-up a string: " + string);

        return -1;
    }

    private void enableAllNetworks() {
        for (WifiConfiguration config : mConfiguredNetworks) {
            if(config != null && config.status == Status.DISABLED) {
                WifiNative.enableNetworkCommand(config.networkId, false);
            }
        }
        WifiNative.saveConfigCommand();
        updateConfigAndSendChangeBroadcast();
    }

    private int addOrUpdateNetworkNative(WifiConfiguration config) {
        /*
         * If the supplied networkId is -1, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */
        int netId = config.networkId;
        boolean newNetwork = netId == -1;
        // networkId of -1 means we want to create a new network

        if (newNetwork) {
            netId = WifiNative.addNetworkCommand();
            if (netId < 0) {
                Log.e(TAG, "Failed to add a network!");
                return -1;
          }
        }

        setVariables: {

            if (config.SSID != null &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.ssidVarName,
                        config.SSID)) {
                Log.d(TAG, "failed to set SSID: "+config.SSID);
                break setVariables;
            }

            if (config.BSSID != null &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.bssidVarName,
                        config.BSSID)) {
                Log.d(TAG, "failed to set BSSID: "+config.BSSID);
                break setVariables;
            }

            String allowedKeyManagementString =
                makeString(config.allowedKeyManagement, WifiConfiguration.KeyMgmt.strings);
            if (config.allowedKeyManagement.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.KeyMgmt.varName,
                        allowedKeyManagementString)) {
                Log.d(TAG, "failed to set key_mgmt: "+
                        allowedKeyManagementString);
                break setVariables;
            }

            String allowedProtocolsString =
                makeString(config.allowedProtocols, WifiConfiguration.Protocol.strings);
            if (config.allowedProtocols.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.Protocol.varName,
                        allowedProtocolsString)) {
                Log.d(TAG, "failed to set proto: "+
                        allowedProtocolsString);
                break setVariables;
            }

            String allowedAuthAlgorithmsString =
                makeString(config.allowedAuthAlgorithms, WifiConfiguration.AuthAlgorithm.strings);
            if (config.allowedAuthAlgorithms.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.AuthAlgorithm.varName,
                        allowedAuthAlgorithmsString)) {
                Log.d(TAG, "failed to set auth_alg: "+
                        allowedAuthAlgorithmsString);
                break setVariables;
            }

            String allowedPairwiseCiphersString =
                    makeString(config.allowedPairwiseCiphers,
                    WifiConfiguration.PairwiseCipher.strings);
            if (config.allowedPairwiseCiphers.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.PairwiseCipher.varName,
                        allowedPairwiseCiphersString)) {
                Log.d(TAG, "failed to set pairwise: "+
                        allowedPairwiseCiphersString);
                break setVariables;
            }

            String allowedGroupCiphersString =
                makeString(config.allowedGroupCiphers, WifiConfiguration.GroupCipher.strings);
            if (config.allowedGroupCiphers.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.GroupCipher.varName,
                        allowedGroupCiphersString)) {
                Log.d(TAG, "failed to set group: "+
                        allowedGroupCiphersString);
                break setVariables;
            }

            // Prevent client screw-up by passing in a WifiConfiguration we gave it
            // by preventing "*" as a key.
            if (config.preSharedKey != null && !config.preSharedKey.equals("*") &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.pskVarName,
                        config.preSharedKey)) {
                Log.d(TAG, "failed to set psk: "+config.preSharedKey);
                break setVariables;
            }

            boolean hasSetKey = false;
            if (config.wepKeys != null) {
                for (int i = 0; i < config.wepKeys.length; i++) {
                    // Prevent client screw-up by passing in a WifiConfiguration we gave it
                    // by preventing "*" as a key.
                    if (config.wepKeys[i] != null && !config.wepKeys[i].equals("*")) {
                        if (!WifiNative.setNetworkVariableCommand(
                                    netId,
                                    WifiConfiguration.wepKeyVarNames[i],
                                    config.wepKeys[i])) {
                            Log.d(TAG,
                                    "failed to set wep_key"+i+": " +
                                    config.wepKeys[i]);
                            break setVariables;
                        }
                        hasSetKey = true;
                    }
                }
            }

            if (hasSetKey) {
                if (!WifiNative.setNetworkVariableCommand(
                            netId,
                            WifiConfiguration.wepTxKeyIdxVarName,
                            Integer.toString(config.wepTxKeyIndex))) {
                    Log.d(TAG,
                            "failed to set wep_tx_keyidx: "+
                            config.wepTxKeyIndex);
                    break setVariables;
                }
            }

            if (!WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.priorityVarName,
                        Integer.toString(config.priority))) {
                Log.d(TAG, config.SSID + ": failed to set priority: "
                        +config.priority);
                break setVariables;
            }

            if (config.hiddenSSID && !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.hiddenSSIDVarName,
                        Integer.toString(config.hiddenSSID ? 1 : 0))) {
                Log.d(TAG, config.SSID + ": failed to set hiddenSSID: "+
                        config.hiddenSSID);
                break setVariables;
            }

            for (WifiConfiguration.EnterpriseField field
                    : config.enterpriseFields) {
                String varName = field.varName();
                String value = field.value();
                if (value != null) {
                    if (field != config.eap) {
                        value = (value.length() == 0) ? "NULL" : convertToQuotedString(value);
                    }
                    if (!WifiNative.setNetworkVariableCommand(
                                netId,
                                varName,
                                value)) {
                        Log.d(TAG, config.SSID + ": failed to set " + varName +
                                ": " + value);
                        break setVariables;
                    }
                }
            }
            return netId;
        }

        if (newNetwork) {
            WifiNative.removeNetworkCommand(netId);
            Log.d(TAG,
                    "Failed to set a network variable, removed network: "
                    + netId);
        }

        return -1;
    }

    private void updateConfiguredNetworks() {
        String listStr = WifiNative.listNetworksCommand();
        mLastPriority = 0;

        synchronized (mConfiguredNetworks) {
            mConfiguredNetworks.clear();

            if (listStr == null)
                return;

            String[] lines = listStr.split("\n");
            // Skip the first line, which is a header
            for (int i = 1; i < lines.length; i++) {
                String[] result = lines[i].split("\t");
                // network-id | ssid | bssid | flags
                WifiConfiguration config = new WifiConfiguration();
                try {
                    config.networkId = Integer.parseInt(result[0]);
                } catch(NumberFormatException e) {
                    continue;
                }
                if (result.length > 3) {
                    if (result[3].indexOf("[CURRENT]") != -1)
                        config.status = WifiConfiguration.Status.CURRENT;
                    else if (result[3].indexOf("[DISABLED]") != -1)
                        config.status = WifiConfiguration.Status.DISABLED;
                    else
                        config.status = WifiConfiguration.Status.ENABLED;
                } else {
                    config.status = WifiConfiguration.Status.ENABLED;
                }
                readNetworkVariables(config);
                if (config.priority > mLastPriority) {
                    mLastPriority = config.priority;
                }
                mConfiguredNetworks.add(config);
            }
        }
    }


    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     *
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    private void readNetworkVariables(WifiConfiguration config) {

        int netId = config.networkId;
        if (netId < 0)
            return;

        /*
         * TODO: maybe should have a native method that takes an array of
         * variable names and returns an array of values. But we'd still
         * be doing a round trip to the supplicant daemon for each variable.
         */
        String value;

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.ssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.SSID = removeDoubleQuotes(value);
        } else {
            config.SSID = null;
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.bssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.BSSID = value;
        } else {
            config.BSSID = null;
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.priorityVarName);
        config.priority = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.priority = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.hiddenSSIDVarName);
        config.hiddenSSID = false;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.hiddenSSID = Integer.parseInt(value) != 0;
            } catch (NumberFormatException ignore) {
            }
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.wepTxKeyIdxVarName);
        config.wepTxKeyIndex = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.wepTxKeyIndex = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        for (int i = 0; i < 4; i++) {
            value = WifiNative.getNetworkVariableCommand(netId,
                    WifiConfiguration.wepKeyVarNames[i]);
            if (!TextUtils.isEmpty(value)) {
                config.wepKeys[i] = value;
            } else {
                config.wepKeys[i] = null;
            }
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.pskVarName);
        if (!TextUtils.isEmpty(value)) {
            config.preSharedKey = value;
        } else {
            config.preSharedKey = null;
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.Protocol.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.Protocol.strings);
                if (0 <= index) {
                    config.allowedProtocols.set(index);
                }
            }
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.KeyMgmt.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.KeyMgmt.strings);
                if (0 <= index) {
                    config.allowedKeyManagement.set(index);
                }
            }
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.AuthAlgorithm.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.AuthAlgorithm.strings);
                if (0 <= index) {
                    config.allowedAuthAlgorithms.set(index);
                }
            }
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.PairwiseCipher.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.PairwiseCipher.strings);
                if (0 <= index) {
                    config.allowedPairwiseCiphers.set(index);
                }
            }
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.GroupCipher.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.GroupCipher.strings);
                if (0 <= index) {
                    config.allowedGroupCiphers.set(index);
                }
            }
        }

        for (WifiConfiguration.EnterpriseField field :
                config.enterpriseFields) {
            value = WifiNative.getNetworkVariableCommand(netId,
                    field.varName());
            if (!TextUtils.isEmpty(value)) {
                if (field != config.eap) value = removeDoubleQuotes(value);
                field.setValue(value);
            }
        }

    }

    /**
     * Poll for info not reported via events
     * RSSI & Linkspeed
     */
    private void requestPolledInfo() {
        int newRssi = WifiNative.getRssiCommand();
        if (newRssi != -1 && -200 < newRssi && newRssi < 256) { // screen out invalid values
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
             * broadcasts, at the cost of not mWifiInforming others
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
            mWifiInfo.setRssi(-200);
        }
        int newLinkSpeed = WifiNative.getLinkSpeedCommand();
        if (newLinkSpeed != -1) {
            mWifiInfo.setLinkSpeed(newLinkSpeed);
        }
    }

    /**
     * Resets the Wi-Fi Connections by clearing any state, resetting any sockets
     * using the interface, stopping DHCP & disabling interface
     */
    private void handleNetworkDisconnect() {
        Log.d(TAG, "Reset connections and stopping DHCP");

        /*
         * Reset connections & stop DHCP
         */
        NetworkUtils.resetConnections(mInterfaceName);

        if (!NetworkUtils.stopDhcp(mInterfaceName)) {
            Log.e(TAG, "Could not stop DHCP");
        }

        /* Disable interface */
        NetworkUtils.disableInterface(mInterfaceName);

        /* send event to CM & network change broadcast */
        setDetailedState(DetailedState.DISCONNECTED);
        sendNetworkStateChangeBroadcast(mLastBssid);

        /* Reset data structures */
        mWifiInfo.setIpAddress(0);
        mWifiInfo.setBSSID(null);
        mWifiInfo.setSSID(null);
        mWifiInfo.setNetworkId(-1);

        /* Clear network properties */
        mNetworkProperties.clear();

        mLastBssid= null;
        mLastNetworkId = -1;

    }


    /*********************************************************
     * Notifications from WifiMonitor
     ********************************************************/

    /**
     * A structure for supplying information about a supplicant state
     * change in the STATE_CHANGE event message that comes from the
     * WifiMonitor
     * thread.
     */
    private static class StateChangeResult {
        StateChangeResult(int networkId, String BSSID, Object state) {
            this.state = state;
            this.BSSID = BSSID;
            this.networkId = networkId;
        }
        int networkId;
        String BSSID;
        Object state;
    }

    /**
     * Send the tracker a notification that a user-entered password key
     * may be incorrect (i.e., caused authentication to fail).
     */
    void notifyPasswordKeyMayBeIncorrect() {
        sendMessage(PASSWORD_MAY_BE_INCORRECT_EVENT);
    }

    /**
     * Send the tracker a notification that a connection to the supplicant
     * daemon has been established.
     */
    void notifySupplicantConnection() {
        sendMessage(SUP_CONNECTION_EVENT);
    }

    /**
     * Send the tracker a notification that a connection to the supplicant
     * daemon has been established.
     */
    void notifySupplicantLost() {
        sendMessage(SUP_DISCONNECTION_EVENT);
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
    void notifyNetworkStateChange(DetailedState newState, String BSSID, int networkId) {
        if (newState == NetworkInfo.DetailedState.CONNECTED) {
            sendMessage(obtainMessage(NETWORK_CONNECTION_EVENT,
                    new StateChangeResult(networkId, BSSID, newState)));
        } else {
            sendMessage(obtainMessage(NETWORK_DISCONNECTION_EVENT,
                    new StateChangeResult(networkId, BSSID, newState)));
        }
    }

    /**
     * Send the tracker a notification that the state of the supplicant
     * has changed.
     * @param networkId the configured network on which the state change occurred
     * @param newState the new {@code SupplicantState}
     */
    void notifySupplicantStateChange(int networkId, String BSSID, SupplicantState newState) {
        sendMessage(obtainMessage(SUPPLICANT_STATE_CHANGE_EVENT,
                new StateChangeResult(networkId, BSSID, newState)));
    }

    /**
     * Send the tracker a notification that a scan has completed, and results
     * are available.
     */
    void notifyScanResultsAvailable() {
        /**
         * Switch scan mode over to passive.
         * Turning off scan-only mode happens only in "Connect" mode
         */
        setScanType(false);
        sendMessage(SCAN_RESULTS_EVENT);
    }

    void notifyDriverStarted() {
        sendMessage(DRIVER_START_EVENT);
    }

    void notifyDriverStopped() {
        sendMessage(DRIVER_STOP_EVENT);
    }

    void notifyDriverHung() {
        setWifiEnabled(false);
        setWifiEnabled(true);
    }


    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends HierarchicalState {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            SyncParams syncParams;
            switch (message.what) {
                    /* Synchronous call returns */
                case CMD_PING_SUPPLICANT:
                case CMD_REMOVE_NETWORK:
                case CMD_ENABLE_NETWORK:
                case CMD_DISABLE_NETWORK:
                case CMD_ADD_OR_UPDATE_NETWORK:
                case CMD_GET_RSSI:
                case CMD_GET_RSSI_APPROX:
                case CMD_GET_LINK_SPEED:
                case CMD_GET_MAC_ADDR:
                case CMD_SAVE_CONFIG:
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.boolValue = false;
                    syncParams.mSyncReturn.intValue = -1;
                    syncParams.mSyncReturn.stringValue = null;
                    notifyOnMsgObject(message);
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    mEnableRssiPolling = (message.arg1 == 1);
                    mSupplicantStateTracker.sendMessage(CMD_ENABLE_RSSI_POLL);
                    break;
                    /* Discard */
                case CMD_LOAD_DRIVER:
                case CMD_UNLOAD_DRIVER:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONFIGURE_IP:
                case SUP_CONNECTION_EVENT:
                case SUP_DISCONNECTION_EVENT:
                case DRIVER_START_EVENT:
                case DRIVER_STOP_EVENT:
                case NETWORK_CONNECTION_EVENT:
                case NETWORK_DISCONNECTION_EVENT:
                case SCAN_RESULTS_EVENT:
                case SUPPLICANT_STATE_CHANGE_EVENT:
                case PASSWORD_MAY_BE_INCORRECT_EVENT:
                case CMD_BLACKLIST_NETWORK:
                case CMD_CLEAR_BLACKLIST:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_POWER_MODE:
                case CMD_SET_BLUETOOTH_COEXISTENCE:
                case CMD_SET_BLUETOOTH_SCAN_MODE:
                case CMD_SET_NUM_ALLOWED_CHANNELS:
                case CMD_REQUEST_CM_WAKELOCK:
                case CMD_CONNECT_NETWORK:
                case CMD_SAVE_NETWORK:
                case CMD_FORGET_NETWORK:
                    break;
                default:
                    Log.e(TAG, "Error! unhandled message" + message);
                    break;
            }
            return HANDLED;
        }
    }

    class InitialState extends HierarchicalState {
        @Override
        //TODO: could move logging into a common class
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            // [31-8] Reserved for future use
            // [7 - 0] HSM state change
            // 50021 wifi_state_changed (custom|1|5)
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            if (WifiNative.isDriverLoaded()) {
                transitionTo(mDriverLoadedState);
            }
            else {
                transitionTo(mDriverUnloadedState);
            }
        }
    }

    class DriverLoadingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            final Message message = new Message();
            message.copyFrom(getCurrentMessage());
            /* TODO: add a timeout to fail when driver load is hung.
             * Similarly for driver unload.
             */
            new Thread(new Runnable() {
                public void run() {
                    sWakeLock.acquire();
                    //enabling state
                    switch(message.arg1) {
                        case WIFI_STATE_ENABLING:
                            setWifiState(WIFI_STATE_ENABLING);
                            break;
                        case WIFI_AP_STATE_ENABLING:
                            setWifiApState(WIFI_AP_STATE_ENABLING);
                            break;
                    }

                    if(WifiNative.loadDriver()) {
                        Log.d(TAG, "Driver load successful");
                        sendMessage(CMD_LOAD_DRIVER_SUCCESS);
                    } else {
                        Log.e(TAG, "Failed to load driver!");
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
                    sWakeLock.release();
                }
            }).start();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
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
                case CMD_SET_POWER_MODE:
                case CMD_SET_BLUETOOTH_COEXISTENCE:
                case CMD_SET_BLUETOOTH_SCAN_MODE:
                case CMD_SET_NUM_ALLOWED_CHANNELS:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverLoadedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_UNLOAD_DRIVER:
                    transitionTo(mDriverUnloadingState);
                    break;
                case CMD_START_SUPPLICANT:
                    if(WifiNative.startSupplicant()) {
                        Log.d(TAG, "Supplicant start successful");
                        mWifiMonitor.startMonitoring();
                        setWifiState(WIFI_STATE_ENABLED);
                        transitionTo(mWaitForSupState);
                    } else {
                        Log.e(TAG, "Failed to start supplicant!");
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_STATE_UNKNOWN, 0));
                    }
                    break;
                case CMD_START_AP:
                    try {
                        nwService.startAccessPoint((WifiConfiguration) message.obj,
                                    mInterfaceName,
                                    SOFTAP_IFACE);
                    } catch(Exception e) {
                        Log.e(TAG, "Exception in startAccessPoint()");
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_AP_STATE_FAILED, 0));
                        break;
                    }
                    Log.d(TAG, "Soft AP start successful");
                    setWifiApState(WIFI_AP_STATE_ENABLED);
                    transitionTo(mSoftApStartedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverUnloadingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            final Message message = new Message();
            message.copyFrom(getCurrentMessage());
            new Thread(new Runnable() {
                public void run() {
                    if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
                    sWakeLock.acquire();
                    if(WifiNative.unloadDriver()) {
                        Log.d(TAG, "Driver unload successful");
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
                        Log.e(TAG, "Failed to unload driver!");
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
                    sWakeLock.release();
                }
            }).start();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
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
                case CMD_SET_POWER_MODE:
                case CMD_SET_BLUETOOTH_COEXISTENCE:
                case CMD_SET_BLUETOOTH_SCAN_MODE:
                case CMD_SET_NUM_ALLOWED_CHANNELS:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverUnloadedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_LOAD_DRIVER:
                    transitionTo(mDriverLoadingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverFailedState extends HierarchicalState {
        @Override
        public void enter() {
            Log.e(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            return NOT_HANDLED;
        }
    }


    class WaitForSupState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case SUP_CONNECTION_EVENT:
                    Log.d(TAG, "Supplicant connection established");
                    mSupplicantStateTracker.resetSupplicantState();
                    /* Initialize data structures */
                    mLastBssid = null;
                    mLastNetworkId = -1;
                    mLastSignalLevel = -1;

                    mWifiInfo.setMacAddress(WifiNative.getMacAddressCommand());

                    updateConfiguredNetworks();
                    enableAllNetworks();

                    //TODO: initialize and fix multicast filtering
                    //mWM.initializeMulticastFiltering();

                    if (mBluetoothA2dp == null) {
                        mBluetoothA2dp = new BluetoothA2dp(mContext);
                    }
                    checkIsBluetoothPlaying();

                    checkUseStaticIp();
                    sendSupplicantConnectionChangedBroadcast(true);
                    transitionTo(mDriverSupReadyState);
                    break;
                case CMD_STOP_SUPPLICANT:
                    Log.d(TAG, "Stop supplicant received");
                    WifiNative.stopSupplicant();
                    transitionTo(mDriverLoadedState);
                    break;
                    /* Fail soft ap when waiting for supplicant start */
                case CMD_START_AP:
                    Log.d(TAG, "Failed to start soft AP with a running supplicant");
                    setWifiApState(WIFI_AP_STATE_FAILED);
                    break;
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_MODE:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_POWER_MODE:
                case CMD_SET_BLUETOOTH_COEXISTENCE:
                case CMD_SET_BLUETOOTH_SCAN_MODE:
                case CMD_SET_NUM_ALLOWED_CHANNELS:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                case CMD_STOP_AP:
                case CMD_START_SUPPLICANT:
                case CMD_UNLOAD_DRIVER:
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverSupReadyState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
            /* Initialize for connect mode operation at start */
            mIsScanMode = false;
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            SyncParams syncParams;
            WifiConfiguration config;
            switch(message.what) {
                case CMD_STOP_SUPPLICANT:   /* Supplicant stopped by user */
                    Log.d(TAG, "Stop supplicant received");
                    WifiNative.stopSupplicant();
                    //$FALL-THROUGH$
                case SUP_DISCONNECTION_EVENT:  /* Supplicant died */
                    EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
                    WifiNative.closeSupplicantConnection();
                    handleNetworkDisconnect();
                    sendSupplicantConnectionChangedBroadcast(false);
                    mSupplicantStateTracker.resetSupplicantState();
                    transitionTo(mDriverLoadedState);

                    /* When supplicant dies, unload driver and enter failed state */
                    //TODO: consider bringing up supplicant again
                    if (message.what == SUP_DISCONNECTION_EVENT) {
                        Log.d(TAG, "Supplicant died, unloading driver");
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_STATE_UNKNOWN, 0));
                    }
                    break;
                case CMD_START_DRIVER:
                    EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
                    WifiNative.startDriverCommand();
                    transitionTo(mDriverStartingState);
                    break;
                case SCAN_RESULTS_EVENT:
                    setScanResults(WifiNative.scanResultsCommand());
                    sendScanResultsAvailableBroadcast();
                    break;
                case CMD_PING_SUPPLICANT:
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.boolValue = WifiNative.pingCommand();
                    notifyOnMsgObject(message);
                    break;
                case CMD_ADD_OR_UPDATE_NETWORK:
                    EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
                    syncParams = (SyncParams) message.obj;
                    config = (WifiConfiguration) syncParams.mParameter;
                    syncParams.mSyncReturn.intValue = addOrUpdateNetworkNative(config);
                    updateConfigAndSendChangeBroadcast();
                    notifyOnMsgObject(message);
                    break;
                case CMD_REMOVE_NETWORK:
                    EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.boolValue = WifiNative.removeNetworkCommand(
                            message.arg1);
                    updateConfigAndSendChangeBroadcast();
                    notifyOnMsgObject(message);
                    break;
                case CMD_ENABLE_NETWORK:
                    EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
                    syncParams = (SyncParams) message.obj;
                    EnableNetParams enableNetParams = (EnableNetParams) syncParams.mParameter;
                    syncParams.mSyncReturn.boolValue = WifiNative.enableNetworkCommand(
                            enableNetParams.netId, enableNetParams.disableOthers);
                    updateConfigAndSendChangeBroadcast();
                    notifyOnMsgObject(message);
                    break;
                case CMD_DISABLE_NETWORK:
                    EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.boolValue = WifiNative.disableNetworkCommand(
                            message.arg1);
                    updateConfigAndSendChangeBroadcast();
                    notifyOnMsgObject(message);
                    break;
                case CMD_BLACKLIST_NETWORK:
                    EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
                    WifiNative.addToBlacklistCommand((String)message.obj);
                    break;
                case CMD_CLEAR_BLACKLIST:
                    WifiNative.clearBlacklistCommand();
                    break;
                case CMD_SAVE_CONFIG:
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.boolValue = WifiNative.saveConfigCommand();
                    notifyOnMsgObject(message);
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
                case CMD_GET_MAC_ADDR:
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.stringValue = WifiNative.getMacAddressCommand();
                    notifyOnMsgObject(message);
                    break;
                    /* Cannot start soft AP while in client mode */
                case CMD_START_AP:
                    Log.d(TAG, "Failed to start soft AP with a running supplicant");
                    EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
                    setWifiApState(WIFI_AP_STATE_FAILED);
                    break;
                case CMD_SET_SCAN_MODE:
                    mIsScanMode = (message.arg1 == SCAN_ONLY_MODE);
                    break;
                case CMD_SAVE_NETWORK:
                    config = (WifiConfiguration) message.obj;
                    int netId = addOrUpdateNetworkNative(config);
                    /* enable a new network */
                    if (config.networkId < 0) {
                        WifiNative.enableNetworkCommand(netId, false);
                    }
                    WifiNative.saveConfigCommand();
                    updateConfigAndSendChangeBroadcast();
                    break;
                case CMD_FORGET_NETWORK:
                    WifiNative.removeNetworkCommand(message.arg1);
                    WifiNative.saveConfigCommand();
                    updateConfigAndSendChangeBroadcast();
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStartingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case DRIVER_START_EVENT:
                    transitionTo(mDriverStartedState);
                    break;
                    /* Queue driver commands & connection events */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case SUPPLICANT_STATE_CHANGE_EVENT:
                case NETWORK_CONNECTION_EVENT:
                case NETWORK_DISCONNECTION_EVENT:
                case PASSWORD_MAY_BE_INCORRECT_EVENT:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_POWER_MODE:
                case CMD_SET_BLUETOOTH_COEXISTENCE:
                case CMD_SET_BLUETOOTH_SCAN_MODE:
                case CMD_SET_NUM_ALLOWED_CHANNELS:
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
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverStartedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            try {
                mBatteryStats.noteWifiRunning();
            } catch (RemoteException ignore) {}

            /* Initialize channel count */
            setNumAllowedChannels();

            if (mIsScanMode) {
                WifiNative.setScanResultHandlingCommand(SCAN_ONLY_MODE);
                WifiNative.disconnectCommand();
                transitionTo(mScanModeState);
            } else {
                WifiNative.setScanResultHandlingCommand(CONNECT_MODE);
                /* If supplicant has already connected, before we could finish establishing
                 * the control channel connection, we miss all the supplicant events.
                 * Disconnect and reconnect when driver has started to ensure we receive
                 * all supplicant events.
                 *
                 * TODO: This is a bit unclean, ideally the supplicant should never
                 * connect until told to do so by the framework
                 */
                WifiNative.disconnectCommand();
                WifiNative.reconnectCommand();
                transitionTo(mConnectModeState);
            }
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            SyncParams syncParams;
            switch(message.what) {
                case CMD_SET_SCAN_TYPE:
                    if (message.arg1 == SCAN_ACTIVE) {
                        WifiNative.setScanModeCommand(true);
                    } else {
                        WifiNative.setScanModeCommand(false);
                    }
                    break;
                case CMD_SET_POWER_MODE:
                    WifiNative.setPowerModeCommand(message.arg1);
                    break;
                case CMD_SET_BLUETOOTH_COEXISTENCE:
                    WifiNative.setBluetoothCoexistenceModeCommand(message.arg1);
                    break;
                case CMD_SET_BLUETOOTH_SCAN_MODE:
                    WifiNative.setBluetoothCoexistenceScanModeCommand(message.arg1 == 1);
                    break;
                case CMD_SET_NUM_ALLOWED_CHANNELS:
                    mNumAllowedChannels = message.arg1;
                    WifiNative.setNumAllowedChannelsCommand(message.arg1);
                    break;
                case CMD_START_DRIVER:
                    /* Ignore another driver start */
                    break;
                case CMD_STOP_DRIVER:
                    WifiNative.stopDriverCommand();
                    transitionTo(mDriverStoppingState);
                    break;
                case CMD_REQUEST_CM_WAKELOCK:
                    if (mCm == null) {
                        mCm = (ConnectivityManager)mContext.getSystemService(
                                Context.CONNECTIVITY_SERVICE);
                    }
                    mCm.requestNetworkTransitionWakelock(TAG);
                    break;
                case CMD_START_PACKET_FILTERING:
                    WifiNative.startPacketFiltering();
                    break;
                case CMD_STOP_PACKET_FILTERING:
                    WifiNative.stopPacketFiltering();
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
        @Override
        public void exit() {
            if (DBG) Log.d(TAG, getName() + "\n");
            try {
                mBatteryStats.noteWifiStopped();
            } catch (RemoteException ignore) { }
            mScanResults = null;
        }
    }

    class DriverStoppingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case DRIVER_STOP_EVENT:
                    transitionTo(mDriverStoppedState);
                    break;
                    /* Queue driver commands */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_SCAN_TYPE:
                case CMD_SET_POWER_MODE:
                case CMD_SET_BLUETOOTH_COEXISTENCE:
                case CMD_SET_BLUETOOTH_SCAN_MODE:
                case CMD_SET_NUM_ALLOWED_CHANNELS:
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
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DriverStoppedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            return NOT_HANDLED;
        }
    }

    class ScanModeState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            SyncParams syncParams;
            switch(message.what) {
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        /* Ignore */
                        return HANDLED;
                    } else {
                        WifiNative.setScanResultHandlingCommand(message.arg1);
                        WifiNative.reconnectCommand();
                        mIsScanMode = false;
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case CMD_START_SCAN:
                    WifiNative.scanCommand(message.arg1 == SCAN_ACTIVE);
                    break;
                    /* Ignore */
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case SUPPLICANT_STATE_CHANGE_EVENT:
                case NETWORK_CONNECTION_EVENT:
                case NETWORK_DISCONNECTION_EVENT:
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class ConnectModeState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            SyncParams syncParams;
            StateChangeResult stateChangeResult;
            switch(message.what) {
                case PASSWORD_MAY_BE_INCORRECT_EVENT:
                    mPasswordKeyMayBeIncorrect = true;
                    break;
                case SUPPLICANT_STATE_CHANGE_EVENT:
                    stateChangeResult = (StateChangeResult) message.obj;
                    mSupplicantStateTracker.handleEvent(stateChangeResult);
                    break;
                case CMD_START_SCAN:
                    /* We need to set scan type in completed state */
                    Message newMsg = obtainMessage();
                    newMsg.copyFrom(message);
                    mSupplicantStateTracker.sendMessage(newMsg);
                    break;
                    /* Do a redundant disconnect without transition */
                case CMD_DISCONNECT:
                    WifiNative.disconnectCommand();
                    break;
                case CMD_RECONNECT:
                    WifiNative.reconnectCommand();
                    break;
                case CMD_REASSOCIATE:
                    WifiNative.reassociateCommand();
                    break;
                case CMD_CONNECT_NETWORK:
                    int netId = message.arg1;
                    WifiConfiguration config = (WifiConfiguration) message.obj;
                    if (config != null) {
                        netId = addOrUpdateNetworkNative(config);
                    }
                    // Reset the priority of each network at start or if it goes too high.
                    if (mLastPriority == -1 || mLastPriority > 1000000) {
                        for (WifiConfiguration conf : mConfiguredNetworks) {
                            if (conf.networkId != -1) {
                                conf.priority = 0;
                                addOrUpdateNetworkNative(conf);
                            }
                        }
                        mLastPriority = 0;
                    }

                    // Set to the highest priority and save the configuration.
                    config = new WifiConfiguration();
                    config.networkId = netId;
                    config.priority = ++mLastPriority;

                    addOrUpdateNetworkNative(config);
                    WifiNative.saveConfigCommand();

                    /* We connect to a specific network by first enabling that network
                     * and disabling all other networks in the supplicant. Disabling a
                     * connected network will cause a disconnection from the network.
                     * A reconnectCommand() will then initiate a connection to the enabled
                     * network.
                     */
                    WifiNative.enableNetworkCommand(netId, true);
                    /* Save a flag to indicate that we need to enable all
                     * networks after supplicant indicates a network
                     * state change event
                     */
                    mEnableAllNetworks = true;
                    WifiNative.reconnectCommand();
                    /* update the configured networks but not send a
                     * broadcast to avoid a fetch from settings
                     * during this temporary disabling of networks
                     */
                    updateConfiguredNetworks();
                    transitionTo(mDisconnectingState);
                    break;
                case SCAN_RESULTS_EVENT:
                    /* Set the scan setting back to "connect" mode */
                    WifiNative.setScanResultHandlingCommand(CONNECT_MODE);
                    /* Handle scan results */
                    return NOT_HANDLED;
                case NETWORK_CONNECTION_EVENT:
                    Log.d(TAG,"Network connection established");
                    stateChangeResult = (StateChangeResult) message.obj;

                    //TODO: make supplicant modification to push this in events
                    mWifiInfo.setSSID(fetchSSID());
                    mWifiInfo.setBSSID(mLastBssid = stateChangeResult.BSSID);
                    mWifiInfo.setNetworkId(stateChangeResult.networkId);
                    mLastNetworkId = stateChangeResult.networkId;
                    /* send event to CM & network change broadcast */
                    setDetailedState(DetailedState.OBTAINING_IPADDR);
                    sendNetworkStateChangeBroadcast(mLastBssid);
                    transitionTo(mConnectingState);
                    break;
                case NETWORK_DISCONNECTION_EVENT:
                    Log.d(TAG,"Network connection lost");
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                case CMD_GET_RSSI:
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.intValue = WifiNative.getRssiCommand();
                    notifyOnMsgObject(message);
                    break;
                case CMD_GET_RSSI_APPROX:
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.intValue = WifiNative.getRssiApproxCommand();
                    notifyOnMsgObject(message);
                    break;
                case CMD_GET_LINK_SPEED:
                    syncParams = (SyncParams) message.obj;
                    syncParams.mSyncReturn.intValue = WifiNative.getLinkSpeedCommand();
                    notifyOnMsgObject(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class ConnectingState extends HierarchicalState {
        boolean modifiedBluetoothCoexistenceMode;
        int powerMode;
        Thread mDhcpThread;

        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());

            if (!mUseStaticIp) {

                mDhcpThread = null;
                modifiedBluetoothCoexistenceMode = false;
                powerMode = DRIVER_POWER_MODE_AUTO;

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
                    WifiNative.setBluetoothCoexistenceModeCommand(
                            WifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED);
                }

                powerMode =  WifiNative.getPowerModeCommand();
                if (powerMode < 0) {
                  // Handle the case where supplicant driver does not support
                  // getPowerModeCommand.
                    powerMode = DRIVER_POWER_MODE_AUTO;
                }
                if (powerMode != DRIVER_POWER_MODE_ACTIVE) {
                    WifiNative.setPowerModeCommand(DRIVER_POWER_MODE_ACTIVE);
                }

                Log.d(TAG, "DHCP request started");
                mDhcpThread = new Thread(new Runnable() {
                    public void run() {
                        if (NetworkUtils.runDhcp(mInterfaceName, mDhcpInfo)) {
                            Log.d(TAG, "DHCP request succeeded");
                            sendMessage(CMD_IP_CONFIG_SUCCESS);
                        } else {
                            Log.d(TAG, "DHCP request failed: " +
                                    NetworkUtils.getDhcpError());
                            sendMessage(CMD_IP_CONFIG_FAILURE);
                        }
                    }
                });
                mDhcpThread.start();
            } else {
                if (NetworkUtils.configureInterface(mInterfaceName, mDhcpInfo)) {
                    Log.v(TAG, "Static IP configuration succeeded");
                    sendMessage(CMD_IP_CONFIG_SUCCESS);
                } else {
                    Log.v(TAG, "Static IP configuration failed");
                    sendMessage(CMD_IP_CONFIG_FAILURE);
                }
            }
         }
      @Override
      public boolean processMessage(Message message) {
          if (DBG) Log.d(TAG, getName() + message.toString() + "\n");

          switch(message.what) {
              case CMD_IP_CONFIG_SUCCESS:
                  mReconnectCount = 0;
                  mLastSignalLevel = -1; // force update of signal strength
                  mWifiInfo.setIpAddress(mDhcpInfo.ipAddress);
                  Log.d(TAG, "IP configuration: " + mDhcpInfo);
                  configureNetworkProperties();
                  setDetailedState(DetailedState.CONNECTED);
                  sendNetworkStateChangeBroadcast(mLastBssid);
                  //TODO: we could also detect an IP config change
                  // from a DHCP renewal and send out a config change
                  // broadcast
                  if (mConfigChanged) {
                      sendConfigChangeBroadcast();
                      mConfigChanged = false;
                  }
                  transitionTo(mConnectedState);
                  break;
              case CMD_IP_CONFIG_FAILURE:
                  mWifiInfo.setIpAddress(0);

                  Log.e(TAG, "IP configuration failed");
                  /**
                   * If we've exceeded the maximum number of retries for DHCP
                   * to a given network, disable the network
                   */
                  if (++mReconnectCount > getMaxDhcpRetries()) {
                          Log.e(TAG, "Failed " +
                                  mReconnectCount + " times, Disabling " + mLastNetworkId);
                      WifiNative.disableNetworkCommand(mLastNetworkId);
                      updateConfigAndSendChangeBroadcast();
                  }

                  /* DHCP times out after about 30 seconds, we do a
                   * disconnect and an immediate reconnect to try again
                   */
                  WifiNative.disconnectCommand();
                  WifiNative.reconnectCommand();
                  transitionTo(mDisconnectingState);
                  break;
              case CMD_DISCONNECT:
                  WifiNative.disconnectCommand();
                  transitionTo(mDisconnectingState);
                  break;
                  /* Ignore connection to same network */
              case CMD_CONNECT_NETWORK:
                  int netId = message.arg1;
                  if (mWifiInfo.getNetworkId() == netId) {
                      break;
                  }
                  return NOT_HANDLED;
                  /* Ignore */
              case NETWORK_CONNECTION_EVENT:
                  break;
              case CMD_STOP_DRIVER:
                  sendMessage(CMD_DISCONNECT);
                  deferMessage(message);
                  break;
              case CMD_SET_SCAN_MODE:
                  if (message.arg1 == SCAN_ONLY_MODE) {
                      sendMessage(CMD_DISCONNECT);
                      deferMessage(message);
                  }
                  break;
              case CMD_RECONFIGURE_IP:
                  deferMessage(message);
                  break;
              default:
                return NOT_HANDLED;
          }
          EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
          return HANDLED;
      }

      @Override
      public void exit() {
          /* reset power state & bluetooth coexistence if on DHCP */
          if (!mUseStaticIp) {
              if (powerMode != DRIVER_POWER_MODE_ACTIVE) {
                  WifiNative.setPowerModeCommand(powerMode);
              }

              if (modifiedBluetoothCoexistenceMode) {
                  // Set the coexistence mode back to its default value
                  WifiNative.setBluetoothCoexistenceModeCommand(
                          WifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE);
              }
          }

      }
    }

    class ConnectedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_DISCONNECT:
                    WifiNative.disconnectCommand();
                    transitionTo(mDisconnectingState);
                    break;
                case CMD_RECONFIGURE_IP:
                    Log.d(TAG,"Reconfiguring IP on connection");
                    NetworkUtils.resetConnections(mInterfaceName);
                    transitionTo(mConnectingState);
                    break;
                case CMD_STOP_DRIVER:
                    sendMessage(CMD_DISCONNECT);
                    deferMessage(message);
                    break;
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        sendMessage(CMD_DISCONNECT);
                        deferMessage(message);
                    }
                    break;
                    /* Ignore connection to same network */
                case CMD_CONNECT_NETWORK:
                    int netId = message.arg1;
                    if (mWifiInfo.getNetworkId() == netId) {
                        break;
                    }
                    return NOT_HANDLED;
                    /* Ignore */
                case NETWORK_CONNECTION_EVENT:
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class DisconnectingState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_STOP_DRIVER: /* Stop driver only after disconnect handled */
                    deferMessage(message);
                    break;
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        deferMessage(message);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
        @Override
        public void exit() {
            if (mEnableAllNetworks) {
                mEnableAllNetworks = false;
                enableAllNetworks();
            }
        }
    }

    class DisconnectedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch (message.what) {
                case CMD_SET_SCAN_MODE:
                    if (message.arg1 == SCAN_ONLY_MODE) {
                        WifiNative.setScanResultHandlingCommand(message.arg1);
                        //Supplicant disconnect to prevent further connects
                        WifiNative.disconnectCommand();
                        mIsScanMode = true;
                        transitionTo(mScanModeState);
                    }
                    break;
                    /* Ignore network disconnect */
                case NETWORK_DISCONNECTION_EVENT:
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }

    class SoftApStartedState extends HierarchicalState {
        @Override
        public void enter() {
            if (DBG) Log.d(TAG, getName() + "\n");
            EventLog.writeEvent(EVENTLOG_WIFI_STATE_CHANGED, getName());
        }
        @Override
        public boolean processMessage(Message message) {
            if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
            switch(message.what) {
                case CMD_STOP_AP:
                    Log.d(TAG,"Stopping Soft AP");
                    setWifiApState(WIFI_AP_STATE_DISABLING);
                    try {
                        nwService.stopAccessPoint();
                    } catch(Exception e) {
                        Log.e(TAG, "Exception in stopAccessPoint()");
                    }
                    transitionTo(mDriverLoadedState);
                    break;
                case CMD_START_AP:
                    Log.d(TAG,"SoftAP set on a running access point");
                    try {
                        nwService.setAccessPoint((WifiConfiguration) message.obj,
                                    mInterfaceName,
                                    SOFTAP_IFACE);
                    } catch(Exception e) {
                        Log.e(TAG, "Exception in nwService during soft AP set");
                        try {
                            nwService.stopAccessPoint();
                        } catch (Exception ee) {
                            Slog.e(TAG, "Could not stop AP, :" + ee);
                        }
                        sendMessage(obtainMessage(CMD_UNLOAD_DRIVER, WIFI_AP_STATE_FAILED, 0));
                    }
                    break;
                /* Fail client mode operation when soft AP is enabled */
                case CMD_START_SUPPLICANT:
                    Log.e(TAG,"Cannot start supplicant with a running soft AP");
                    setWifiState(WIFI_STATE_UNKNOWN);
                    break;
                default:
                    return NOT_HANDLED;
            }
            EventLog.writeEvent(EVENTLOG_WIFI_EVENT_HANDLED, message.what);
            return HANDLED;
        }
    }


    class SupplicantStateTracker extends HierarchicalStateMachine {

        private int mRssiPollToken = 0;

        /**
         * The max number of the WPA supplicant loop iterations before we
         * decide that the loop should be terminated:
         */
        private static final int MAX_SUPPLICANT_LOOP_ITERATIONS = 4;
        private int mLoopDetectIndex = 0;
        private int mLoopDetectCount = 0;

        /**
         *  Supplicant state change commands follow
         *  the ordinal values defined in SupplicantState.java
         */
        private static final int DISCONNECTED           = 0;
        private static final int INACTIVE               = 1;
        private static final int SCANNING               = 2;
        private static final int ASSOCIATING            = 3;
        private static final int ASSOCIATED             = 4;
        private static final int FOUR_WAY_HANDSHAKE     = 5;
        private static final int GROUP_HANDSHAKE        = 6;
        private static final int COMPLETED              = 7;
        private static final int DORMANT                = 8;
        private static final int UNINITIALIZED          = 9;
        private static final int INVALID                = 10;

        private HierarchicalState mUninitializedState = new UninitializedState();
        private HierarchicalState mInitializedState = new InitializedState();;
        private HierarchicalState mInactiveState = new InactiveState();
        private HierarchicalState mDisconnectState = new DisconnectedState();
        private HierarchicalState mScanState = new ScanState();
        private HierarchicalState mConnectState = new ConnectState();
        private HierarchicalState mHandshakeState = new HandshakeState();
        private HierarchicalState mCompletedState = new CompletedState();
        private HierarchicalState mDormantState = new DormantState();

        public SupplicantStateTracker(Context context, Handler target) {
            super(TAG, target.getLooper());

            addState(mUninitializedState);
            addState(mInitializedState);
                addState(mInactiveState, mInitializedState);
                addState(mDisconnectState, mInitializedState);
                addState(mScanState, mInitializedState);
                addState(mConnectState, mInitializedState);
                    addState(mHandshakeState, mConnectState);
                    addState(mCompletedState, mConnectState);
                addState(mDormantState, mInitializedState);

            setInitialState(mUninitializedState);

            //start the state machine
            start();
        }

        public void handleEvent(StateChangeResult stateChangeResult) {
            SupplicantState newState = (SupplicantState) stateChangeResult.state;

            // Supplicant state change
            // [31-13] Reserved for future use
            // [8 - 0] Supplicant state (as defined in SupplicantState.java)
            // 50023 supplicant_state_changed (custom|1|5)
            EventLog.writeEvent(EVENTLOG_SUPPLICANT_STATE_CHANGED, newState.ordinal());

            sendMessage(obtainMessage(newState.ordinal(), stateChangeResult));
        }

        public void resetSupplicantState() {
            transitionTo(mUninitializedState);
        }

        private void resetLoopDetection() {
            mLoopDetectCount = 0;
            mLoopDetectIndex = 0;
        }

        private boolean handleTransition(Message msg) {
            if (DBG) Log.d(TAG, getName() + msg.toString() + "\n");
            switch (msg.what) {
                case DISCONNECTED:
                    transitionTo(mDisconnectState);
                    break;
                case SCANNING:
                    transitionTo(mScanState);
                    break;
                case ASSOCIATING:
                    StateChangeResult stateChangeResult = (StateChangeResult) msg.obj;
                    /* BSSID is valid only in ASSOCIATING state */
                    mWifiInfo.setBSSID(stateChangeResult.BSSID);
                    //$FALL-THROUGH$
                case ASSOCIATED:
                case FOUR_WAY_HANDSHAKE:
                case GROUP_HANDSHAKE:
                    transitionTo(mHandshakeState);
                    break;
                case COMPLETED:
                    transitionTo(mCompletedState);
                    break;
                case DORMANT:
                    transitionTo(mDormantState);
                    break;
                case INACTIVE:
                    transitionTo(mInactiveState);
                    break;
                case UNINITIALIZED:
                case INVALID:
                    transitionTo(mUninitializedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            StateChangeResult stateChangeResult = (StateChangeResult) msg.obj;
            SupplicantState supState = (SupplicantState) stateChangeResult.state;
            setDetailedState(WifiInfo.getDetailedStateOf(supState));
            mWifiInfo.setSupplicantState(supState);
            mWifiInfo.setNetworkId(stateChangeResult.networkId);
            return HANDLED;
        }

        /********************************************************
         * HSM states
         *******************************************************/

        class InitializedState extends HierarchicalState {
            @Override
             public void enter() {
                 if (DBG) Log.d(TAG, getName() + "\n");
             }
            @Override
            public boolean processMessage(Message message) {
                if (DBG) Log.d(TAG, getName() + message.toString() + "\n");
                switch (message.what) {
                    case CMD_START_SCAN:
                        WifiNative.scanCommand(message.arg1 == SCAN_ACTIVE);
                        break;
                    default:
                        if (DBG) Log.w(TAG, "Ignoring " + message);
                        break;
                }
                return HANDLED;
            }
        }

        class UninitializedState extends HierarchicalState {
            @Override
             public void enter() {
                 if (DBG) Log.d(TAG, getName() + "\n");
                 mNetworkInfo.setIsAvailable(false);
                 resetLoopDetection();
                 mPasswordKeyMayBeIncorrect = false;
             }
            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    default:
                        if (!handleTransition(message)) {
                            if (DBG) Log.w(TAG, "Ignoring " + message);
                        }
                        break;
                }
                return HANDLED;
            }
            @Override
            public void exit() {
                mNetworkInfo.setIsAvailable(true);
            }
        }

        class InactiveState extends HierarchicalState {
            @Override
             public void enter() {
                 if (DBG) Log.d(TAG, getName() + "\n");
                 Message message = getCurrentMessage();
                 StateChangeResult stateChangeResult = (StateChangeResult) message.obj;

                 mNetworkInfo.setIsAvailable(false);
                 resetLoopDetection();
                 mPasswordKeyMayBeIncorrect = false;

                 sendSupplicantStateChangedBroadcast(stateChangeResult, false);
             }
            @Override
            public boolean processMessage(Message message) {
                return handleTransition(message);
            }
            @Override
            public void exit() {
                mNetworkInfo.setIsAvailable(true);
            }
        }


        class DisconnectedState extends HierarchicalState {
            @Override
             public void enter() {
                 if (DBG) Log.d(TAG, getName() + "\n");
                 Message message = getCurrentMessage();
                 StateChangeResult stateChangeResult = (StateChangeResult) message.obj;

                 resetLoopDetection();

                 /* If a disconnect event happens after a password key failure
                  * event, disable the network
                  */
                 if (mPasswordKeyMayBeIncorrect) {
                     Log.d(TAG, "Failed to authenticate, disabling network " +
                             mWifiInfo.getNetworkId());
                     WifiNative.disableNetworkCommand(mWifiInfo.getNetworkId());
                     mPasswordKeyMayBeIncorrect = false;
                     sendSupplicantStateChangedBroadcast(stateChangeResult, true);
                     updateConfigAndSendChangeBroadcast();
                 }
                 else {
                     sendSupplicantStateChangedBroadcast(stateChangeResult, false);
                 }
             }
            @Override
            public boolean processMessage(Message message) {
                return handleTransition(message);
            }
        }

        class ScanState extends HierarchicalState {
            @Override
             public void enter() {
                 if (DBG) Log.d(TAG, getName() + "\n");
                 Message message = getCurrentMessage();
                 StateChangeResult stateChangeResult = (StateChangeResult) message.obj;

                 mPasswordKeyMayBeIncorrect = false;
                 resetLoopDetection();
                 sendSupplicantStateChangedBroadcast(stateChangeResult, false);
             }
            @Override
            public boolean processMessage(Message message) {
                return handleTransition(message);
            }
        }

        class ConnectState extends HierarchicalState {
            @Override
             public void enter() {
                 if (DBG) Log.d(TAG, getName() + "\n");
             }
            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START_SCAN:
                        WifiNative.setScanResultHandlingCommand(SCAN_ONLY_MODE);
                        WifiNative.scanCommand(message.arg1 == SCAN_ACTIVE);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class HandshakeState extends HierarchicalState {
            @Override
             public void enter() {
                 if (DBG) Log.d(TAG, getName() + "\n");
                 final Message message = getCurrentMessage();
                 StateChangeResult stateChangeResult = (StateChangeResult) message.obj;

                 if (mLoopDetectIndex > message.what) {
                     mLoopDetectCount++;
                 }
                 if (mLoopDetectCount > MAX_SUPPLICANT_LOOP_ITERATIONS) {
                     WifiNative.disableNetworkCommand(stateChangeResult.networkId);
                     updateConfigAndSendChangeBroadcast();
                     mLoopDetectCount = 0;
                 }

                 mLoopDetectIndex = message.what;

                 mPasswordKeyMayBeIncorrect = false;
                 sendSupplicantStateChangedBroadcast(stateChangeResult, false);
             }
            @Override
            public boolean processMessage(Message message) {
                return handleTransition(message);
            }
        }

        class CompletedState extends HierarchicalState {
            @Override
             public void enter() {
                 if (DBG) Log.d(TAG, getName() + "\n");
                 Message message = getCurrentMessage();
                 StateChangeResult stateChangeResult = (StateChangeResult) message.obj;

                 mRssiPollToken++;
                 if (mEnableRssiPolling) {
                     sendMessageDelayed(obtainMessage(CMD_RSSI_POLL, mRssiPollToken, 0),
                             POLL_RSSI_INTERVAL_MSECS);
                 }

                 resetLoopDetection();

                 mPasswordKeyMayBeIncorrect = false;
                 sendSupplicantStateChangedBroadcast(stateChangeResult, false);
             }
            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case ASSOCIATING:
                    case ASSOCIATED:
                    case FOUR_WAY_HANDSHAKE:
                    case GROUP_HANDSHAKE:
                    case COMPLETED:
                        break;
                    case CMD_RSSI_POLL:
                        if (message.arg1 == mRssiPollToken) {
                            // Get Info and continue polling
                            requestPolledInfo();
                            sendMessageDelayed(obtainMessage(CMD_RSSI_POLL, mRssiPollToken, 0),
                                    POLL_RSSI_INTERVAL_MSECS);
                        } else {
                            // Polling has completed
                        }
                        break;
                    case CMD_ENABLE_RSSI_POLL:
                        mRssiPollToken++;
                        if (mEnableRssiPolling) {
                            // first poll
                            requestPolledInfo();
                            sendMessageDelayed(obtainMessage(CMD_RSSI_POLL, mRssiPollToken, 0),
                                    POLL_RSSI_INTERVAL_MSECS);
                        }
                        break;
                    default:
                        return handleTransition(message);
                }
                return HANDLED;
            }
        }

        class DormantState extends HierarchicalState {
            @Override
            public void enter() {
                if (DBG) Log.d(TAG, getName() + "\n");
                Message message = getCurrentMessage();
                StateChangeResult stateChangeResult = (StateChangeResult) message.obj;

                resetLoopDetection();
                mPasswordKeyMayBeIncorrect = false;

                sendSupplicantStateChangedBroadcast(stateChangeResult, false);

                /* TODO: reconnect is now being handled at DHCP failure handling
                 * If we run into issues with staying in Dormant state, might
                 * need a reconnect here
                 */
            }
            @Override
            public boolean processMessage(Message message) {
                return handleTransition(message);
            }
        }
    }
}
