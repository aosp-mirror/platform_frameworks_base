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

package com.android.server;

import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.IWifiManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNative;
import android.net.wifi.WifiStateTracker;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.NetworkStateTracker;
import android.net.DhcpInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * WifiService handles remote WiFi operation requests by implementing
 * the IWifiManager interface. It also creates a WifiMonitor to listen
 * for Wifi-related events.
 *
 * @hide
 */
public class WifiService extends IWifiManager.Stub {
    private static final String TAG = "WifiService";
    private static final boolean DBG = false;
    private static final Pattern scanResultPattern = Pattern.compile("\t+");
    private final WifiStateTracker mWifiStateTracker;

    private Context mContext;
    private int mWifiState;

    private AlarmManager mAlarmManager;
    private PendingIntent mIdleIntent;
    private static final int IDLE_REQUEST = 0;
    private boolean mScreenOff;
    private boolean mDeviceIdle;
    private int mPluggedType;

    private final LockList mLocks = new LockList();
    /**
     * See {@link Settings.System#WIFI_IDLE_MS}. This is the default value if a
     * Settings.System value is not present.
     */
    private static final long DEFAULT_IDLE_MILLIS = 2 * 60 * 1000; /* 2 minutes */

    private static final String WAKELOCK_TAG = "WifiService";

    /**
     * The maximum amount of time to hold the wake lock after a disconnect
     * caused by stopping the driver. Establishing an EDGE connection has been
     * observed to take about 5 seconds under normal circumstances. This
     * provides a bit of extra margin.
     * <p>
     * See {@link android.provider.Settings.Secure#WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS}.
     * This is the default value if a Settings.System value is not present.
     */
    private static final int DEFAULT_WAKELOCK_TIMEOUT = 8000;

    // Wake lock used by driver-stop operation
    private static PowerManager.WakeLock sDriverStopWakeLock;
    // Wake lock used by other operations
    private static PowerManager.WakeLock sWakeLock;

    private static final int MESSAGE_ENABLE_WIFI      = 0;
    private static final int MESSAGE_DISABLE_WIFI     = 1;
    private static final int MESSAGE_STOP_WIFI        = 2;
    private static final int MESSAGE_START_WIFI       = 3;
    private static final int MESSAGE_RELEASE_WAKELOCK = 4;

    private final  WifiHandler mWifiHandler;

    /*
     * Map used to keep track of hidden networks presence, which
     * is needed to switch between active and passive scan modes.
     * If there is at least one hidden network that is currently
     * present (enabled), we want to do active scans instead of
     * passive.
     */
    private final Map<Integer, Boolean> mIsHiddenNetworkPresent;
    /*
     * The number of currently present hidden networks. When this
     * counter goes from 0 to 1 or from 1 to 0, we change the
     * scan mode to active or passive respectively. Initially, we
     * set the counter to 0 and we increment it every time we add
     * a new present (enabled) hidden network.
     */
    private int mNumHiddenNetworkPresent;
    /*
     * Whether we change the scan mode is due to a hidden network
     * (in this class, this is always the case)
     */
    private final static boolean SET_DUE_TO_A_HIDDEN_NETWORK = true;

    /*
     * Cache of scan results objects (size is somewhat arbitrary)
     */
    private static final int SCAN_RESULT_CACHE_SIZE = 80;
    private final LinkedHashMap<String, ScanResult> mScanResultCache;

    /*
     * Character buffer used to parse scan results (optimization)
     */
    private static final int SCAN_RESULT_BUFFER_SIZE = 512;
    private char[] mScanResultBuffer;
    private boolean mNeedReconfig;

    /**
     * Number of allowed radio frequency channels in various regulatory domains.
     * This list is sufficient for 802.11b/g networks (2.4GHz range).
     */
    private static int[] sValidRegulatoryChannelCounts = new int[] {11, 13, 14};

    private static final String ACTION_DEVICE_IDLE =
            "com.android.server.WifiManager.action.DEVICE_IDLE";

    WifiService(Context context, WifiStateTracker tracker) {
        mContext = context;
        mWifiStateTracker = tracker;

        /*
         * Initialize the hidden-networks state
         */
        mIsHiddenNetworkPresent = new HashMap<Integer, Boolean>();
        mNumHiddenNetworkPresent = 0;

        mScanResultCache = new LinkedHashMap<String, ScanResult>(
            SCAN_RESULT_CACHE_SIZE, 0.75f, true) {
                /*
                 * Limit the cache size by SCAN_RESULT_CACHE_SIZE
                 * elements
                 */
                public boolean removeEldestEntry(Map.Entry eldest) {
                    return SCAN_RESULT_CACHE_SIZE < this.size();
                }
            };

        mScanResultBuffer = new char [SCAN_RESULT_BUFFER_SIZE];

        HandlerThread wifiThread = new HandlerThread("WifiService");
        wifiThread.start();
        mWifiHandler = new WifiHandler(wifiThread.getLooper());

        mWifiState = WIFI_STATE_DISABLED;
        boolean wifiEnabled = getPersistedWifiEnabled();

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent idleIntent = new Intent(ACTION_DEVICE_IDLE, null);
        mIdleIntent = PendingIntent.getBroadcast(mContext, IDLE_REQUEST, idleIntent, 0);

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        sWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        sDriverStopWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        mWifiStateTracker.setReleaseWakeLockCallback(
                new Runnable() {
                    public void run() {
                        mWifiHandler.removeMessages(MESSAGE_RELEASE_WAKELOCK);
                        synchronized (sDriverStopWakeLock) {
                            if (sDriverStopWakeLock.isHeld()) {
                                if (DBG) Log.d(TAG, "Releasing driver-stop wakelock " +
                                        sDriverStopWakeLock);
                                sDriverStopWakeLock.release();
                            }
                        }
                    }
                }
        );

        Log.d(TAG, "WifiService starting up with Wi-Fi " +
            (wifiEnabled ? "enabled" : "disabled"));
        registerForBroadcasts();
        setWifiEnabledBlocking(wifiEnabled, false);
    }

