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

package com.android.systemui.power;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HardwarePropertiesManager;
import android.os.IBinder;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Temperature;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.phone.StatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

public class PowerUI extends SystemUI {
    static final String TAG = "PowerUI";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long TEMPERATURE_INTERVAL = 30 * DateUtils.SECOND_IN_MILLIS;
    private static final long TEMPERATURE_LOGGING_INTERVAL = DateUtils.HOUR_IN_MILLIS;
    private static final int TEMPERATURE_OVERHEAT_WARNING = 0;
    private static final int TEMPERATURE_OVERHEAT_ALARM = 1;
    private static final int MAX_RECENT_TEMPS = 125; // TEMPERATURE_LOGGING_INTERVAL plus a buffer
    static final long THREE_HOURS_IN_MILLIS = DateUtils.HOUR_IN_MILLIS * 3;
    private static final int CHARGE_CYCLE_PERCENT_RESET = 45;
    private static final long SIX_HOURS_MILLIS = Duration.ofHours(6).toMillis();

    private final Handler mHandler = new Handler();
    @VisibleForTesting
    final Receiver mReceiver = new Receiver();

    private PowerManager mPowerManager;
    private HardwarePropertiesManager mHardwarePropertiesManager;
    private WarningsUI mWarnings;
    private final Configuration mLastConfiguration = new Configuration();
    private long mTimeRemaining = Long.MAX_VALUE;
    private int mPlugType = 0;
    private int mInvalidCharger = 0;
    private EnhancedEstimates mEnhancedEstimates;
    private Estimate mLastEstimate;
    private boolean mLowWarningShownThisChargeCycle;
    private boolean mSevereWarningShownThisChargeCycle;
    private boolean mEnableTemperatureWarning;
    private boolean mEnableTemperatureAlarm;
    private boolean mIsOverheatAlarming;

    private int mLowBatteryAlertCloseLevel;
    private final int[] mLowBatteryReminderLevels = new int[2];

    private long mScreenOffTime = -1;

    private float mThresholdWarningTemp;
    private float mThresholdAlarmTemp;
    private float mThresholdAlarmTempTolerance;
    private float[] mRecentSkinTemps = new float[MAX_RECENT_TEMPS];
    private float[] mRecentAlarmTemps = new float[MAX_RECENT_TEMPS];
    private int mWarningNumTemps;
    private int mAlarmNumTemps;
    private long mNextLogTime;
    private IThermalService mThermalService;

    @VisibleForTesting int mBatteryLevel = 100;
    @VisibleForTesting int mBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;

    // by using the same instance (method references are not guaranteed to be the same object
    // We create a method reference here so that we are guaranteed that we can remove a callback
    // each time they are created).
    private final Runnable mUpdateTempCallback = this::updateTemperature;

    public void start() {
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mHardwarePropertiesManager = (HardwarePropertiesManager)
                mContext.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE);
        mScreenOffTime = mPowerManager.isScreenOn() ? -1 : SystemClock.elapsedRealtime();
        mWarnings = Dependency.get(WarningsUI.class);
        mEnhancedEstimates = Dependency.get(EnhancedEstimates.class);
        mLastConfiguration.setTo(mContext.getResources().getConfiguration());

