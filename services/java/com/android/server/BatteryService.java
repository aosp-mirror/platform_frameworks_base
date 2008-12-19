/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server;

import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UEventObserver;
import android.util.EventLog;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.String;

/**
 * <p>BatteryService monitors the charging status, and charge level of the device
 * battery.  When these values change this service broadcasts the new values
 * to all {@link android.content.BroadcastReceiver IntentReceivers} that are
 * watching the {@link android.content.Intent#ACTION_BATTERY_CHANGED
 * BATTERY_CHANGED} action.</p>
 * <p>The new values are stored in the Intent data and can be retrieved by
 * calling {@link android.content.Intent#getExtra Intent.getExtra} with the
 * following keys:</p>
 * <p>&quot;scale&quot; - int, the maximum value for the charge level</p>
 * <p>&quot;level&quot; - int, charge level, from 0 through &quot;scale&quot; inclusive</p>
 * <p>&quot;status&quot; - String, the current charging status.<br />
 * <p>&quot;health&quot; - String, the current battery health.<br />
 * <p>&quot;present&quot; - boolean, true if the battery is present<br />
 * <p>&quot;icon-small&quot; - int, suggested small icon to use for this state</p>
 * <p>&quot;plugged&quot; - int, 0 if the device is not plugged in; 1 if plugged
 * into an AC power adapter; 2 if plugged in via USB.</p>
 * <p>&quot;voltage&quot; - int, current battery voltage in millivolts</p>
 * <p>&quot;temperature&quot; - int, current battery temperature in tenths of
 * a degree Centigrade</p>
 * <p>&quot;technology&quot; - String, the type of battery installed, e.g. "Li-ion"</p>
 */
class BatteryService extends Binder {
    private static final String TAG = BatteryService.class.getSimpleName();
    
    static final int LOG_BATTERY_LEVEL = 2722;
    static final int LOG_BATTERY_STATUS = 2723;
    
    static final int BATTERY_SCALE = 100;    // battery capacity is a percentage

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    
    private boolean mAcOnline;
    private boolean mUsbOnline;
    private int mBatteryStatus;
    private int mBatteryHealth;
    private boolean mBatteryPresent;
    private int mBatteryLevel;
    private int mBatteryVoltage;
    private int mBatteryTemperature;
    private String mBatteryTechnology;

    private int mLastBatteryStatus;
    private int mLastBatteryHealth;
    private boolean mLastBatteryPresent;
    private int mLastBatteryLevel;
    private int mLastBatteryVoltage;
    private int mLastBatteryTemperature;
    
    private int mPlugType;
    private int mLastPlugType;
    
    public BatteryService(Context context) {
        mContext = context;
        mBatteryStats = BatteryStatsService.getService();

        mUEventObserver.startObserving("SUBSYSTEM=power_supply");

        // set initial status
        update();
    }

    final boolean isPowered() {
        // assume we are powered if battery state is unknown so the "stay on while plugged in" option will work.
        return (mAcOnline || mUsbOnline || mBatteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN);
    }

