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

import android.os.ParcelFileDescriptor;
import android.os.WorkSource;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.SignalStrength;

interface IBatteryStats {
    // These first methods are also called by native code, so must
    // be kept in sync with frameworks/native/include/binder/IBatteryStats.h
    void noteStartSensor(int uid, int sensor);
    void noteStopSensor(int uid, int sensor);
    void noteStartVideo(int uid);
    void noteStopVideo(int uid);
    void noteStartAudio(int uid);
    void noteStopAudio(int uid);
    void noteResetVideo();
    void noteResetAudio();

    // Remaining methods are only used in Java.
    byte[] getStatistics();

    ParcelFileDescriptor getStatisticsStream();

    // Return the computed amount of time remaining on battery, in milliseconds.
    // Returns -1 if nothing could be computed.
    long computeBatteryTimeRemaining();

    // Return the computed amount of time remaining to fully charge, in milliseconds.
    // Returns -1 if nothing could be computed.
    long computeChargeTimeRemaining();

    void addIsolatedUid(int isolatedUid, int appUid);
    void removeIsolatedUid(int isolatedUid, int appUid);

    void noteEvent(int code, String name, int uid);

    void noteProcessStart(String name, int uid);
    void noteProcessState(String name, int uid, int state);
    void noteProcessFinish(String name, int uid);
    void noteSyncStart(String name, int uid);
    void noteSyncFinish(String name, int uid);
    void noteJobStart(String name, int uid);
    void noteJobFinish(String name, int uid);

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

    void noteVibratorOn(int uid, long durationMillis);
    void noteVibratorOff(int uid);
    void noteFlashlightOn();
    void noteFlashlightOff();
    void noteStartGps(int uid);
    void noteStopGps(int uid);
    void noteScreenState(int state);
    void noteScreenBrightness(int brightness);
    void noteUserActivity(int uid, int event);
    void noteInteractive(boolean interactive);
    void noteMobileRadioPowerState(int powerState, long timestampNs);
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
    void noteBluetoothOn();
    void noteBluetoothOff();
    void noteBluetoothState(int bluetoothState);
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
    void noteWifiMulticastEnabledFromSource(in WorkSource ws);
    void noteWifiMulticastDisabledFromSource(in WorkSource ws);
    void noteNetworkInterfaceType(String iface, int type);
    void noteNetworkStatsEnabled();
    void setBatteryState(int status, int health, int plugType, int level, int temp, int volt);
    long getAwakeTimeBattery();
    long getAwakeTimePlugged();
}
