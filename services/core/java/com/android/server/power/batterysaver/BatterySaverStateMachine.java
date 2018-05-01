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

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.EventLogTags;
import com.android.server.power.BatterySaverPolicy;
import com.android.server.power.BatterySaverStateMachineProto;

import java.io.PrintWriter;

/**
 * Decides when to enable / disable battery saver.
 *
 * (n.b. This isn't really implemented as a "state machine" though.)
 *
 * Test:
  atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/BatterySaverStateMachineTest.java
 */
public class BatterySaverStateMachine {
    private static final String TAG = "BatterySaverStateMachine";
    private final Object mLock = new Object();

    private static final boolean DEBUG = BatterySaverPolicy.DEBUG;

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

    /** Whether the battery level is considered to be "low" or not.*/
    @GuardedBy("mLock")
    private boolean mIsBatteryLevelLow;

    /** Previously known value of Global.LOW_POWER_MODE. */
    @GuardedBy("mLock")
    private boolean mSettingBatterySaverEnabled;

    /** Previously known value of Global.LOW_POWER_MODE_STICKY. */
    @GuardedBy("mLock")
    private boolean mSettingBatterySaverEnabledSticky;

    /**
     * Previously known value of Global.LOW_POWER_MODE_TRIGGER_LEVEL.
     * (Currently only used in dumpsys.)
     */
    @GuardedBy("mLock")
    private int mSettingBatterySaverTriggerThreshold;

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