    final boolean isPowered(int plugTypeSet) {
        // assume we are powered if battery state is unknown so
        // the "stay on while plugged in" option will work.
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            return true;
        }
        if (plugTypeSet == 0) {
            return false;
        }
        int plugTypeBit = 0;
        if (mAcOnline) {
            plugTypeBit = BatteryManager.BATTERY_PLUGGED_AC;
        } else if (mUsbOnline) {
            plugTypeBit = BatteryManager.BATTERY_PLUGGED_USB;
        }
        return (plugTypeSet & plugTypeBit) != 0;
    }

    final int getPlugType() {
        return mPlugType;
    }

    private UEventObserver mUEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            update();
        }
    };

    // returns battery level as a percentage
    final int getBatteryLevel() {
        return mBatteryLevel;
    }

    private native void native_update();

    private synchronized final void update() {
        native_update();
        if (mAcOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_AC;
        } else if (mUsbOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_USB;
        } else {
            mPlugType = 0;
        }
        if (mBatteryStatus != mLastBatteryStatus ||
                mBatteryHealth != mLastBatteryHealth ||
                mBatteryPresent != mLastBatteryPresent ||
                mBatteryLevel != mLastBatteryLevel ||
                mPlugType != mLastPlugType ||
                mBatteryVoltage != mLastBatteryVoltage ||
                mBatteryTemperature != mLastBatteryTemperature) {
            
            if (mBatteryStatus != mLastBatteryStatus ||
                    mBatteryHealth != mLastBatteryHealth ||
                    mBatteryPresent != mLastBatteryPresent ||
                    mPlugType != mLastPlugType) {
                EventLog.writeEvent(LOG_BATTERY_STATUS,
                        mBatteryStatus, mBatteryHealth, mBatteryPresent ? 1 : 0,
                        mPlugType, mBatteryTechnology);
            }
            if (mBatteryLevel != mLastBatteryLevel ||
                    mBatteryVoltage != mLastBatteryVoltage ||
                    mBatteryTemperature != mLastBatteryTemperature) {
                EventLog.writeEvent(LOG_BATTERY_LEVEL,
                        mBatteryLevel, mBatteryVoltage, mBatteryTemperature);
            }
            
            // Separate broadcast is sent for power connected / not connected
            // since the standard intent will not wake any applications and some
            // applications may want to have smart behavior based on this.
            if (mPlugType != 0 && mLastPlugType == 0) {
                mContext.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED));
            }
            else if (mPlugType == 0 && mLastPlugType != 0) {
                mContext.sendBroadcast(new Intent(Intent.ACTION_POWER_DISCONNECTED));
            }
            
            mLastBatteryStatus = mBatteryStatus;
            mLastBatteryHealth = mBatteryHealth;
            mLastBatteryPresent = mBatteryPresent;
            mLastBatteryLevel = mBatteryLevel;
            mLastPlugType = mPlugType;
            mLastBatteryVoltage = mBatteryVoltage;
            mLastBatteryTemperature = mBatteryTemperature;
            
            sendIntent();
        }
    }

    private final void sendIntent() {
        //  Pack up the values and broadcast them to everyone
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        try {
            mBatteryStats.setOnBattery(mPlugType == 0);
        } catch (RemoteException e) {
            // Should never happen.
        }
        
        int icon = getIcon(mBatteryLevel);

        intent.putExtra("status", mBatteryStatus);
        intent.putExtra("health", mBatteryHealth);
        intent.putExtra("present", mBatteryPresent);
        intent.putExtra("level", mBatteryLevel);
        intent.putExtra("scale", BATTERY_SCALE);
        intent.putExtra("icon-small", icon);
        intent.putExtra("plugged", mPlugType);
        intent.putExtra("voltage", mBatteryVoltage);
        intent.putExtra("temperature", mBatteryTemperature);
        intent.putExtra("technology", mBatteryTechnology);

        if (false) {
            Log.d(TAG, "updateBattery level:" + mBatteryLevel +
                    " scale:" + BATTERY_SCALE + " status:" + mBatteryStatus + 
                    " health:" + mBatteryHealth +  " present:" + mBatteryPresent + 
                    " voltage: " + mBatteryVoltage +
                    " temperature: " + mBatteryTemperature +
                    " technology: " + mBatteryTechnology +
                    " AC powered:" + mAcOnline + " USB powered:" + mUsbOnline +
                    " icon:" + icon );
        }

        ActivityManagerNative.broadcastStickyIntent(intent, null);
    }

    private final int getIcon(int level) {
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
            return com.android.internal.R.drawable.stat_sys_battery_charge;
        } else if (mBatteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING ||
                mBatteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING ||
                mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
            return com.android.internal.R.drawable.stat_sys_battery;
        } else {
            return com.android.internal.R.drawable.stat_sys_battery_unknown;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingPermission("android.permission.DUMP")
                != PackageManager.PERMISSION_GRANTED) {
            
            pw.println("Permission Denial: can't dump Battery service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (this) {
            pw.println("Current Battery Service state:");
            pw.println("  AC powered: " + mAcOnline);
            pw.println("  USB powered: " + mUsbOnline);
            pw.println("  status: " + mBatteryStatus);
            pw.println("  health: " + mBatteryHealth);
            pw.println("  present: " + mBatteryPresent);
            pw.println("  level: " + mBatteryLevel);
            pw.println("  scale: " + BATTERY_SCALE);
            pw.println("  voltage:" + mBatteryVoltage);
            pw.println("  temperature: " + mBatteryTemperature);
            pw.println("  technology: " + mBatteryTechnology);
        }
    }
}
