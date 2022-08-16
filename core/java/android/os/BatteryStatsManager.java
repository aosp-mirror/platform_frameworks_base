/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.net.NetworkStack;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.WifiBatteryStats;
import android.telephony.DataConnectionRealTimeInfo;

import com.android.internal.app.IBatteryStats;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * This class provides an API surface for internal system components to report events that are
 * needed for battery usage/estimation and battery blaming for apps.
 *
 * Note: This internally uses the same {@link IBatteryStats} binder service as the public
 * {@link BatteryManager}.
 * @hide
 */
@SystemApi
@SystemService(Context.BATTERY_STATS_SERVICE)
public final class BatteryStatsManager {
    /**
     * Wifi states.
     *
     * @see #noteWifiState(int, String)
     */
    /**
     * Wifi fully off.
     */
    public static final int WIFI_STATE_OFF = 0;
    /**
     * Wifi connectivity off, but scanning enabled.
     */
    public static final int WIFI_STATE_OFF_SCANNING = 1;
    /**
     * Wifi on, but no saved infrastructure networks to connect to.
     */
    public static final int WIFI_STATE_ON_NO_NETWORKS = 2;
    /**
     * Wifi on, but not connected to any infrastructure networks.
     */
    public static final int WIFI_STATE_ON_DISCONNECTED = 3;
    /**
     * Wifi on and connected to a infrastructure network.
     */
    public static final int WIFI_STATE_ON_CONNECTED_STA = 4;
    /**
     * Wifi on and connected to a P2P device, but no infrastructure connection to a network.
     */
    public static final int WIFI_STATE_ON_CONNECTED_P2P = 5;
    /**
     * Wifi on and connected to both a P2P device and infrastructure connection to a network.
     */
    public static final int WIFI_STATE_ON_CONNECTED_STA_P2P = 6;
    /**
     * SoftAp/Hotspot turned on.
     */
    public static final int WIFI_STATE_SOFT_AP = 7;

    /** @hide */
    public static final int NUM_WIFI_STATES = WIFI_STATE_SOFT_AP + 1;

