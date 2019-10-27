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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.connectivity.WifiBatteryStats;

import com.android.internal.app.IBatteryStats;

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
public class BatteryStatsManager {
    private final IBatteryStats mBatteryStats;

    /** @hide */
    public BatteryStatsManager(IBatteryStats batteryStats) {
        mBatteryStats = batteryStats;
    }

    /**
     * Indicates that the wifi connection RSSI has changed.
     *
     * @param newRssi The new RSSI value.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteWifiRssiChanged(@IntRange(from = -127, to = 0) int newRssi) {
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
    public void noteWifiOn() {
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
    public void noteWifiOff() {
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
    public void noteWifiState(@BatteryStats.WifiState int newWifiState,
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
     * @param ws Worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteWifiScanStartedFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteWifiScanStartedFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that an ongoing wifi scan has stopped.
     *
     * @param ws Worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteWifiScanStoppedFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteWifiScanStoppedFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that a new wifi batched scan has started.
     *
     * @param ws Worksource (to be used for battery blaming).
     * @param csph Channels scanned per hour.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteWifiBatchedScanStartedFromSource(@NonNull WorkSource ws,
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
     * @param ws Worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteWifiBatchedScanStoppedFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteWifiBatchedScanStoppedFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves all the wifi related battery stats.
     *
     * @return Instance of {@link WifiBatteryStats}.
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public @NonNull WifiBatteryStats getWifiBatteryStats() {
        try {
            return mBatteryStats.getWifiBatteryStats();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Indicates an app acquiring full wifi lock.
     *
     * @param ws Worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteFullWifiLockAcquiredFromSource(@NonNull WorkSource ws) {
        try {
            mBatteryStats.noteFullWifiLockAcquiredFromSource(ws);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates an app releasing full wifi lock.
     *
     * @param ws Worksource (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteFullWifiLockReleasedFromSource(@NonNull WorkSource ws) {
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
    public void noteWifiSupplicantStateChanged(@BatteryStats.WifiSupplState int newSupplState,
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
     * @param uid UID of the app that acquired the wifi lock (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteWifiMulticastEnabled(int uid) {
        try {
            mBatteryStats.noteWifiMulticastEnabled(uid);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates that an app has released the wifi multicast lock.
     *
     * @param uid UID of the app that released the wifi lock (to be used for battery blaming).
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_DEVICE_STATS)
    public void noteWifiMulticastDisabled(int uid) {
        try {
            mBatteryStats.noteWifiMulticastDisabled(uid);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
}
