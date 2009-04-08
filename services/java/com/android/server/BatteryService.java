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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.provider.Checkin;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;



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
    
    private static final boolean LOCAL_LOGV = false;
    
    static final int LOG_BATTERY_LEVEL = 2722;
    static final int LOG_BATTERY_STATUS = 2723;
    static final int LOG_BATTERY_DISCHARGE_STATUS = 2730;
    
    static final int BATTERY_SCALE = 100;    // battery capacity is a percentage

    // Used locally for determining when to make a last ditch effort to log
    // discharge stats before the device dies.
    private static final int CRITICAL_BATTERY_LEVEL = 4; 

    private static final int DUMP_MAX_LENGTH = 24 * 1024;
    private static final String[] DUMPSYS_ARGS = new String[] { "-c", "-u" };
    private static final String BATTERY_STATS_SERVICE_NAME = "batteryinfo";
    
    private static final String DUMPSYS_DATA_PATH = "/data/system/";

    // This should probably be exposed in the API, though it's not critical
    private static final int BATTERY_PLUGGED_NONE = 0;

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
    private boolean mBatteryLevelCritical;

    private int mLastBatteryStatus;
    private int mLastBatteryHealth;
    private boolean mLastBatteryPresent;
    private int mLastBatteryLevel;
    private int mLastBatteryVoltage;
    private int mLastBatteryTemperature;
    private boolean mLastBatteryLevelCritical;
    
    private int mPlugType;
    private int mLastPlugType = -1; // Extra state so we can detect first run
    
    private long mDischargeStartTime;
    private int mDischargeStartLevel;
    
    
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
            plugTypeBit |= BatteryManager.BATTERY_PLUGGED_AC;
        }
        if (mUsbOnline) {
            plugTypeBit |= BatteryManager.BATTERY_PLUGGED_USB;
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

        boolean logOutlier = false;
        long dischargeDuration = 0;
        
        mBatteryLevelCritical = mBatteryLevel <= CRITICAL_BATTERY_LEVEL;
        if (mAcOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_AC;
        } else if (mUsbOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_USB;
        } else {
            mPlugType = BATTERY_PLUGGED_NONE;
        }
        if (mBatteryStatus != mLastBatteryStatus ||
                mBatteryHealth != mLastBatteryHealth ||
                mBatteryPresent != mLastBatteryPresent ||
                mBatteryLevel != mLastBatteryLevel ||
                mPlugType != mLastPlugType ||
                mBatteryVoltage != mLastBatteryVoltage ||
                mBatteryTemperature != mLastBatteryTemperature) {
            
            if (mPlugType != mLastPlugType) {
                if (mLastPlugType == BATTERY_PLUGGED_NONE) {
                    // discharging -> charging
                    
                    // There's no value in this data unless we've discharged at least once and the
                    // battery level has changed; so don't log until it does.
                    if (mDischargeStartTime != 0 && mDischargeStartLevel != mBatteryLevel) {
                        dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                        logOutlier = true;
                        EventLog.writeEvent(LOG_BATTERY_DISCHARGE_STATUS, dischargeDuration,
                                mDischargeStartLevel, mBatteryLevel);
                        // make sure we see a discharge event before logging again
                        mDischargeStartTime = 0; 
                    }
                } else if (mPlugType == BATTERY_PLUGGED_NONE) {
                    // charging -> discharging or we just powered up
                    mDischargeStartTime = SystemClock.elapsedRealtime();
                    mDischargeStartLevel = mBatteryLevel;
                }
            }
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
            if (mBatteryLevelCritical && !mLastBatteryLevelCritical &&
                    mPlugType == BATTERY_PLUGGED_NONE) {
                // We want to make sure we log discharge cycle outliers
                // if the battery is about to die.
                dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                logOutlier = true;
            }
            
            // Separate broadcast is sent for power connected / not connected
            // since the standard intent will not wake any applications and some
            // applications may want to have smart behavior based on this.
            if (mPlugType != 0 && mLastPlugType == 0) {
                Intent intent = new Intent(Intent.ACTION_POWER_CONNECTED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                mContext.sendBroadcast(intent);
            }
            else if (mPlugType == 0 && mLastPlugType != 0) {
                Intent intent = new Intent(Intent.ACTION_POWER_DISCONNECTED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                mContext.sendBroadcast(intent);
            }
            
            mLastBatteryStatus = mBatteryStatus;
            mLastBatteryHealth = mBatteryHealth;
            mLastBatteryPresent = mBatteryPresent;
            mLastBatteryLevel = mBatteryLevel;
            mLastPlugType = mPlugType;
            mLastBatteryVoltage = mBatteryVoltage;
            mLastBatteryTemperature = mBatteryTemperature;
            mLastBatteryLevelCritical = mBatteryLevelCritical;
            
            sendIntent();
            
            // This needs to be done after sendIntent() so that we get the lastest battery stats.
            if (logOutlier && dischargeDuration != 0) {
                logOutlier(dischargeDuration);
            }
        }
    }

    private final void sendIntent() {
        //  Pack up the values and broadcast them to everyone
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        try {
            mBatteryStats.setOnBattery(mPlugType == BATTERY_PLUGGED_NONE, mBatteryLevel);
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

    private final void logBatteryStats() {
        
        IBinder batteryInfoService = ServiceManager.getService(BATTERY_STATS_SERVICE_NAME);
        if (batteryInfoService != null) {
            byte[] buffer = new byte[DUMP_MAX_LENGTH];
            File dumpFile = null;
            FileOutputStream dumpStream = null;
            try {
                // dump the service to a file
                dumpFile = new File(DUMPSYS_DATA_PATH + BATTERY_STATS_SERVICE_NAME + ".dump");
                dumpStream = new FileOutputStream(dumpFile);
                batteryInfoService.dump(dumpStream.getFD(), DUMPSYS_ARGS);
                dumpStream.getFD().sync();

                // read dumped file above into buffer truncated to DUMP_MAX_LENGTH
                // and insert into events table.
                int length = (int) Math.min(dumpFile.length(), DUMP_MAX_LENGTH);
                FileInputStream fileInputStream = new FileInputStream(dumpFile);
                int nread = fileInputStream.read(buffer, 0, length);
                if (nread > 0) {
                    Checkin.logEvent(mContext.getContentResolver(), 
                            Checkin.Events.Tag.BATTERY_DISCHARGE_INFO, 
                            new String(buffer, 0, nread));
                    if (LOCAL_LOGV) Log.v(TAG, "dumped " + nread + "b from " + 
                            batteryInfoService + "to log");
                    if (LOCAL_LOGV) Log.v(TAG, "actual dump:" + new String(buffer, 0, nread));
                }
            } catch (RemoteException e) {
                Log.e(TAG, "failed to dump service '" + BATTERY_STATS_SERVICE_NAME + 
                        "':" + e);
            } catch (IOException e) {
                Log.e(TAG, "failed to write dumpsys file: " +  e);
            } finally {
                // make sure we clean up
                if (dumpStream != null) {
                    try {
                        dumpStream.close();
                    } catch (IOException e) {
                        Log.e(TAG, "failed to close dumpsys output stream");
                    }
                }
                if (dumpFile != null && !dumpFile.delete()) {
                    Log.e(TAG, "failed to delete temporary dumpsys file: "
                            + dumpFile.getAbsolutePath());
                }
            }
        }
    }
    
    private final void logOutlier(long duration) {
        ContentResolver cr = mContext.getContentResolver();
        String dischargeThresholdString = Settings.Gservices.getString(cr,
                Settings.Gservices.BATTERY_DISCHARGE_THRESHOLD);
        String durationThresholdString = Settings.Gservices.getString(cr,
                Settings.Gservices.BATTERY_DISCHARGE_DURATION_THRESHOLD);
        
        if (dischargeThresholdString != null && durationThresholdString != null) {
            try {
                long durationThreshold = Long.parseLong(durationThresholdString);
                int dischargeThreshold = Integer.parseInt(dischargeThresholdString);
                if (duration <= durationThreshold && 
                        mDischargeStartLevel - mBatteryLevel >= dischargeThreshold) {
                    // If the discharge cycle is bad enough we want to know about it.
                    logBatteryStats();
                }
                if (LOCAL_LOGV) Log.v(TAG, "duration threshold: " + durationThreshold + 
                        " discharge threshold: " + dischargeThreshold);
                if (LOCAL_LOGV) Log.v(TAG, "duration: " + duration + " discharge: " + 
                        (mDischargeStartLevel - mBatteryLevel));
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid DischargeThresholds GService string: " + 
                        durationThresholdString + " or " + dischargeThresholdString);
                return;
            }
        }
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
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
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
