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

import android.os.WorkSource;
import android.telephony.SignalStrength;

interface IBatteryStats {
    byte[] getStatistics();
    void noteStartWakelock(int uid, int pid, String name, int type);
    void noteStopWakelock(int uid, int pid, String name, int type);
    
    /* DO NOT CHANGE the position of noteStartSensor without updating
       SensorService.cpp */
    void noteStartSensor(int uid, int sensor);

    /* DO NOT CHANGE the position of noteStopSensor without updating
       SensorService.cpp */
    void noteStopSensor(int uid, int sensor);

    void noteStartWakelockFromSource(in WorkSource ws, int pid, String name, int type);
    void noteStopWakelockFromSource(in WorkSource ws, int pid, String name, int type);

    void noteStartGps(int uid);
    void noteStopGps(int uid);
    void noteScreenOn();
    void noteScreenBrightness(int brightness);
    void noteScreenOff();
    void noteInputEvent();
    void noteUserActivity(int uid, int event);
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
    void noteBluetoothOn();
    void noteBluetoothOff();
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
    void noteWifiMulticastEnabledFromSource(in WorkSource ws);
    void noteWifiMulticastDisabledFromSource(in WorkSource ws);
    void noteNetworkInterfaceType(String iface, int type);
    void setBatteryState(int status, int health, int plugType, int level, int temp, int volt);
    long getAwakeTimeBattery();
    long getAwakeTimePlugged();
}
