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
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.phone.StatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Future;

public class PowerUI extends SystemUI {
    static final String TAG = "PowerUI";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long TEMPERATURE_INTERVAL = 30 * DateUtils.SECOND_IN_MILLIS;
    private static final long TEMPERATURE_LOGGING_INTERVAL = DateUtils.HOUR_IN_MILLIS;
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
    private Future mLastShowWarningTask;
    private boolean mEnableSkinTemperatureWarning;
    private boolean mEnableUsbTemperatureAlarm;

    private int mLowBatteryAlertCloseLevel;
    private final int[] mLowBatteryReminderLevels = new int[2];

    private long mScreenOffTime = -1;

    @VisibleForTesting IThermalService mThermalService;

    @VisibleForTesting int mBatteryLevel = 100;
    @VisibleForTesting int mBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;

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
                if (mLastShowWarningTask != null) {
                    mLastShowWarningTask.cancel(true);
                }
                mLastShowWarningTask = ThreadUtils.postOnBackgroundThread(() -> {
                    maybeShowBatteryWarning(
                            oldBatteryLevel, plugged, oldPlugged, oldBucket, bucket);
                });

            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOffTime = SystemClock.elapsedRealtime();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mScreenOffTime = -1;
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mWarnings.userSwitched();
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
            // Turbo is not always booted once SysUI is running so we have to make sure we actually
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
                if (mTimeRemaining <= mEnhancedEstimates.getSevereWarningThreshold()
                        || mBatteryLevel <= mLowBatteryReminderLevels[1]) {
                    mSevereWarningShownThisChargeCycle = true;
                    mLowWarningShownThisChargeCycle = true;
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

        final boolean canShow = canShowWarning || canShowSevereWarning;
        if (DEBUG) {
            Slog.d(TAG, "Enhanced trigger is: " + canShow + "\nwith values: "
                    + " mLowWarningShownThisChargeCycle: " + mLowWarningShownThisChargeCycle
                    + " mSevereWarningShownThisChargeCycle: " + mSevereWarningShownThisChargeCycle
                    + " mEnhancedEstimates.timeremaining: " + timeRemaining
                    + " mBatteryLevel: " + mBatteryLevel
                    + " canShowWarning: " + canShowWarning
                    + " canShowSevereWarning: " + canShowSevereWarning
                    + " plugged: " + plugged
                    + " batteryStatus: " + batteryStatus
                    + " isPowerSaver: " + isPowerSaver);
        }
        return canShowWarning || canShowSevereWarning;
    }

    private void initTemperature() {
        ContentResolver resolver = mContext.getContentResolver();
        Resources resources = mContext.getResources();

        mEnableSkinTemperatureWarning = Settings.Global.getInt(resolver,
                Settings.Global.SHOW_TEMPERATURE_WARNING,
                resources.getInteger(R.integer.config_showTemperatureWarning)) != 0;
        mEnableUsbTemperatureAlarm = Settings.Global.getInt(resolver,
                Settings.Global.SHOW_USB_TEMPERATURE_ALARM,
                resources.getInteger(R.integer.config_showUsbPortAlarm)) != 0;

        if (mThermalService == null) {
            // Enable push notifications of throttling from vendor thermal
            // management subsystem via thermalservice, in addition to our
            // usual polling, to react to temperature jumps more quickly.
            IBinder b = ServiceManager.getService(Context.THERMAL_SERVICE);

            if (b != null) {
                mThermalService = IThermalService.Stub.asInterface(b);
                registerThermalEventListener();
            } else {
                Slog.w(TAG, "cannot find thermalservice, no throttling push notifications");
            }
        }
    }

    @VisibleForTesting
    void registerThermalEventListener() {
        try {
            if (mEnableSkinTemperatureWarning) {
                mThermalService.registerThermalEventListenerWithType(
                        new ThermalEventSkinListener(), Temperature.TYPE_SKIN);
            }
            if (mEnableUsbTemperatureAlarm) {
                mThermalService.registerThermalEventListenerWithType(
                        new ThermalEventUsbListener(), Temperature.TYPE_USB_PORT);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register thermal callback.", e);
        }
    }

    private void showThermalShutdownDialog() {
        if (mPowerManager.getLastShutdownReason()
                == PowerManager.SHUTDOWN_REASON_THERMAL_SHUTDOWN) {
            mWarnings.showThermalShutdownWarning();
        }
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
        pw.print("mEnableSkinTemperatureWarning=");
        pw.println(mEnableSkinTemperatureWarning);
        pw.print("mEnableUsbTemperatureAlarm=");
        pw.println(mEnableUsbTemperatureAlarm);
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
         * Display USB overheat alarm
         */
        void showUsbHighTemperatureAlarm();

        void showThermalShutdownWarning();

        void dump(PrintWriter pw);

        void userSwitched();
    }

    // Thermal event received from thermal service manager subsystem
    @VisibleForTesting
    final class ThermalEventSkinListener extends IThermalEventListener.Stub {
        @Override public void notifyThrottling(Temperature temp) {
            int status = temp.getStatus();

            if (status >= Temperature.THROTTLING_EMERGENCY) {
                StatusBar statusBar = getComponent(StatusBar.class);
                if (statusBar != null && !statusBar.isDeviceInVrMode()) {
                    mWarnings.showHighTemperatureWarning();
                    Slog.d(TAG, "ThermalEventSkinListener: notifyThrottling was called "
                            + ", current skin status = " + status
                            + ", temperature = " + temp.getValue());
                }
            } else {
                mWarnings.dismissHighTemperatureWarning();
            }
        }
    }

    // Thermal event received from thermal service manager subsystem
    @VisibleForTesting
    final class ThermalEventUsbListener extends IThermalEventListener.Stub {
        @Override public void notifyThrottling(Temperature temp) {
            int status = temp.getStatus();

            if (status >= Temperature.THROTTLING_EMERGENCY) {
                mWarnings.showUsbHighTemperatureAlarm();
                Slog.d(TAG, "ThermalEventUsbListener: notifyThrottling was called "
                        + ", current usb port status = " + status
                        + ", temperature = " + temp.getValue());
            }
        }
    }
}
