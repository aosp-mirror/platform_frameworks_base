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

package com.android.server;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.IWifiManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiStateTracker;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.NetworkStateTracker;
import android.net.DhcpInfo;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;
import com.android.internal.R;

/**
 * WifiService handles remote WiFi operation requests by implementing
 * the IWifiManager interface.
 *
 * @hide
 */
//TODO: Clean up multiple locks and implement WifiService
// as a SM to track soft AP/client/adhoc bring up based
// on device idle state, airplane mode and boot.

public class WifiService extends IWifiManager.Stub {
    private static final String TAG = "WifiService";
    private static final boolean DBG = true;

    private final WifiStateTracker mWifiStateTracker;

    private Context mContext;

    private AlarmManager mAlarmManager;
    private PendingIntent mIdleIntent;
    private static final int IDLE_REQUEST = 0;
    private boolean mScreenOff;
    private boolean mDeviceIdle;
    private int mPluggedType;

    // true if the user enabled Wifi while in airplane mode
    private AtomicBoolean mAirplaneModeOverwridden = new AtomicBoolean(false);

    private final LockList mLocks = new LockList();
    // some wifi lock statistics
    private int mFullLocksAcquired;
    private int mFullLocksReleased;
    private int mScanLocksAcquired;
    private int mScanLocksReleased;

    private final List<Multicaster> mMulticasters =
            new ArrayList<Multicaster>();
    private int mMulticastEnabled;
    private int mMulticastDisabled;

    private final IBatteryStats mBatteryStats;

    ConnectivityManager mCm;
    private String[] mWifiRegexs;

    /**
     * See {@link Settings.Secure#WIFI_IDLE_MS}. This is the default value if a
     * Settings.Secure value is not present. This timeout value is chosen as
     * the approximate point at which the battery drain caused by Wi-Fi
     * being enabled but not active exceeds the battery drain caused by
     * re-establishing a connection to the mobile data network.
     */
    private static final long DEFAULT_IDLE_MILLIS = 15 * 60 * 1000; /* 15 minutes */

    /**
     * Number of allowed radio frequency channels in various regulatory domains.
     * This list is sufficient for 802.11b/g networks (2.4GHz range).
     */
    private static int[] sValidRegulatoryChannelCounts = new int[] {11, 13, 14};

    private static final String ACTION_DEVICE_IDLE =
            "com.android.server.WifiManager.action.DEVICE_IDLE";

    private boolean mIsReceiverRegistered = false;

