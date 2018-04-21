/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import androidx.annotation.VisibleForTesting;
import android.util.Slog;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.settingslib.Utils;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.settingslib.utils.PowerUtil;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.NotificationChannels;

import java.io.PrintWriter;
import java.text.NumberFormat;

public class PowerNotificationWarnings implements PowerUI.WarningsUI {
    private static final String TAG = PowerUI.TAG + ".Notification";
    private static final boolean DEBUG = PowerUI.DEBUG;

    private static final String TAG_BATTERY = "low_battery";
    private static final String TAG_TEMPERATURE = "high_temp";
    private static final String TAG_AUTO_SAVER = "auto_saver";

    private static final int SHOWING_NOTHING = 0;
    private static final int SHOWING_WARNING = 1;
    private static final int SHOWING_INVALID_CHARGER = 3;
    private static final int SHOWING_AUTO_SAVER_SUGGESTION = 4;
    private static final String[] SHOWING_STRINGS = {
        "SHOWING_NOTHING",
        "SHOWING_WARNING",
        "SHOWING_SAVER",
        "SHOWING_INVALID_CHARGER",
        "SHOWING_AUTO_SAVER_SUGGESTION",
    };

    private static final String ACTION_SHOW_BATTERY_SETTINGS = "PNW.batterySettings";
    private static final String ACTION_START_SAVER = "PNW.startSaver";
    private static final String ACTION_DISMISSED_WARNING = "PNW.dismissedWarning";
    private static final String ACTION_CLICKED_TEMP_WARNING = "PNW.clickedTempWarning";
    private static final String ACTION_DISMISSED_TEMP_WARNING = "PNW.dismissedTempWarning";
    private static final String ACTION_CLICKED_THERMAL_SHUTDOWN_WARNING =
            "PNW.clickedThermalShutdownWarning";
    private static final String ACTION_DISMISSED_THERMAL_SHUTDOWN_WARNING =
            "PNW.dismissedThermalShutdownWarning";
    private static final String ACTION_SHOW_START_SAVER_CONFIRMATION =
            BatterySaverUtils.ACTION_SHOW_START_SAVER_CONFIRMATION;
    private static final String ACTION_SHOW_AUTO_SAVER_SUGGESTION =
            BatterySaverUtils.ACTION_SHOW_AUTO_SAVER_SUGGESTION;
    private static final String ACTION_DISMISS_AUTO_SAVER_SUGGESTION =
            "PNW.dismissAutoSaverSuggestion";

    private static final String ACTION_ENABLE_AUTO_SAVER =
            "PNW.enableAutoSaver";
    private static final String ACTION_AUTO_SAVER_NO_THANKS =
            "PNW.autoSaverNoThanks";

    private static final String SETTINGS_ACTION_OPEN_BATTERY_SAVER_SETTING =
            "android.settings.BATTERY_SAVER_SETTINGS";

    private static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    private final Context mContext;
    private final NotificationManager mNoMan;
    private final PowerManager mPowerMan;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Receiver mReceiver = new Receiver();
    private final Intent mOpenBatterySettings = settings(Intent.ACTION_POWER_USAGE_SUMMARY);

    private int mBatteryLevel;
    private int mBucket;
    private long mScreenOffTime;
    private int mShowing;

    private long mWarningTriggerTimeMs;

    private Estimate mEstimate;
    private long mLowWarningThreshold;
    private long mSevereWarningThreshold;
    private boolean mWarning;
    private boolean mShowAutoSaverSuggestion;
    private boolean mPlaySound;
    private boolean mInvalidCharger;
    private SystemUIDialog mSaverConfirmation;
    private SystemUIDialog mSaverEnabledConfirmation;
    private boolean mHighTempWarning;
    private SystemUIDialog mHighTempDialog;
    private SystemUIDialog mThermalShutdownDialog;

    public PowerNotificationWarnings(Context context) {
        mContext = context;
        mNoMan = mContext.getSystemService(NotificationManager.class);
        mPowerMan = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mReceiver.init();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("mWarning="); pw.println(mWarning);
        pw.print("mPlaySound="); pw.println(mPlaySound);
        pw.print("mInvalidCharger="); pw.println(mInvalidCharger);
        pw.print("mShowing="); pw.println(SHOWING_STRINGS[mShowing]);
        pw.print("mSaverConfirmation="); pw.println(mSaverConfirmation != null ? "not null" : null);
        pw.print("mSaverEnabledConfirmation=");
        pw.println(mSaverEnabledConfirmation != null ? "not null" : null);
        pw.print("mHighTempWarning="); pw.println(mHighTempWarning);
        pw.print("mHighTempDialog="); pw.println(mHighTempDialog != null ? "not null" : null);
        pw.print("mThermalShutdownDialog=");
        pw.println(mThermalShutdownDialog != null ? "not null" : null);
    }

