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

package com.android.internal.app;

import android.bluetooth.BluetoothActivityEnergyInfo;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.BluetoothBatteryStats;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.WakeLockStats;
import android.os.WorkSource;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.connectivity.WifiBatteryStats;
import android.os.connectivity.GpsBatteryStats;
import android.os.health.HealthStatsParceler;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.SignalStrength;

interface IBatteryStats {
    /** @hide */
    const String KEY_UID_SNAPSHOTS = "uid_snapshots";

    // These first methods are also called by native code, so must
    // be kept in sync with frameworks/native/libs/binder/include_batterystats/batterystats/IBatteryStats.h
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStartSensor(int uid, int sensor);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStopSensor(int uid, int sensor);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStartVideo(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStopVideo(int uid);
    // The audio battery stats interface is oneway to prevent inversion. These calls
    // are ordered with respect to each other, but not with any other calls.
    @EnforcePermission("UPDATE_DEVICE_STATS")
    oneway void noteStartAudio(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    oneway void noteStopAudio(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteResetVideo();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    oneway void noteResetAudio();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteFlashlightOn(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteFlashlightOff(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStartCamera(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStopCamera(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteResetCamera();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteResetFlashlight();
    void noteWakeupSensorEvent(long elapsedNanos, int uid, int handle);

    // Remaining methods are only used in Java.
    @EnforcePermission("BATTERY_STATS")
    List<BatteryUsageStats> getBatteryUsageStats(in List<BatteryUsageStatsQuery> queries);

    // Return true if we see the battery as currently charging.
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    @RequiresNoPermission
    boolean isCharging();

    // Return the computed amount of time remaining on battery, in milliseconds.
    // Returns -1 if nothing could be computed.
    @RequiresNoPermission
    long computeBatteryTimeRemaining();

    // Return the computed amount of time remaining to fully charge, in milliseconds.
    // Returns -1 if nothing could be computed.
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    @RequiresNoPermission
    long computeChargeTimeRemaining();

    @EnforcePermission("BATTERY_STATS")
    long computeBatteryScreenOffRealtimeMs();
    @EnforcePermission("BATTERY_STATS")
    long getScreenOffDischargeMah();

    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteEvent(int code, String name, int uid);

    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteSyncStart(String name, int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteSyncFinish(String name, int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteJobStart(String name, int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteJobFinish(String name, int uid, int stopReason);

    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStartWakelock(int uid, int pid, String name, String historyName,
            int type, boolean unimportantForLogging);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStopWakelock(int uid, int pid, String name, String historyName, int type);

    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStartWakelockFromSource(in WorkSource ws, int pid, String name, String historyName,
            int type, boolean unimportantForLogging);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteChangeWakelockFromSource(in WorkSource ws, int pid, String name, String histyoryName,
            int type, in WorkSource newWs, int newPid, String newName,
            String newHistoryName, int newType, boolean newUnimportantForLogging);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteStopWakelockFromSource(in WorkSource ws, int pid, String name, String historyName,
            int type);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteLongPartialWakelockStart(String name, String historyName, int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteLongPartialWakelockStartFromSource(String name, String historyName,
            in WorkSource workSource);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteLongPartialWakelockFinish(String name, String historyName, int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteLongPartialWakelockFinishFromSource(String name, String historyName,
            in WorkSource workSource);

    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteVibratorOn(int uid, long durationMillis);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteVibratorOff(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteGpsChanged(in WorkSource oldSource, in WorkSource newSource);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteGpsSignalQuality(int signalLevel);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteScreenState(int displayId, int state, int reason);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteScreenBrightness(int displayId, int brightness);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteUserActivity(int uid, int event);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWakeUp(String reason, int reasonUid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteInteractive(boolean interactive);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteConnectivityChanged(int type, String extra);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteMobileRadioPowerState(int powerState, long timestampNs, int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void notePhoneOn();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void notePhoneOff();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void notePhoneSignalStrength(in SignalStrength signalStrength);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void notePhoneDataConnectionState(int dataType, boolean hasData, int serviceType, int nrState,
            int nrFrequency);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void notePhoneState(int phoneState);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiOn();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiOff();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiRunning(in WorkSource ws);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiRunningChanged(in WorkSource oldWs, in WorkSource newWs);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiStopped(in WorkSource ws);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiState(int wifiState, String accessPoint);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiSupplicantStateChanged(int supplState, boolean failedAuth);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiRssiChanged(int newRssi);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteFullWifiLockAcquired(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteFullWifiLockReleased(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiScanStarted(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiScanStopped(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiMulticastEnabled(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiMulticastDisabled(int uid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteFullWifiLockAcquiredFromSource(in WorkSource ws);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteFullWifiLockReleasedFromSource(in WorkSource ws);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiScanStartedFromSource(in WorkSource ws);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiScanStoppedFromSource(in WorkSource ws);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiBatchedScanStartedFromSource(in WorkSource ws, int csph);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiBatchedScanStoppedFromSource(in WorkSource ws);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteWifiRadioPowerState(int powerState, long timestampNs, int uid);
    @EnforcePermission(anyOf = {"NETWORK_STACK", "android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK"})
    void noteNetworkInterfaceForTransports(String iface, in int[] transportTypes);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteNetworkStatsEnabled();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteDeviceIdleMode(int mode, String activeReason, int activeUid);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void setBatteryState(int status, int health, int plugType, int level, int temp, int volt,
            int chargeUAh, int chargeFullUAh, long chargeTimeToFullSeconds);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    @EnforcePermission("BATTERY_STATS")
    long getAwakeTimeBattery();
    @EnforcePermission("BATTERY_STATS")
    long getAwakeTimePlugged();

    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteBleScanStarted(in WorkSource ws, boolean isUnoptimized);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteBleScanStopped(in WorkSource ws, boolean isUnoptimized);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteBleScanReset();
    @EnforcePermission("UPDATE_DEVICE_STATS")
    void noteBleScanResults(in WorkSource ws, int numNewResults);

    /** {@hide} */
    @EnforcePermission(anyOf = {"UPDATE_DEVICE_STATS", "BATTERY_STATS"})
    CellularBatteryStats getCellularBatteryStats();

    /** {@hide} */
    @EnforcePermission(anyOf = {"UPDATE_DEVICE_STATS", "BATTERY_STATS"})
    WifiBatteryStats getWifiBatteryStats();

    /** {@hide} */
    @EnforcePermission("BATTERY_STATS")
    GpsBatteryStats getGpsBatteryStats();

    /** {@hide} */
    @EnforcePermission("BATTERY_STATS")
    WakeLockStats getWakeLockStats();

    /** {@hide} */
    @EnforcePermission("BATTERY_STATS")
    BluetoothBatteryStats getBluetoothBatteryStats();

    @PermissionManuallyEnforced
    HealthStatsParceler takeUidSnapshot(int uid);
    @PermissionManuallyEnforced
    HealthStatsParceler[] takeUidSnapshots(in int[] uid);

    @PermissionManuallyEnforced
    oneway void takeUidSnapshotsAsync(in int[] uid, in ResultReceiver result);

    @EnforcePermission("UPDATE_DEVICE_STATS")
    oneway void noteBluetoothControllerActivity(in BluetoothActivityEnergyInfo info);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    oneway void noteModemControllerActivity(in ModemActivityInfo info);
    @EnforcePermission("UPDATE_DEVICE_STATS")
    oneway void noteWifiControllerActivity(in WifiActivityEnergyInfo info);

    /** {@hide} */
    @EnforcePermission("POWER_SAVER")
    boolean setChargingStateUpdateDelayMillis(int delay);

    /** Exposed as a test API. */
    @EnforcePermission("DEVICE_POWER")
    void setChargerAcOnline(boolean online, boolean forceUpdate);
    /** Exposed as a test API. */
    @EnforcePermission("DEVICE_POWER")
    void setBatteryLevel(int level, boolean forceUpdate);
    /** Exposed as a test API. */
    @EnforcePermission("DEVICE_POWER")
    void unplugBattery(boolean forceUpdate);
    /** Exposed as a test API. */
    @EnforcePermission("DEVICE_POWER")
    void resetBattery(boolean forceUpdate);
    /** Exposed as a test API. */
    @EnforcePermission("DEVICE_POWER")
    void suspendBatteryInput();
}