    /**
     * Initializes the hidden networks state. Must be called when we
     * enable Wi-Fi.
     */
    private synchronized void initializeHiddenNetworksState() {
        // First, reset the state
        resetHiddenNetworksState();

        // ... then add networks that are marked as hidden
        List<WifiConfiguration> networks = getConfiguredNetworks();
        if (!networks.isEmpty()) {
            for (WifiConfiguration config : networks) {
                if (config != null && config.hiddenSSID) {
                    addOrUpdateHiddenNetwork(
                        config.networkId,
                        config.status != WifiConfiguration.Status.DISABLED);
                }
            }

        }
    }

    /**
     * Resets the hidden networks state.
     */
    private synchronized void resetHiddenNetworksState() {
        mNumHiddenNetworkPresent = 0;
        mIsHiddenNetworkPresent.clear();
    }

    /**
     * Marks all but netId network as not present.
     */
    private synchronized void markAllHiddenNetworksButOneAsNotPresent(int netId) {
        for (Map.Entry<Integer, Boolean> entry : mIsHiddenNetworkPresent.entrySet()) {
            if (entry != null) {
                Integer networkId = entry.getKey();
                if (networkId != netId) {
                    updateNetworkIfHidden(
                        networkId, false);
                }
            }
        }
    }

    /**
     * Updates the netId network presence status if netId is an existing
     * hidden network.
     */
    private synchronized void updateNetworkIfHidden(int netId, boolean present) {
        if (isHiddenNetwork(netId)) {
            addOrUpdateHiddenNetwork(netId, present);
        }
    }

    /**
     * Updates the netId network presence status if netId is an existing
     * hidden network. If the network does not exist, adds the network.
     */
    private synchronized void addOrUpdateHiddenNetwork(int netId, boolean present) {
        if (0 <= netId) {

            // If we are adding a new entry or modifying an existing one
            Boolean isPresent = mIsHiddenNetworkPresent.get(netId);
            if (isPresent == null || isPresent != present) {
                if (present) {
                    incrementHiddentNetworkPresentCounter();
                } else {
                    // If we add a new hidden network, no need to change
                    // the counter (it must be 0)
                    if (isPresent != null) {
                        decrementHiddentNetworkPresentCounter();
                    }
                }
                mIsHiddenNetworkPresent.put(netId, new Boolean(present));
            }
        } else {
            Log.e(TAG, "addOrUpdateHiddenNetwork(): Invalid (negative) network id!");
        }
    }

    /**
     * Removes the netId network if it is hidden (being kept track of).
     */
    private synchronized void removeNetworkIfHidden(int netId) {
        if (isHiddenNetwork(netId)) {
            removeHiddenNetwork(netId);
        }
    }

    /**
     * Removes the netId network. For the call to be successful, the network
     * must be hidden.
     */
    private synchronized void removeHiddenNetwork(int netId) {
        if (0 <= netId) {
            Boolean isPresent =
                mIsHiddenNetworkPresent.remove(netId);
            if (isPresent != null) {
                // If we remove an existing hidden network that is not
                // present, no need to change the counter
                if (isPresent) {
                    decrementHiddentNetworkPresentCounter();
                }
            } else {
                if (DBG) {
                    Log.d(TAG, "removeHiddenNetwork(): Removing a non-existent network!");
                }
            }
        } else {
            Log.e(TAG, "removeHiddenNetwork(): Invalid (negative) network id!");
        }
    }

    /**
     * Returns true if netId is an existing hidden network.
     */
    private synchronized boolean isHiddenNetwork(int netId) {
        return mIsHiddenNetworkPresent.containsKey(netId);
    }

    /**
     * Increments the present (enabled) hidden networks counter. If the
     * counter value goes from 0 to 1, changes the scan mode to active.
     */
    private void incrementHiddentNetworkPresentCounter() {
        ++mNumHiddenNetworkPresent;
        if (1 == mNumHiddenNetworkPresent) {
            // Switch the scan mode to "active"
            mWifiStateTracker.setScanMode(true, SET_DUE_TO_A_HIDDEN_NETWORK);
        }
    }

    /**
     * Decrements the present (enabled) hidden networks counter. If the
     * counter goes from 1 to 0, changes the scan mode back to passive.
     */
    private void decrementHiddentNetworkPresentCounter() {
        if (0 < mNumHiddenNetworkPresent) {
            --mNumHiddenNetworkPresent;
            if (0 == mNumHiddenNetworkPresent) {
                // Switch the scan mode to "passive"
                mWifiStateTracker.setScanMode(false, SET_DUE_TO_A_HIDDEN_NETWORK);
            }
        } else {
            Log.e(TAG, "Hidden-network counter invariant violation!");
        }
    }

    private boolean getPersistedWifiEnabled() {
        final ContentResolver cr = mContext.getContentResolver();
        try {
            return Settings.Secure.getInt(cr, Settings.Secure.WIFI_ON) == 1;
        } catch (Settings.SettingNotFoundException e) {
            Settings.Secure.putInt(cr, Settings.Secure.WIFI_ON, 0);
            return false;
        }
    }