        ContentObserver obs = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateBatteryWarningLevels();
            }
        };
        final ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL),
                false, obs, UserHandle.USER_ALL);
        updateBatteryWarningLevels();
        mReceiver.init();

        // Check to see if we need to let the user know that the phone previously shut down due
        // to the temperature being too high.
        showThermalShutdownDialog();

        initTemperature();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        final int mask = ActivityInfo.CONFIG_MCC | ActivityInfo.CONFIG_MNC;

        // Safe to modify mLastConfiguration here as it's only updated by the main thread (here).
        if ((mLastConfiguration.updateFrom(newConfig) & mask) != 0) {
            mHandler.post(this::initTemperature);
        }
    }

    void updateBatteryWarningLevels() {
        int critLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        int warnLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);

        if (warnLevel < critLevel) {
            warnLevel = critLevel;
        }

        mLowBatteryReminderLevels[0] = warnLevel;
        mLowBatteryReminderLevels[1] = critLevel;
        mLowBatteryAlertCloseLevel = mLowBatteryReminderLevels[0]
                + mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_lowBatteryCloseWarningBump);
    }

    /**
     * Buckets the battery level.
     *
     * The code in this function is a little weird because I couldn't comprehend
     * the bucket going up when the battery level was going down. --joeo
     *
     * 1 means that the battery is "ok"
     * 0 means that the battery is between "ok" and what we should warn about.
     * less than 0 means that the battery is low
     */
    private int findBatteryLevelBucket(int level) {
        if (level >= mLowBatteryAlertCloseLevel) {
            return 1;
        }
        if (level > mLowBatteryReminderLevels[0]) {
            return 0;
        }
        final int N = mLowBatteryReminderLevels.length;
        for (int i=N-1; i>=0; i--) {
            if (level <= mLowBatteryReminderLevels[i]) {
                return -1-i;
            }
        }
        throw new RuntimeException("not possible!");
    }

    @VisibleForTesting
    final class Receiver extends BroadcastReceiver {

        public void init() {
            // Register for Intent broadcasts for...
            IntentFilter filter = new IntentFilter();
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            mContext.registerReceiver(this, filter, null, mHandler);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) {
                ThreadUtils.postOnBackgroundThread(() -> {
                    if (mPowerManager.isPowerSaveMode()) {
                        mWarnings.dismissLowBatteryWarning();
                    }
                });
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                final int oldBatteryLevel = mBatteryLevel;
                mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
                final int oldBatteryStatus = mBatteryStatus;
                mBatteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                final int oldPlugType = mPlugType;
                mPlugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 1);
                final int oldInvalidCharger = mInvalidCharger;
                mInvalidCharger = intent.getIntExtra(BatteryManager.EXTRA_INVALID_CHARGER, 0);

                final boolean plugged = mPlugType != 0;
                final boolean oldPlugged = oldPlugType != 0;

                int oldBucket = findBatteryLevelBucket(oldBatteryLevel);
                int bucket = findBatteryLevelBucket(mBatteryLevel);

                if (DEBUG) {
                    Slog.d(TAG, "buckets   ....." + mLowBatteryAlertCloseLevel
                            + " .. " + mLowBatteryReminderLevels[0]
                            + " .. " + mLowBatteryReminderLevels[1]);
                    Slog.d(TAG, "level          " + oldBatteryLevel + " --> " + mBatteryLevel);
                    Slog.d(TAG, "status         " + oldBatteryStatus + " --> " + mBatteryStatus);
                    Slog.d(TAG, "plugType       " + oldPlugType + " --> " + mPlugType);
                    Slog.d(TAG, "invalidCharger " + oldInvalidCharger + " --> " + mInvalidCharger);
                    Slog.d(TAG, "bucket         " + oldBucket + " --> " + bucket);
                    Slog.d(TAG, "plugged        " + oldPlugged + " --> " + plugged);
                }

                mWarnings.update(mBatteryLevel, bucket, mScreenOffTime);
                if (oldInvalidCharger == 0 && mInvalidCharger != 0) {
                    Slog.d(TAG, "showing invalid charger warning");
                    mWarnings.showInvalidChargerWarning();
                    return;
                } else if (oldInvalidCharger != 0 && mInvalidCharger == 0) {
                    mWarnings.dismissInvalidChargerWarning();
                } else if (mWarnings.isInvalidChargerWarningShowing()) {
                    // if invalid charger is showing, don't show low battery
                    return;
                }

                // Show the correct version of low battery warning if needed
                ThreadUtils.postOnBackgroundThread(() -> {
                    maybeShowBatteryWarning(
                            oldBatteryLevel, plugged, oldPlugged, oldBucket, bucket);
                });

            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOffTime = SystemClock.elapsedRealtime();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mScreenOffTime = -1;
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mWarnings.userSwitched();
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                updateTemperature();
            } else {
                Slog.w(TAG, "unknown intent: " + intent);
            }
        }
    }

    protected void maybeShowBatteryWarning(int oldBatteryLevel, boolean plugged, boolean oldPlugged,
            int oldBucket, int bucket) {
        boolean isPowerSaver = mPowerManager.isPowerSaveMode();
        // only play SFX when the dialog comes up or the bucket changes
        final boolean playSound = bucket != oldBucket || oldPlugged;
        final boolean hybridEnabled = mEnhancedEstimates.isHybridNotificationEnabled();
        if (hybridEnabled) {
            Estimate estimate = mLastEstimate;
            if (estimate == null || mBatteryLevel != oldBatteryLevel) {
                estimate = mEnhancedEstimates.getEstimate();
                mLastEstimate = estimate;
            }
            // Turbo is not always booted once SysUI is running so we have ot make sure we actually
            // get data back
            if (estimate != null) {
                mTimeRemaining = estimate.estimateMillis;
                mWarnings.updateEstimate(estimate);
                mWarnings.updateThresholds(mEnhancedEstimates.getLowWarningThreshold(),
                        mEnhancedEstimates.getSevereWarningThreshold());

                // if we are now over 45% battery & 6 hours remaining we can trigger hybrid
                // notification again
                if (mBatteryLevel >= CHARGE_CYCLE_PERCENT_RESET
                        && mTimeRemaining > SIX_HOURS_MILLIS) {
                    mLowWarningShownThisChargeCycle = false;
                    mSevereWarningShownThisChargeCycle = false;
                }
            }
        }

        if (shouldShowLowBatteryWarning(plugged, oldPlugged, oldBucket, bucket,
                mTimeRemaining, isPowerSaver, mBatteryStatus)) {
            mWarnings.showLowBatteryWarning(playSound);

            // mark if we've already shown a warning this cycle. This will prevent the notification
            // trigger from spamming users by only showing low/critical warnings once per cycle
            if (hybridEnabled) {
                if (mTimeRemaining < mEnhancedEstimates.getSevereWarningThreshold()
                        || mBatteryLevel < mLowBatteryReminderLevels[1]) {
                    mSevereWarningShownThisChargeCycle = true;
                } else {
                    mLowWarningShownThisChargeCycle = true;
                }
            }
        } else if (shouldDismissLowBatteryWarning(plugged, oldBucket, bucket, mTimeRemaining,
                isPowerSaver)) {
            mWarnings.dismissLowBatteryWarning();
        } else {
            mWarnings.updateLowBatteryWarning();
        }
    }

    @VisibleForTesting
    boolean shouldShowLowBatteryWarning(boolean plugged, boolean oldPlugged, int oldBucket,
            int bucket, long timeRemaining, boolean isPowerSaver, int batteryStatus) {
        if (mEnhancedEstimates.isHybridNotificationEnabled()) {
            // triggering logic when enhanced estimate is available
            return isEnhancedTrigger(plugged, timeRemaining, isPowerSaver, batteryStatus);
        }
        // legacy triggering logic
        return !plugged
                && !isPowerSaver
                && (((bucket < oldBucket || oldPlugged) && bucket < 0))
                && batteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN;
    }

    @VisibleForTesting
    boolean shouldDismissLowBatteryWarning(boolean plugged, int oldBucket, int bucket,
            long timeRemaining, boolean isPowerSaver) {
        final boolean hybridEnabled = mEnhancedEstimates.isHybridNotificationEnabled();
        final boolean hybridWouldDismiss = hybridEnabled
                && timeRemaining > mEnhancedEstimates.getLowWarningThreshold();
        final boolean standardWouldDismiss = (bucket > oldBucket && bucket > 0);
        return (isPowerSaver && !hybridEnabled)
                || plugged
                || (standardWouldDismiss && (!mEnhancedEstimates.isHybridNotificationEnabled()
                        || hybridWouldDismiss));
    }

    private boolean isEnhancedTrigger(boolean plugged, long timeRemaining, boolean isPowerSaver,
            int batteryStatus) {
        if (plugged || batteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            return false;
        }
        int warnLevel = mLowBatteryReminderLevels[0];
        int critLevel = mLowBatteryReminderLevels[1];

        // Only show the low warning once per charge cycle & no battery saver
        final boolean canShowWarning = !mLowWarningShownThisChargeCycle && !isPowerSaver
                && (timeRemaining < mEnhancedEstimates.getLowWarningThreshold()
                        || mBatteryLevel <= warnLevel);

        // Only show the severe warning once per charge cycle
        final boolean canShowSevereWarning = !mSevereWarningShownThisChargeCycle
                && (timeRemaining < mEnhancedEstimates.getSevereWarningThreshold()
                        || mBatteryLevel <= critLevel);

        return canShowWarning || canShowSevereWarning;
    }

    private void initTemperature() {
        initTemperatureWarning();
        initTemperatureAlarm();
        if (mEnableTemperatureWarning || mEnableTemperatureAlarm) {
            bindThermalService();
        }
    }

    private void initTemperatureAlarm() {
        mEnableTemperatureAlarm = mContext.getResources().getInteger(
                R.integer.config_showTemperatureAlarm) != 0;
        if (!mEnableTemperatureAlarm) {
            return;
        }

        float[] throttlingTemps = mHardwarePropertiesManager.getDeviceTemperatures(
                HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN,
                HardwarePropertiesManager.TEMPERATURE_THROTTLING);
        if (throttlingTemps == null || throttlingTemps.length < TEMPERATURE_OVERHEAT_ALARM + 1) {
            mThresholdAlarmTemp = mContext.getResources().getInteger(
                    R.integer.config_alarmTemperature);
        } else {
            mThresholdAlarmTemp = throttlingTemps[TEMPERATURE_OVERHEAT_ALARM];
        }
        mThresholdAlarmTempTolerance = mThresholdAlarmTemp - mContext.getResources().getInteger(
                R.integer.config_alarmTemperatureTolerance);
        Log.d(TAG, "mThresholdAlarmTemp=" + mThresholdAlarmTemp + ", mThresholdAlarmTempTolerance="
                + mThresholdAlarmTempTolerance);
    }

    private void initTemperatureWarning() {
        ContentResolver resolver = mContext.getContentResolver();
        Resources resources = mContext.getResources();
        mEnableTemperatureWarning = Settings.Global.getInt(resolver,
                Settings.Global.SHOW_TEMPERATURE_WARNING,
                resources.getInteger(R.integer.config_showTemperatureWarning)) != 0;
        if (!mEnableTemperatureWarning) {
            return;
        }

        mThresholdWarningTemp = Settings.Global.getFloat(resolver,
                Settings.Global.WARNING_TEMPERATURE,
                resources.getInteger(R.integer.config_warningTemperature));

        if (mThresholdWarningTemp < 0f) {
            // Get the shutdown temperature, adjust for warning tolerance.
            float[] throttlingTemps = mHardwarePropertiesManager.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN,
                    HardwarePropertiesManager.TEMPERATURE_SHUTDOWN);
            if (throttlingTemps == null
                    || throttlingTemps.length == 0
                    || throttlingTemps[0] == HardwarePropertiesManager.UNDEFINED_TEMPERATURE) {
                return;
            }
            mThresholdWarningTemp = throttlingTemps[0] - resources.getInteger(
                    R.integer.config_warningTemperatureTolerance);
        }
    }

    private void bindThermalService() {
        if (mThermalService == null) {
            // Enable push notifications of throttling from vendor thermal
            // management subsystem via thermalservice, in addition to our
            // usual polling, to react to temperature jumps more quickly.
            IBinder b = ServiceManager.getService("thermalservice");

            if (b != null) {
                mThermalService = IThermalService.Stub.asInterface(b);
                try {
                    mThermalService.registerThermalEventListener(
                        new ThermalEventListener());
                } catch (RemoteException e) {
                    // Should never happen.
                }
            } else {
                Slog.w(TAG, "cannot find thermalservice, no throttling push notifications");
            }
        }

        setNextLogTime();

        // This initialization method may be called on a configuration change. Only one set of
        // ongoing callbacks should be occurring, so remove any now. updateTemperatureWarning will
        // schedule an ongoing callback.
        mHandler.removeCallbacks(mUpdateTempCallback);

        // We have passed all of the checks, start checking the temp
        updateTemperature();
    }

    private void showThermalShutdownDialog() {
        if (mPowerManager.getLastShutdownReason()
                == PowerManager.SHUTDOWN_REASON_THERMAL_SHUTDOWN) {
            mWarnings.showThermalShutdownWarning();
        }
    }

    /**
     * Update temperature depend on config, there are type types of messages by design
     * TEMPERATURE_OVERHEAT_WARNING Send generic notification to notify user
     * TEMPERATURE_OVERHEAT_ALARM popup emergency Dialog for user
     */
    @VisibleForTesting
    protected void updateTemperature() {
        if (!mEnableTemperatureWarning && !mEnableTemperatureAlarm) {
            return;
        }

        float[] temps = mHardwarePropertiesManager.getDeviceTemperatures(
                HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN,
                HardwarePropertiesManager.TEMPERATURE_CURRENT);
        if (temps == null) {
            Log.e(TAG, "Can't query current temperature value from HardProps SKIN type");
            return;
        }

        final float[] temps_compat;
        if (temps.length < TEMPERATURE_OVERHEAT_ALARM + 1) {
            temps_compat = new float[] { temps[0], temps[0] };
        } else {
            temps_compat = temps;
        }

        if (mEnableTemperatureWarning) {
            updateTemperatureWarning(temps_compat);
            logTemperatureStats(TEMPERATURE_OVERHEAT_WARNING);
        }

        if (mEnableTemperatureAlarm) {
            updateTemperatureAlarm(temps_compat);
            logTemperatureStats(TEMPERATURE_OVERHEAT_ALARM);
        }

        mHandler.postDelayed(mUpdateTempCallback, TEMPERATURE_INTERVAL);
    }

    /**
     * Update legacy overheat warning notification from skin temperatures
     *
     * @param temps the array include two types of value from DEVICE_TEMPERATURE_SKIN
     *              this function only obtain the value of temps[TEMPERATURE_OVERHEAT_WARNING]
     */
    @VisibleForTesting
    protected void updateTemperatureWarning(float[] temps) {
        if (temps.length != 0) {
            float temp = temps[TEMPERATURE_OVERHEAT_WARNING];
            mRecentSkinTemps[mWarningNumTemps++] = temp;

            StatusBar statusBar = getComponent(StatusBar.class);
            if (statusBar != null && !statusBar.isDeviceInVrMode()
                    && temp >= mThresholdWarningTemp) {
                logAtTemperatureThreshold(TEMPERATURE_OVERHEAT_WARNING, temp,
                        mThresholdWarningTemp);
                mWarnings.showHighTemperatureWarning();
            } else {
                mWarnings.dismissHighTemperatureWarning();
            }
        }
    }

    /**
     * Update overheat alarm from skin temperatures
     * OEM can config alarm with beep sound by config_alarmTemperatureBeepSound
     *
     * @param temps the array include two types of value from DEVICE_TEMPERATURE_SKIN
     *              this function only obtain the value of temps[TEMPERATURE_OVERHEAT_ALARM]
     */
    @VisibleForTesting
    protected void updateTemperatureAlarm(float[] temps) {
        if (temps.length != 0) {
            final float temp = temps[TEMPERATURE_OVERHEAT_ALARM];
            final boolean shouldBeepSound = mContext.getResources().getBoolean(
                    R.bool.config_alarmTemperatureBeepSound);
            mRecentAlarmTemps[mAlarmNumTemps++] = temp;
            if (temp >= mThresholdAlarmTemp && !mIsOverheatAlarming) {
                mWarnings.notifyHighTemperatureAlarm(true /* overheat */, shouldBeepSound);
                mIsOverheatAlarming = true;
            } else if (temp <= mThresholdAlarmTempTolerance && mIsOverheatAlarming) {
                mWarnings.notifyHighTemperatureAlarm(false /* overheat */, false /* beepSound */);
                mIsOverheatAlarming = false;
            }
            logAtTemperatureThreshold(TEMPERATURE_OVERHEAT_ALARM, temp, mThresholdAlarmTemp);
        }
    }

    private void logAtTemperatureThreshold(int type, float temp, float thresholdTemp) {
        StringBuilder sb = new StringBuilder();
        sb.append("currentTemp=").append(temp)
                .append(",overheatType=").append(type)
                .append(",isOverheatAlarm=").append(mIsOverheatAlarming)
                .append(",thresholdTemp=").append(thresholdTemp)
                .append(",batteryStatus=").append(mBatteryStatus)
                .append(",recentTemps=");
        if (type == TEMPERATURE_OVERHEAT_WARNING) {
            for (int i = 0; i < mWarningNumTemps; i++) {
                sb.append(mRecentSkinTemps[i]).append(',');
            }
        } else {
            for (int i = 0; i < mAlarmNumTemps; i++) {
                sb.append(mRecentAlarmTemps[i]).append(',');
            }
        }
        Slog.i(TAG, sb.toString());
    }

    /**
     * Calculates and logs min, max, and average
     * {@link HardwarePropertiesManager#DEVICE_TEMPERATURE_SKIN} over the past
     * {@link #TEMPERATURE_LOGGING_INTERVAL}.
     * @param type TEMPERATURE_OVERHEAT_WARNING Send generic notification to notify user
     *             TEMPERATURE_OVERHEAT_ALARM Popup emergency Dialog for user
     */
    private void logTemperatureStats(int type) {
        int numTemp = type == TEMPERATURE_OVERHEAT_ALARM ? mAlarmNumTemps : mWarningNumTemps;
        if (mNextLogTime > System.currentTimeMillis() && numTemp != MAX_RECENT_TEMPS) {
            return;
        }
        float[] recentTemps =
                type == TEMPERATURE_OVERHEAT_ALARM ? mRecentAlarmTemps : mRecentSkinTemps;
        if (numTemp > 0) {
            float sum = recentTemps[0], min = recentTemps[0], max = recentTemps[0];
            for (int i = 1; i < numTemp; i++) {
                float temp = recentTemps[i];
                sum += temp;
                if (temp > max) {
                    max = temp;
                }
                if (temp < min) {
                    min = temp;
                }
            }

            float avg = sum / numTemp;
            Slog.i(TAG, "Type=" + type + ",avg=" + avg + ",min=" + min + ",max=" + max);
            String t = type == TEMPERATURE_OVERHEAT_WARNING ? "skin" : "alarm";
            MetricsLogger.histogram(mContext,
                    String.format(Locale.ENGLISH, "device_%1$s_temp_avg", t), (int) avg);
            MetricsLogger.histogram(mContext,
                    String.format(Locale.ENGLISH, "device_%1$s_temp_min", t), (int) min);
            MetricsLogger.histogram(mContext,
                    String.format(Locale.ENGLISH, "device_%1$s_temp_max", t), (int) max);
        }
        setNextLogTime();
        if (type == TEMPERATURE_OVERHEAT_ALARM) {
            mAlarmNumTemps = 0;
        } else {
            mWarningNumTemps = 0;
        }
    }

    private void setNextLogTime() {
        mNextLogTime = System.currentTimeMillis() + TEMPERATURE_LOGGING_INTERVAL;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("mLowBatteryAlertCloseLevel=");
        pw.println(mLowBatteryAlertCloseLevel);
        pw.print("mLowBatteryReminderLevels=");
        pw.println(Arrays.toString(mLowBatteryReminderLevels));
        pw.print("mBatteryLevel=");
        pw.println(Integer.toString(mBatteryLevel));
        pw.print("mBatteryStatus=");
        pw.println(Integer.toString(mBatteryStatus));
        pw.print("mPlugType=");
        pw.println(Integer.toString(mPlugType));
        pw.print("mInvalidCharger=");
        pw.println(Integer.toString(mInvalidCharger));
        pw.print("mScreenOffTime=");
        pw.print(mScreenOffTime);
        if (mScreenOffTime >= 0) {
            pw.print(" (");
            pw.print(SystemClock.elapsedRealtime() - mScreenOffTime);
            pw.print(" ago)");
        }
        pw.println();
        pw.print("soundTimeout=");
        pw.println(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.LOW_BATTERY_SOUND_TIMEOUT, 0));
        pw.print("bucket: ");
        pw.println(Integer.toString(findBatteryLevelBucket(mBatteryLevel)));
        pw.print("mThresholdWarningTemp=");
        pw.println(Float.toString(mThresholdWarningTemp));
        pw.print("mThresholdAlarmTemp=");
        pw.println(Float.toString(mThresholdAlarmTemp));
        pw.print("mNextLogTime=");
        pw.println(Long.toString(mNextLogTime));
        mWarnings.dump(pw);
    }

    public interface WarningsUI {
        void update(int batteryLevel, int bucket, long screenOffTime);

        void updateEstimate(Estimate estimate);

        void updateThresholds(long lowThreshold, long severeThreshold);

        void dismissLowBatteryWarning();

        void showLowBatteryWarning(boolean playSound);

        void dismissInvalidChargerWarning();

        void showInvalidChargerWarning();

        void updateLowBatteryWarning();

        boolean isInvalidChargerWarningShowing();

        void dismissHighTemperatureWarning();

        void showHighTemperatureWarning();

        /**
         * PowerUI detect thermal overheat, notify to popup alert dialog strongly.
         * Alarm with beep sound with un-dismissible dialog until device cool down below threshold,
         * then popup another dismissible dialog to user.
         *
         * @param overheat whether device temperature over threshold
         * @param beepSound should beep sound once overheat
         */
        void notifyHighTemperatureAlarm(boolean overheat, boolean beepSound);

        void showThermalShutdownWarning();

        void dump(PrintWriter pw);

        void userSwitched();
    }

    // Thermal event received from vendor thermal management subsystem
    private final class ThermalEventListener extends IThermalEventListener.Stub {
        @Override public void notifyThrottling(boolean isThrottling, Temperature temp) {
            // Trigger an update of the temperature warning.  Only one
            // callback can be enabled at a time, so remove any existing
            // callback; updateTemperature will schedule another one.
            mHandler.removeCallbacks(mUpdateTempCallback);
            updateTemperature();
        }
    }
}
