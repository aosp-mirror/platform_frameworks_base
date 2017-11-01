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

import android.app.ActivityManagerInternal;
import android.database.ContentObserver;
import android.os.BatteryStats;

import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.BatteryProperties;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBatteryPropertiesListener;
import android.os.IBatteryPropertiesRegistrar;
import android.os.IBinder;
import android.os.DropBoxManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.battery.BatteryServiceDumpProto;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import java.io.File;
import java.io.FileDescriptor;
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
 *
 * <p>
 * The battery service may be called by the power manager while holding its locks so
 * we take care to post all outcalls into the activity manager to a handler.
 *
 * FIXME: Ideally the power manager would perform all of its calls into the battery
 * service asynchronously itself.
 * </p>
 */
public final class BatteryService extends SystemService {
    private static final String TAG = BatteryService.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final int BATTERY_SCALE = 100;    // battery capacity is a percentage

    // Used locally for determining when to make a last ditch effort to log
    // discharge stats before the device dies.
    private int mCriticalBatteryLevel;

    private static final String[] DUMPSYS_ARGS = new String[] { "--checkin", "--unplugged" };

    private static final String DUMPSYS_DATA_PATH = "/data/system/";

    // This should probably be exposed in the API, though it's not critical
    private static final int BATTERY_PLUGGED_NONE = 0;

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    BinderService mBinderService;
    private final Handler mHandler;

    private final Object mLock = new Object();

    private BatteryProperties mBatteryProps;
    private final BatteryProperties mLastBatteryProps = new BatteryProperties();
    private boolean mBatteryLevelCritical;
    private int mLastBatteryStatus;
    private int mLastBatteryHealth;
    private boolean mLastBatteryPresent;
    private int mLastBatteryLevel;
    private int mLastBatteryVoltage;
    private int mLastBatteryTemperature;
    private boolean mLastBatteryLevelCritical;
    private int mLastMaxChargingCurrent;
    private int mLastMaxChargingVoltage;
    private int mLastChargeCounter;

    private int mSequence = 1;

    private int mInvalidCharger;
    private int mLastInvalidCharger;

    private int mLowBatteryWarningLevel;
    private int mLowBatteryCloseWarningLevel;
    private int mShutdownBatteryTemperature;

    private int mPlugType;
    private int mLastPlugType = -1; // Extra state so we can detect first run

    private boolean mBatteryLevelLow;

    private long mDischargeStartTime;
    private int mDischargeStartLevel;

    private boolean mUpdatesStopped;

    private Led mLed;

    private boolean mSentLowBatteryBroadcast = false;

    private ActivityManagerInternal mActivityManagerInternal;

    public BatteryService(Context context) {
        super(context);

        mContext = context;
        mHandler = new Handler(true /*async*/);
        mLed = new Led(context, getLocalService(LightsManager.class));
        mBatteryStats = BatteryStatsService.getService();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);

        mCriticalBatteryLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mLowBatteryWarningLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryCloseWarningLevel = mLowBatteryWarningLevel + mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryCloseWarningBump);
        mShutdownBatteryTemperature = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_shutdownBatteryTemperature);

        // watch for invalid charger messages if the invalid_charger switch exists
        if (new File("/sys/devices/virtual/switch/invalid_charger/state").exists()) {
            UEventObserver invalidChargerObserver = new UEventObserver() {
                @Override
                public void onUEvent(UEvent event) {
                    final int invalidCharger = "1".equals(event.get("SWITCH_STATE")) ? 1 : 0;
                    synchronized (mLock) {
                        if (mInvalidCharger != invalidCharger) {
                            mInvalidCharger = invalidCharger;
                        }
                    }
                }
            };
            invalidChargerObserver.startObserving(
                    "DEVPATH=/devices/virtual/switch/invalid_charger");
        }
    }

    @Override
    public void onStart() {
        IBinder b = ServiceManager.getService("batteryproperties");
        final IBatteryPropertiesRegistrar batteryPropertiesRegistrar =
                IBatteryPropertiesRegistrar.Stub.asInterface(b);
        try {
            batteryPropertiesRegistrar.registerListener(new BatteryListener());
        } catch (RemoteException e) {
            // Should never happen.
        }

        mBinderService = new BinderService();
        publishBinderService("battery", mBinderService);
        publishLocalService(BatteryManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            // check our power situation now that it is safe to display the shutdown dialog.
            synchronized (mLock) {
                ContentObserver obs = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (mLock) {
                            updateBatteryWarningLevelLocked();
                        }
                    }
                };
                final ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Global.getUriFor(
                        Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL),
                        false, obs, UserHandle.USER_ALL);
                updateBatteryWarningLevelLocked();
            }
        }
    }

    private void updateBatteryWarningLevelLocked() {
        final ContentResolver resolver = mContext.getContentResolver();
        int defWarnLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryWarningLevel = Settings.Global.getInt(resolver,
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, defWarnLevel);
        if (mLowBatteryWarningLevel == 0) {
            mLowBatteryWarningLevel = defWarnLevel;
        }
        if (mLowBatteryWarningLevel < mCriticalBatteryLevel) {
            mLowBatteryWarningLevel = mCriticalBatteryLevel;
        }
        mLowBatteryCloseWarningLevel = mLowBatteryWarningLevel + mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryCloseWarningBump);
        processValuesLocked(true);
    }

    private boolean isPoweredLocked(int plugTypeSet) {
        // assume we are powered if battery state is unknown so
        // the "stay on while plugged in" option will work.
        if (mBatteryProps.batteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_AC) != 0 && mBatteryProps.chargerAcOnline) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_USB) != 0 && mBatteryProps.chargerUsbOnline) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 && mBatteryProps.chargerWirelessOnline) {
            return true;
        }
        return false;
    }

    private boolean shouldSendBatteryLowLocked() {
        final boolean plugged = mPlugType != BATTERY_PLUGGED_NONE;
        final boolean oldPlugged = mLastPlugType != BATTERY_PLUGGED_NONE;

        /* The ACTION_BATTERY_LOW broadcast is sent in these situations:
         * - is just un-plugged (previously was plugged) and battery level is
         *   less than or equal to WARNING, or
         * - is not plugged and battery level falls to WARNING boundary
         *   (becomes <= mLowBatteryWarningLevel).
         */
        return !plugged
                && mBatteryProps.batteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN
                && mBatteryProps.batteryLevel <= mLowBatteryWarningLevel
                && (oldPlugged || mLastBatteryLevel > mLowBatteryWarningLevel);
    }

    private void shutdownIfNoPowerLocked() {
        // shut down gracefully if our battery is critically low and we are not powered.
        // wait until the system has booted before attempting to display the shutdown dialog.
        if (mBatteryProps.batteryLevel == 0 && !isPoweredLocked(BatteryManager.BATTERY_PLUGGED_ANY)) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mActivityManagerInternal.isSystemReady()) {
                        Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
                        intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    }
                }
            });
        }
    }

    private void shutdownIfOverTempLocked() {
        // shut down gracefully if temperature is too high (> 68.0C by default)
        // wait until the system has booted before attempting to display the
        // shutdown dialog.
        if (mBatteryProps.batteryTemperature > mShutdownBatteryTemperature) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mActivityManagerInternal.isSystemReady()) {
                        Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
                        intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    }
                }
            });
        }
    }

    private void update(BatteryProperties props) {
        synchronized (mLock) {
            if (!mUpdatesStopped) {
                mBatteryProps = props;
                // Process the new values.
                processValuesLocked(false);
            } else {
                mLastBatteryProps.set(props);
            }
        }
    }

    private void processValuesLocked(boolean force) {
        boolean logOutlier = false;
        long dischargeDuration = 0;

        mBatteryLevelCritical = (mBatteryProps.batteryLevel <= mCriticalBatteryLevel);
        if (mBatteryProps.chargerAcOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_AC;
        } else if (mBatteryProps.chargerUsbOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_USB;
        } else if (mBatteryProps.chargerWirelessOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_WIRELESS;
        } else {
            mPlugType = BATTERY_PLUGGED_NONE;
        }

        if (DEBUG) {
            Slog.d(TAG, "Processing new values: "
                    + "chargerAcOnline=" + mBatteryProps.chargerAcOnline
                    + ", chargerUsbOnline=" + mBatteryProps.chargerUsbOnline
                    + ", chargerWirelessOnline=" + mBatteryProps.chargerWirelessOnline
                    + ", maxChargingCurrent" + mBatteryProps.maxChargingCurrent
                    + ", maxChargingVoltage" + mBatteryProps.maxChargingVoltage
                    + ", batteryStatus=" + mBatteryProps.batteryStatus
                    + ", batteryHealth=" + mBatteryProps.batteryHealth
                    + ", batteryPresent=" + mBatteryProps.batteryPresent
                    + ", batteryLevel=" + mBatteryProps.batteryLevel
                    + ", batteryTechnology=" + mBatteryProps.batteryTechnology
                    + ", batteryVoltage=" + mBatteryProps.batteryVoltage
                    + ", batteryChargeCounter=" + mBatteryProps.batteryChargeCounter
                    + ", batteryFullCharge=" + mBatteryProps.batteryFullCharge
                    + ", batteryTemperature=" + mBatteryProps.batteryTemperature
                    + ", mBatteryLevelCritical=" + mBatteryLevelCritical
                    + ", mPlugType=" + mPlugType);
        }

        // Let the battery stats keep track of the current level.
        try {
            mBatteryStats.setBatteryState(mBatteryProps.batteryStatus, mBatteryProps.batteryHealth,
                    mPlugType, mBatteryProps.batteryLevel, mBatteryProps.batteryTemperature,
                    mBatteryProps.batteryVoltage, mBatteryProps.batteryChargeCounter,
                    mBatteryProps.batteryFullCharge);
        } catch (RemoteException e) {
            // Should never happen.
        }

        shutdownIfNoPowerLocked();
        shutdownIfOverTempLocked();

        if (force || (mBatteryProps.batteryStatus != mLastBatteryStatus ||
                mBatteryProps.batteryHealth != mLastBatteryHealth ||
                mBatteryProps.batteryPresent != mLastBatteryPresent ||
                mBatteryProps.batteryLevel != mLastBatteryLevel ||
                mPlugType != mLastPlugType ||
                mBatteryProps.batteryVoltage != mLastBatteryVoltage ||
                mBatteryProps.batteryTemperature != mLastBatteryTemperature ||
                mBatteryProps.maxChargingCurrent != mLastMaxChargingCurrent ||
                mBatteryProps.maxChargingVoltage != mLastMaxChargingVoltage ||
                mBatteryProps.batteryChargeCounter != mLastChargeCounter ||
                mInvalidCharger != mLastInvalidCharger)) {

            if (mPlugType != mLastPlugType) {
                if (mLastPlugType == BATTERY_PLUGGED_NONE) {
                    // discharging -> charging

                    // There's no value in this data unless we've discharged at least once and the
                    // battery level has changed; so don't log until it does.
                    if (mDischargeStartTime != 0 && mDischargeStartLevel != mBatteryProps.batteryLevel) {
                        dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                        logOutlier = true;
                        EventLog.writeEvent(EventLogTags.BATTERY_DISCHARGE, dischargeDuration,
                                mDischargeStartLevel, mBatteryProps.batteryLevel);
                        // make sure we see a discharge event before logging again
                        mDischargeStartTime = 0;
                    }
                } else if (mPlugType == BATTERY_PLUGGED_NONE) {
                    // charging -> discharging or we just powered up
                    mDischargeStartTime = SystemClock.elapsedRealtime();
                    mDischargeStartLevel = mBatteryProps.batteryLevel;
                }
            }
            if (mBatteryProps.batteryStatus != mLastBatteryStatus ||
                    mBatteryProps.batteryHealth != mLastBatteryHealth ||
                    mBatteryProps.batteryPresent != mLastBatteryPresent ||
                    mPlugType != mLastPlugType) {
                EventLog.writeEvent(EventLogTags.BATTERY_STATUS,
                        mBatteryProps.batteryStatus, mBatteryProps.batteryHealth, mBatteryProps.batteryPresent ? 1 : 0,
                        mPlugType, mBatteryProps.batteryTechnology);
            }
            if (mBatteryProps.batteryLevel != mLastBatteryLevel) {
                // Don't do this just from voltage or temperature changes, that is
                // too noisy.
                EventLog.writeEvent(EventLogTags.BATTERY_LEVEL,
                        mBatteryProps.batteryLevel, mBatteryProps.batteryVoltage, mBatteryProps.batteryTemperature);
            }
            if (mBatteryLevelCritical && !mLastBatteryLevelCritical &&
                    mPlugType == BATTERY_PLUGGED_NONE) {
                // We want to make sure we log discharge cycle outliers
                // if the battery is about to die.
                dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                logOutlier = true;
            }

            if (!mBatteryLevelLow) {
                // Should we now switch in to low battery mode?
                if (mPlugType == BATTERY_PLUGGED_NONE
                        && mBatteryProps.batteryLevel <= mLowBatteryWarningLevel) {
                    mBatteryLevelLow = true;
                }
            } else {
                // Should we now switch out of low battery mode?
                if (mPlugType != BATTERY_PLUGGED_NONE) {
                    mBatteryLevelLow = false;
                } else if (mBatteryProps.batteryLevel >= mLowBatteryCloseWarningLevel)  {
                    mBatteryLevelLow = false;
                } else if (force && mBatteryProps.batteryLevel >= mLowBatteryWarningLevel) {
                    // If being forced, the previous state doesn't matter, we will just
                    // absolutely check to see if we are now above the warning level.
                    mBatteryLevelLow = false;
                }
            }

            mSequence++;

            // Separate broadcast is sent for power connected / not connected
            // since the standard intent will not wake any applications and some
            // applications may want to have smart behavior based on this.
            if (mPlugType != 0 && mLastPlugType == 0) {
                final Intent statusIntent = new Intent(Intent.ACTION_POWER_CONNECTED);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                statusIntent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL);
                    }
                });
            }
            else if (mPlugType == 0 && mLastPlugType != 0) {
                final Intent statusIntent = new Intent(Intent.ACTION_POWER_DISCONNECTED);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                statusIntent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL);
                    }
                });
            }

            if (shouldSendBatteryLowLocked()) {
                mSentLowBatteryBroadcast = true;
                final Intent statusIntent = new Intent(Intent.ACTION_BATTERY_LOW);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                statusIntent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL);
                    }
                });
            } else if (mSentLowBatteryBroadcast &&
                    mBatteryProps.batteryLevel >= mLowBatteryCloseWarningLevel) {
                mSentLowBatteryBroadcast = false;
                final Intent statusIntent = new Intent(Intent.ACTION_BATTERY_OKAY);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                statusIntent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL);
                    }
                });
            }

            // We are doing this after sending the above broadcasts, so anything processing
            // them will get the new sequence number at that point.  (See for example how testing
            // of JobScheduler's BatteryController works.)
            sendIntentLocked();

            // Update the battery LED
            mLed.updateLightsLocked();

            // This needs to be done after sendIntent() so that we get the lastest battery stats.
            if (logOutlier && dischargeDuration != 0) {
                logOutlierLocked(dischargeDuration);
            }

            mLastBatteryStatus = mBatteryProps.batteryStatus;
            mLastBatteryHealth = mBatteryProps.batteryHealth;
            mLastBatteryPresent = mBatteryProps.batteryPresent;
            mLastBatteryLevel = mBatteryProps.batteryLevel;
            mLastPlugType = mPlugType;
            mLastBatteryVoltage = mBatteryProps.batteryVoltage;
            mLastBatteryTemperature = mBatteryProps.batteryTemperature;
            mLastMaxChargingCurrent = mBatteryProps.maxChargingCurrent;
            mLastMaxChargingVoltage = mBatteryProps.maxChargingVoltage;
            mLastChargeCounter = mBatteryProps.batteryChargeCounter;
            mLastBatteryLevelCritical = mBatteryLevelCritical;
            mLastInvalidCharger = mInvalidCharger;
        }
    }

    private void sendIntentLocked() {
        //  Pack up the values and broadcast them to everyone
        final Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);

        int icon = getIconLocked(mBatteryProps.batteryLevel);

        intent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
        intent.putExtra(BatteryManager.EXTRA_STATUS, mBatteryProps.batteryStatus);
        intent.putExtra(BatteryManager.EXTRA_HEALTH, mBatteryProps.batteryHealth);
        intent.putExtra(BatteryManager.EXTRA_PRESENT, mBatteryProps.batteryPresent);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, mBatteryProps.batteryLevel);
        intent.putExtra(BatteryManager.EXTRA_SCALE, BATTERY_SCALE);
        intent.putExtra(BatteryManager.EXTRA_ICON_SMALL, icon);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, mPlugType);
        intent.putExtra(BatteryManager.EXTRA_VOLTAGE, mBatteryProps.batteryVoltage);
        intent.putExtra(BatteryManager.EXTRA_TEMPERATURE, mBatteryProps.batteryTemperature);
        intent.putExtra(BatteryManager.EXTRA_TECHNOLOGY, mBatteryProps.batteryTechnology);
        intent.putExtra(BatteryManager.EXTRA_INVALID_CHARGER, mInvalidCharger);
        intent.putExtra(BatteryManager.EXTRA_MAX_CHARGING_CURRENT, mBatteryProps.maxChargingCurrent);
        intent.putExtra(BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE, mBatteryProps.maxChargingVoltage);
        intent.putExtra(BatteryManager.EXTRA_CHARGE_COUNTER, mBatteryProps.batteryChargeCounter);
        if (DEBUG) {
            Slog.d(TAG, "Sending ACTION_BATTERY_CHANGED.  level:" + mBatteryProps.batteryLevel +
                    ", scale:" + BATTERY_SCALE + ", status:" + mBatteryProps.batteryStatus +
                    ", health:" + mBatteryProps.batteryHealth +
                    ", present:" + mBatteryProps.batteryPresent +
                    ", voltage: " + mBatteryProps.batteryVoltage +
                    ", temperature: " + mBatteryProps.batteryTemperature +
                    ", technology: " + mBatteryProps.batteryTechnology +
                    ", AC powered:" + mBatteryProps.chargerAcOnline +
                    ", USB powered:" + mBatteryProps.chargerUsbOnline +
                    ", Wireless powered:" + mBatteryProps.chargerWirelessOnline +
                    ", icon:" + icon  + ", invalid charger:" + mInvalidCharger +
                    ", maxChargingCurrent:" + mBatteryProps.maxChargingCurrent +
                    ", maxChargingVoltage:" + mBatteryProps.maxChargingVoltage +
                    ", chargeCounter:" + mBatteryProps.batteryChargeCounter);
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                ActivityManager.broadcastStickyIntent(intent, UserHandle.USER_ALL);
            }
        });
    }

    private void logBatteryStatsLocked() {
        IBinder batteryInfoService = ServiceManager.getService(BatteryStats.SERVICE_NAME);
        if (batteryInfoService == null) return;

        DropBoxManager db = (DropBoxManager) mContext.getSystemService(Context.DROPBOX_SERVICE);
        if (db == null || !db.isTagEnabled("BATTERY_DISCHARGE_INFO")) return;

        File dumpFile = null;
        FileOutputStream dumpStream = null;
        try {
            // dump the service to a file
            dumpFile = new File(DUMPSYS_DATA_PATH + BatteryStats.SERVICE_NAME + ".dump");
            dumpStream = new FileOutputStream(dumpFile);
            batteryInfoService.dump(dumpStream.getFD(), DUMPSYS_ARGS);
            FileUtils.sync(dumpStream);

            // add dump file to drop box
            db.addFile("BATTERY_DISCHARGE_INFO", dumpFile, DropBoxManager.IS_TEXT);
        } catch (RemoteException e) {
            Slog.e(TAG, "failed to dump battery service", e);
        } catch (IOException e) {
            Slog.e(TAG, "failed to write dumpsys file", e);
        } finally {
            // make sure we clean up
            if (dumpStream != null) {
                try {
                    dumpStream.close();
                } catch (IOException e) {
                    Slog.e(TAG, "failed to close dumpsys output stream");
                }
            }
            if (dumpFile != null && !dumpFile.delete()) {
                Slog.e(TAG, "failed to delete temporary dumpsys file: "
                        + dumpFile.getAbsolutePath());
            }
        }
    }

    private void logOutlierLocked(long duration) {
        ContentResolver cr = mContext.getContentResolver();
        String dischargeThresholdString = Settings.Global.getString(cr,
                Settings.Global.BATTERY_DISCHARGE_THRESHOLD);
        String durationThresholdString = Settings.Global.getString(cr,
                Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD);

        if (dischargeThresholdString != null && durationThresholdString != null) {
            try {
                long durationThreshold = Long.parseLong(durationThresholdString);
                int dischargeThreshold = Integer.parseInt(dischargeThresholdString);
                if (duration <= durationThreshold &&
                        mDischargeStartLevel - mBatteryProps.batteryLevel >= dischargeThreshold) {
                    // If the discharge cycle is bad enough we want to know about it.
                    logBatteryStatsLocked();
                }
                if (DEBUG) Slog.v(TAG, "duration threshold: " + durationThreshold +
                        " discharge threshold: " + dischargeThreshold);
                if (DEBUG) Slog.v(TAG, "duration: " + duration + " discharge: " +
                        (mDischargeStartLevel - mBatteryProps.batteryLevel));
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Invalid DischargeThresholds GService string: " +
                        durationThresholdString + " or " + dischargeThresholdString);
            }
        }
    }

    private int getIconLocked(int level) {
        if (mBatteryProps.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
            return com.android.internal.R.drawable.stat_sys_battery_charge;
        } else if (mBatteryProps.batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
            return com.android.internal.R.drawable.stat_sys_battery;
        } else if (mBatteryProps.batteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING
                || mBatteryProps.batteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
            if (isPoweredLocked(BatteryManager.BATTERY_PLUGGED_ANY)
                    && mBatteryProps.batteryLevel >= 100) {
                return com.android.internal.R.drawable.stat_sys_battery_charge;
            } else {
                return com.android.internal.R.drawable.stat_sys_battery;
            }
        } else {
            return com.android.internal.R.drawable.stat_sys_battery_unknown;
        }
    }

    class Shell extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            dumpHelp(pw);
        }
    }

    static void dumpHelp(PrintWriter pw) {
        pw.println("Battery service (battery) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set [-f] [ac|usb|wireless|status|level|temp|present|invalid] <value>");
        pw.println("    Force a battery property value, freezing battery state.");
        pw.println("    -f: force a battery change broadcast be sent, prints new sequence.");
        pw.println("  unplug [-f]");
        pw.println("    Force battery unplugged, freezing battery state.");
        pw.println("    -f: force a battery change broadcast be sent, prints new sequence.");
        pw.println("  reset [-f]");
        pw.println("    Unfreeze battery state, returning to current hardware values.");
        pw.println("    -f: force a battery change broadcast be sent, prints new sequence.");
    }

    static final int OPTION_FORCE_UPDATE = 1<<0;

    int parseOptions(Shell shell) {
        String opt;
        int opts = 0;
        while ((opt = shell.getNextOption()) != null) {
            if ("-f".equals(opt)) {
                opts |= OPTION_FORCE_UPDATE;
            }
        }
        return opts;
    }

    int onShellCommand(Shell shell, String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        switch (cmd) {
            case "unplug": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                if (!mUpdatesStopped) {
                    mLastBatteryProps.set(mBatteryProps);
                }
                mBatteryProps.chargerAcOnline = false;
                mBatteryProps.chargerUsbOnline = false;
                mBatteryProps.chargerWirelessOnline = false;
                long ident = Binder.clearCallingIdentity();
                try {
                    mUpdatesStopped = true;
                    processValuesFromShellLocked(pw, opts);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } break;
            case "set": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                final String key = shell.getNextArg();
                if (key == null) {
                    pw.println("No property specified");
                    return -1;

                }
                final String value = shell.getNextArg();
                if (value == null) {
                    pw.println("No value specified");
                    return -1;

                }
                try {
                    if (!mUpdatesStopped) {
                        mLastBatteryProps.set(mBatteryProps);
                    }
                    boolean update = true;
                    switch (key) {
                        case "present":
                            mBatteryProps.batteryPresent = Integer.parseInt(value) != 0;
                            break;
                        case "ac":
                            mBatteryProps.chargerAcOnline = Integer.parseInt(value) != 0;
                            break;
                        case "usb":
                            mBatteryProps.chargerUsbOnline = Integer.parseInt(value) != 0;
                            break;
                        case "wireless":
                            mBatteryProps.chargerWirelessOnline = Integer.parseInt(value) != 0;
                            break;
                        case "status":
                            mBatteryProps.batteryStatus = Integer.parseInt(value);
                            break;
                        case "level":
                            mBatteryProps.batteryLevel = Integer.parseInt(value);
                            break;
                        case "temp":
                            mBatteryProps.batteryTemperature = Integer.parseInt(value);
                            break;
                        case "invalid":
                            mInvalidCharger = Integer.parseInt(value);
                            break;
                        default:
                            pw.println("Unknown set option: " + key);
                            update = false;
                            break;
                    }
                    if (update) {
                        long ident = Binder.clearCallingIdentity();
                        try {
                            mUpdatesStopped = true;
                            processValuesFromShellLocked(pw, opts);
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                } catch (NumberFormatException ex) {
                    pw.println("Bad value: " + value);
                    return -1;
                }
            } break;
            case "reset": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                long ident = Binder.clearCallingIdentity();
                try {
                    if (mUpdatesStopped) {
                        mUpdatesStopped = false;
                        mBatteryProps.set(mLastBatteryProps);
                        processValuesFromShellLocked(pw, opts);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } break;
            default:
                return shell.handleDefaultCommands(cmd);
        }
        return 0;
    }

    private void processValuesFromShellLocked(PrintWriter pw, int opts) {
        processValuesLocked((opts & OPTION_FORCE_UPDATE) != 0);
        if ((opts & OPTION_FORCE_UPDATE) != 0) {
            pw.println(mSequence);
        }
    }

    private void dumpInternal(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            if (args == null || args.length == 0 || "-a".equals(args[0])) {
                pw.println("Current Battery Service state:");
                if (mUpdatesStopped) {
                    pw.println("  (UPDATES STOPPED -- use 'reset' to restart)");
                }
                pw.println("  AC powered: " + mBatteryProps.chargerAcOnline);
                pw.println("  USB powered: " + mBatteryProps.chargerUsbOnline);
                pw.println("  Wireless powered: " + mBatteryProps.chargerWirelessOnline);
                pw.println("  Max charging current: " + mBatteryProps.maxChargingCurrent);
                pw.println("  Max charging voltage: " + mBatteryProps.maxChargingVoltage);
                pw.println("  Charge counter: " + mBatteryProps.batteryChargeCounter);
                pw.println("  status: " + mBatteryProps.batteryStatus);
                pw.println("  health: " + mBatteryProps.batteryHealth);
                pw.println("  present: " + mBatteryProps.batteryPresent);
                pw.println("  level: " + mBatteryProps.batteryLevel);
                pw.println("  scale: " + BATTERY_SCALE);
                pw.println("  voltage: " + mBatteryProps.batteryVoltage);
                pw.println("  temperature: " + mBatteryProps.batteryTemperature);
                pw.println("  technology: " + mBatteryProps.batteryTechnology);
            } else {
                Shell shell = new Shell();
                shell.exec(mBinderService, null, fd, null, args, null, new ResultReceiver(null));
            }
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            proto.write(BatteryServiceDumpProto.ARE_UPDATES_STOPPED, mUpdatesStopped);
            int batteryPluggedValue = BatteryServiceDumpProto.BATTERY_PLUGGED_NONE;
            if (mBatteryProps.chargerAcOnline) {
                batteryPluggedValue = BatteryServiceDumpProto.BATTERY_PLUGGED_AC;
            } else if (mBatteryProps.chargerUsbOnline) {
                batteryPluggedValue = BatteryServiceDumpProto.BATTERY_PLUGGED_USB;
            } else if (mBatteryProps.chargerWirelessOnline) {
                batteryPluggedValue = BatteryServiceDumpProto.BATTERY_PLUGGED_WIRELESS;
            }
            proto.write(BatteryServiceDumpProto.PLUGGED, batteryPluggedValue);
            proto.write(BatteryServiceDumpProto.MAX_CHARGING_CURRENT, mBatteryProps.maxChargingCurrent);
            proto.write(BatteryServiceDumpProto.MAX_CHARGING_VOLTAGE, mBatteryProps.maxChargingVoltage);
            proto.write(BatteryServiceDumpProto.CHARGE_COUNTER, mBatteryProps.batteryChargeCounter);
            proto.write(BatteryServiceDumpProto.STATUS, mBatteryProps.batteryStatus);
            proto.write(BatteryServiceDumpProto.HEALTH, mBatteryProps.batteryHealth);
            proto.write(BatteryServiceDumpProto.IS_PRESENT, mBatteryProps.batteryPresent);
            proto.write(BatteryServiceDumpProto.LEVEL, mBatteryProps.batteryLevel);
            proto.write(BatteryServiceDumpProto.SCALE, BATTERY_SCALE);
            proto.write(BatteryServiceDumpProto.VOLTAGE, mBatteryProps.batteryVoltage);
            proto.write(BatteryServiceDumpProto.TEMPERATURE, mBatteryProps.batteryTemperature);
            proto.write(BatteryServiceDumpProto.TECHNOLOGY, mBatteryProps.batteryTechnology);
        }
        proto.flush();
    }

    private final class Led {
        private final Light mBatteryLight;

        private final int mBatteryLowARGB;
        private final int mBatteryMediumARGB;
        private final int mBatteryFullARGB;
        private final int mBatteryLedOn;
        private final int mBatteryLedOff;

        public Led(Context context, LightsManager lights) {
            mBatteryLight = lights.getLight(LightsManager.LIGHT_ID_BATTERY);

            mBatteryLowARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLowARGB);
            mBatteryMediumARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryMediumARGB);
            mBatteryFullARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryFullARGB);
            mBatteryLedOn = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLedOn);
            mBatteryLedOff = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLedOff);
        }

        /**
         * Synchronize on BatteryService.
         */
        public void updateLightsLocked() {
            final int level = mBatteryProps.batteryLevel;
            final int status = mBatteryProps.batteryStatus;
            if (level < mLowBatteryWarningLevel) {
                if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                    // Solid red when battery is charging
                    mBatteryLight.setColor(mBatteryLowARGB);
                } else {
                    // Flash red when battery is low and not charging
                    mBatteryLight.setFlashing(mBatteryLowARGB, Light.LIGHT_FLASH_TIMED,
                            mBatteryLedOn, mBatteryLedOff);
                }
            } else if (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL) {
                if (status == BatteryManager.BATTERY_STATUS_FULL || level >= 90) {
                    // Solid green when full or charging and nearly full
                    mBatteryLight.setColor(mBatteryFullARGB);
                } else {
                    // Solid orange when charging and halfway full
                    mBatteryLight.setColor(mBatteryMediumARGB);
                }
            } else {
                // No lights if not charging and not low
                mBatteryLight.turnOff();
            }
        }
    }

    private final class BatteryListener extends IBatteryPropertiesListener.Stub {
        @Override public void batteryPropertiesChanged(BatteryProperties props) {
            final long identity = Binder.clearCallingIdentity();
            try {
                BatteryService.this.update(props);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
       }
    }

    private final class BinderService extends Binder {
        @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            if (args.length > 0 && "--proto".equals(args[0])) {
                dumpProto(fd);
            } else {
                dumpInternal(fd, pw, args);
            }
        }

        @Override public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new Shell()).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    private final class LocalService extends BatteryManagerInternal {
        @Override
        public boolean isPowered(int plugTypeSet) {
            synchronized (mLock) {
                return isPoweredLocked(plugTypeSet);
            }
        }

        @Override
        public int getPlugType() {
            synchronized (mLock) {
                return mPlugType;
            }
        }

        @Override
        public int getBatteryLevel() {
            synchronized (mLock) {
                return mBatteryProps.batteryLevel;
            }
        }

        @Override
        public boolean getBatteryLevelLow() {
            synchronized (mLock) {
                return mBatteryLevelLow;
            }
        }

        @Override
        public int getInvalidCharger() {
            synchronized (mLock) {
                return mInvalidCharger;
            }
        }
    }
}