    private final ContentObserver mSettingsObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            synchronized (mLock) {
                refreshSettingsLocked();
            }
        }
    };

    public BatterySaverStateMachine(
            Context context, BatterySaverController batterySaverController) {
        mContext = context;
        mBatterySaverController = batterySaverController;
    }

    private boolean isBatterySaverEnabled() {
        return mBatterySaverController.isEnabled();
    }

    private boolean isAutoBatterySaverConfigured() {
        return mSettingBatterySaverTriggerThreshold > 0;
    }

    /**
     * {@link com.android.server.power.PowerManagerService} calls it when the system is booted.
     */
    public void onBootCompleted() {
        if (DEBUG) {
            Slog.d(TAG, "onBootCompleted");
        }
        // This is called with the power manager lock held. Don't do any
        runOnBgThread(() -> {
            synchronized (mLock) {

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
     * Run a {@link Runnable} on a background handler, but lazily. If the same {@link Runnable},
     * it'll be first removed before a new one is posted.
     */
    @VisibleForTesting
    void runOnBgThreadLazy(Runnable r, int delayMillis) {
        final Handler h = BackgroundThread.getHandler();
        h.removeCallbacks(r);
        h.postDelayed(r, delayMillis);
    }

    void refreshSettingsLocked() {
        final ContentResolver cr = mContext.getContentResolver();

        final boolean lowPowerModeEnabled = getGlobalSetting(
                Settings.Global.LOW_POWER_MODE, 0) != 0;
        final boolean lowPowerModeEnabledSticky = getGlobalSetting(
                Settings.Global.LOW_POWER_MODE_STICKY, 0) != 0;
        final int lowPowerModeTriggerLevel = getGlobalSetting(
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);

        setSettingsLocked(lowPowerModeEnabled, lowPowerModeEnabledSticky,
                lowPowerModeTriggerLevel);
    }

    /**
     * {@link com.android.server.power.PowerManagerService} calls it when relevant global settings
     * have changed.
     *
     * Note this will be called before {@link #onBootCompleted} too.
     */
    @VisibleForTesting
    void setSettingsLocked(boolean batterySaverEnabled, boolean batterySaverEnabledSticky,
            int batterySaverTriggerThreshold) {
        if (DEBUG) {
            Slog.d(TAG, "setSettings: enabled=" + batterySaverEnabled
                    + " sticky=" + batterySaverEnabledSticky
                    + " threshold=" + batterySaverTriggerThreshold);
        }

        mSettingsLoaded = true;

        final boolean enabledChanged = mSettingBatterySaverEnabled != batterySaverEnabled;
        final boolean stickyChanged =
                mSettingBatterySaverEnabledSticky != batterySaverEnabledSticky;
        final boolean thresholdChanged
                = mSettingBatterySaverTriggerThreshold != batterySaverTriggerThreshold;

        if (!(enabledChanged || stickyChanged || thresholdChanged)) {
            return;
        }

        mSettingBatterySaverEnabled = batterySaverEnabled;
        mSettingBatterySaverEnabledSticky = batterySaverEnabledSticky;
        mSettingBatterySaverTriggerThreshold = batterySaverTriggerThreshold;

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
     * Decide whether to auto-start / stop battery saver.
     */
    private void doAutoBatterySaverLocked() {
        if (DEBUG) {
            Slog.d(TAG, "doAutoBatterySaverLocked: mBootCompleted=" + mBootCompleted
                    + " mSettingsLoaded=" + mSettingsLoaded
                    + " mBatteryStatusSet=" + mBatteryStatusSet
                    + " mIsBatteryLevelLow=" + mIsBatteryLevelLow
                    + " mBatterySaverSnoozing=" + mBatterySaverSnoozing
                    + " mIsPowered=" + mIsPowered
                    + " mSettingBatterySaverEnabledSticky=" + mSettingBatterySaverEnabledSticky);
        }
        if (!(mBootCompleted && mSettingsLoaded && mBatteryStatusSet)) {
            return; // Not fully initialized yet.
        }
        if (!mIsBatteryLevelLow) {
            updateSnoozingLocked(false, "Battery not low");
        }
        if (mIsPowered) {
            updateSnoozingLocked(false, "Plugged in");
            enableBatterySaverLocked(/*enable=*/ false, /*manual=*/ false,
                    BatterySaverController.REASON_PLUGGED_IN,
                    "Plugged in");

        } else if (mSettingBatterySaverEnabledSticky) {
            // Re-enable BS.
            enableBatterySaverLocked(/*enable=*/ true, /*manual=*/ true,
                    BatterySaverController.REASON_STICKY_RESTORE,
                    "Sticky restore");

        } else if (mIsBatteryLevelLow) {
            if (!mBatterySaverSnoozing && isAutoBatterySaverConfigured()) {
                enableBatterySaverLocked(/*enable=*/ true, /*manual=*/ false,
                        BatterySaverController.REASON_AUTOMATIC_ON,
                        "Auto ON");
            }
        } else { // Battery not low
            enableBatterySaverLocked(/*enable=*/ false, /*manual=*/ false,
                    BatterySaverController.REASON_AUTOMATIC_OFF,
                    "Auto OFF");
        }
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
    private void enableBatterySaverLocked(boolean enable, boolean manual, int intReason,
            String strReason) {
        if (DEBUG) {
            Slog.d(TAG, "enableBatterySaver: enable=" + enable + " manual=" + manual
                    + " reason=" + strReason + "(" + intReason + ")");
        }
        final boolean wasEnabled = mBatterySaverController.isEnabled();

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
                if (isBatterySaverEnabled() && mIsBatteryLevelLow) {
                    updateSnoozingLocked(true, "Manual snooze");
                }
            }
        }

        mSettingBatterySaverEnabled = enable;
        putGlobalSetting(Global.LOW_POWER_MODE, enable ? 1 : 0);

        if (manual) {
            mSettingBatterySaverEnabledSticky = enable;
            putGlobalSetting(Global.LOW_POWER_MODE_STICKY, enable ? 1 : 0);
        }
        mBatterySaverController.enableBatterySaver(enable, intReason);

        if (DEBUG) {
            Slog.d(TAG, "Battery saver: Enabled=" + enable
                    + " manual=" + manual
                    + " reason=" + strReason + "(" + intReason + ")");
        }
    }

    private void updateSnoozingLocked(boolean snoozing, String reason) {
        if (mBatterySaverSnoozing == snoozing) {
            return;
        }
        if (DEBUG) Slog.d(TAG, "Snooze: " + (snoozing ? "start" : "stop")  + " reason=" + reason);
        mBatterySaverSnoozing = snoozing;
    }

    @VisibleForTesting
    protected void putGlobalSetting(String key, int value) {
        Global.putInt(mContext.getContentResolver(), key, value);
    }

    @VisibleForTesting
    protected int getGlobalSetting(String key, int defValue) {
        return Global.getInt(mContext.getContentResolver(), key, defValue);
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Battery saver state machine:");

            pw.print("  Enabled=");
            pw.println(mBatterySaverController.isEnabled());

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
            pw.print("  mSettingBatterySaverTriggerThreshold=");
            pw.println(mSettingBatterySaverTriggerThreshold);
        }
    }

    public void dumpProto(ProtoOutputStream proto, long tag) {
        synchronized (mLock) {
            final long token = proto.start(tag);

            proto.write(BatterySaverStateMachineProto.ENABLED,
                    mBatterySaverController.isEnabled());

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

            proto.end(token);
        }
    }
}
