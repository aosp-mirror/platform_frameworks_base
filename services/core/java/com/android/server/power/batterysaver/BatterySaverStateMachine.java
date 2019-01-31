/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.power.batterysaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.BatterySaverPolicyConfig;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.EventLogTags;
import com.android.server.power.BatterySaverStateMachineProto;

import java.io.PrintWriter;

/**
 * Decides when to enable / disable battery saver.
 *
 * IMPORTANT: This class shares the power manager lock, which is very low in the lock hierarchy.
 * Do not call out with the lock held. (Settings provider is okay.)
 *
 * Test: atest com.android.server.power.batterysaver.BatterySaverStateMachineTest
 */
public class BatterySaverStateMachine {
    private static final String TAG = "BatterySaverStateMachine";
    private static final String DYNAMIC_MODE_NOTIF_CHANNEL_ID = "dynamic_mode_notification";
    private static final int DYNAMIC_MODE_NOTIFICATION_ID = 1992;
    private final Object mLock;

    private static final boolean DEBUG = BatterySaverPolicy.DEBUG;

    private static final long ADAPTIVE_CHANGE_TIMEOUT_MS = 24 * 60 * 60 * 1000L;

    private final Context mContext;
    private final BatterySaverController mBatterySaverController;

    /** Whether the system has booted. */
    @GuardedBy("mLock")
    private boolean mBootCompleted;

    /** Whether global settings have been loaded already. */
    @GuardedBy("mLock")
    private boolean mSettingsLoaded;

    /** Whether the first battery status has arrived. */
    @GuardedBy("mLock")
    private boolean mBatteryStatusSet;

    /** Whether the device is connected to any power source. */
    @GuardedBy("mLock")
    private boolean mIsPowered;

    /** Current battery level in %, 0-100. (Currently only used in dumpsys.) */
    @GuardedBy("mLock")
    private int mBatteryLevel;

    /** Whether the battery level is considered to be "low" or not. */
    @GuardedBy("mLock")
    private boolean mIsBatteryLevelLow;

    /** Previously known value of Settings.Global.LOW_POWER_MODE. */
    @GuardedBy("mLock")
    private boolean mSettingBatterySaverEnabled;

    /** Previously known value of Settings.Global.LOW_POWER_MODE_STICKY. */
    @GuardedBy("mLock")
    private boolean mSettingBatterySaverEnabledSticky;

    /** Config flag to track if battery saver's sticky behaviour is disabled. */
    private final boolean mBatterySaverStickyBehaviourDisabled;

    /**
     * Whether or not to end sticky battery saver upon reaching a level specified by
     * {@link #mSettingBatterySaverStickyAutoDisableThreshold}.
     */
    @GuardedBy("mLock")
    private boolean mSettingBatterySaverStickyAutoDisableEnabled;

    /**
     * The battery level at which to end sticky battery saver. Only useful if
     * {@link #mSettingBatterySaverStickyAutoDisableEnabled} is {@code true}.
     */
    @GuardedBy("mLock")
    private int mSettingBatterySaverStickyAutoDisableThreshold;

    /** Config flag to track default disable threshold for Dynamic Power Savings enabled battery
     * saver. */
    @GuardedBy("mLock")
    private final int mDynamicPowerSavingsDefaultDisableThreshold;

    /**
     * Previously known value of Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL.
     * (Currently only used in dumpsys.)
     */
    @GuardedBy("mLock")
    private int mSettingBatterySaverTriggerThreshold;

    /** Previously known value of Settings.Global.AUTOMATIC_POWER_SAVER_MODE. */
    @GuardedBy("mLock")
    private int mSettingAutomaticBatterySaver;

    /** When to disable battery saver again if it was enabled due to an external suggestion.
     *  Corresponds to Settings.Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD.
     */
    @GuardedBy("mLock")
    private int mDynamicPowerSavingsDisableThreshold;

