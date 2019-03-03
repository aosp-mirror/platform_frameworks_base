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

import com.android.internal.os.BatteryStatsImpl;

import android.bluetooth.BluetoothActivityEnergyInfo;
import android.net.wifi.WifiActivityEnergyInfo;
import android.os.ParcelFileDescriptor;
import android.os.WorkSource;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.WifiBatteryStats;
import android.os.connectivity.GpsBatteryStats;
import android.os.health.HealthStatsParceler;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.SignalStrength;

interface IBatteryStats {
    // These first methods are also called by native code, so must
    // be kept in sync with frameworks/native/libs/binder/include/binder/IBatteryStats.h
    void noteStartSensor(int uid, int sensor);
    void noteStopSensor(int uid, int sensor);
    void noteStartVideo(int uid);
    void noteStopVideo(int uid);
    void noteStartAudio(int uid);
    void noteStopAudio(int uid);
    void noteResetVideo();
    void noteResetAudio();
    void noteFlashlightOn(int uid);
    void noteFlashlightOff(int uid);
    void noteStartCamera(int uid);
    void noteStopCamera(int uid);
    void noteResetCamera();
    void noteResetFlashlight();

    // Remaining methods are only used in Java.
    @UnsupportedAppUsage
    byte[] getStatistics();

    ParcelFileDescriptor getStatisticsStream();

    // Return true if we see the battery as currently charging.
    @UnsupportedAppUsage
    boolean isCharging();

    // Return the computed amount of time remaining on battery, in milliseconds.
    // Returns -1 if nothing could be computed.
    long computeBatteryTimeRemaining();

    // Return the computed amount of time remaining to fully charge, in milliseconds.
    // Returns -1 if nothing could be computed.
    @UnsupportedAppUsage
    long computeChargeTimeRemaining();

    void noteEvent(int code, String name, int uid);

    void noteSyncStart(String name, int uid);
    void noteSyncFinish(String name, int uid);
    void noteJobStart(String name, int uid);
    void noteJobFinish(String name, int uid, int stopReason);

    void noteStartWakelock(int uid, int pid, String name, String historyName,
            int type, boolean unimportantForLogging);
    void noteStopWakelock(int uid, int pid, String name, String historyName, int type);

    void noteStartWakelockFromSource(in WorkSource ws, int pid, String name, String historyName,
            int type, boolean unimportantForLogging);
    void noteChangeWakelockFromSource(in WorkSource ws, int pid, String name, String histyoryName,
            int type, in WorkSource newWs, int newPid, String newName,
            String newHistoryName, int newType, boolean newUnimportantForLogging);
    void noteStopWakelockFromSource(in WorkSource ws, int pid, String name, String historyName,
            int type);
    void noteLongPartialWakelockStart(String name, String historyName, int uid);
    void noteLongPartialWakelockStartFromSource(String name, String historyName,
            in WorkSource workSource);
    void noteLongPartialWakelockFinish(String name, String historyName, int uid);
    void noteLongPartialWakelockFinishFromSource(String name, String historyName,
            in WorkSource workSource);

    void noteVibratorOn(int uid, long durationMillis);
    void noteVibratorOff(int uid);
    void noteGpsChanged(in WorkSource oldSource, in WorkSource newSource);
    void noteGpsSignalQuality(int signalLevel);
    void noteScreenState(int state);
    void noteScreenBrightness(int brightness);
    void noteUserActivity(int uid, int event);
    void noteWakeUp(String reason, int reasonUid);
    void noteInteractive(boolean interactive);
    void noteConnectivityChanged(int type, String extra);
    void noteMobileRadioPowerState(int powerState, long timestampNs, int uid);
    void notePhoneOn();
    void notePhoneOff();
    void notePhoneSignalStrength(in SignalStrength signalStrength);
    void notePhoneDataConnectionState(int dataType, boolean hasData);
    void notePhoneState(int phoneState);
    void noteWifiOn();
    void noteWifiOff();
    void noteWifiRunning(in WorkSource ws);
    void noteWifiRunningChanged(in WorkSource oldWs, in WorkSource newWs);
    void noteWifiStopped(in WorkSource ws);
    void noteWifiState(int wifiState, String accessPoint);
    void noteWifiSupplicantStateChanged(int supplState, boolean failedAuth);
    void noteWifiRssiChanged(int newRssi);
    void noteFullWifiLockAcquired(int uid);
    void noteFullWifiLockReleased(int uid);
    void noteWifiScanStarted(int uid);
    void noteWifiScanStopped(int uid);
    void noteWifiMulticastEnabled(int uid);
    void noteWifiMulticastDisabled(int uid);
    void noteFullWifiLockAcquiredFromSource(in WorkSource ws);
    void noteFullWifiLockReleasedFromSource(in WorkSource ws);
    void noteWifiScanStartedFromSource(in WorkSource ws);
    void noteWifiScanStoppedFromSource(in WorkSource ws);
    void noteWifiBatchedScanStartedFromSource(in WorkSource ws, int csph);
    void noteWifiBatchedScanStoppedFromSource(in WorkSource ws);
    void noteWifiRadioPowerState(int powerState, long timestampNs, int uid);
    void noteNetworkInterfaceType(String iface, int type);
    void noteNetworkStatsEnabled();
    void noteDeviceIdleMode(int mode, String activeReason, int activeUid);
    void setBatteryState(int status, int health, int plugType, int level, int temp, int volt,
            int chargeUAh, int chargeFullUAh);
    @UnsupportedAppUsage
    long getAwakeTimeBattery();
    long getAwakeTimePlugged();

    void noteBleScanStarted(in WorkSource ws, boolean isUnoptimized);
    void noteBleScanStopped(in WorkSource ws, boolean isUnoptimized);
    void noteResetBleScan();
    void noteBleScanResults(in WorkSource ws, int numNewResults);

    /** {@hide} */
    CellularBatteryStats getCellularBatteryStats();

    /** {@hide} */
    WifiBatteryStats getWifiBatteryStats();

    /** {@hide} */
    GpsBatteryStats getGpsBatteryStats();

    HealthStatsParceler takeUidSnapshot(int uid);
    HealthStatsParceler[] takeUidSnapshots(in int[] uid);

    oneway void noteBluetoothControllerActivity(in BluetoothActivityEnergyInfo info);
    oneway void noteModemControllerActivity(in ModemActivityInfo info);
    oneway void noteWifiControllerActivity(in WifiActivityEnergyInfo info);

    /** {@hide} */
    boolean setChargingStateUpdateDelayMillis(int delay);
}