    WifiService(Context context, WifiStateTracker tracker) {
        mContext = context;
        mWifiStateTracker = tracker;
        mWifiStateTracker.enableRssiPolling(true);
        mBatteryStats = BatteryStatsService.getService();

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent idleIntent = new Intent(ACTION_DEVICE_IDLE, null);
        mIdleIntent = PendingIntent.getBroadcast(mContext, IDLE_REQUEST, idleIntent, 0);

        HandlerThread wifiThread = new HandlerThread("WifiService");
        wifiThread.start();

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        // clear our flag indicating the user has overwridden airplane mode
                        mAirplaneModeOverwridden.set(false);
                        // on airplane disable, restore Wifi if the saved state indicates so
                        if (!isAirplaneModeOn() && testAndClearWifiSavedState()) {
                            persistWifiEnabled(true);
                        }
                        updateWifiState();
                    }
                },
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));

        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {

                    ArrayList<String> available = intent.getStringArrayListExtra(
                            ConnectivityManager.EXTRA_AVAILABLE_TETHER);
                    ArrayList<String> active = intent.getStringArrayListExtra(
                            ConnectivityManager.EXTRA_ACTIVE_TETHER);
                    updateTetherState(available, active);

                }
            },new IntentFilter(ConnectivityManager.ACTION_TETHER_STATE_CHANGED));
    }

    /**
     * Check if Wi-Fi needs to be enabled and start
     * if needed
     *
     * This function is used only at boot time
     */
    public void startWifi() {
        /* Start if Wi-Fi is enabled or the saved state indicates Wi-Fi was on */
        boolean wifiEnabled = !isAirplaneModeOn()
                && (getPersistedWifiEnabled() || testAndClearWifiSavedState());
        Slog.i(TAG, "WifiService starting up with Wi-Fi " +
                (wifiEnabled ? "enabled" : "disabled"));
        setWifiEnabled(wifiEnabled);
    }

    private void updateTetherState(ArrayList<String> available, ArrayList<String> tethered) {

        boolean wifiTethered = false;
        boolean wifiAvailable = false;

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

        if (mCm == null) {
            mCm = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        }

        mWifiRegexs = mCm.getTetherableWifiRegexs();

        for (String intf : available) {
            for (String regex : mWifiRegexs) {
                if (intf.matches(regex)) {

                    InterfaceConfiguration ifcg = null;
                    try {
                        ifcg = service.getInterfaceConfig(intf);
                        if (ifcg != null) {
                            /* IP/netmask: 192.168.43.1/255.255.255.0 */
                            ifcg.ipAddr = (192 << 24) + (168 << 16) + (43 << 8) + 1;
                            ifcg.netmask = (255 << 24) + (255 << 16) + (255 << 8) + 0;
                            ifcg.interfaceFlags = "up";

                            service.setInterfaceConfig(intf, ifcg);
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, "Error configuring interface " + intf + ", :" + e);
                        setWifiApEnabled(null, false);
                        return;
                    }

                    if(mCm.tether(intf) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                        Slog.e(TAG, "Error tethering on " + intf);
                        setWifiApEnabled(null, false);
                        return;
                    }
                    break;
                }
            }
        }
    }

    private boolean testAndClearWifiSavedState() {
        final ContentResolver cr = mContext.getContentResolver();
        int wifiSavedState = 0;
        try {
            wifiSavedState = Settings.Secure.getInt(cr, Settings.Secure.WIFI_SAVED_STATE);
            if(wifiSavedState == 1)
                Settings.Secure.putInt(cr, Settings.Secure.WIFI_SAVED_STATE, 0);
        } catch (Settings.SettingNotFoundException e) {
            ;
        }
        return (wifiSavedState == 1);
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
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean pingSupplicant() {
        enforceAccessPermission();
        return mWifiStateTracker.pingSupplicant();
    }

    /**
     * see {@link android.net.wifi.WifiManager#startScan()}
     * @return {@code true} if the operation succeeds
     */
    public boolean startScan(boolean forceActive) {
        enforceChangePermission();
        return mWifiStateTracker.startScan(forceActive);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                                                "WifiService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                                                "WifiService");

    }

    private void enforceMulticastChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                "WifiService");
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)}
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    public synchronized boolean setWifiEnabled(boolean enable) {
        enforceChangePermission();

        if (DBG) {
            Slog.e(TAG, "Invoking mWifiStateTracker.setWifiEnabled\n");
        }

        // set a flag if the user is enabling Wifi while in airplane mode
        if (enable && isAirplaneModeOn() && isAirplaneToggleable()) {
            mAirplaneModeOverwridden.set(true);
        }

        mWifiStateTracker.setWifiEnabled(enable);
        persistWifiEnabled(enable);

        if (enable) {
            if (!mIsReceiverRegistered) {
                registerForBroadcasts();
                mIsReceiverRegistered = true;
            }
        } else if (mIsReceiverRegistered){
            mContext.unregisterReceiver(mReceiver);
            mIsReceiverRegistered = false;
        }

        return true;
    }

    /**
     * see {@link WifiManager#getWifiState()}
     * @return One of {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    public int getWifiEnabledState() {
        enforceAccessPermission();
        return mWifiStateTracker.getWifiState();
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiApEnabled(WifiConfiguration, boolean)}
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @param enabled true to enable and false to disable
     * @return {@code true} if the start operation was
     *         started or is already in the queue.
     */
    public synchronized boolean setWifiApEnabled(WifiConfiguration wifiConfig, boolean enabled) {
        enforceChangePermission();

        if (enabled) {
            /* Use default config if there is no existing config */
            if (wifiConfig == null && ((wifiConfig = getWifiApConfiguration()) == null)) {
                wifiConfig = new WifiConfiguration();
                wifiConfig.SSID = mContext.getString(R.string.wifi_tether_configure_ssid_default);
                wifiConfig.allowedKeyManagement.set(KeyMgmt.NONE);
            }
            setWifiApConfiguration(wifiConfig);
        }

        mWifiStateTracker.setWifiApEnabled(wifiConfig, enabled);

        return true;
    }

    /**
     * see {@link WifiManager#getWifiApState()}
     * @return One of {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *         {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *         {@link WifiManager#WIFI_AP_STATE_FAILED}
     */
    public int getWifiApEnabledState() {
        enforceAccessPermission();
        return mWifiStateTracker.getWifiApState();
    }

    /**
     * see {@link WifiManager#getWifiApConfiguration()}
     * @return soft access point configuration
     */
    public synchronized WifiConfiguration getWifiApConfiguration() {
        final ContentResolver cr = mContext.getContentResolver();
        WifiConfiguration wifiConfig = new WifiConfiguration();
        int authType;
        try {
            wifiConfig.SSID = Settings.Secure.getString(cr, Settings.Secure.WIFI_AP_SSID);
            if (wifiConfig.SSID == null)
                return null;
            authType = Settings.Secure.getInt(cr, Settings.Secure.WIFI_AP_SECURITY);
            wifiConfig.allowedKeyManagement.set(authType);
            wifiConfig.preSharedKey = Settings.Secure.getString(cr, Settings.Secure.WIFI_AP_PASSWD);
            return wifiConfig;
        } catch (Settings.SettingNotFoundException e) {
            Slog.e(TAG,"AP settings not found, returning");
            return null;
        }
    }

    /**
     * see {@link WifiManager#setWifiApConfiguration(WifiConfiguration)}
     * @param wifiConfig WifiConfiguration details for soft access point
     */
    public synchronized void setWifiApConfiguration(WifiConfiguration wifiConfig) {
        enforceChangePermission();
        final ContentResolver cr = mContext.getContentResolver();
        boolean isWpa;
        if (wifiConfig == null)
            return;
        Settings.Secure.putString(cr, Settings.Secure.WIFI_AP_SSID, wifiConfig.SSID);
        isWpa = wifiConfig.allowedKeyManagement.get(KeyMgmt.WPA_PSK);
        Settings.Secure.putInt(cr,
                               Settings.Secure.WIFI_AP_SECURITY,
                               isWpa ? KeyMgmt.WPA_PSK : KeyMgmt.NONE);
        if (isWpa)
            Settings.Secure.putString(cr, Settings.Secure.WIFI_AP_PASSWD, wifiConfig.preSharedKey);
    }

    /**
     * see {@link android.net.wifi.WifiManager#disconnect()}
     * @return {@code true} if the operation succeeds
     */
    public boolean disconnect() {
        enforceChangePermission();
        return mWifiStateTracker.disconnectCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#reconnect()}
     * @return {@code true} if the operation succeeds
     */
    public boolean reconnect() {
        enforceChangePermission();
        return mWifiStateTracker.reconnectCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#reassociate()}
     * @return {@code true} if the operation succeeds
     */
    public boolean reassociate() {
        enforceChangePermission();
        return mWifiStateTracker.reassociateCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#getConfiguredNetworks()}
     * @return the list of configured networks
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        enforceAccessPermission();
        return mWifiStateTracker.getConfiguredNetworks();
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOrUpdateNetwork(WifiConfiguration)}
     * @return the supplicant-assigned identifier for the new or updated
     * network if the operation succeeds, or {@code -1} if it fails
     */
    public int addOrUpdateNetwork(WifiConfiguration config) {
        enforceChangePermission();
        return mWifiStateTracker.addOrUpdateNetwork(config);
    }

     /**
     * See {@link android.net.wifi.WifiManager#removeNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean removeNetwork(int netId) {
        enforceChangePermission();
        return mWifiStateTracker.removeNetwork(netId);
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
        return mWifiStateTracker.enableNetwork(netId, disableOthers);
    }

    /**
     * See {@link android.net.wifi.WifiManager#disableNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    public boolean disableNetwork(int netId) {
        enforceChangePermission();
        return mWifiStateTracker.disableNetwork(netId);
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
        return mWifiStateTracker.getScanResultsList();
    }

    /**
     * Tell the supplicant to persist the current list of configured networks.
     * @return {@code true} if the operation succeeded
     *
     * TODO: deprecate this
     */
    public boolean saveConfiguration() {
        boolean result = true;
        enforceChangePermission();
        return mWifiStateTracker.saveConfig();
    }

    /**
     * Set the number of radio frequency channels that are allowed to be used
     * in the current regulatory domain. This method should be used only
     * if the correct number of channels cannot be determined automatically
     * for some reason. If the operation is successful, the new value may be
     * persisted as a Secure setting.
     * @param numChannels the number of allowed channels. Must be greater than 0
     * and less than or equal to 16.
     * @param persist {@code true} if the setting should be remembered.
     * @return {@code true} if the operation succeeds, {@code false} otherwise, e.g.,
     * {@code numChannels} is outside the valid range.
     */
    public synchronized boolean setNumAllowedChannels(int numChannels, boolean persist) {
        Slog.i(TAG, "WifiService trying to setNumAllowed to "+numChannels+
                " with persist set to "+persist);
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

        if (persist) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS,
                    numChannels);
        }

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

        /*
         * If we can't get the value from the driver (e.g., because
         * Wi-Fi is not currently enabled), get the value from
         * Settings.
         */
        numChannels = mWifiStateTracker.getNumAllowedChannels();
        if (numChannels < 0) {
            numChannels = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS,
                    -1);
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

            long idleMillis =
                Settings.Secure.getLong(mContext.getContentResolver(),
                                        Settings.Secure.WIFI_IDLE_MS, DEFAULT_IDLE_MILLIS);
            int stayAwakeConditions =
                Settings.System.getInt(mContext.getContentResolver(),
                                       Settings.System.STAY_ON_WHILE_PLUGGED_IN, 0);
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                Slog.d(TAG, "ACTION_SCREEN_ON");
                mAlarmManager.cancel(mIdleIntent);
                mDeviceIdle = false;
                mScreenOff = false;
                mWifiStateTracker.enableRssiPolling(true);
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                Slog.d(TAG, "ACTION_SCREEN_OFF");
                mScreenOff = true;
                mWifiStateTracker.enableRssiPolling(false);
                /*
                 * Set a timer to put Wi-Fi to sleep, but only if the screen is off
                 * AND the "stay on while plugged in" setting doesn't match the
                 * current power conditions (i.e, not plugged in, plugged in to USB,
                 * or plugged in to AC).
                 */
                if (!shouldWifiStayAwake(stayAwakeConditions, mPluggedType)) {
                    WifiInfo info = mWifiStateTracker.requestConnectionInfo();
                    if (info.getSupplicantState() != SupplicantState.COMPLETED) {
                        // we used to go to sleep immediately, but this caused some race conditions
                        // we don't have time to track down for this release.  Delay instead,
                        // but not as long as we would if connected (below)
                        // TODO - fix the race conditions and switch back to the immediate turn-off
                        long triggerTime = System.currentTimeMillis() + (2*60*1000); // 2 min
                        Slog.d(TAG, "setting ACTION_DEVICE_IDLE timer for 120,000 ms");
                        mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, mIdleIntent);
                        //  // do not keep Wifi awake when screen is off if Wifi is not associated
                        //  mDeviceIdle = true;
                        //  updateWifiState();
                    } else {
                        long triggerTime = System.currentTimeMillis() + idleMillis;
                        Slog.d(TAG, "setting ACTION_DEVICE_IDLE timer for " + idleMillis + "ms");
                        mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, mIdleIntent);
                    }
                }
                /* we can return now -- there's nothing to do until we get the idle intent back */
                return;
            } else if (action.equals(ACTION_DEVICE_IDLE)) {
                Slog.d(TAG, "got ACTION_DEVICE_IDLE");
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
                Slog.d(TAG, "ACTION_BATTERY_CHANGED pluggedType: " + pluggedType);
                if (mScreenOff && shouldWifiStayAwake(stayAwakeConditions, mPluggedType) &&
                        !shouldWifiStayAwake(stayAwakeConditions, pluggedType)) {
                    long triggerTime = System.currentTimeMillis() + idleMillis;
                    Slog.d(TAG, "setting ACTION_DEVICE_IDLE timer for " + idleMillis + "ms");
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, mIdleIntent);
                    mPluggedType = pluggedType;
                    return;
                }
                mPluggedType = pluggedType;
            } else if (action.equals(BluetoothA2dp.ACTION_SINK_STATE_CHANGED)) {
                BluetoothA2dp a2dp = new BluetoothA2dp(mContext);
                Set<BluetoothDevice> sinks = a2dp.getConnectedSinks();
                boolean isBluetoothPlaying = false;
                for (BluetoothDevice sink : sinks) {
                    if (a2dp.getSinkState(sink) == BluetoothA2dp.STATE_PLAYING) {
                        isBluetoothPlaying = true;
                    }
                }
                mWifiStateTracker.setBluetoothScanMode(isBluetoothPlaying);

            } else {
                return;
            }

            updateWifiState();
        }

        /**
         * Determines whether the Wi-Fi chipset should stay awake or be put to
         * sleep. Looks at the setting for the sleep policy and the current
         * conditions.
         *
         * @see #shouldDeviceStayAwake(int, int)
         */
        private boolean shouldWifiStayAwake(int stayAwakeConditions, int pluggedType) {
            int wifiSleepPolicy = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.WIFI_SLEEP_POLICY, Settings.System.WIFI_SLEEP_POLICY_DEFAULT);

            if (wifiSleepPolicy == Settings.System.WIFI_SLEEP_POLICY_NEVER) {
                // Never sleep
                return true;
            } else if ((wifiSleepPolicy == Settings.System.WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED) &&
                    (pluggedType != 0)) {
                // Never sleep while plugged, and we're plugged
                return true;
            } else {
                // Default
                return shouldDeviceStayAwake(stayAwakeConditions, pluggedType);
            }
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
        private boolean shouldDeviceStayAwake(int stayAwakeConditions, int pluggedType) {
            return (stayAwakeConditions & pluggedType) != 0;
        }
    };

    private void updateWifiState() {
        boolean wifiEnabled = getPersistedWifiEnabled();
        boolean airplaneMode = isAirplaneModeOn() && !mAirplaneModeOverwridden.get();
        boolean lockHeld = mLocks.hasLocks();
        int strongestLockMode;
        boolean wifiShouldBeEnabled = wifiEnabled && !airplaneMode;
        boolean wifiShouldBeStarted = !mDeviceIdle || lockHeld;
        if (mDeviceIdle && lockHeld) {
            strongestLockMode = mLocks.getStrongestLockMode();
        } else {
            strongestLockMode = WifiManager.WIFI_MODE_FULL;
        }

        /* Disable tethering when airplane mode is enabled */
        if (airplaneMode) {
            mWifiStateTracker.setWifiApEnabled(null, false);
        }

        if (wifiShouldBeEnabled) {
            if (wifiShouldBeStarted) {
                mWifiStateTracker.setWifiEnabled(true);
                mWifiStateTracker.setScanOnlyMode(
                        strongestLockMode == WifiManager.WIFI_MODE_SCAN_ONLY);
                mWifiStateTracker.startWifi(true);
            } else {
                mWifiStateTracker.requestCmWakeLock();
                mWifiStateTracker.disconnectAndStop();
            }
        } else {
            mWifiStateTracker.setWifiEnabled(false);
        }
    }

    private void registerForBroadcasts() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        intentFilter.addAction(ACTION_DEVICE_IDLE);
        intentFilter.addAction(BluetoothA2dp.ACTION_SINK_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    private boolean isAirplaneSensitive() {
        String airplaneModeRadios = Settings.System.getString(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_RADIOS);
        return airplaneModeRadios == null
            || airplaneModeRadios.contains(Settings.System.RADIO_WIFI);
    }

    private boolean isAirplaneToggleable() {
        String toggleableRadios = Settings.System.getString(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS);
        return toggleableRadios != null
            && toggleableRadios.contains(Settings.System.RADIO_WIFI);
    }

    /**
     * Returns true if Wi-Fi is sensitive to airplane mode, and airplane mode is
     * currently on.
     * @return {@code true} if airplane mode is on.
     */
    private boolean isAirplaneModeOn() {
        return isAirplaneSensitive() && Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("Wi-Fi is " + mWifiStateTracker.getWifiStateByName());
        pw.println("Stay-awake conditions: " +
                Settings.System.getInt(mContext.getContentResolver(),
                                       Settings.System.STAY_ON_WHILE_PLUGGED_IN, 0));
        pw.println();

        pw.println("Internal state:");
        pw.println(mWifiStateTracker);
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
        pw.println("Locks acquired: " + mFullLocksAcquired + " full, " +
                mScanLocksAcquired + " scan");
        pw.println("Locks released: " + mFullLocksReleased + " full, " +
                mScanLocksReleased + " scan");
        pw.println();
        pw.println("Locks held:");
        mLocks.dump(pw);
    }

    private class WifiLock extends DeathRecipient {
        WifiLock(int lockMode, String tag, IBinder binder) {
            super(lockMode, tag, binder);
        }

        public void binderDied() {
            synchronized (mLocks) {
                releaseWifiLockLocked(mBinder);
            }
        }

        public String toString() {
            return "WifiLock{" + mTag + " type=" + mMode + " binder=" + mBinder + "}";
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
                if (l.mMode == WifiManager.WIFI_MODE_FULL) {
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
                WifiLock ret = mList.remove(index);
                ret.unlinkDeathRecipient();
                return ret;
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
        Slog.d(TAG, "acquireWifiLockLocked: " + wifiLock);

        mLocks.addLock(wifiLock);

        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            switch(wifiLock.mMode) {
            case WifiManager.WIFI_MODE_FULL:
                ++mFullLocksAcquired;
                mBatteryStats.noteFullWifiLockAcquired(uid);
                break;
            case WifiManager.WIFI_MODE_SCAN_ONLY:
                ++mScanLocksAcquired;
                mBatteryStats.noteScanWifiLockAcquired(uid);
                break;
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

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
        boolean hadLock;

        WifiLock wifiLock = mLocks.removeLock(lock);

        Slog.d(TAG, "releaseWifiLockLocked: " + wifiLock);

        hadLock = (wifiLock != null);

        if (hadLock) {
            int uid = Binder.getCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                switch(wifiLock.mMode) {
                    case WifiManager.WIFI_MODE_FULL:
                        ++mFullLocksReleased;
                        mBatteryStats.noteFullWifiLockReleased(uid);
                        break;
                    case WifiManager.WIFI_MODE_SCAN_ONLY:
                        ++mScanLocksReleased;
                        mBatteryStats.noteScanWifiLockReleased(uid);
                        break;
                }
            } catch (RemoteException e) {
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        // TODO - should this only happen if you hadLock?
        updateWifiState();
        return hadLock;
    }

    private abstract class DeathRecipient
            implements IBinder.DeathRecipient {
        String mTag;
        int mMode;
        IBinder mBinder;

        DeathRecipient(int mode, String tag, IBinder binder) {
            super();
            mTag = tag;
            mMode = mode;
            mBinder = binder;
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }
    }

    private class Multicaster extends DeathRecipient {
        Multicaster(String tag, IBinder binder) {
            super(Binder.getCallingUid(), tag, binder);
        }

        public void binderDied() {
            Slog.e(TAG, "Multicaster binderDied");
            synchronized (mMulticasters) {
                int i = mMulticasters.indexOf(this);
                if (i != -1) {
                    removeMulticasterLocked(i, mMode);
                }
            }
        }

        public String toString() {
            return "Multicaster{" + mTag + " binder=" + mBinder + "}";
        }

        public int getUid() {
            return mMode;
        }
    }

    public void initializeMulticastFiltering() {
        enforceMulticastChangePermission();

        synchronized (mMulticasters) {
            // if anybody had requested filters be off, leave off
            if (mMulticasters.size() != 0) {
                return;
            } else {
                mWifiStateTracker.startPacketFiltering();
            }
        }
    }

    public void acquireMulticastLock(IBinder binder, String tag) {
        enforceMulticastChangePermission();

        synchronized (mMulticasters) {
            mMulticastEnabled++;
            mMulticasters.add(new Multicaster(tag, binder));
            // Note that we could call stopPacketFiltering only when
            // our new size == 1 (first call), but this function won't
            // be called often and by making the stopPacket call each
            // time we're less fragile and self-healing.
            mWifiStateTracker.stopPacketFiltering();
        }

        int uid = Binder.getCallingUid();
        Long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteWifiMulticastEnabled(uid);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void releaseMulticastLock() {
        enforceMulticastChangePermission();

        int uid = Binder.getCallingUid();
        synchronized (mMulticasters) {
            mMulticastDisabled++;
            int size = mMulticasters.size();
            for (int i = size - 1; i >= 0; i--) {
                Multicaster m = mMulticasters.get(i);
                if ((m != null) && (m.getUid() == uid)) {
                    removeMulticasterLocked(i, uid);
                }
            }
        }
    }

    private void removeMulticasterLocked(int i, int uid)
    {
        Multicaster removed = mMulticasters.remove(i);

        if (removed != null) {
            removed.unlinkDeathRecipient();
        }
        if (mMulticasters.size() == 0) {
            mWifiStateTracker.startPacketFiltering();
        }

        Long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteWifiMulticastDisabled(uid);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean isMulticastEnabled() {
        enforceAccessPermission();

        synchronized (mMulticasters) {
            return (mMulticasters.size() > 0);
        }
    }
}