    private int getLowBatteryAutoTriggerDefaultLevel() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryAutoTriggerDefaultLevel);
    }

    @Override
    public void update(int batteryLevel, int bucket, long screenOffTime) {
        mBatteryLevel = batteryLevel;
        if (bucket >= 0) {
            mWarningTriggerTimeMs = 0;
        } else if (bucket < mBucket) {
            mWarningTriggerTimeMs = System.currentTimeMillis();
        }
        mBucket = bucket;
        mScreenOffTime = screenOffTime;
    }

    @Override
    public void updateEstimate(Estimate estimate) {
        mEstimate = estimate;
        if (estimate.estimateMillis <= mLowWarningThreshold) {
            mWarningTriggerTimeMs = System.currentTimeMillis();
        }
    }

    @Override
    public void updateThresholds(long lowThreshold, long severeThreshold) {
        mLowWarningThreshold = lowThreshold;
        mSevereWarningThreshold = severeThreshold;
    }

    private void updateNotification() {
        if (DEBUG) Slog.d(TAG, "updateNotification mWarning=" + mWarning + " mPlaySound="
                + mPlaySound + " mInvalidCharger=" + mInvalidCharger);
        if (mInvalidCharger) {
            showInvalidChargerNotification();
            mShowing = SHOWING_INVALID_CHARGER;
        } else if (mWarning) {
            showWarningNotification();
            mShowing = SHOWING_WARNING;
        } else if (mShowAutoSaverSuggestion) {
            showAutoSaverSuggestionNotification();
            mShowing = SHOWING_AUTO_SAVER_SUGGESTION;
        } else {
            mNoMan.cancelAsUser(TAG_BATTERY, SystemMessage.NOTE_BAD_CHARGER, UserHandle.ALL);
            mNoMan.cancelAsUser(TAG_BATTERY, SystemMessage.NOTE_POWER_LOW, UserHandle.ALL);
            mNoMan.cancelAsUser(TAG_AUTO_SAVER,
                    SystemMessage.NOTE_AUTO_SAVER_SUGGESTION, UserHandle.ALL);
            mShowing = SHOWING_NOTHING;
        }
    }

    private void showInvalidChargerNotification() {
        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.ALERTS)
                        .setSmallIcon(R.drawable.ic_power_low)
                        .setWhen(0)
                        .setShowWhen(false)
                        .setOngoing(true)
                        .setContentTitle(mContext.getString(R.string.invalid_charger_title))
                        .setContentText(mContext.getString(R.string.invalid_charger_text))
                        .setColor(mContext.getColor(
                                com.android.internal.R.color.system_notification_accent_color));
        SystemUI.overrideNotificationAppName(mContext, nb, false);
        final Notification n = nb.build();
        mNoMan.cancelAsUser(TAG_BATTERY, SystemMessage.NOTE_POWER_LOW, UserHandle.ALL);
        mNoMan.notifyAsUser(TAG_BATTERY, SystemMessage.NOTE_BAD_CHARGER, n, UserHandle.ALL);
    }

    protected void showWarningNotification() {
        final String percentage = NumberFormat.getPercentInstance()
                .format((double) mBatteryLevel / 100.0);

        // get standard notification copy
        String title = mContext.getString(R.string.battery_low_title);
        String contentText = mContext.getString(R.string.battery_low_percent_format, percentage);

        // override notification copy if hybrid notification enabled
        if (mEstimate != null) {
            contentText = getHybridContentString(percentage);
        }

        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.BATTERY)
                        .setSmallIcon(R.drawable.ic_power_low)
                        // Bump the notification when the bucket dropped.
                        .setWhen(mWarningTriggerTimeMs)
                        .setShowWhen(false)
                        .setContentText(contentText)
                        .setContentTitle(title)
                        .setOnlyAlertOnce(true)
                        .setDeleteIntent(pendingBroadcast(ACTION_DISMISSED_WARNING))
                        .setStyle(new Notification.BigTextStyle().bigText(contentText))
                        .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (hasBatterySettings()) {
            nb.setContentIntent(pendingBroadcast(ACTION_SHOW_BATTERY_SETTINGS));
        }
        // Make the notification red if the percentage goes below a certain amount or the time
        // remaining estimate is disabled
        if (mEstimate == null || mBucket < 0
                || mEstimate.estimateMillis < mSevereWarningThreshold) {
            nb.setColor(Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorError));
        }
        nb.addAction(0,
                mContext.getString(R.string.battery_saver_start_action),
                pendingBroadcast(ACTION_START_SAVER));
        nb.setOnlyAlertOnce(!mPlaySound);
        mPlaySound = false;
        SystemUI.overrideNotificationAppName(mContext, nb, false);
        final Notification n = nb.build();
        mNoMan.cancelAsUser(TAG_BATTERY, SystemMessage.NOTE_BAD_CHARGER, UserHandle.ALL);
        mNoMan.notifyAsUser(TAG_BATTERY, SystemMessage.NOTE_POWER_LOW, n, UserHandle.ALL);
    }

    private void showAutoSaverSuggestionNotification() {
        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.HINTS)
                        .setSmallIcon(R.drawable.ic_power_saver)
                        .setWhen(0)
                        .setShowWhen(false)
                        .setContentTitle(mContext.getString(R.string.auto_saver_title))
                        .setContentText(mContext.getString(R.string.auto_saver_text,
                                getLowBatteryAutoTriggerDefaultLevel()));
        nb.setContentIntent(pendingBroadcast(ACTION_ENABLE_AUTO_SAVER));
        nb.setDeleteIntent(pendingBroadcast(ACTION_DISMISS_AUTO_SAVER_SUGGESTION));
        nb.addAction(0,
                mContext.getString(R.string.no_auto_saver_action),
                pendingBroadcast(ACTION_AUTO_SAVER_NO_THANKS));

        SystemUI.overrideNotificationAppName(mContext, nb, false);

        final Notification n = nb.build();
        mNoMan.notifyAsUser(
                TAG_AUTO_SAVER, SystemMessage.NOTE_AUTO_SAVER_SUGGESTION, n, UserHandle.ALL);
    }

    private String getHybridContentString(String percentage) {
        return PowerUtil.getBatteryRemainingStringFormatted(
            mContext,
            mEstimate.estimateMillis,
            percentage,
            mEstimate.isBasedOnUsage);
    }

    private PendingIntent pendingBroadcast(String action) {
        return PendingIntent.getBroadcastAsUser(mContext, 0,
                new Intent(action).setPackage(mContext.getPackageName()), 0, UserHandle.CURRENT);
    }

    private static Intent settings(String action) {
        return new Intent(action).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    public boolean isInvalidChargerWarningShowing() {
        return mInvalidCharger;
    }

    @Override
    public void dismissHighTemperatureWarning() {
        if (!mHighTempWarning) {
            return;
        }
        mHighTempWarning = false;
        dismissHighTemperatureWarningInternal();
    }

    /**
     * Internal only version of {@link #dismissHighTemperatureWarning()} that simply dismisses
     * the notification. As such, the notification will not show again until
     * {@link #dismissHighTemperatureWarning()} is called.
     */
    private void dismissHighTemperatureWarningInternal() {
        mNoMan.cancelAsUser(TAG_TEMPERATURE, SystemMessage.NOTE_HIGH_TEMP, UserHandle.ALL);
    }

    @Override
    public void showHighTemperatureWarning() {
        if (mHighTempWarning) {
            return;
        }
        mHighTempWarning = true;
        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.ALERTS)
                        .setSmallIcon(R.drawable.ic_device_thermostat_24)
                        .setWhen(0)
                        .setShowWhen(false)
                        .setContentTitle(mContext.getString(R.string.high_temp_title))
                        .setContentText(mContext.getString(R.string.high_temp_notif_message))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(pendingBroadcast(ACTION_CLICKED_TEMP_WARNING))
                        .setDeleteIntent(pendingBroadcast(ACTION_DISMISSED_TEMP_WARNING))
                        .setColor(Utils.getColorAttrDefaultColor(mContext,
                                android.R.attr.colorError));
        SystemUI.overrideNotificationAppName(mContext, nb, false);
        final Notification n = nb.build();
        mNoMan.notifyAsUser(TAG_TEMPERATURE, SystemMessage.NOTE_HIGH_TEMP, n, UserHandle.ALL);
    }

    private void showHighTemperatureDialog() {
        if (mHighTempDialog != null) return;
        final SystemUIDialog d = new SystemUIDialog(mContext);
        d.setIconAttribute(android.R.attr.alertDialogIcon);
        d.setTitle(R.string.high_temp_title);
        d.setMessage(R.string.high_temp_dialog_message);
        d.setPositiveButton(com.android.internal.R.string.ok, null);
        d.setShowForAllUsers(true);
        d.setOnDismissListener(dialog -> mHighTempDialog = null);
        d.show();
        mHighTempDialog = d;
    }

    @VisibleForTesting
    void dismissThermalShutdownWarning() {
        mNoMan.cancelAsUser(TAG_TEMPERATURE, SystemMessage.NOTE_THERMAL_SHUTDOWN, UserHandle.ALL);
    }

    private void showThermalShutdownDialog() {
        if (mThermalShutdownDialog != null) return;
        final SystemUIDialog d = new SystemUIDialog(mContext);
        d.setIconAttribute(android.R.attr.alertDialogIcon);
        d.setTitle(R.string.thermal_shutdown_title);
        d.setMessage(R.string.thermal_shutdown_dialog_message);
        d.setPositiveButton(com.android.internal.R.string.ok, null);
        d.setShowForAllUsers(true);
        d.setOnDismissListener(dialog -> mThermalShutdownDialog = null);
        d.show();
        mThermalShutdownDialog = d;
    }

    @Override
    public void showThermalShutdownWarning() {
        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.ALERTS)
                        .setSmallIcon(R.drawable.ic_device_thermostat_24)
                        .setWhen(0)
                        .setShowWhen(false)
                        .setContentTitle(mContext.getString(R.string.thermal_shutdown_title))
                        .setContentText(mContext.getString(R.string.thermal_shutdown_message))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(pendingBroadcast(ACTION_CLICKED_THERMAL_SHUTDOWN_WARNING))
                        .setDeleteIntent(
                                pendingBroadcast(ACTION_DISMISSED_THERMAL_SHUTDOWN_WARNING))
                        .setColor(Utils.getColorAttrDefaultColor(mContext,
                                android.R.attr.colorError));
        SystemUI.overrideNotificationAppName(mContext, nb, false);
        final Notification n = nb.build();
        mNoMan.notifyAsUser(
                TAG_TEMPERATURE, SystemMessage.NOTE_THERMAL_SHUTDOWN, n, UserHandle.ALL);
    }

    @Override
    public void updateLowBatteryWarning() {
        updateNotification();
    }

    @Override
    public void dismissLowBatteryWarning() {
        if (DEBUG) Slog.d(TAG, "dismissing low battery warning: level=" + mBatteryLevel);
        dismissLowBatteryNotification();
    }

    private void dismissLowBatteryNotification() {
        if (mWarning) Slog.i(TAG, "dismissing low battery notification");
        mWarning = false;
        updateNotification();
    }

    private boolean hasBatterySettings() {
        return mOpenBatterySettings.resolveActivity(mContext.getPackageManager()) != null;
    }

    @Override
    public void showLowBatteryWarning(boolean playSound) {
        Slog.i(TAG,
                "show low battery warning: level=" + mBatteryLevel
                        + " [" + mBucket + "] playSound=" + playSound);
        mPlaySound = playSound;
        mWarning = true;
        updateNotification();
    }

    @Override
    public void dismissInvalidChargerWarning() {
        dismissInvalidChargerNotification();
    }

    private void dismissInvalidChargerNotification() {
        if (mInvalidCharger) Slog.i(TAG, "dismissing invalid charger notification");
        mInvalidCharger = false;
        updateNotification();
    }

    @Override
    public void showInvalidChargerWarning() {
        mInvalidCharger = true;
        updateNotification();
    }

    private void showAutoSaverSuggestion() {
        mShowAutoSaverSuggestion = true;
        updateNotification();
    }

    private void dismissAutoSaverSuggestion() {
        mShowAutoSaverSuggestion = false;
        updateNotification();
    }

    @Override
    public void userSwitched() {
        updateNotification();
    }

    private void showStartSaverConfirmation() {
        if (mSaverConfirmation != null) return;
        final SystemUIDialog d = new SystemUIDialog(mContext);
        d.setTitle(R.string.battery_saver_confirmation_title);
        d.setMessage(com.android.internal.R.string.battery_saver_description);
        d.setNegativeButton(android.R.string.cancel, null);
        d.setPositiveButton(R.string.battery_saver_confirmation_ok,
                (dialog, which) -> setSaverMode(true, false));
        d.setShowForAllUsers(true);
        d.setOnDismissListener((dialog) -> mSaverConfirmation = null);
        d.show();
        mSaverConfirmation = d;
    }

    private void showAutoSaverEnabledConfirmation() {
        if (mSaverEnabledConfirmation != null) return;

        // Open the Battery Saver setting page.
        final Intent actionBatterySaverSetting =
                new Intent(SETTINGS_ACTION_OPEN_BATTERY_SAVER_SETTING)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final SystemUIDialog d = new SystemUIDialog(mContext);
        d.setTitle(R.string.auto_saver_enabled_title);
        d.setMessage(mContext.getString(R.string.auto_saver_enabled_text,
                getLowBatteryAutoTriggerDefaultLevel()));

        // "Got it". Just close the dialog. Automatic battery has been enabled already.
        d.setPositiveButton(R.string.auto_saver_okay_action,
                (dialog, which) -> onAutoSaverEnabledConfirmationClosed());

        // "Settings" -> Opens the battery saver settings activity.
        d.setNeutralButton(R.string.open_saver_setting_action, (dialog, which) -> {
            mContext.startActivity(actionBatterySaverSetting);
            onAutoSaverEnabledConfirmationClosed();
        });
        d.setShowForAllUsers(true);
        d.setOnDismissListener((dialog) -> onAutoSaverEnabledConfirmationClosed());
        d.show();
        mSaverEnabledConfirmation = d;
    }

    private void onAutoSaverEnabledConfirmationClosed() {
        mSaverEnabledConfirmation = null;
    }

    private void setSaverMode(boolean mode, boolean needFirstTimeWarning) {
        BatterySaverUtils.setPowerSaveMode(mContext, mode, needFirstTimeWarning);
    }

    private void scheduleAutoBatterySaver() {
        int autoTriggerThreshold = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        if (autoTriggerThreshold == 0) {
            autoTriggerThreshold = 15;
        }

        BatterySaverUtils.ensureAutoBatterySaver(mContext, autoTriggerThreshold);
        showAutoSaverEnabledConfirmation();
    }

    private final class Receiver extends BroadcastReceiver {

        public void init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SHOW_BATTERY_SETTINGS);
            filter.addAction(ACTION_START_SAVER);
            filter.addAction(ACTION_DISMISSED_WARNING);
            filter.addAction(ACTION_CLICKED_TEMP_WARNING);
            filter.addAction(ACTION_DISMISSED_TEMP_WARNING);
            filter.addAction(ACTION_CLICKED_THERMAL_SHUTDOWN_WARNING);
            filter.addAction(ACTION_DISMISSED_THERMAL_SHUTDOWN_WARNING);
            filter.addAction(ACTION_SHOW_START_SAVER_CONFIRMATION);
            filter.addAction(ACTION_SHOW_AUTO_SAVER_SUGGESTION);
            filter.addAction(ACTION_ENABLE_AUTO_SAVER);
            filter.addAction(ACTION_AUTO_SAVER_NO_THANKS);
            filter.addAction(ACTION_DISMISS_AUTO_SAVER_SUGGESTION);
            mContext.registerReceiverAsUser(this, UserHandle.ALL, filter,
                    android.Manifest.permission.DEVICE_POWER, mHandler);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Slog.i(TAG, "Received " + action);
            if (action.equals(ACTION_SHOW_BATTERY_SETTINGS)) {
                dismissLowBatteryNotification();
                mContext.startActivityAsUser(mOpenBatterySettings, UserHandle.CURRENT);
            } else if (action.equals(ACTION_START_SAVER)) {
                setSaverMode(true, true);
                dismissLowBatteryNotification();
            } else if (action.equals(ACTION_SHOW_START_SAVER_CONFIRMATION)) {
                dismissLowBatteryNotification();
                showStartSaverConfirmation();
            } else if (action.equals(ACTION_DISMISSED_WARNING)) {
                dismissLowBatteryWarning();
            } else if (ACTION_CLICKED_TEMP_WARNING.equals(action)) {
                dismissHighTemperatureWarningInternal();
                showHighTemperatureDialog();
            } else if (ACTION_DISMISSED_TEMP_WARNING.equals(action)) {
                dismissHighTemperatureWarningInternal();
            } else if (ACTION_CLICKED_THERMAL_SHUTDOWN_WARNING.equals(action)) {
                dismissThermalShutdownWarning();
                showThermalShutdownDialog();
            } else if (ACTION_DISMISSED_THERMAL_SHUTDOWN_WARNING.equals(action)) {
                dismissThermalShutdownWarning();
            } else if (ACTION_SHOW_AUTO_SAVER_SUGGESTION.equals(action)) {
                showAutoSaverSuggestion();
            } else if (ACTION_DISMISS_AUTO_SAVER_SUGGESTION.equals(action)) {
                dismissAutoSaverSuggestion();
            } else if (ACTION_ENABLE_AUTO_SAVER.equals(action)) {
                dismissAutoSaverSuggestion();
                scheduleAutoBatterySaver();
            } else if (ACTION_AUTO_SAVER_NO_THANKS.equals(action)) {
                dismissAutoSaverSuggestion();
                BatterySaverUtils.suppressAutoBatterySaver(context);
            }
        }
    }
}