    /** @hide */
    @IntDef(prefix = { "WIFI_STATE_" }, value = {
            WIFI_STATE_OFF,
            WIFI_STATE_OFF_SCANNING,
            WIFI_STATE_ON_NO_NETWORKS,
            WIFI_STATE_ON_DISCONNECTED,
            WIFI_STATE_ON_CONNECTED_STA,
            WIFI_STATE_ON_CONNECTED_P2P,
            WIFI_STATE_ON_CONNECTED_STA_P2P,
            WIFI_STATE_SOFT_AP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WifiState {}

    /**
     * Wifi supplicant daemon states.
     *
     * @see android.net.wifi.SupplicantState for detailed description of states.
     * @see #noteWifiSupplicantStateChanged(int)
     */
    /** @see android.net.wifi.SupplicantState#INVALID */
    public static final int WIFI_SUPPL_STATE_INVALID = 0;
    /** @see android.net.wifi.SupplicantState#DISCONNECTED*/
    public static final int WIFI_SUPPL_STATE_DISCONNECTED = 1;
    /** @see android.net.wifi.SupplicantState#INTERFACE_DISABLED */
    public static final int WIFI_SUPPL_STATE_INTERFACE_DISABLED = 2;
    /** @see android.net.wifi.SupplicantState#INACTIVE*/
    public static final int WIFI_SUPPL_STATE_INACTIVE = 3;
    /** @see android.net.wifi.SupplicantState#SCANNING*/
    public static final int WIFI_SUPPL_STATE_SCANNING = 4;
    /** @see android.net.wifi.SupplicantState#AUTHENTICATING */
    public static final int WIFI_SUPPL_STATE_AUTHENTICATING = 5;
    /** @see android.net.wifi.SupplicantState#ASSOCIATING */
    public static final int WIFI_SUPPL_STATE_ASSOCIATING = 6;
    /** @see android.net.wifi.SupplicantState#ASSOCIATED */
    public static final int WIFI_SUPPL_STATE_ASSOCIATED = 7;
    /** @see android.net.wifi.SupplicantState#FOUR_WAY_HANDSHAKE */
    public static final int WIFI_SUPPL_STATE_FOUR_WAY_HANDSHAKE = 8;
    /** @see android.net.wifi.SupplicantState#GROUP_HANDSHAKE */
    public static final int WIFI_SUPPL_STATE_GROUP_HANDSHAKE = 9;
    /** @see android.net.wifi.SupplicantState#COMPLETED */
    public static final int WIFI_SUPPL_STATE_COMPLETED = 10;
    /** @see android.net.wifi.SupplicantState#DORMANT */
    public static final int WIFI_SUPPL_STATE_DORMANT = 11;
    /** @see android.net.wifi.SupplicantState#UNINITIALIZED */
    public static final int WIFI_SUPPL_STATE_UNINITIALIZED = 12;

    /** @hide */
    public static final int NUM_WIFI_SUPPL_STATES = WIFI_SUPPL_STATE_UNINITIALIZED + 1;

    /** @hide */
    @IntDef(prefix = { "WIFI_SUPPL_STATE_" }, value = {
            WIFI_SUPPL_STATE_INVALID,
            WIFI_SUPPL_STATE_DISCONNECTED,
            WIFI_SUPPL_STATE_INTERFACE_DISABLED,
            WIFI_SUPPL_STATE_INACTIVE,
            WIFI_SUPPL_STATE_SCANNING,
            WIFI_SUPPL_STATE_AUTHENTICATING,
            WIFI_SUPPL_STATE_ASSOCIATING,
            WIFI_SUPPL_STATE_ASSOCIATED,
            WIFI_SUPPL_STATE_FOUR_WAY_HANDSHAKE,
            WIFI_SUPPL_STATE_GROUP_HANDSHAKE,
            WIFI_SUPPL_STATE_COMPLETED,
            WIFI_SUPPL_STATE_DORMANT,
            WIFI_SUPPL_STATE_UNINITIALIZED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WifiSupplState {}


    private final IBatteryStats mBatteryStats;

    /** @hide */
    public BatteryStatsManager(IBatteryStats batteryStats) {
        mBatteryStats = batteryStats;
    }


    /**
     * Returns BatteryUsageStats, which contains power attribution data on a per-subsystem
     * and per-UID basis.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BATTERY_STATS)
    @NonNull
    public BatteryUsageStats getBatteryUsageStats() {
        return getBatteryUsageStats(BatteryUsageStatsQuery.DEFAULT);
    }

    /**
     * Returns BatteryUsageStats, which contains power attribution data on a per-subsystem
     * and per-UID basis.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BATTERY_STATS)
    @NonNull
    public BatteryUsageStats getBatteryUsageStats(BatteryUsageStatsQuery query) {
        return getBatteryUsageStats(List.of(query)).get(0);
    }

    /**
     * Returns BatteryUsageStats, which contains power attribution data on a per-subsystem
     * and per-UID basis.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BATTERY_STATS)
    @NonNull
    public List<BatteryUsageStats> getBatteryUsageStats(List<BatteryUsageStatsQuery> queries) {
        try {
            return mBatteryStats.getBatteryUsageStats(queries);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that the wifi connection RSSI has changed.
     *
     * @param newRssi The new RSSI value.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiRssiChanged(@IntRange(from = -127, to = 0) int newRssi) {
        try {
            mBatteryStats.noteWifiRssiChanged(newRssi);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that wifi was toggled on.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiOn() {
        try {
            mBatteryStats.noteWifiOn();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that wifi was toggled off.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiOff() {
        try {
            mBatteryStats.noteWifiOff();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that wifi state has changed.
     *
     * @param newWifiState The new wifi State.
     * @param accessPoint SSID of the network if wifi is connected to STA, else null.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiState(@WifiState int newWifiState,
            @Nullable String accessPoint) {
        try {
            mBatteryStats.noteWifiState(newWifiState, accessPoint);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that a new wifi scan has started.
     *
     * @param ws worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiScanStartedFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteWifiScanStartedFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that an ongoing wifi scan has stopped.
     *
     * @param ws worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiScanStoppedFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteWifiScanStoppedFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that a new wifi batched scan has started.
     *
     * @param ws worksource (to be used for battery blaming).
     * @param csph Channels scanned per hour.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiBatchedScanStartedFromSource(@NonNull WorkSource ws,
            @IntRange(from = 0) int csph) {
        try {
            mBatteryStats.noteWifiBatchedScanStartedFromSource(ws, csph);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that an ongoing wifi batched scan has stopped.
     *
     * @param ws worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiBatchedScanStoppedFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteWifiBatchedScanStoppedFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves all the cellular related battery stats.
     *
     * @return Instance of {@link CellularBatteryStats}.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.BATTERY_STATS,
            android.Manifest.permission.UPDATE_DEVICE_STATS})
    public @NonNull CellularBatteryStats getCellularBatteryStats() {
        try {
            return mBatteryStats.getCellularBatteryStats();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Retrieves all the wifi related battery stats.
     *
     * @return Instance of {@link WifiBatteryStats}.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.BATTERY_STATS,
            android.Manifest.permission.UPDATE_DEVICE_STATS})
    public @NonNull WifiBatteryStats getWifiBatteryStats() {
        try {
            return mBatteryStats.getWifiBatteryStats();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Retrieves accumulate wake lock stats.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BATTERY_STATS)
    @NonNull
    public WakeLockStats getWakeLockStats() {
        try {
            return mBatteryStats.getWakeLockStats();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves accumulated bluetooth stats.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BATTERY_STATS)
    @NonNull
    public BluetoothBatteryStats getBluetoothBatteryStats() {
        try {
            return mBatteryStats.getBluetoothBatteryStats();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates an app acquiring full wifi lock.
     *
     * @param ws worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportFullWifiLockAcquiredFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteFullWifiLockAcquiredFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates an app releasing full wifi lock.
     *
     * @param ws worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportFullWifiLockReleasedFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteFullWifiLockReleasedFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that supplicant state has changed.
     *
     * @param newSupplState The new Supplicant state.
     * @param failedAuth Boolean indicating whether there was a connection failure due to
     *                   authentication failure.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiSupplicantStateChanged(@WifiSupplState int newSupplState,
            boolean failedAuth) {
        try {
            mBatteryStats.noteWifiSupplicantStateChanged(newSupplState, failedAuth);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that an app has acquired the wifi multicast lock.
     *
     * @param ws Worksource with the uid of the app that acquired the wifi lock (to be used for
     *           battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiMulticastEnabled(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteWifiMulticastEnabled(ws.getAttributionUid());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that an app has released the wifi multicast lock.
     *
     * @param ws Worksource with the uid of the app that released the wifi lock (to be used for
     *           battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiMulticastDisabled(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteWifiMulticastDisabled(ws.getAttributionUid());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that the radio power state has changed.
     *
     * @param isActive indicates if the mobile radio is powered.
     * @param uid Uid of this event. For the active state it represents the uid that was responsible
     *            for waking the radio, or -1 if the system was responsible for waking the radio.
     *            For inactive state, the UID should always be -1.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportMobileRadioPowerState(boolean isActive, int uid) {
        try {
            mBatteryStats.noteMobileRadioPowerState(getDataConnectionPowerState(isActive),
                    SystemClock.elapsedRealtimeNanos(), uid);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that the wifi power state has changed.
     *
     * @param isActive indicates if the wifi radio is powered.
     * @param uid Uid of this event. For the active state it represents the uid that was responsible
     *            for waking the radio, or -1 if the system was responsible for waking the radio.
     *            For inactive state, the UID should always be -1.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportWifiRadioPowerState(boolean isActive, int uid) {
        try {
            mBatteryStats.noteWifiRadioPowerState(getDataConnectionPowerState(isActive),
                    SystemClock.elapsedRealtimeNanos(), uid);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the battery stats of a new interface, and the transport types of the network that
     * includes that interface.
     *
     * @param iface The interface of the network.
     * @param transportTypes The transport type of the network {@link Transport}.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK})
    public void reportNetworkInterfaceForTransports(@NonNull String iface,
            @NonNull int[] transportTypes) throws RuntimeException {
        try {
            mBatteryStats.noteNetworkInterfaceForTransports(iface, transportTypes);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that Bluetooth was toggled on.
     *
     * @param uid calling package uid
     * @param reason why Bluetooth has been turned on
     * @param packageName package responsible for this change
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void reportBluetoothOn(int uid, int reason, @NonNull String packageName) {
        try {
            mBatteryStats.noteBluetoothOn(uid, reason, packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that Bluetooth was toggled off.
     *
     * @param uid calling package uid
     * @param reason why Bluetooth has been turned on
     * @param packageName package responsible for this change
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void reportBluetoothOff(int uid, int reason, @NonNull String packageName) {
        try {
            mBatteryStats.noteBluetoothOff(uid, reason, packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that a new Bluetooth LE scan has started.
     *
     * @param ws worksource (to be used for battery blaming).
     * @param isUnoptimized whether or not the scan has a filter.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportBleScanStarted(@NonNull WorkSource ws, boolean isUnoptimized) {
        try {
            mBatteryStats.noteBleScanStarted(ws, isUnoptimized);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that an ongoing Bluetooth LE scan has stopped.
     *
     * @param ws worksource (to be used for battery blaming).
     * @param isUnoptimized whether or not the scan has a filter.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportBleScanStopped(@NonNull WorkSource ws, boolean isUnoptimized) {
        try {
            mBatteryStats.noteBleScanStopped(ws, isUnoptimized);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that Bluetooth LE has been reset.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportBleScanReset() {
        try {
            mBatteryStats.noteBleScanReset();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that Bluetooth LE scan has received new results.
     *
     * @param ws worksource (to be used for battery blaming).
     * @param numNewResults number of results received since last update.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void reportBleScanResults(@NonNull WorkSource ws, int numNewResults) {
        try {
            mBatteryStats.noteBleScanResults(ws, numNewResults);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private static int getDataConnectionPowerState(boolean isActive) {
        // TODO: DataConnectionRealTimeInfo is under telephony package but the constants are used
        // for both Wifi and mobile. It would make more sense to separate the constants to a
        // generic class or move it to generic package.
        return isActive ? DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                : DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    }

    /**
     * Sets battery AC charger to enabled/disabled, and freezes the battery state.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void setChargerAcOnline(boolean online, boolean forceUpdate) {
        try {
            mBatteryStats.setChargerAcOnline(online, forceUpdate);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets battery level, and freezes the battery state.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void setBatteryLevel(int level, boolean forceUpdate) {
        try {
            mBatteryStats.setBatteryLevel(level, forceUpdate);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Unplugs battery, and freezes the battery state.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void unplugBattery(boolean forceUpdate) {
        try {
            mBatteryStats.unplugBattery(forceUpdate);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Unfreezes battery state, returning to current hardware values.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void resetBattery(boolean forceUpdate) {
        try {
            mBatteryStats.resetBattery(forceUpdate);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Suspend charging even if plugged in.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public void suspendBatteryInput() {
        try {
            mBatteryStats.suspendBatteryInput();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
}