    private void persistWifiEnabled(boolean enabled) {
        final ContentResolver cr = mContext.getContentResolver();
        Settings.Secure.putInt(cr, Settings.Secure.WIFI_ON, enabled ? 1 : 0);
    }

    NetworkStateTracker getNetworkStateTracker() {
        return mWifiStateTracker;
    }

    /**
     * see {@link android.net.wifi.WifiManager#pingSupplicant()}
     * @return {@code true} if the operation succeeds
     */
    public boolean pingSupplicant() {
        enforceChangePermission();
        synchronized (mWifiStateTracker) {
            return WifiNative.pingCommand();
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#startScan()}
     * @return {@code true} if the operation succeeds
     */
    public boolean startScan() {
        enforceChangePermission();
        synchronized (mWifiStateTracker) {
            switch (mWifiStateTracker.getSupplicantState()) {
                case DISCONNECTED:
                case INACTIVE:
                case SCANNING:
                case DORMANT:
                    break;
                default:
                    WifiNative.setScanResultHandlingCommand(
                            WifiStateTracker.SUPPL_SCAN_HANDLING_LIST_ONLY);
                    break;
            }
            return WifiNative.scanCommand();
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)}
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    public boolean setWifiEnabled(boolean enable) {
        enforceChangePermission();
        if (mWifiHandler == null) return false;

        /*
         * Remove any enable/disable Wi-Fi messages we may have in the queue
         * before adding a new one
         */
        synchronized (mWifiHandler) {
            sWakeLock.acquire();
            sendEnableMessage(enable, true);
        }

        return true;
    }

    /**
     * Enables/disables Wi-Fi synchronously.
     * @param enable {@code true} to turn Wi-Fi on, {@code false} to turn it off.
     * @param persist {@code true} if the setting should be persisted.
     * @return {@code true} if the operation succeeds (or if the existing state
     *         is the same as the requested state)
     */
    private boolean setWifiEnabledBlocking(boolean enable, boolean persist) {
        final int eventualWifiState = enable ? WIFI_STATE_ENABLED : WIFI_STATE_DISABLED;

        if (mWifiState == eventualWifiState) {
            return true;
        }
        if (enable && isAirplaneModeOn()) {
            return false;
        }

        updateWifiState(enable ? WIFI_STATE_ENABLING : WIFI_STATE_DISABLING);

        if (enable) {
            if (!WifiNative.loadDriver()) {
                Log.e(TAG, "Failed to load Wi-Fi driver.");
                updateWifiState(WIFI_STATE_UNKNOWN);
                return false;
            }
            if (!WifiNative.startSupplicant()) {
                WifiNative.unloadDriver();
                Log.e(TAG, "Failed to start supplicant daemon.");
                updateWifiState(WIFI_STATE_UNKNOWN);
                return false;
            }
            mWifiStateTracker.startEventLoop();
        } else {

            // Remove notification (it will no-op if it isn't visible)
            mWifiStateTracker.setNotificationVisible(false, 0, false, 0);

            boolean failedToStopSupplicantOrUnloadDriver = false;
            if (!WifiNative.stopSupplicant()) {
                Log.e(TAG, "Failed to stop supplicant daemon.");
                updateWifiState(WIFI_STATE_UNKNOWN);
                failedToStopSupplicantOrUnloadDriver = true;
            }
            
            // We must reset the interface before we unload the driver
            mWifiStateTracker.resetInterface();
            
            if (!WifiNative.unloadDriver()) {
                Log.e(TAG, "Failed to unload Wi-Fi driver.");
                if (!failedToStopSupplicantOrUnloadDriver) {
                    updateWifiState(WIFI_STATE_UNKNOWN);
                    failedToStopSupplicantOrUnloadDriver = true;
                }
            }
            if (failedToStopSupplicantOrUnloadDriver) {
                return false;
            }
        }

        // Success!

        if (persist) {
            persistWifiEnabled(enable);
        }
        updateWifiState(eventualWifiState);

        /*
         * Initialize the hidden networks state and the number of allowed
         * radio channels if Wi-Fi is being turned on.
         */
        if (enable) {
            mWifiStateTracker.setNumAllowedChannels();
            initializeHiddenNetworksState();
        }

        return true;
    }

    private void updateWifiState(int wifiState) {
        final int previousWifiState = mWifiState;

        // Update state
        mWifiState = wifiState;

        // Broadcast
        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, wifiState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, previousWifiState);
        mContext.sendStickyBroadcast(intent);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                                                "WifiService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                                                "WifiService");

    }

    /**
     * see {@link WifiManager#getWifiState()}
     * @return One of {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    public int getWifiState() {
        enforceAccessPermission();
        return mWifiState;
    }

    /**
     * see {@link android.net.wifi.WifiManager#disconnect()}
     * @return {@code true} if the operation succeeds
     */
    public boolean disconnect() {
        enforceChangePermission();
        synchronized (mWifiStateTracker) {
            return WifiNative.disconnectCommand();
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#reconnect()}
     * @return {@code true} if the operation succeeds
     */
    public boolean reconnect() {
        enforceChangePermission();
        synchronized (mWifiStateTracker) {
            return WifiNative.reconnectCommand();
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#reassociate()}
     * @return {@code true} if the operation succeeds
     */
    public boolean reassociate() {
        enforceChangePermission();
        synchronized (mWifiStateTracker) {
            return WifiNative.reassociateCommand();
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#getConfiguredNetworks()}
     * @return the list of configured networks
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        enforceAccessPermission();
        String listStr;
        /*
         * We don't cache the list, because we want to allow
         * for the possibility that the configuration file
         * has been modified through some external means,
         * such as the wpa_cli command line program.
         */
        synchronized (mWifiStateTracker) {
            listStr = WifiNative.listNetworksCommand();
        }
        List<WifiConfiguration> networks =
            new ArrayList<WifiConfiguration>();
        if (listStr == null)
            return networks;

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
           } else
               config.status = WifiConfiguration.Status.ENABLED;
           synchronized (mWifiStateTracker) {
               readNetworkVariables(config);
           }
           networks.add(config);
       }

        return networks;
    }

    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     * <p/>
     * The caller must hold the synchronization monitor.
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    private static void readNetworkVariables(WifiConfiguration config) {

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
            config.SSID = value;
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

        /*
         * Get up to 4 WEP keys. Note that the actual keys are not passed back,
         * just a "*" if the key is set, or the null string otherwise.
         */
        for (int i = 0; i < 4; i++) {
            value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.wepKeyVarNames[i]);
            if (!TextUtils.isEmpty(value)) {
                config.wepKeys[i] = value;
            } else {
                config.wepKeys[i] = null;
            }
        }

        /*
         * Get the private shared key. Note that the actual keys are not passed back,
         * just a "*" if the key is set, or the null string otherwise.
         */
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
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOrUpdateNetwork(WifiConfiguration)}
     * @return the supplicant-assigned identifier for the new or updated
     * network if the operation succeeds, or {@code -1} if it fails
     */
    public synchronized int addOrUpdateNetwork(WifiConfiguration config) {
        enforceChangePermission();
        /*
         * If the supplied networkId is -1, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */
        int netId = config.networkId;
        boolean newNetwork = netId == -1;
        boolean doReconfig;
        int currentPriority;
        // networkId of -1 means we want to create a new network
        if (newNetwork) {
            netId = WifiNative.addNetworkCommand();
            if (netId < 0) {
                if (DBG) {
                    Log.d(TAG, "Failed to add a network!");
                }
                return -1;
            }
            doReconfig = true;
        } else {
            String priorityVal = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.priorityVarName);
            currentPriority = -1;
            if (!TextUtils.isEmpty(priorityVal)) {
                try {
                    currentPriority = Integer.parseInt(priorityVal);
                } catch (NumberFormatException ignore) {
                }
            }
            doReconfig = currentPriority != config.priority;
        }
        mNeedReconfig = mNeedReconfig || doReconfig;

        /*
         * If we have hidden networks, we may have to change the scan mode
         */
        if (config.hiddenSSID) {
            // Mark the network as present unless it is disabled
            addOrUpdateHiddenNetwork(
                netId, config.status != WifiConfiguration.Status.DISABLED);
        }

        setVariables: {
            /*
             * Note that if a networkId for a non-existent network
             * was supplied, then the first setNetworkVariableCommand()
             * will fail, so we don't bother to make a separate check
             * for the validity of the ID up front.
             */

            if (config.SSID != null &&
                !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.ssidVarName,
                    config.SSID)) {
                if (DBG) {
                    Log.d(TAG, "failed to set SSID: "+config.SSID);
                }
                break setVariables;
            }

            if (config.BSSID != null &&
                !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.bssidVarName,
                    config.BSSID)) {
                if (DBG) {
                    Log.d(TAG, "failed to set BSSID: "+config.BSSID);
                }
                break setVariables;
            }

            String allowedKeyManagementString =
                makeString(config.allowedKeyManagement, WifiConfiguration.KeyMgmt.strings);
            if (config.allowedKeyManagement.cardinality() != 0 &&
                !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.KeyMgmt.varName,
                    allowedKeyManagementString)) {
                if (DBG) {
                    Log.d(TAG, "failed to set key_mgmt: "+
                          allowedKeyManagementString);
                }
                break setVariables;
            }

            String allowedProtocolsString =
                makeString(config.allowedProtocols, WifiConfiguration.Protocol.strings);
            if (config.allowedProtocols.cardinality() != 0 &&
                !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.Protocol.varName,
                    allowedProtocolsString)) {
                if (DBG) {
                    Log.d(TAG, "failed to set proto: "+
                          allowedProtocolsString);
                }
                break setVariables;
            }

