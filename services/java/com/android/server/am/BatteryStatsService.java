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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.
 */
public final class BatteryStatsService extends IBatteryStats.Stub {
    static IBatteryStats sService;
    
    final BatteryStatsImpl mStats;
    Context mContext;
    private boolean mBluetoothPendingStats;
    private BluetoothHeadset mBluetoothHeadset;

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
            mStats.shutdownLocked();
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
    
    public void noteStartWakelock(int uid, int pid, String name, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartWakeLocked(uid, pid, name, type);
        }
    }

    public void noteStopWakelock(int uid, int pid, String name, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopWakeLocked(uid, pid, name, type);
        }
    }

    public void noteStartWakelockFromSource(WorkSource ws, int pid, String name, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartWakeFromSourceLocked(ws, pid, name, type);
        }
    }

    public void noteStopWakelockFromSource(WorkSource ws, int pid, String name, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopWakeFromSourceLocked(ws, pid, name, type);
        }
    }

    public void noteStartSensor(int uid, int sensor) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartSensorLocked(uid, sensor);
        }
    }
    
    public void noteStopSensor(int uid, int sensor) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopSensorLocked(uid, sensor);
        }
    }
    
    public void noteStartGps(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStartGpsLocked(uid);
        }
    }
    
    public void noteStopGps(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteStopGpsLocked(uid);
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
        int simState = TelephonyManager.getDefault().getSimState();
        synchronized (mStats) {
            mStats.notePhoneStateLocked(state, simState);
        }
    }

    public void noteWifiOn() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiOnLocked();
        }
    }
    
    public void noteWifiOff() {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiOffLocked();
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

    public void noteWifiRunning(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiRunningLocked(ws);
        }
    }

    public void noteWifiRunningChanged(WorkSource oldWs, WorkSource newWs) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiRunningChangedLocked(oldWs, newWs);
        }
    }

    public void noteWifiStopped(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiStoppedLocked(ws);
        }
    }

    public void noteBluetoothOn() {
        enforceCallingPermission();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(mContext, mBluetoothProfileServiceListener,
                                    BluetoothProfile.HEADSET);
        }
        synchronized (mStats) {
            if (mBluetoothHeadset != null) {
                mStats.noteBluetoothOnLocked();
                mStats.setBtHeadset(mBluetoothHeadset);
            } else {
                mBluetoothPendingStats = true;
            }
        }
    }

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mBluetoothHeadset = (BluetoothHeadset) proxy;
            synchronized (mStats) {
                if (mBluetoothPendingStats) {
                    mStats.noteBluetoothOnLocked();
                    mStats.setBtHeadset(mBluetoothHeadset);
                    mBluetoothPendingStats = false;
                }
            }
        }

        public void onServiceDisconnected(int profile) {
            mBluetoothHeadset = null;
        }
    };

    public void noteBluetoothOff() {
        enforceCallingPermission();
        synchronized (mStats) {
            mBluetoothPendingStats = false;
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

    public void noteWifiScanStarted(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiScanStartedLocked(uid);
        }
    }

    public void noteWifiScanStopped(int uid) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiScanStoppedLocked(uid);
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

    public void noteFullWifiLockAcquiredFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFullWifiLockAcquiredFromSourceLocked(ws);
        }
    }

    public void noteFullWifiLockReleasedFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteFullWifiLockReleasedFromSourceLocked(ws);
        }
    }

    public void noteWifiScanStartedFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiScanStartedFromSourceLocked(ws);
        }
    }

    public void noteWifiScanStoppedFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiScanStoppedFromSourceLocked(ws);
        }
    }

    public void noteWifiMulticastEnabledFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiMulticastEnabledFromSourceLocked(ws);
        }
    }

    public void noteWifiMulticastDisabledFromSource(WorkSource ws) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteWifiMulticastDisabledFromSourceLocked(ws);
        }
    }

    public void noteNetworkInterfaceType(String iface, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.noteNetworkInterfaceTypeLocked(iface, type);
        }
    }

    public boolean isOnBattery() {
        return mStats.isOnBattery();
    }
    
    public void setBatteryState(int status, int health, int plugType, int level,
            int temp, int volt) {
        enforceCallingPermission();
        mStats.setBatteryState(status, health, plugType, level, temp, volt);
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
    
    private void dumpHelp(PrintWriter pw) {
        pw.println("Battery stats (batteryinfo) dump options:");
        pw.println("  [--checkin] [--reset] [--write] [-h]");
        pw.println("  --checkin: format output for a checkin report.");
        pw.println("  --reset: reset the stats, clearing all current data.");
        pw.println("  --write: force write current collected stats to disk.");
        pw.println("  -h: print this help text.");
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump BatteryStats from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        boolean isCheckin = false;
        boolean noOutput = false;
        if (args != null) {
            for (String arg : args) {
                if ("--checkin".equals(arg)) {
                    isCheckin = true;
                } else if ("--reset".equals(arg)) {
                    synchronized (mStats) {
                        mStats.resetAllStatsLocked();
                        pw.println("Battery stats reset.");
                        noOutput = true;
                    }
                } else if ("--write".equals(arg)) {
                    synchronized (mStats) {
                        mStats.writeSyncLocked();
                        pw.println("Battery stats written.");
                        noOutput = true;
                    }
                } else if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // fall through
                } else {
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                }
            }
        }
        if (noOutput) {
            return;
        }
        if (isCheckin) {
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(0);
            synchronized (mStats) {
                mStats.dumpCheckinLocked(pw, args, apps);
            }
        } else {
            synchronized (mStats) {
                mStats.dumpLocked(pw);
            }
        }
    }
}
