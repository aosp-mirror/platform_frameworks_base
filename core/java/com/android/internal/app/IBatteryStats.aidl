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

import android.telephony.SignalStrength;

interface IBatteryStats {
    byte[] getStatistics();
    void noteStartWakelock(int uid, String name, int type);
    void noteStopWakelock(int uid, String name, int type);
    void noteStartSensor(int uid, int sensor);
    void noteStopSensor(int uid, int sensor);
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
    void noteWifiOn(int uid);
    void noteWifiOff(int uid);
    void noteWifiRunning();
    void noteWifiStopped();
    void noteBluetoothOn();
    void noteBluetoothOff();
    void noteFullWifiLockAcquired(int uid);
    void noteFullWifiLockReleased(int uid);
    void noteScanWifiLockAcquired(int uid);
    void noteScanWifiLockReleased(int uid);
    void noteWifiMulticastEnabled(int uid);
    void noteWifiMulticastDisabled(int uid);
    void setOnBattery(boolean onBattery, int level);
    void recordCurrentLevel(int level);
    long getAwakeTimeBattery();
    long getAwakeTimePlugged();
}