            String allowedAuthAlgorithmsString =
                makeString(config.allowedAuthAlgorithms, WifiConfiguration.AuthAlgorithm.strings);
            if (config.allowedAuthAlgorithms.cardinality() != 0 &&
                !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.AuthAlgorithm.varName,
                    allowedAuthAlgorithmsString)) {
                if (DBG) {
                    Log.d(TAG, "failed to set auth_alg: "+
                          allowedAuthAlgorithmsString);
                }
                break setVariables;
            }

            String allowedPairwiseCiphersString =
                makeString(config.allowedPairwiseCiphers, WifiConfiguration.PairwiseCipher.strings);
            if (config.allowedPairwiseCiphers.cardinality() != 0 &&
                !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.PairwiseCipher.varName,
                    allowedPairwiseCiphersString)) {
                if (DBG) {
                    Log.d(TAG, "failed to set pairwise: "+
                          allowedPairwiseCiphersString);
                }
                break setVariables;
            }

            String allowedGroupCiphersString =
                makeString(config.allowedGroupCiphers, WifiConfiguration.GroupCipher.strings);
            if (config.allowedGroupCiphers.cardinality() != 0 &&
                !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.GroupCipher.varName,
                    allowedGroupCiphersString)) {
                if (DBG) {
                    Log.d(TAG, "failed to set group: "+
                          allowedGroupCiphersString);
                }
                break setVariables;
            }

            // Prevent client screw-up by passing in a WifiConfiguration we gave it
            // by preventing "*" as a key.
            if (config.preSharedKey != null && !config.preSharedKey.equals("*") &&
                !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.pskVarName,
                    config.preSharedKey)) {
                if (DBG) {
                    Log.d(TAG, "failed to set psk: "+config.preSharedKey);
                }
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
                            if (DBG) {
                                Log.d(TAG,
                                      "failed to set wep_key"+i+": " +
                                      config.wepKeys[i]);
                            }
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
                    if (DBG) {
                        Log.d(TAG,
                              "failed to set wep_tx_keyidx: "+
                              config.wepTxKeyIndex);
                    }
                    break setVariables;
                }
            }

            if (!WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.priorityVarName,
                    Integer.toString(config.priority))) {
                if (DBG) {
                    Log.d(TAG, config.SSID + ": failed to set priority: "
                          +config.priority);
                }
                break setVariables;
            }

            if (config.hiddenSSID && !WifiNative.setNetworkVariableCommand(
                    netId,
                    WifiConfiguration.hiddenSSIDVarName,
                    Integer.toString(config.hiddenSSID ? 1 : 0))) {
                if (DBG) {
                    Log.d(TAG, config.SSID + ": failed to set hiddenSSID: "+
                          config.hiddenSSID);
                }
                break setVariables;
            }

            return netId;
        }

        /*
         * For an update, if one of the setNetworkVariable operations fails,
         * we might want to roll back all the changes already made. But the
         * chances are that if anything is going to go wrong, it'll happen
         * the first time we try to set one of the variables.
         */
        if (newNetwork) {
            removeNetwork(netId);
            if (DBG) {
                Log.d(TAG,
                      "Failed to set a network variable, removed network: "
                      + netId);
            }
        }
        return -1;
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

        if (DBG) {
            // if we ever get here, we should probably add the
            // value to WifiConfiguration to reflect that it's
            // supported by the WPA supplicant
            Log.w(TAG, "Failed to look-up a string: " + string);
        }

        return -1;
    }

    /**
     * See {@link android.net.wifi.WifiManager#removeNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean removeNetwork(int netId) {
        enforceChangePermission();

        /*
         * If we have hidden networks, we may have to change the scan mode
         */
        removeNetworkIfHidden(netId);

        synchronized (mWifiStateTracker) {
            return WifiNative.removeNetworkCommand(netId);
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#enableNetwork(int, boolean)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @param disableOthers if true, disable all other networks.
     * @return {@code true} if the operation succeeded
     */
    public boolean enableNetwork(int netId, boolean disableOthers) {
        enforceChangePermission();

        /*
         * If we have hidden networks, we may have to change the scan mode
         */
         synchronized(this) {
             if (disableOthers) {
                 markAllHiddenNetworksButOneAsNotPresent(netId);
             }
             updateNetworkIfHidden(netId, true);
         }

        synchronized (mWifiStateTracker) {
            return WifiNative.enableNetworkCommand(netId, disableOthers);
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#disableNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean disableNetwork(int netId) {
        enforceChangePermission();

        /*
         * If we have hidden networks, we may have to change the scan mode
         */
        updateNetworkIfHidden(netId, false);

        synchronized (mWifiStateTracker) {
            return WifiNative.disableNetworkCommand(netId);
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#getConnectionInfo()}
     * @return the Wi-Fi information, contained in {@link WifiInfo}.
     */
    public WifiInfo getConnectionInfo() {
        enforceAccessPermission();
        /*
         * Make sure we have the latest information, by sending
         * a status request to the supplicant.
         */
        return mWifiStateTracker.requestConnectionInfo();
    }

    /**
     * Return the results of the most recent access point scan, in the form of
     * a list of {@link ScanResult} objects.
     * @return the list of results
     */
    public List<ScanResult> getScanResults() {
        enforceAccessPermission();
        String reply;
        synchronized (mWifiStateTracker) {
            reply = WifiNative.scanResultsCommand();
        }
        if (reply == null) {
            return null;
        }

        List<ScanResult> scanList = new ArrayList<ScanResult>();

        int lineCount = 0;

        int replyLen = reply.length();
        // Parse the result string, keeping in mind that the last line does
        // not end with a newline.
        for (int lineBeg = 0, lineEnd = 0; lineEnd <= replyLen; ++lineEnd) {
            if (lineEnd == replyLen || reply.charAt(lineEnd) == '\n') {
                ++lineCount;
                /*
                 * Skip the first line, which is a header
                 */
                if (lineCount == 1) {
                    lineBeg = lineEnd + 1;
                    continue;
                }
                int lineLen = lineEnd - lineBeg;
                if (0 < lineLen && lineLen <= SCAN_RESULT_BUFFER_SIZE) {
                    int scanResultLevel = 0;
                    /*
                     * At most one thread should have access to the buffer at a time!
                     */
                    synchronized(mScanResultBuffer) {
                        boolean parsingScanResultLevel = false;
                        for (int i = lineBeg; i < lineEnd; ++i) {
                            char ch = reply.charAt(i);
                            /*
                             * Assume that the signal level starts with a '-'
                             */
                            if (ch == '-') {
                                /*
                                 * Skip whatever instances of '-' we may have
                                 * after we parse the signal level
                                 */
                                parsingScanResultLevel = (scanResultLevel == 0);
                            } else if (parsingScanResultLevel) {
                                int digit = Character.digit(ch, 10);
                                if (0 <= digit) {
                                    scanResultLevel =
                                        10 * scanResultLevel + digit;
                                    /*
                                     * Replace the signal level number in
                                     * the string with 0's for caching
                                     */
                                    ch = '0';
                                } else {
                                    /*
                                     * Reset the flag if we meet a non-digit
                                     * character
                                     */
                                    parsingScanResultLevel = false;
                                }
                            }
                            mScanResultBuffer[i - lineBeg] = ch;
                        }
                        if (scanResultLevel != 0) {
                            ScanResult scanResult = parseScanResult(
                                new String(mScanResultBuffer, 0, lineLen));
                            if (scanResult != null) {
                              scanResult.level = -scanResultLevel;
                              scanList.add(scanResult);
                              //if (DBG) Log.d(TAG, "ScanResult: " + scanResult);
                            }
                        } else if (DBG) {
                            Log.w(TAG,
                                  "ScanResult.level=0: misformatted scan result?");
                        }
                    }
                } else if (0 < lineLen) {
                    if (DBG) {
                        Log.w(TAG, "Scan result line is too long: " +
                              (lineEnd - lineBeg) + ", skipping the line!");
                    }
                }
                lineBeg = lineEnd + 1;
            }
        }
        mWifiStateTracker.setScanResultsList(scanList);
        return scanList;
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
                scanResult = mScanResultCache.get(line);
                if (scanResult == null) {
                    String[] result = scanResultPattern.split(line);
                    if (3 <= result.length && result.length <= 5) {
                        // bssid | frequency | level | flags | ssid
                        int frequency;
                        int level;
                        try {
                            frequency = Integer.parseInt(result[1]);
                            level = Integer.parseInt(result[2]);
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

                        // Do not add scan results that have no SSID set
                        if (0 < ssid.trim().length()) {
                            scanResult =
                                new ScanResult(
                                    ssid, result[0], flags, level, frequency);
                            mScanResultCache.put(line, scanResult);
                        }
                    } else {
                        Log.w(TAG, "Misformatted scan result text with " +
                              result.length + " fields: " + line);
                    }
                }
            }
        }

        return scanResult;
    }

    /**
     * Parse the "flags" field passed back in a scan result by wpa_supplicant,
     * and construct a {@code WifiConfiguration} that describes the encryption,
     * key management, and authenticaion capabilities of the access point.
     * @param flags the string returned by wpa_supplicant
     * @return the {@link WifiConfiguration} object, filled in
     */
    WifiConfiguration parseScanFlags(String flags) {
        WifiConfiguration config = new WifiConfiguration();

        if (flags.length() == 0) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }
        // ... to be implemented
        return config;
    }

    /**
     * Tell the supplicant to persist the current list of configured networks.
     * @return {@code true} if the operation succeeded
     */
    public boolean saveConfiguration() {
        boolean result;
        enforceChangePermission();
        synchronized (mWifiStateTracker) {
            result = WifiNative.saveConfigCommand();
            if (result && mNeedReconfig) {
                mNeedReconfig = false;
                result = WifiNative.reloadConfigCommand();
                
                if (result) {
                    Intent intent = new Intent(WifiManager.NETWORK_IDS_CHANGED_ACTION);
                    mContext.sendBroadcast(intent);
                }
            }
        }
        return result;
    }

    /**
     * Set the number of radio frequency channels that are allowed to be used
     * in the current regulatory domain. This method should be used only
     * if the correct number of channels cannot be determined automatically
     * for some reason. If the operation is successful, the new value is
     * persisted as a System setting.
     * @param numChannels the number of allowed channels. Must be greater than 0
     * and less than or equal to 16.
     * @return {@code true} if the operation succeeds, {@code false} otherwise, e.g.,
     * {@code numChannels} is outside the valid range.
     */
    public boolean setNumAllowedChannels(int numChannels) {
        enforceChangePermission();
        /*
         * Validate the argument. We'd like to let the Wi-Fi driver do this,
         * but if Wi-Fi isn't currently enabled, that's not possible, and
         * we want to persist the setting anyway,so that it will take
         * effect when Wi-Fi does become enabled.
         */
        boolean found = false;
        for (int validChan : sValidRegulatoryChannelCounts) {
            if (validChan == numChannels) {
                found = true;
                break;
            }
        }
        if (!found) {
            return false;
        }

        Settings.Secure.putInt(mContext.getContentResolver(),
                               Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS,
                               numChannels);
        mWifiStateTracker.setNumAllowedChannels(numChannels);
        return true;
    }

    /**
     * Return the number of frequency channels that are allowed
     * to be used in the current regulatory domain.
     * @return the number of allowed channels, or {@code -1} if an error occurs
     */
    public int getNumAllowedChannels() {
        int numChannels;

        enforceAccessPermission();
        synchronized (mWifiStateTracker) {
            /*
             * If we can't get the value from the driver (e.g., because
             * Wi-Fi is not currently enabled), get the value from
             * Settings.
             */
            numChannels = WifiNative.getNumAllowedChannelsCommand();
            if (numChannels < 0) {
                numChannels = Settings.Secure.getInt(mContext.getContentResolver(),
                                                     Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS,
                                                     -1);
            }
        }
        return numChannels;
    }

    /**
     * Return the list of valid values for the number of allowed radio channels
     * for various regulatory domains.
     * @return the list of channel counts
     */
    public int[] getValidChannelCounts() {
        enforceAccessPermission();
        return sValidRegulatoryChannelCounts;
    }

    /**
     * Return the DHCP-assigned addresses from the last successful DHCP request,
     * if any.
     * @return the DHCP information
     */
    public DhcpInfo getDhcpInfo() {
        enforceAccessPermission();
        return mWifiStateTracker.getDhcpInfo();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            long idleMillis = Settings.System.getLong(mContext.getContentResolver(),
                                                  Settings.System.WIFI_IDLE_MS, DEFAULT_IDLE_MILLIS);
            int stayAwakeConditions =
                    Settings.System.getInt(mContext.getContentResolver(),
                                           Settings.System.STAY_ON_WHILE_PLUGGED_IN, 0);
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                /* do nothing, we'll check isAirplaneModeOn later. */
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mAlarmManager.cancel(mIdleIntent);
                mDeviceIdle = false;
                mScreenOff = false;
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOff = true;
                /*
                 * Set a timer to put Wi-Fi to sleep, but only if the screen is off
                 * AND the "stay on while plugged in" setting doesn't match the
                 * current power conditions (i.e, not plugged in, plugged in to USB,
                 * or plugged in to AC).
                 */
                if (!shouldStayAwake(stayAwakeConditions, mPluggedType)) {
                    long triggerTime = System.currentTimeMillis() + idleMillis;
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, mIdleIntent);
                }
                /* we can return now -- there's nothing to do until we get the idle intent back */
                return;
            } else if (action.equals(ACTION_DEVICE_IDLE)) {
                mDeviceIdle = true;
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                /*
                 * Set a timer to put Wi-Fi to sleep, but only if the screen is off
                 * AND we are transitioning from a state in which the device was supposed
                 * to stay awake to a state in which it is not supposed to stay awake.
                 * If "stay awake" state is not changing, we do nothing, to avoid resetting
                 * the already-set timer.
                 */
                int pluggedType = intent.getIntExtra("plugged", 0);
                if (mScreenOff && shouldStayAwake(stayAwakeConditions, mPluggedType) &&
                        !shouldStayAwake(stayAwakeConditions, pluggedType)) {
                    long triggerTime = System.currentTimeMillis() + idleMillis;
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, mIdleIntent);
                    mPluggedType = pluggedType;
                    return;
                }
                mPluggedType = pluggedType;
            } else {
                return;
            }

            updateWifiState();
        }

        /**
         * Determine whether the bit value corresponding to {@code pluggedType} is set in
         * the bit string {@code stayAwakeConditions}. Because a {@code pluggedType} value
         * of {@code 0} isn't really a plugged type, but rather an indication that the
         * device isn't plugged in at all, there is no bit value corresponding to a
         * {@code pluggedType} value of {@code 0}. That is why we shift by
         * {@code pluggedType&nbsp;&#8212;&nbsp;1} instead of by {@code pluggedType}.
         * @param stayAwakeConditions a bit string specifying which "plugged types" should
         * keep the device (and hence Wi-Fi) awake.
         * @param pluggedType the type of plug (USB, AC, or none) for which the check is
         * being made
         * @return {@code true} if {@code pluggedType} indicates that the device is
         * supposed to stay awake, {@code false} otherwise.
         */
        private boolean shouldStayAwake(int stayAwakeConditions, int pluggedType) {
            return (stayAwakeConditions & pluggedType) != 0;
        }
    };

    private void sendEnableMessage(boolean enable, boolean persist) {
        Message msg = Message.obtain(mWifiHandler,
                                     (enable ? MESSAGE_ENABLE_WIFI : MESSAGE_DISABLE_WIFI),
                                     (persist ? 1 : 0), 0);
        msg.sendToTarget();
    }

    private void sendStartMessage(boolean scanOnlyMode) {
        if (DBG) Log.d(TAG, "sendStartMessage(" + scanOnlyMode + ")");
        Message.obtain(mWifiHandler, MESSAGE_START_WIFI, scanOnlyMode ? 1 : 0, 0).sendToTarget();
    }

    private void updateWifiState() {
        boolean wifiEnabled = getPersistedWifiEnabled();
        boolean airplaneMode = isAirplaneModeOn();
        boolean lockHeld = mLocks.hasLocks();
        int strongestLockMode;

        boolean wifiShouldBeEnabled = wifiEnabled && !airplaneMode;
        boolean wifiShouldBeStarted = !mDeviceIdle || lockHeld;
        if (mDeviceIdle && lockHeld) {
            strongestLockMode = mLocks.getStrongestLockMode();
        } else {
            strongestLockMode = WifiManager.WIFI_MODE_FULL;
        }

        synchronized (mWifiHandler) {
            if (wifiShouldBeEnabled) {
                if (wifiShouldBeStarted) {
                    sWakeLock.acquire();
                    sendEnableMessage(true, false);
                    sWakeLock.acquire();
                    sendStartMessage(strongestLockMode == WifiManager.WIFI_MODE_SCAN_ONLY);
                } else {
                    int wakeLockTimeout =
                            Settings.Secure.getInt(
                                    mContext.getContentResolver(),
                                    Settings.Secure.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS,
                                    DEFAULT_WAKELOCK_TIMEOUT);
                    sDriverStopWakeLock.acquire();
                    mWifiHandler.sendEmptyMessage(MESSAGE_STOP_WIFI);
                    mWifiHandler.sendEmptyMessageDelayed(MESSAGE_RELEASE_WAKELOCK, wakeLockTimeout);
                }
            } else {
                sWakeLock.acquire();
                sendEnableMessage(false, false);
            }
        }
    }

    private void registerForBroadcasts() {
        String airplaneModeRadios = Settings.System.getString(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_RADIOS);
        boolean isAirplaneSensitive = airplaneModeRadios == null
            || airplaneModeRadios.contains(Settings.System.RADIO_WIFI);
        if (isAirplaneSensitive) {
            mContext.registerReceiver(mReceiver,
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));
        }
        mContext.registerReceiver(mReceiver,
                new IntentFilter(Intent.ACTION_SCREEN_ON));
        mContext.registerReceiver(mReceiver,
                new IntentFilter(Intent.ACTION_SCREEN_OFF));
        mContext.registerReceiver(mReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        mContext.registerReceiver(mReceiver,
                new IntentFilter(ACTION_DEVICE_IDLE));
    }
    /**
     * Returns true if airplane mode is currently on.
     * @return {@code true} if airplane mode is on.
     */
    private boolean isAirplaneModeOn() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
    }

    /**
     * Handler that allows posting to the WifiThread.
     */
    private class WifiHandler extends Handler {
        public WifiHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {

                case MESSAGE_ENABLE_WIFI:
                    setWifiEnabledBlocking(true, msg.arg1 == 1);
                    sWakeLock.release();
                    break;

                case MESSAGE_START_WIFI:
                    mWifiStateTracker.setScanOnlyMode(msg.arg1 != 0);
                    mWifiStateTracker.restart();
                    sWakeLock.release();
                    break;

                case MESSAGE_DISABLE_WIFI:
                    // a non-zero msg.arg1 value means the "enabled" setting
                    // should be persisted
                    setWifiEnabledBlocking(false, msg.arg1 == 1);
                    sWakeLock.release();
                    break;

                case MESSAGE_STOP_WIFI:
                    mWifiStateTracker.disconnectAndStop();
                    // don't release wakelock
                    break;

                case MESSAGE_RELEASE_WAKELOCK:
                    synchronized (sDriverStopWakeLock) {
                        if (sDriverStopWakeLock.isHeld()) {
                            sDriverStopWakeLock.release();
                        }
                    }
                    break;
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("Wi-Fi is " + stateName(mWifiState));
        pw.println("stay-awake conditions: " +
                Settings.System.getInt(mContext.getContentResolver(),
                                       Settings.System.STAY_ON_WHILE_PLUGGED_IN, 0));
        pw.println();

        pw.println("Latest scan results:");
        List<ScanResult> scanResults = mWifiStateTracker.getScanResultsList();
        if (scanResults != null && scanResults.size() != 0) {
            pw.println("  BSSID              Frequency   RSSI  Flags             SSID");
            for (ScanResult r : scanResults) {
                pw.printf("  %17s  %9d  %5d  %-16s  %s%n",
                                         r.BSSID,
                                         r.frequency,
                                         r.level,
                                         r.capabilities,
                                         r.SSID == null ? "" : r.SSID);
            }
        }
        pw.println();
        pw.println("Locks held:");
        mLocks.dump(pw);
    }

    private static String stateName(int wifiState) {
        switch (wifiState) {
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

    private class WifiLock implements IBinder.DeathRecipient {
        String mTag;
        int mLockMode;
        IBinder mBinder;

        WifiLock(int lockMode, String tag, IBinder binder) {
            super();
            mTag = tag;
            mLockMode = lockMode;
            mBinder = binder;
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        public void binderDied() {
            synchronized (mLocks) {
                releaseWifiLockLocked(mBinder);
            }
        }

        public String toString() {
            return "WifiLock{" + mTag + " type=" + mLockMode + " binder=" + mBinder + "}";
        }
    }

    private class LockList {
        private List<WifiLock> mList;

        private LockList() {
            mList = new ArrayList<WifiLock>();
        }

        private synchronized boolean hasLocks() {
            return !mList.isEmpty();
        }

        private synchronized int getStrongestLockMode() {
            if (mList.isEmpty()) {
                return WifiManager.WIFI_MODE_FULL;
            }
            for (WifiLock l : mList) {
                if (l.mLockMode == WifiManager.WIFI_MODE_FULL) {
                    return WifiManager.WIFI_MODE_FULL;
                }
            }
            return WifiManager.WIFI_MODE_SCAN_ONLY;
        }

        private void addLock(WifiLock lock) {
            if (findLockByBinder(lock.mBinder) < 0) {
                mList.add(lock);
            }
        }

        private WifiLock removeLock(IBinder binder) {
            int index = findLockByBinder(binder);
            if (index >= 0) {
                return mList.remove(index);
            } else {
                return null;
            }
        }

        private int findLockByBinder(IBinder binder) {
            int size = mList.size();
            for (int i = size - 1; i >= 0; i--)
                if (mList.get(i).mBinder == binder)
                    return i;
            return -1;
        }

        private synchronized void clear() {
            if (!mList.isEmpty()) {
                mList.clear();
                updateWifiState();
            }
        }

        private void dump(PrintWriter pw) {
            for (WifiLock l : mList) {
                pw.print("    ");
                pw.println(l);
            }
        }
    }

    public boolean acquireWifiLock(IBinder binder, int lockMode, String tag) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (lockMode != WifiManager.WIFI_MODE_FULL && lockMode != WifiManager.WIFI_MODE_SCAN_ONLY) {
            return false;
        }
        WifiLock wifiLock = new WifiLock(lockMode, tag, binder);
        synchronized (mLocks) {
            return acquireWifiLockLocked(wifiLock);
        }
    }

    private boolean acquireWifiLockLocked(WifiLock wifiLock) {
        mLocks.addLock(wifiLock);
        updateWifiState();
        return true;
    }

    public boolean releaseWifiLock(IBinder lock) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        synchronized (mLocks) {
            return releaseWifiLockLocked(lock);
        }
    }

    private boolean releaseWifiLockLocked(IBinder lock) {
        boolean result;
        result = (mLocks.removeLock(lock) != null);
        updateWifiState();
        return result;
    }
}
