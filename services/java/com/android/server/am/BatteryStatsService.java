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

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.ServiceManager;

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
     * to reflect events that affect battery usage.
     */
    public BatteryStatsImpl getActiveStatistics() {
        return mStats;
    }
    
    public BatteryStatsImpl getStatistics() {
        return mStats;
    }
    
    public void noteStartWakelock(int uid, String name, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
            mStats.getUidStatsLocked(uid).noteStartWakeLocked(name, type);
        }
    }

    public void noteStopWakelock(int uid, String name, int type) {
        enforceCallingPermission();
        synchronized (mStats) {
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

    public boolean isOnBattery() {
        return mStats.isOnBattery();
    }
    
    public void setOnBattery(boolean onBattery) {
        enforceCallingPermission();
        mStats.setOnBattery(onBattery);
    }
    
    public long getAwakeTimeBattery() {
        return mStats.getAwakeTimeBattery();
    }

    public long getAwakeTimePlugged() {
        return mStats.getAwakeTimePlugged();
    }

    public void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.BATTERY_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }
    
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this) {
            mStats.dumpLocked(fd, pw, args);
        }
    }
}
