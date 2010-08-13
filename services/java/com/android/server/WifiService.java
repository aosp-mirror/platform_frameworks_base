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

import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

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
import android.net.wifi.WifiNative;
import android.net.wifi.WifiStateTracker;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.NetworkStateTracker;
import android.net.DhcpInfo;
import android.net.NetworkUtils;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.UnknownHostException;

import com.android.internal.app.IBatteryStats;
import android.app.backup.IBackupManager;
import com.android.server.am.BatteryStatsService;
import com.android.internal.R;

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
    /* TODO: fetch a configurable interface */
    private static final String SOFTAP_IFACE = "wl0.1";

    private Context mContext;
    private int mWifiApState;

    private AlarmManager mAlarmManager;
    private PendingIntent mIdleIntent;
    private static final int IDLE_REQUEST = 0;
    private boolean mScreenOff;
    private boolean mDeviceIdle;
    private int mPluggedType;

    private enum DriverAction {DRIVER_UNLOAD, NO_DRIVER_UNLOAD};

    // true if the user enabled Wifi while in airplane mode
    private boolean mAirplaneModeOverwridden;

    private final LockList mLocks = new LockList();
    // some wifi lock statistics
    private int mFullHighPerfLocksAcquired;
    private int mFullHighPerfLocksReleased;
    private int mFullLocksAcquired;
    private int mFullLocksReleased;
    private int mScanLocksAcquired;
    private int mScanLocksReleased;

    private final List<Multicaster> mMulticasters =
            new ArrayList<Multicaster>();
    private int mMulticastEnabled;
    private int mMulticastDisabled;

    private final IBatteryStats mBatteryStats;

    private INetworkManagementService nwService;
    ConnectivityManager mCm;
    private WifiWatchdogService mWifiWatchdogService = null;
    private String[] mWifiRegexs;

    /**
     * See {@link Settings.Secure#WIFI_IDLE_MS}. This is the default value if a
     * Settings.Secure value is not present. This timeout value is chosen as
     * the approximate point at which the battery drain caused by Wi-Fi
     * being enabled but not active exceeds the battery drain caused by
     * re-establishing a connection to the mobile data network.
     */
    private static final long DEFAULT_IDLE_MILLIS = 15 * 60 * 1000; /* 15 minutes */

    private static final String WAKELOCK_TAG = "WifiService";

    /**
     * The maximum amount of time to hold the wake lock after a disconnect
     * caused by stopping the driver. Establishing an EDGE connection has been
     * observed to take about 5 seconds under normal circumstances. This
     * provides a bit of extra margin.
     * <p>
     * See {@link android.provider.Settings.Secure#WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS}.
     * This is the default value if a Settings.Secure value is not present.
     */
    private static final int DEFAULT_WAKELOCK_TIMEOUT = 8000;

    // Wake lock used by driver-stop operation
    private static PowerManager.WakeLock sDriverStopWakeLock;
    // Wake lock used by other operations
    private static PowerManager.WakeLock sWakeLock;

    private static final int MESSAGE_ENABLE_WIFI        = 0;
    private static final int MESSAGE_DISABLE_WIFI       = 1;
    private static final int MESSAGE_STOP_WIFI          = 2;
    private static final int MESSAGE_START_WIFI         = 3;
    private static final int MESSAGE_RELEASE_WAKELOCK   = 4;
    private static final int MESSAGE_UPDATE_STATE       = 5;
    private static final int MESSAGE_START_ACCESS_POINT = 6;
    private static final int MESSAGE_STOP_ACCESS_POINT  = 7;
    private static final int MESSAGE_SET_CHANNELS       = 8;


    private final  WifiHandler mWifiHandler;

    /*
     * Cache of scan results objects (size is somewhat arbitrary)
     */
    private static final int SCAN_RESULT_CACHE_SIZE = 80;
    private final LinkedHashMap<String, ScanResult> mScanResultCache;

    /*
     * Character buffer used to parse scan results (optimization)
     */
    private static final int SCAN_RESULT_BUFFER_SIZE = 512;
    private boolean mNeedReconfig;

    /*
     * Last UID that asked to enable WIFI.
     */
    private int mLastEnableUid = Process.myUid();

    /*
     * Last UID that asked to enable WIFI AP.
     */
    private int mLastApEnableUid = Process.myUid();


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
        mWifiStateTracker.enableRssiPolling(true);
        mBatteryStats = BatteryStatsService.getService();

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        nwService = INetworkManagementService.Stub.asInterface(b);

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

        HandlerThread wifiThread = new HandlerThread("WifiService");
        wifiThread.start();
        mWifiHandler = new WifiHandler(wifiThread.getLooper());

        mWifiStateTracker.setWifiState(WIFI_STATE_DISABLED);
        mWifiApState = WIFI_AP_STATE_DISABLED;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent idleIntent = new Intent(ACTION_DEVICE_IDLE, null);
        mIdleIntent = PendingIntent.getBroadcast(mContext, IDLE_REQUEST, idleIntent, 0);

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        sWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        sDriverStopWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        // clear our flag indicating the user has overwridden airplane mode
                        mAirplaneModeOverwridden = false;
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

        mCm = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
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
                        try {
                            nwService.stopAccessPoint();
                        } catch (Exception ee) {
                            Slog.e(TAG, "Could not stop AP, :" + ee);
                        }
                        setWifiApEnabledState(WIFI_AP_STATE_FAILED, 0, DriverAction.DRIVER_UNLOAD);
                        return;
                    }

                    if(mCm.tether(intf) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                        Slog.e(TAG, "Error tethering "+intf);
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
     * @return {@code true} if the operation succeeds
     */
    public boolean pingSupplicant() {
        enforceChangePermission();

        return mWifiStateTracker.ping();
    }

    /**
     * see {@link android.net.wifi.WifiManager#startScan()}
     * @return {@code true} if the operation succeeds
     */
    public boolean startScan(boolean forceActive) {
        enforceChangePermission();

        switch (mWifiStateTracker.getSupplicantState()) {
            case DISCONNECTED:
            case INACTIVE:
            case SCANNING:
            case DORMANT:
                break;
            default:
                mWifiStateTracker.setScanResultHandling(
                        WifiStateTracker.SUPPL_SCAN_HANDLING_LIST_ONLY);
                break;
        }
        return mWifiStateTracker.scan(forceActive);
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)}
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    public boolean setWifiEnabled(boolean enable) {
        enforceChangePermission();
        if (mWifiHandler == null) return false;

        synchronized (mWifiHandler) {
            // caller may not have WAKE_LOCK permission - it's not required here
            long ident = Binder.clearCallingIdentity();
            sWakeLock.acquire();
            Binder.restoreCallingIdentity(ident);

            mLastEnableUid = Binder.getCallingUid();
            // set a flag if the user is enabling Wifi while in airplane mode
            mAirplaneModeOverwridden = (enable && isAirplaneModeOn() && isAirplaneToggleable());
            sendEnableMessage(enable, true, Binder.getCallingUid());
        }

        return true;
    }

    /**
     * Enables/disables Wi-Fi synchronously.
     * @param enable {@code true} to turn Wi-Fi on, {@code false} to turn it off.
     * @param persist {@code true} if the setting should be persisted.
     * @param uid The UID of the process making the request.
     * @return {@code true} if the operation succeeds (or if the existing state
     *         is the same as the requested state)
     */
    private boolean setWifiEnabledBlocking(boolean enable, boolean persist, int uid) {
        final int eventualWifiState = enable ? WIFI_STATE_ENABLED : WIFI_STATE_DISABLED;
        final int wifiState = mWifiStateTracker.getWifiState();

        if (wifiState == eventualWifiState) {
            return true;
        }
        if (enable && isAirplaneModeOn() && !mAirplaneModeOverwridden) {
            return false;
        }

        /**
         * Multiple calls to unregisterReceiver() cause exception and a system crash.
         * This can happen if a supplicant is lost (or firmware crash occurs) and user indicates
         * disable wifi at the same time.
         * Avoid doing a disable when the current Wifi state is UNKNOWN
         * TODO: Handle driver load fail and supplicant lost as seperate states
         */
        if ((wifiState == WIFI_STATE_UNKNOWN) && !enable) {
            return false;
        }

        /**
         * Fail Wifi if AP is enabled
         * TODO: Deprecate WIFI_STATE_UNKNOWN and rename it
         * WIFI_STATE_FAILED
         */
        if ((mWifiApState == WIFI_AP_STATE_ENABLED) && enable) {
            setWifiEnabledState(WIFI_STATE_UNKNOWN, uid);
            return false;
        }

        setWifiEnabledState(enable ? WIFI_STATE_ENABLING : WIFI_STATE_DISABLING, uid);

        if (enable) {
            if (!mWifiStateTracker.loadDriver()) {
                Slog.e(TAG, "Failed to load Wi-Fi driver.");
                setWifiEnabledState(WIFI_STATE_UNKNOWN, uid);
                return false;
            }
            if (!mWifiStateTracker.startSupplicant()) {
                mWifiStateTracker.unloadDriver();
                Slog.e(TAG, "Failed to start supplicant daemon.");
                setWifiEnabledState(WIFI_STATE_UNKNOWN, uid);
                return false;
            }

            registerForBroadcasts();
            mWifiStateTracker.startEventLoop();

        } else {

            mContext.unregisterReceiver(mReceiver);
           // Remove notification (it will no-op if it isn't visible)
            mWifiStateTracker.setNotificationVisible(false, 0, false, 0);

            boolean failedToStopSupplicantOrUnloadDriver = false;

            if (!mWifiStateTracker.stopSupplicant()) {
                Slog.e(TAG, "Failed to stop supplicant daemon.");
                setWifiEnabledState(WIFI_STATE_UNKNOWN, uid);
                failedToStopSupplicantOrUnloadDriver = true;
            }

            /**
             * Reset connections and disable interface
             * before we unload the driver
             */
            mWifiStateTracker.resetConnections(true);

            if (!mWifiStateTracker.unloadDriver()) {
                Slog.e(TAG, "Failed to unload Wi-Fi driver.");
                if (!failedToStopSupplicantOrUnloadDriver) {
                    setWifiEnabledState(WIFI_STATE_UNKNOWN, uid);
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
        setWifiEnabledState(eventualWifiState, uid);
        return true;
    }

    private void setWifiEnabledState(int wifiState, int uid) {
        final int previousWifiState = mWifiStateTracker.getWifiState();

        long ident = Binder.clearCallingIdentity();
        try {
            if (wifiState == WIFI_STATE_ENABLED) {
                mBatteryStats.noteWifiOn(uid);
            } else if (wifiState == WIFI_STATE_DISABLED) {
                mBatteryStats.noteWifiOff(uid);
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        // Update state
        mWifiStateTracker.setWifiState(wifiState);

        // Broadcast
        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
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

    private void enforceMulticastChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
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
    public int getWifiEnabledState() {
        enforceAccessPermission();
        return mWifiStateTracker.getWifiState();
    }

    /**
     * see {@link android.net.wifi.WifiManager#disconnect()}
     * @return {@code true} if the operation succeeds
     */
    public boolean disconnect() {
        enforceChangePermission();

        return mWifiStateTracker.disconnect();
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

        return mWifiStateTracker.reassociate();
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiApEnabled(WifiConfiguration, boolean)}
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @param enabled, true to enable and false to disable
     * @return {@code true} if the start operation was
     *         started or is already in the queue.
     */
    public boolean setWifiApEnabled(WifiConfiguration wifiConfig, boolean enabled) {
        enforceChangePermission();
        if (mWifiHandler == null) return false;

        synchronized (mWifiHandler) {

            long ident = Binder.clearCallingIdentity();
            sWakeLock.acquire();
            Binder.restoreCallingIdentity(ident);

            mLastApEnableUid = Binder.getCallingUid();
            sendAccessPointMessage(enabled, wifiConfig, Binder.getCallingUid());
        }

        return true;
    }

    public WifiConfiguration getWifiApConfiguration() {
        enforceAccessPermission();
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

    public void setWifiApConfiguration(WifiConfiguration wifiConfig) {
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
     * Enables/disables Wi-Fi AP synchronously. The driver is loaded
     * and soft access point configured as a single operation.
     * @param enable {@code true} to turn Wi-Fi on, {@code false} to turn it off.
     * @param uid The UID of the process making the request.
     * @param wifiConfig The WifiConfiguration for AP
     * @return {@code true} if the operation succeeds (or if the existing state
     *         is the same as the requested state)
     */
    private boolean setWifiApEnabledBlocking(boolean enable,
                                int uid, WifiConfiguration wifiConfig) {
        final int eventualWifiApState = enable ? WIFI_AP_STATE_ENABLED : WIFI_AP_STATE_DISABLED;

        if (mWifiApState == eventualWifiApState) {
            /* Configuration changed on a running access point */
            if(enable && (wifiConfig != null)) {
                try {
                    nwService.setAccessPoint(wifiConfig, mWifiStateTracker.getInterfaceName(),
                                             SOFTAP_IFACE);
                    setWifiApConfiguration(wifiConfig);
                    return true;
                } catch(Exception e) {
                    Slog.e(TAG, "Exception in nwService during AP restart");
                    try {
                        nwService.stopAccessPoint();
                    } catch (Exception ee) {
                        Slog.e(TAG, "Could not stop AP, :" + ee);
                    }
                    setWifiApEnabledState(WIFI_AP_STATE_FAILED, uid, DriverAction.DRIVER_UNLOAD);
                    return false;
                }
            } else {
                return true;
            }
        }

        /**
         * Fail AP if Wifi is enabled
         */
        if ((mWifiStateTracker.getWifiState() == WIFI_STATE_ENABLED) && enable) {
            setWifiApEnabledState(WIFI_AP_STATE_FAILED, uid, DriverAction.NO_DRIVER_UNLOAD);
            return false;
        }

        setWifiApEnabledState(enable ? WIFI_AP_STATE_ENABLING :
                                       WIFI_AP_STATE_DISABLING, uid, DriverAction.NO_DRIVER_UNLOAD);

        if (enable) {

            /* Use default config if there is no existing config */
            if (wifiConfig == null && ((wifiConfig = getWifiApConfiguration()) == null)) {
                wifiConfig = new WifiConfiguration();
                wifiConfig.SSID = mContext.getString(R.string.wifi_tether_configure_ssid_default);
                wifiConfig.allowedKeyManagement.set(KeyMgmt.NONE);
            }

            if (!mWifiStateTracker.loadDriver()) {
                Slog.e(TAG, "Failed to load Wi-Fi driver for AP mode");
                setWifiApEnabledState(WIFI_AP_STATE_FAILED, uid, DriverAction.NO_DRIVER_UNLOAD);
                return false;
            }

            try {
                nwService.startAccessPoint(wifiConfig, mWifiStateTracker.getInterfaceName(),
                                           SOFTAP_IFACE);
            } catch(Exception e) {
                Slog.e(TAG, "Exception in startAccessPoint()");
                setWifiApEnabledState(WIFI_AP_STATE_FAILED, uid, DriverAction.DRIVER_UNLOAD);
                return false;
            }

            setWifiApConfiguration(wifiConfig);

        } else {

            try {
                nwService.stopAccessPoint();
            } catch(Exception e) {
                Slog.e(TAG, "Exception in stopAccessPoint()");
                setWifiApEnabledState(WIFI_AP_STATE_FAILED, uid, DriverAction.DRIVER_UNLOAD);
                return false;
            }

            if (!mWifiStateTracker.unloadDriver()) {
                Slog.e(TAG, "Failed to unload Wi-Fi driver for AP mode");
                setWifiApEnabledState(WIFI_AP_STATE_FAILED, uid, DriverAction.NO_DRIVER_UNLOAD);
                return false;
            }
        }

        setWifiApEnabledState(eventualWifiApState, uid, DriverAction.NO_DRIVER_UNLOAD);
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
        return mWifiApState;
    }

    private void setWifiApEnabledState(int wifiAPState, int uid, DriverAction flag) {
        final int previousWifiApState = mWifiApState;

        /**
         * Unload the driver if going to a failed state
         */
        if ((mWifiApState == WIFI_AP_STATE_FAILED) && (flag == DriverAction.DRIVER_UNLOAD)) {
            mWifiStateTracker.unloadDriver();
        }

        long ident = Binder.clearCallingIdentity();
        try {
            if (wifiAPState == WIFI_AP_STATE_ENABLED) {
                mBatteryStats.noteWifiOn(uid);
            } else if (wifiAPState == WIFI_AP_STATE_DISABLED) {
                mBatteryStats.noteWifiOff(uid);
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        // Update state
        mWifiApState = wifiAPState;

        // Broadcast
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, wifiAPState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, previousWifiApState);
        mContext.sendStickyBroadcast(intent);
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
        listStr = mWifiStateTracker.listNetworks();

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
           } else {
               config.status = WifiConfiguration.Status.ENABLED;
           }
           readNetworkVariables(config);
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

        value = mWifiStateTracker.getNetworkVariable(netId, WifiConfiguration.ssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.SSID = value;
        } else {
            config.SSID = null;
        }

        value = mWifiStateTracker.getNetworkVariable(netId, WifiConfiguration.bssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.BSSID = value;
        } else {
            config.BSSID = null;
        }

        value = mWifiStateTracker.getNetworkVariable(netId, WifiConfiguration.priorityVarName);
        config.priority = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.priority = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiStateTracker.getNetworkVariable(netId, WifiConfiguration.hiddenSSIDVarName);
        config.hiddenSSID = false;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.hiddenSSID = Integer.parseInt(value) != 0;
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiStateTracker.getNetworkVariable(netId, WifiConfiguration.wepTxKeyIdxVarName);
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
            value = mWifiStateTracker.getNetworkVariable(netId, WifiConfiguration.wepKeyVarNames[i]);
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
        value = mWifiStateTracker.getNetworkVariable(netId, WifiConfiguration.pskVarName);
        if (!TextUtils.isEmpty(value)) {
            config.preSharedKey = value;
        } else {
            config.preSharedKey = null;
        }

        value = mWifiStateTracker.getNetworkVariable(config.networkId,
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

        value = mWifiStateTracker.getNetworkVariable(config.networkId,
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

        value = mWifiStateTracker.getNetworkVariable(config.networkId,
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

        value = mWifiStateTracker.getNetworkVariable(config.networkId,
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

        value = mWifiStateTracker.getNetworkVariable(config.networkId,
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
            value = mWifiStateTracker.getNetworkVariable(netId,
                    field.varName());
            if (!TextUtils.isEmpty(value)) {
                if (field != config.eap) value = removeDoubleQuotes(value);
                field.setValue(value);
            }
        }
    }

    private static String removeDoubleQuotes(String string) {
        if (string.length() <= 2) return "";
        return string.substring(1, string.length() - 1);
    }

    private static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOrUpdateNetwork(WifiConfiguration)}
     * @return the supplicant-assigned identifier for the new or updated
     * network if the operation succeeds, or {@code -1} if it fails
     */
    public int addOrUpdateNetwork(WifiConfiguration config) {
        enforceChangePermission();

        /*
         * If the supplied networkId is -1, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */
        int netId = config.networkId;
        boolean newNetwork = netId == -1;
        boolean doReconfig = false;
        // networkId of -1 means we want to create a new network
        synchronized (mWifiStateTracker) {
            if (newNetwork) {
                netId = mWifiStateTracker.addNetwork();
                if (netId < 0) {
                    if (DBG) {
                        Slog.d(TAG, "Failed to add a network!");
                    }
                    return -1;
                }
                doReconfig = true;
            }
            mNeedReconfig = mNeedReconfig || doReconfig;
        }

        setVariables: {
            /*
             * Note that if a networkId for a non-existent network
             * was supplied, then the first setNetworkVariable()
             * will fail, so we don't bother to make a separate check
             * for the validity of the ID up front.
             */
            if (config.SSID != null &&
                    !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.ssidVarName,
                        config.SSID)) {
                if (DBG) {
                    Slog.d(TAG, "failed to set SSID: "+config.SSID);
                }
                break setVariables;
            }

            if (config.BSSID != null &&
                    !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.bssidVarName,
                        config.BSSID)) {
                if (DBG) {
                    Slog.d(TAG, "failed to set BSSID: "+config.BSSID);
                }
                break setVariables;
            }

            String allowedKeyManagementString =
                makeString(config.allowedKeyManagement, WifiConfiguration.KeyMgmt.strings);
            if (config.allowedKeyManagement.cardinality() != 0 &&
                    !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.KeyMgmt.varName,
                        allowedKeyManagementString)) {
                if (DBG) {
                    Slog.d(TAG, "failed to set key_mgmt: "+
                            allowedKeyManagementString);
                }
                break setVariables;
            }

            String allowedProtocolsString =
                makeString(config.allowedProtocols, WifiConfiguration.Protocol.strings);
            if (config.allowedProtocols.cardinality() != 0 &&
                    !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.Protocol.varName,
                        allowedProtocolsString)) {
                if (DBG) {
                    Slog.d(TAG, "failed to set proto: "+
                            allowedProtocolsString);
                }
                break setVariables;
            }

            String allowedAuthAlgorithmsString =
                makeString(config.allowedAuthAlgorithms, WifiConfiguration.AuthAlgorithm.strings);
            if (config.allowedAuthAlgorithms.cardinality() != 0 &&
                    !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.AuthAlgorithm.varName,
                        allowedAuthAlgorithmsString)) {
                if (DBG) {
                    Slog.d(TAG, "failed to set auth_alg: "+
                            allowedAuthAlgorithmsString);
                }
                break setVariables;
            }

            String allowedPairwiseCiphersString =
                makeString(config.allowedPairwiseCiphers, WifiConfiguration.PairwiseCipher.strings);
            if (config.allowedPairwiseCiphers.cardinality() != 0 &&
                    !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.PairwiseCipher.varName,
                        allowedPairwiseCiphersString)) {
                if (DBG) {
                    Slog.d(TAG, "failed to set pairwise: "+
                            allowedPairwiseCiphersString);
                }
                break setVariables;
            }

            String allowedGroupCiphersString =
                makeString(config.allowedGroupCiphers, WifiConfiguration.GroupCipher.strings);
            if (config.allowedGroupCiphers.cardinality() != 0 &&
                    !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.GroupCipher.varName,
                        allowedGroupCiphersString)) {
                if (DBG) {
                    Slog.d(TAG, "failed to set group: "+
                            allowedGroupCiphersString);
                }
                break setVariables;
            }

            // Prevent client screw-up by passing in a WifiConfiguration we gave it
            // by preventing "*" as a key.
            if (config.preSharedKey != null && !config.preSharedKey.equals("*") &&
                    !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.pskVarName,
                        config.preSharedKey)) {
                if (DBG) {
                    Slog.d(TAG, "failed to set psk: "+config.preSharedKey);
                }
                break setVariables;
            }

            boolean hasSetKey = false;
            if (config.wepKeys != null) {
                for (int i = 0; i < config.wepKeys.length; i++) {
                    // Prevent client screw-up by passing in a WifiConfiguration we gave it
                    // by preventing "*" as a key.
                    if (config.wepKeys[i] != null && !config.wepKeys[i].equals("*")) {
                        if (!mWifiStateTracker.setNetworkVariable(
                                    netId,
                                    WifiConfiguration.wepKeyVarNames[i],
                                    config.wepKeys[i])) {
                            if (DBG) {
                                Slog.d(TAG,
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
                if (!mWifiStateTracker.setNetworkVariable(
                            netId,
                            WifiConfiguration.wepTxKeyIdxVarName,
                            Integer.toString(config.wepTxKeyIndex))) {
                    if (DBG) {
                        Slog.d(TAG,
                                "failed to set wep_tx_keyidx: "+
                                config.wepTxKeyIndex);
                    }
                    break setVariables;
                }
            }

            if (!mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.priorityVarName,
                        Integer.toString(config.priority))) {
                if (DBG) {
                    Slog.d(TAG, config.SSID + ": failed to set priority: "
                            +config.priority);
                }
                break setVariables;
            }

            if (config.hiddenSSID && !mWifiStateTracker.setNetworkVariable(
                        netId,
                        WifiConfiguration.hiddenSSIDVarName,
                        Integer.toString(config.hiddenSSID ? 1 : 0))) {
                if (DBG) {
                    Slog.d(TAG, config.SSID + ": failed to set hiddenSSID: "+
                            config.hiddenSSID);
                }
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
                    if (!mWifiStateTracker.setNetworkVariable(
                                netId,
                                varName,
                                value)) {
                        if (DBG) {
                            Slog.d(TAG, config.SSID + ": failed to set " + varName +
                                    ": " + value);
                        }
                        break setVariables;
                    }
                }
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
                Slog.d(TAG,
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
            Slog.w(TAG, "Failed to look-up a string: " + string);
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

        String ifname = mWifiStateTracker.getInterfaceName();
        NetworkUtils.enableInterface(ifname);
        boolean result = mWifiStateTracker.enableNetwork(netId, disableOthers);
        if (!result) {
            NetworkUtils.disableInterface(ifname);
        }
        return result;
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
        String reply;

        reply = mWifiStateTracker.scanResults();
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
                if (lineEnd > lineBeg) {
                    String line = reply.substring(lineBeg, lineEnd);
                    ScanResult scanResult = parseScanResult(line);
                    if (scanResult != null) {
                        scanList.add(scanResult);
                    } else if (DBG) {
                        Slog.w(TAG, "misformatted scan result for: " + line);
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
                    Slog.w(TAG, "Misformatted scan result text with " +
                          result.length + " fields: " + line);
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
            result = mWifiStateTracker.saveConfig();
            if (result && mNeedReconfig) {
                mNeedReconfig = false;
                result = mWifiStateTracker.reloadConfig();

                if (result) {
                    Intent intent = new Intent(WifiManager.NETWORK_IDS_CHANGED_ACTION);
                    mContext.sendBroadcast(intent);
                }
            }
        }
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
        return result;
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
    public boolean setNumAllowedChannels(int numChannels, boolean persist) {
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

        if (mWifiHandler == null) return false;

        Message.obtain(mWifiHandler,
                MESSAGE_SET_CHANNELS, numChannels, (persist ? 1 : 0)).sendToTarget();

        return true;
    }

    /**
     * sets the number of allowed radio frequency channels synchronously
     * @param numChannels the number of allowed channels. Must be greater than 0
     * and less than or equal to 16.
     * @param persist {@code true} if the setting should be remembered.
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    private boolean setNumAllowedChannelsBlocking(int numChannels, boolean persist) {
        if (persist) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.WIFI_NUM_ALLOWED_CHANNELS,
                    numChannels);
        }
        return mWifiStateTracker.setNumAllowedChannels(numChannels);
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
                        // we don't have time to track down for this release.  Delay instead, but not
                        // as long as we would if connected (below)
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

    private void sendEnableMessage(boolean enable, boolean persist, int uid) {
        Message msg = Message.obtain(mWifiHandler,
                                     (enable ? MESSAGE_ENABLE_WIFI : MESSAGE_DISABLE_WIFI),
                                     (persist ? 1 : 0), uid);
        msg.sendToTarget();
    }

    private void sendStartMessage(int lockMode) {
        Message.obtain(mWifiHandler, MESSAGE_START_WIFI, lockMode, 0).sendToTarget();
    }

    private void sendAccessPointMessage(boolean enable, WifiConfiguration wifiConfig, int uid) {
        Message.obtain(mWifiHandler,
                (enable ? MESSAGE_START_ACCESS_POINT : MESSAGE_STOP_ACCESS_POINT),
                uid, 0, wifiConfig).sendToTarget();
    }

    private void updateWifiState() {
        // send a message so it's all serialized
        Message.obtain(mWifiHandler, MESSAGE_UPDATE_STATE, 0, 0).sendToTarget();
    }

    private void doUpdateWifiState() {
        boolean wifiEnabled = getPersistedWifiEnabled();
        boolean airplaneMode = isAirplaneModeOn() && !mAirplaneModeOverwridden;
        boolean lockHeld = mLocks.hasLocks();
        int strongestLockMode = WifiManager.WIFI_MODE_FULL;
        boolean wifiShouldBeEnabled = wifiEnabled && !airplaneMode;
        boolean wifiShouldBeStarted = !mDeviceIdle || lockHeld;

        if (lockHeld) {
            strongestLockMode = mLocks.getStrongestLockMode();
        }
        /* If device is not idle, lockmode cannot be scan only */
        if (!mDeviceIdle && strongestLockMode == WifiManager.WIFI_MODE_SCAN_ONLY) {
            strongestLockMode = WifiManager.WIFI_MODE_FULL;
        }

        synchronized (mWifiHandler) {
            if ((mWifiStateTracker.getWifiState() == WIFI_STATE_ENABLING) && !airplaneMode) {
                return;
            }

            /* Disable tethering when airplane mode is enabled */
            if (airplaneMode &&
                (mWifiApState == WIFI_AP_STATE_ENABLING || mWifiApState == WIFI_AP_STATE_ENABLED)) {
                sWakeLock.acquire();
                sendAccessPointMessage(false, null, mLastApEnableUid);
            }

            if (wifiShouldBeEnabled) {
                if (wifiShouldBeStarted) {
                    sWakeLock.acquire();
                    sendEnableMessage(true, false, mLastEnableUid);
                    sWakeLock.acquire();
                    sendStartMessage(strongestLockMode);
                } else if (!mWifiStateTracker.isDriverStopped()) {
                    int wakeLockTimeout =
                            Settings.Secure.getInt(
                                    mContext.getContentResolver(),
                                    Settings.Secure.WIFI_MOBILE_DATA_TRANSITION_WAKELOCK_TIMEOUT_MS,
                                    DEFAULT_WAKELOCK_TIMEOUT);
                    /*
                     * We are assuming that ConnectivityService can make
                     * a transition to cellular data within wakeLockTimeout time.
                     * The wakelock is released by the delayed message.
                     */
                    sDriverStopWakeLock.acquire();
                    mWifiHandler.sendEmptyMessage(MESSAGE_STOP_WIFI);
                    mWifiHandler.sendEmptyMessageDelayed(MESSAGE_RELEASE_WAKELOCK, wakeLockTimeout);
                }
            } else {
                sWakeLock.acquire();
                sendEnableMessage(false, false, mLastEnableUid);
            }
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
                    setWifiEnabledBlocking(true, msg.arg1 == 1, msg.arg2);
                    if (mWifiWatchdogService == null) {
                        mWifiWatchdogService = new WifiWatchdogService(mContext, mWifiStateTracker);
                    }
                    sWakeLock.release();
                    break;

                case MESSAGE_START_WIFI:
                    mWifiStateTracker.setScanOnlyMode(msg.arg1 == WifiManager.WIFI_MODE_SCAN_ONLY);
                    mWifiStateTracker.restart();
                    mWifiStateTracker.setHighPerfMode(msg.arg1 ==
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF);
                    sWakeLock.release();
                    break;

                case MESSAGE_UPDATE_STATE:
                    doUpdateWifiState();
                    break;

                case MESSAGE_DISABLE_WIFI:
                    // a non-zero msg.arg1 value means the "enabled" setting
                    // should be persisted
                    setWifiEnabledBlocking(false, msg.arg1 == 1, msg.arg2);
                    mWifiWatchdogService = null;
                    sWakeLock.release();
                    break;

                case MESSAGE_STOP_WIFI:
                    mWifiStateTracker.disconnectAndStop();
                    // don't release wakelock
                    break;

                case MESSAGE_RELEASE_WAKELOCK:
                    sDriverStopWakeLock.release();
                    break;

                case MESSAGE_START_ACCESS_POINT:
                    setWifiApEnabledBlocking(true,
                                             msg.arg1,
                                             (WifiConfiguration) msg.obj);
                    sWakeLock.release();
                    break;

                case MESSAGE_STOP_ACCESS_POINT:
                    setWifiApEnabledBlocking(false,
                                             msg.arg1,
                                             (WifiConfiguration) msg.obj);
                    sWakeLock.release();
                    break;

                case MESSAGE_SET_CHANNELS:
                    setNumAllowedChannelsBlocking(msg.arg1, msg.arg2 == 1);
                    break;

            }
        }
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
        pw.println("Wi-Fi is " + stateName(mWifiStateTracker.getWifiState()));
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
                mFullHighPerfLocksAcquired + " full high perf, " +
                mScanLocksAcquired + " scan");
        pw.println("Locks released: " + mFullLocksReleased + " full, " +
                mFullHighPerfLocksReleased + " full high perf, " +
                mScanLocksReleased + " scan");
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

            if (mFullHighPerfLocksAcquired > mFullHighPerfLocksReleased) {
                return WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            }

            if (mFullLocksAcquired > mFullLocksReleased) {
                return WifiManager.WIFI_MODE_FULL;
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
        if (lockMode != WifiManager.WIFI_MODE_FULL &&
                lockMode != WifiManager.WIFI_MODE_SCAN_ONLY &&
                lockMode != WifiManager.WIFI_MODE_FULL_HIGH_PERF) {
            Slog.e(TAG, "Illegal argument, lockMode= " + lockMode);
            if (DBG) throw new IllegalArgumentException("lockMode=" + lockMode);
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
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                ++mFullHighPerfLocksAcquired;
                /* Treat high power as a full lock for battery stats */
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
                    case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                        ++mFullHighPerfLocksReleased;
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