    /**
     * Whether we've received a suggestion that battery saver should be on from an external app.
     * Updates when Settings.Global.DYNAMIC_POWER_SAVINGS_ENABLED changes.
     */
    @GuardedBy("mLock")
    private boolean mDynamicPowerSavingsBatterySaver;

    /**
     * Whether BS has been manually disabled while the battery level is low, in which case we
     * shouldn't auto re-enable it until the battery level is not low.
     */
    @GuardedBy("mLock")
    private boolean mBatterySaverSnoozing;

    /**
     * Last reason passed to {@link #enableBatterySaverLocked}.
     */
    @GuardedBy("mLock")
    private int mLastChangedIntReason;

    /**
     * Last reason passed to {@link #enableBatterySaverLocked}.
     */
    @GuardedBy("mLock")
    private String mLastChangedStrReason;

    /**
     * The last time adaptive battery saver was changed by an external service, using elapsed
     * realtime as the timebase.
     */
    @GuardedBy("mLock")
    private long mLastAdaptiveBatterySaverChangedExternallyElapsed;

    private final ContentObserver mSettingsObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            synchronized (mLock) {
                refreshSettingsLocked();
            }
        }
    };

    public BatterySaverStateMachine(Object lock,
            Context context, BatterySaverController batterySaverController) {
        mLock = lock;
        mContext = context;
        mBatterySaverController = batterySaverController;

        mBatterySaverStickyBehaviourDisabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_batterySaverStickyBehaviourDisabled);
        mDynamicPowerSavingsDefaultDisableThreshold = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_dynamicPowerSavingsDefaultDisableThreshold);
    }

    private boolean isAutoBatterySaverConfiguredLocked() {
        return mSettingBatterySaverTriggerThreshold > 0;
    }

    /**
     * {@link com.android.server.power.PowerManagerService} calls it when the system is booted.
     */
    public void onBootCompleted() {
        if (DEBUG) {
            Slog.d(TAG, "onBootCompleted");
        }
        // Just booted. We don't want LOW_POWER_MODE to be persisted, so just always clear it.
        putGlobalSetting(Settings.Global.LOW_POWER_MODE, 0);

        // This is called with the power manager lock held. Don't do anything that may call to
        // upper services. (e.g. don't call into AM directly)
        // So use a BG thread.
        runOnBgThread(() -> {

            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_MODE),
                    false, mSettingsObserver, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_MODE_STICKY),
                    false, mSettingsObserver, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL),
                    false, mSettingsObserver, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.AUTOMATIC_POWER_SAVER_MODE),
                    false, mSettingsObserver, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.DYNAMIC_POWER_SAVINGS_ENABLED),
                    false, mSettingsObserver, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD),
                    false, mSettingsObserver, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED),
                    false, mSettingsObserver, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL),
                    false, mSettingsObserver, UserHandle.USER_SYSTEM);

            synchronized (mLock) {

                mBootCompleted = true;

                refreshSettingsLocked();

                doAutoBatterySaverLocked();
            }
        });
    }

    /**
     * Run a {@link Runnable} on a background handler.
     */
    @VisibleForTesting
    void runOnBgThread(Runnable r) {
        BackgroundThread.getHandler().post(r);
    }

    /**
     * Run a {@link Runnable} on a background handler, but lazily. If the same {@link Runnable} is
     * already registered, it'll be first removed before being re-posted.
     */
    @VisibleForTesting
    void runOnBgThreadLazy(Runnable r, int delayMillis) {
        final Handler h = BackgroundThread.getHandler();
        h.removeCallbacks(r);
        h.postDelayed(r, delayMillis);
    }

    @GuardedBy("mLock")
    private void refreshSettingsLocked() {
        final boolean lowPowerModeEnabled = getGlobalSetting(
                Settings.Global.LOW_POWER_MODE, 0) != 0;
        final boolean lowPowerModeEnabledSticky = getGlobalSetting(
                Settings.Global.LOW_POWER_MODE_STICKY, 0) != 0;
        final boolean dynamicPowerSavingsBatterySaver = getGlobalSetting(
                Settings.Global.DYNAMIC_POWER_SAVINGS_ENABLED, 0) != 0;
        final int lowPowerModeTriggerLevel = getGlobalSetting(
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
        final int automaticBatterySaverMode = getGlobalSetting(
                Settings.Global.AUTOMATIC_POWER_SAVER_MODE,
                PowerManager.POWER_SAVER_MODE_PERCENTAGE);
        final int dynamicPowerSavingsDisableThreshold = getGlobalSetting(
                Settings.Global.DYNAMIC_POWER_SAVINGS_DISABLE_THRESHOLD,
                mDynamicPowerSavingsDefaultDisableThreshold);
        final boolean isStickyAutoDisableEnabled = getGlobalSetting(
                Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_ENABLED, 0) != 0;
        final int stickyAutoDisableThreshold = getGlobalSetting(
                Settings.Global.LOW_POWER_MODE_STICKY_AUTO_DISABLE_LEVEL, 90);

        setSettingsLocked(lowPowerModeEnabled, lowPowerModeEnabledSticky,
                lowPowerModeTriggerLevel,
                isStickyAutoDisableEnabled, stickyAutoDisableThreshold,
                automaticBatterySaverMode,
                dynamicPowerSavingsBatterySaver, dynamicPowerSavingsDisableThreshold);
    }

    /**
     * {@link com.android.server.power.PowerManagerService} calls it when relevant global settings
     * have changed.
     *
     * Note this will be called before {@link #onBootCompleted} too.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    void setSettingsLocked(boolean batterySaverEnabled, boolean batterySaverEnabledSticky,
            int batterySaverTriggerThreshold,
            boolean isStickyAutoDisableEnabled, int stickyAutoDisableThreshold,
            int automaticBatterySaver,
            boolean dynamicPowerSavingsBatterySaver, int dynamicPowerSavingsDisableThreshold) {
        if (DEBUG) {
            Slog.d(TAG, "setSettings: enabled=" + batterySaverEnabled
                    + " sticky=" + batterySaverEnabledSticky
                    + " threshold=" + batterySaverTriggerThreshold
                    + " stickyAutoDisableEnabled=" + isStickyAutoDisableEnabled
                    + " stickyAutoDisableThreshold=" + stickyAutoDisableThreshold
                    + " automaticBatterySaver=" + automaticBatterySaver
                    + " dynamicPowerSavingsBatterySaver=" + dynamicPowerSavingsBatterySaver
                    + " dynamicPowerSavingsDisableThreshold="
                    + dynamicPowerSavingsDisableThreshold);
        }

        mSettingsLoaded = true;

        // Set sensible limits.
        stickyAutoDisableThreshold = Math.max(stickyAutoDisableThreshold,
                batterySaverTriggerThreshold);

        final boolean enabledChanged = mSettingBatterySaverEnabled != batterySaverEnabled;
        final boolean stickyChanged =
                mSettingBatterySaverEnabledSticky != batterySaverEnabledSticky;
        final boolean thresholdChanged
                = mSettingBatterySaverTriggerThreshold != batterySaverTriggerThreshold;
        final boolean stickyAutoDisableEnabledChanged =
                mSettingBatterySaverStickyAutoDisableEnabled != isStickyAutoDisableEnabled;
        final boolean stickyAutoDisableThresholdChanged =
                mSettingBatterySaverStickyAutoDisableThreshold != stickyAutoDisableThreshold;
        final boolean automaticModeChanged = mSettingAutomaticBatterySaver != automaticBatterySaver;
        final boolean dynamicPowerSavingsThresholdChanged =
                mDynamicPowerSavingsDisableThreshold != dynamicPowerSavingsDisableThreshold;
        final boolean dynamicPowerSavingsBatterySaverChanged =
                mDynamicPowerSavingsBatterySaver != dynamicPowerSavingsBatterySaver;

        if (!(enabledChanged || stickyChanged || thresholdChanged || automaticModeChanged
                || stickyAutoDisableEnabledChanged || stickyAutoDisableThresholdChanged
                || dynamicPowerSavingsThresholdChanged || dynamicPowerSavingsBatterySaverChanged)) {
            return;
        }

        mSettingBatterySaverEnabled = batterySaverEnabled;
        mSettingBatterySaverEnabledSticky = batterySaverEnabledSticky;
        mSettingBatterySaverTriggerThreshold = batterySaverTriggerThreshold;
        mSettingBatterySaverStickyAutoDisableEnabled = isStickyAutoDisableEnabled;
        mSettingBatterySaverStickyAutoDisableThreshold = stickyAutoDisableThreshold;
        mSettingAutomaticBatterySaver = automaticBatterySaver;
        mDynamicPowerSavingsDisableThreshold = dynamicPowerSavingsDisableThreshold;
        mDynamicPowerSavingsBatterySaver = dynamicPowerSavingsBatterySaver;

        if (thresholdChanged) {
            // To avoid spamming the event log, we throttle logging here.
            runOnBgThreadLazy(mThresholdChangeLogger, 2000);
        }

        if (enabledChanged) {
            final String reason = batterySaverEnabled
                    ? "Global.low_power changed to 1" : "Global.low_power changed to 0";
            enableBatterySaverLocked(/*enable=*/ batterySaverEnabled, /*manual=*/ true,
                    BatterySaverController.REASON_SETTING_CHANGED, reason);
        }
    }

    private final Runnable mThresholdChangeLogger = () -> {
        EventLogTags.writeBatterySaverSetting(mSettingBatterySaverTriggerThreshold);
    };

    /**
     * {@link com.android.server.power.PowerManagerService} calls it when battery state changes.
     *
     * Note this may be called before {@link #onBootCompleted} too.
     */
    public void setBatteryStatus(boolean newPowered, int newLevel, boolean newBatteryLevelLow) {
        if (DEBUG) {
            Slog.d(TAG, "setBatteryStatus: powered=" + newPowered + " level=" + newLevel
                    + " low=" + newBatteryLevelLow);
        }
        synchronized (mLock) {
            mBatteryStatusSet = true;

            final boolean poweredChanged = mIsPowered != newPowered;
            final boolean levelChanged = mBatteryLevel != newLevel;
            final boolean lowChanged = mIsBatteryLevelLow != newBatteryLevelLow;

            if (!(poweredChanged || levelChanged || lowChanged)) {
                return;
            }

            mIsPowered = newPowered;
            mBatteryLevel = newLevel;
            mIsBatteryLevelLow = newBatteryLevelLow;

            doAutoBatterySaverLocked();
        }
    }

    /**
     * Enable or disable the current adaptive battery saver policy. This may not change what's in
     * effect if full battery saver is also enabled.
     */
    public boolean setAdaptiveBatterySaverEnabled(boolean enabled) {
        if (DEBUG) {
            Slog.d(TAG, "setAdaptiveBatterySaverEnabled: enabled=" + enabled);
        }
        synchronized (mLock) {
            mLastAdaptiveBatterySaverChangedExternallyElapsed = SystemClock.elapsedRealtime();
            return mBatterySaverController.setAdaptivePolicyEnabledLocked(
                    enabled, BatterySaverController.REASON_ADAPTIVE_DYNAMIC_POWER_SAVINGS_CHANGED);
        }
    }

    /**
     * Change the adaptive battery saver policy.
     */
    public boolean setAdaptiveBatterySaverPolicy(BatterySaverPolicyConfig config) {
        if (DEBUG) {
            Slog.d(TAG, "setAdaptiveBatterySaverPolicy: config=" + config);
        }

        synchronized (mLock) {
            mLastAdaptiveBatterySaverChangedExternallyElapsed = SystemClock.elapsedRealtime();
            return mBatterySaverController.setAdaptivePolicyLocked(config,
                    BatterySaverController.REASON_ADAPTIVE_DYNAMIC_POWER_SAVINGS_CHANGED);
        }
    }

    @GuardedBy("mLock")
    private boolean isBatteryLowLocked() {
        final boolean percentageLow =
                mSettingAutomaticBatterySaver == PowerManager.POWER_SAVER_MODE_PERCENTAGE
                && mIsBatteryLevelLow;
        final boolean dynamicPowerSavingsLow =
                mSettingAutomaticBatterySaver == PowerManager.POWER_SAVER_MODE_DYNAMIC
                && mBatteryLevel <= mDynamicPowerSavingsDisableThreshold;
        return percentageLow || dynamicPowerSavingsLow;
    }

    /**
     * Decide whether to auto-start / stop battery saver.
     */
    @GuardedBy("mLock")
    private void doAutoBatterySaverLocked() {
        if (DEBUG) {
            Slog.d(TAG, "doAutoBatterySaverLocked: mBootCompleted=" + mBootCompleted
                    + " mSettingsLoaded=" + mSettingsLoaded
                    + " mBatteryStatusSet=" + mBatteryStatusSet
                    + " mIsBatteryLevelLow=" + mIsBatteryLevelLow
                    + " mBatterySaverSnoozing=" + mBatterySaverSnoozing
                    + " mIsPowered=" + mIsPowered
                    + " mSettingAutomaticBatterySaver=" + mSettingAutomaticBatterySaver
                    + " mSettingBatterySaverEnabledSticky=" + mSettingBatterySaverEnabledSticky
                    + " mSettingBatterySaverStickyAutoDisableEnabled="
                    + mSettingBatterySaverStickyAutoDisableEnabled);
        }
        if (!(mBootCompleted && mSettingsLoaded && mBatteryStatusSet)) {
            return; // Not fully initialized yet.
        }

        if (!isBatteryLowLocked()) {
            updateSnoozingLocked(false, "Battery not low");
        }

        if (SystemClock.elapsedRealtime() - mLastAdaptiveBatterySaverChangedExternallyElapsed
                > ADAPTIVE_CHANGE_TIMEOUT_MS) {
            mBatterySaverController.setAdaptivePolicyEnabledLocked(
                    false, BatterySaverController.REASON_TIMEOUT);
            mBatterySaverController.resetAdaptivePolicyLocked(
                    BatterySaverController.REASON_TIMEOUT);
        }

        if (mIsPowered) {
            updateSnoozingLocked(false, "Plugged in");
            enableBatterySaverLocked(/*enable=*/ false, /*manual=*/ false,
                    BatterySaverController.REASON_PLUGGED_IN,
                    "Plugged in");

            if (mBatteryLevel >= 80 /* Arbitrary level */) {
                mBatterySaverController.setAdaptivePolicyEnabledLocked(
                        false, BatterySaverController.REASON_PLUGGED_IN);
            }

        } else if (mSettingBatterySaverEnabledSticky && !mBatterySaverStickyBehaviourDisabled) {
            if (mSettingBatterySaverStickyAutoDisableEnabled
                    && mBatteryLevel >= mSettingBatterySaverStickyAutoDisableThreshold) {
                setStickyActive(false);
            } else {
                // Re-enable BS.
                enableBatterySaverLocked(/*enable=*/ true, /*manual=*/ true,
                        BatterySaverController.REASON_STICKY_RESTORE,
                        "Sticky restore");
            }

        } else if (mSettingAutomaticBatterySaver
                == PowerManager.POWER_SAVER_MODE_PERCENTAGE
                && isAutoBatterySaverConfiguredLocked()) {
            if (mIsBatteryLevelLow && !mBatterySaverSnoozing) {
                enableBatterySaverLocked(/*enable=*/ true, /*manual=*/ false,
                        BatterySaverController.REASON_PERCENTAGE_AUTOMATIC_ON,
                        "Percentage Auto ON");
            } else {
                // Battery not low
                enableBatterySaverLocked(/*enable=*/ false, /*manual=*/ false,
                        BatterySaverController.REASON_PERCENTAGE_AUTOMATIC_OFF,
                        "Percentage Auto OFF");
            }
        } else if (mSettingAutomaticBatterySaver
                == PowerManager.POWER_SAVER_MODE_DYNAMIC) {
            if (mBatteryLevel >= mDynamicPowerSavingsDisableThreshold) {
                enableBatterySaverLocked(/*enable=*/ false, /*manual=*/ false,
                        BatterySaverController.REASON_DYNAMIC_POWER_SAVINGS_AUTOMATIC_OFF,
                        "Dynamic Warning Auto OFF");
            } else if (mDynamicPowerSavingsBatterySaver && !mBatterySaverSnoozing) {
                enableBatterySaverLocked(/*enable=*/ true, /*manual=*/ false,
                        BatterySaverController.REASON_DYNAMIC_POWER_SAVINGS_AUTOMATIC_ON,
                        "Dynamic Warning Auto ON");
            }
        }
        // do nothing if automatic battery saver mode = PERCENTAGE and low warning threshold = 0%
    }

    /**
     * {@link com.android.server.power.PowerManagerService} calls it when
     * {@link android.os.PowerManager#setPowerSaveMode} is called.
     *
     * Note this could? be called before {@link #onBootCompleted} too.
     */
    public void setBatterySaverEnabledManually(boolean enabled) {
        if (DEBUG) {
            Slog.d(TAG, "setBatterySaverEnabledManually: enabled=" + enabled);
        }
        synchronized (mLock) {
            enableBatterySaverLocked(/*enable=*/ enabled, /*manual=*/ true,
                    (enabled ? BatterySaverController.REASON_MANUAL_ON
                            : BatterySaverController.REASON_MANUAL_OFF),
                    (enabled ? "Manual ON" : "Manual OFF"));
        }
    }

    /**
     * Actually enable / disable battery saver. Write the new state to the global settings
     * and propagate it to {@link #mBatterySaverController}.
     */
    @GuardedBy("mLock")
    private void enableBatterySaverLocked(boolean enable, boolean manual, int intReason,
            String strReason) {
        if (DEBUG) {
            Slog.d(TAG, "enableBatterySaver: enable=" + enable + " manual=" + manual
                    + " reason=" + strReason + "(" + intReason + ")");
        }
        final boolean wasEnabled = mBatterySaverController.isFullEnabled();

        if (wasEnabled == enable) {
            if (DEBUG) {
                Slog.d(TAG, "Already " + (enable ? "enabled" : "disabled"));
            }
            return;
        }
        if (enable && mIsPowered) {
            if (DEBUG) Slog.d(TAG, "Can't enable: isPowered");
            return;
        }
        mLastChangedIntReason = intReason;
        mLastChangedStrReason = strReason;

        if (manual) {
            if (enable) {
                updateSnoozingLocked(false, "Manual snooze OFF");
            } else {
                // When battery saver is disabled manually (while battery saver is enabled)
                // when the battery level is low, we "snooze" BS -- i.e. disable auto battery saver.
                // We resume auto-BS once the battery level is not low, or the device is plugged in.
                if (mBatterySaverController.isFullEnabled() && isBatteryLowLocked()) {
                    updateSnoozingLocked(true, "Manual snooze");
                }
                // TODO: maybe turn off adaptive if it's on and advertiseIsEnabled is true
            }
        }

        mSettingBatterySaverEnabled = enable;
        putGlobalSetting(Settings.Global.LOW_POWER_MODE, enable ? 1 : 0);

        if (manual) {
            setStickyActive(!mBatterySaverStickyBehaviourDisabled && enable);
        }
        mBatterySaverController.enableBatterySaver(enable, intReason);

        // Handle triggering the notification to show/hide when appropriate
        if (intReason == BatterySaverController.REASON_DYNAMIC_POWER_SAVINGS_AUTOMATIC_ON) {
            runOnBgThread(this::triggerDynamicModeNotification);
        } else if (!enable) {
            runOnBgThread(this::hideDynamicModeNotification);
        }

        if (DEBUG) {
            Slog.d(TAG, "Battery saver: Enabled=" + enable
                    + " manual=" + manual
                    + " reason=" + strReason + "(" + intReason + ")");
        }
    }

    @VisibleForTesting
    void triggerDynamicModeNotification() {
        NotificationManager manager = mContext.getSystemService(NotificationManager.class);
        ensureNotificationChannelExists(manager);

        manager.notify(DYNAMIC_MODE_NOTIFICATION_ID, buildNotification());
    }

    private void ensureNotificationChannelExists(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(
                DYNAMIC_MODE_NOTIF_CHANNEL_ID,
                mContext.getText(
                        R.string.dynamic_mode_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Resources res = mContext.getResources();
        Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent batterySaverIntent = PendingIntent.getActivity(
                mContext, 0 /* requestCode */, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(mContext, DYNAMIC_MODE_NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_battery)
                .setContentTitle(res.getString(R.string.dynamic_mode_notification_title))
                .setContentText(res.getString(R.string.dynamic_mode_notification_summary))
                .setContentIntent(batterySaverIntent)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void hideDynamicModeNotification() {
        NotificationManager manager = mContext.getSystemService(NotificationManager.class);
        manager.cancel(DYNAMIC_MODE_NOTIFICATION_ID);
    }

    @GuardedBy("mLock")
    private void updateSnoozingLocked(boolean snoozing, String reason) {
        if (mBatterySaverSnoozing == snoozing) {
            return;
        }
        if (DEBUG) Slog.d(TAG, "Snooze: " + (snoozing ? "start" : "stop")  + " reason=" + reason);
        mBatterySaverSnoozing = snoozing;
    }

    private void setStickyActive(boolean active) {
        mSettingBatterySaverEnabledSticky = active;
        putGlobalSetting(Settings.Global.LOW_POWER_MODE_STICKY,
                mSettingBatterySaverEnabledSticky ? 1 : 0);
    }

    @VisibleForTesting
    protected void putGlobalSetting(String key, int value) {
        Settings.Global.putInt(mContext.getContentResolver(), key, value);
    }

    @VisibleForTesting
    protected int getGlobalSetting(String key, int defValue) {
        return Settings.Global.getInt(mContext.getContentResolver(), key, defValue);
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Battery saver state machine:");

            pw.print("  Enabled=");
            pw.println(mBatterySaverController.isEnabled());
            pw.print("    full=");
            pw.println(mBatterySaverController.isFullEnabled());
            pw.print("    adaptive=");
            pw.print(mBatterySaverController.isAdaptiveEnabled());
            if (mBatterySaverController.isAdaptiveEnabled()) {
                pw.print(" (advertise=");
                pw.print(
                        mBatterySaverController.getBatterySaverPolicy().shouldAdvertiseIsEnabled());
                pw.print(")");
            }
            pw.println();

            pw.print("  mLastChangedIntReason=");
            pw.println(mLastChangedIntReason);
            pw.print("  mLastChangedStrReason=");
            pw.println(mLastChangedStrReason);

            pw.print("  mBootCompleted=");
            pw.println(mBootCompleted);
            pw.print("  mSettingsLoaded=");
            pw.println(mSettingsLoaded);
            pw.print("  mBatteryStatusSet=");
            pw.println(mBatteryStatusSet);

            pw.print("  mBatterySaverSnoozing=");
            pw.println(mBatterySaverSnoozing);

            pw.print("  mIsPowered=");
            pw.println(mIsPowered);
            pw.print("  mBatteryLevel=");
            pw.println(mBatteryLevel);
            pw.print("  mIsBatteryLevelLow=");
            pw.println(mIsBatteryLevelLow);

            pw.print("  mSettingBatterySaverEnabled=");
            pw.println(mSettingBatterySaverEnabled);
            pw.print("  mSettingBatterySaverEnabledSticky=");
            pw.println(mSettingBatterySaverEnabledSticky);
            pw.print("  mSettingBatterySaverStickyAutoDisableEnabled=");
            pw.println(mSettingBatterySaverStickyAutoDisableEnabled);
            pw.print("  mSettingBatterySaverStickyAutoDisableThreshold=");
            pw.println(mSettingBatterySaverStickyAutoDisableThreshold);
            pw.print("  mSettingBatterySaverTriggerThreshold=");
            pw.println(mSettingBatterySaverTriggerThreshold);
            pw.print("  mBatterySaverStickyBehaviourDisabled=");
            pw.println(mBatterySaverStickyBehaviourDisabled);

            pw.print("  mLastAdaptiveBatterySaverChangedExternallyElapsed=");
            pw.println(mLastAdaptiveBatterySaverChangedExternallyElapsed);
        }
    }

    public void dumpProto(ProtoOutputStream proto, long tag) {
        synchronized (mLock) {
            final long token = proto.start(tag);

            proto.write(BatterySaverStateMachineProto.ENABLED,
                    mBatterySaverController.isEnabled());
            proto.write(BatterySaverStateMachineProto.IS_FULL_ENABLED,
                    mBatterySaverController.isFullEnabled());
            proto.write(BatterySaverStateMachineProto.IS_ADAPTIVE_ENABLED,
                    mBatterySaverController.isAdaptiveEnabled());
            proto.write(BatterySaverStateMachineProto.SHOULD_ADVERTISE_IS_ENABLED,
                    mBatterySaverController.getBatterySaverPolicy().shouldAdvertiseIsEnabled());

            proto.write(BatterySaverStateMachineProto.BOOT_COMPLETED, mBootCompleted);
            proto.write(BatterySaverStateMachineProto.SETTINGS_LOADED, mSettingsLoaded);
            proto.write(BatterySaverStateMachineProto.BATTERY_STATUS_SET, mBatteryStatusSet);

            proto.write(BatterySaverStateMachineProto.BATTERY_SAVER_SNOOZING,
                    mBatterySaverSnoozing);

            proto.write(BatterySaverStateMachineProto.IS_POWERED, mIsPowered);
            proto.write(BatterySaverStateMachineProto.BATTERY_LEVEL, mBatteryLevel);
            proto.write(BatterySaverStateMachineProto.IS_BATTERY_LEVEL_LOW, mIsBatteryLevelLow);

            proto.write(BatterySaverStateMachineProto.SETTING_BATTERY_SAVER_ENABLED,
                    mSettingBatterySaverEnabled);
            proto.write(BatterySaverStateMachineProto.SETTING_BATTERY_SAVER_ENABLED_STICKY,
                    mSettingBatterySaverEnabledSticky);
            proto.write(BatterySaverStateMachineProto.SETTING_BATTERY_SAVER_TRIGGER_THRESHOLD,
                    mSettingBatterySaverTriggerThreshold);
            proto.write(
                    BatterySaverStateMachineProto.SETTING_BATTERY_SAVER_STICKY_AUTO_DISABLE_ENABLED,
                    mSettingBatterySaverStickyAutoDisableEnabled);
            proto.write(
                    BatterySaverStateMachineProto
                            .SETTING_BATTERY_SAVER_STICKY_AUTO_DISABLE_THRESHOLD,
                    mSettingBatterySaverStickyAutoDisableThreshold);

            proto.write(
                    BatterySaverStateMachineProto
                            .LAST_ADAPTIVE_BATTERY_SAVER_CHANGED_EXTERNALLY_ELAPSED,
                    mLastAdaptiveBatterySaverChangedExternallyElapsed);

            proto.end(token);
        }
    }
}
