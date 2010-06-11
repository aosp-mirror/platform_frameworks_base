/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

package com.android.server.am;

import android.bluetooth.BluetoothHeadset;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.telephony.SignalStrength;
import android.util.Slog;

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.
 */
public final class BatteryStatsService extends IBatteryStats.Stub {
    static IBatteryStats sService;
    
    final BatteryStatsImpl mStats;
    Context mContext;

    BatteryStatsService(String filename) {
        mStats = new BatteryStatsImpl(filename);
    }
    
    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService("batteryinfo", asBinder());
        mStats.setNumSpeedSteps(new PowerProfile(mContext).getNumSpeedSteps());
        mStats.setRadioScanningTimeout(mContext.getResources().getInteger(
                com.android.internal.R.integer.config_radioScanningTimeout)
                * 1000L);
    }
    
    public void shutdown() {
        Slog.w("BatteryStats", "Writing battery stats before shutdown...");
        synchronized (mStats) {
            mStats.writeLocked();
        }
    }
    
    public static IBatteryStats getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService("batteryinfo");
        sService = asInterface(b);
        return sService;
    }
    
    /**
     * @return the current statistics object, which may be modified
     * to reflect events that affect battery usage.  You must lock the
     * stats object before doing anything with it.
     */
    public BatteryStatsImpl getActiveStatistics() {
        return mStats;
    }
    
    public byte[] getStatistics() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        //Slog.i("foo", "SENDING BATTERY INFO:");
        //mStats.dumpLocked(new LogPrinter(Log.INFO, "foo", Log.LOG_ID_SYSTEM));
        Parcel out = Parcel.obtain();
        mStats.writeToParcel(out, 0);
        byte[] data = out.marshall();
        out.recycle();
        return data;
    }
    
    public void noteStartWakelock(int uid, String name, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            Slog.i("battery", "Start wake lock: " + uid + " " + name);
            mStats.getUidStatsLocked(uid).noteStartWakeLocked(name, type);
        }
    }

    public void noteStopWakelock(int uid, String name, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            Slog.i("battery", "Stop wake lock: " + uid + " " + name);
            mStats.getUidStatsLocked(uid).noteStopWakeLocked(name, type);
        }
    }

    public void noteStartSensor(int uid, int sensor) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.getUidStatsLocked(uid).noteStartSensor(sensor);
        }
    }
    
    public void noteStopSensor(int uid, int sensor) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.getUidStatsLocked(uid).noteStopSensor(sensor);
        }
    }
    
    public void noteStartGps(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartGps(uid);
        }
    }
    
    public void noteStopGps(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopGps(uid);
        }
    }
        
    public void noteScreenOn() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteScreenOnLocked();
        }
    }
    
    public void noteScreenBrightness(int brightness) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteScreenBrightnessLocked(brightness);
        }
    }
    
    public void noteScreenOff() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteScreenOffLocked();
        }
    }

    public void noteInputEvent() {
        enforceCallingPermission();
        mStats.noteInputEventAtomic();
    }
    
    public void noteUserActivity(int uid, int event) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteUserActivityLocked(uid, event);
        }
    }
    
    public void notePhoneOn() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneOnLocked();
        }
    }
    
    public void notePhoneOff() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneOffLocked();
        }
    }
    
    public void notePhoneSignalStrength(SignalStrength signalStrength) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneSignalStrengthLocked(signalStrength);
        }
    }
    
    public void notePhoneDataConnectionState(int dataType, boolean hasData) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneDataConnectionStateLocked(dataType, hasData);
        }
    }

    public void notePhoneState(int state) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.notePhoneStateLocked(state);
        }
    }

    public void noteWifiOn(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiOnLocked(uid);
        }
    }
    
    public void noteWifiOff(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiOffLocked(uid);
        }
    }

    public void noteStartAudio(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAudioOnLocked(uid);
        }
    }

    public void noteStopAudio(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteAudioOffLocked(uid);
        }
    }

    public void noteStartVideo(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteVideoOnLocked(uid);
        }
    }

    public void noteStopVideo(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteVideoOffLocked(uid);
        }
    }

    public void noteWifiRunning() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiRunningLocked();
        }
    }

    public void noteWifiStopped() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiStoppedLocked();
        }
    }

    public void noteBluetoothOn() {
        enforceCallingPermission();
        BluetoothHeadset headset = new BluetoothHeadset(mContext, null);
        synchronized (mStats) {
            mStats.noteBluetoothOnLocked();
            mStats.setBtHeadset(headset);
        }
    }
    
    public void noteBluetoothOff() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteBluetoothOffLocked();
        }
    }
    
    public void noteFullWifiLockAcquired(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFullWifiLockAcquiredLocked(uid);
        }
    }
    
    public void noteFullWifiLockReleased(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFullWifiLockReleasedLocked(uid);
        }
    }
    
    public void noteScanWifiLockAcquired(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteScanWifiLockAcquiredLocked(uid);
        }
    }
    
    public void noteScanWifiLockReleased(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteScanWifiLockReleasedLocked(uid);
        }
    }

    public void noteWifiMulticastEnabled(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiMulticastEnabledLocked(uid);
        }
    }

    public void noteWifiMulticastDisabled(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiMulticastDisabledLocked(uid);
        }
    }

    public boolean isOnBattery() {
        return mStats.isOnBattery();
    }
    
    public void setOnBattery(boolean onBattery, int level) {
        enforceCallingPermission();
        mStats.setOnBattery(onBattery, level);
    }
    
    public void recordCurrentLevel(int level) {
        enforceCallingPermission();
        mStats.recordCurrentLevel(level);
    }
    
    public long getAwakeTimeBattery() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        return mStats.getAwakeTimeBattery();
    }

    public long getAwakeTimePlugged() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BATTERY_STATS, null);
        return mStats.getAwakeTimePlugged();
    }

    public void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }
    
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mStats) {
            boolean isCheckin = false;
            if (args != null) {
                for (String arg : args) {
                    if ("--checkin".equals(arg)) {
                        isCheckin = true;
                        break;
                    }
                }
            }
            if (isCheckin) mStats.dumpCheckinLocked(pw, args);
            else mStats.dumpLocked(pw);
        }
    }
}
