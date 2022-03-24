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

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.text.Annotation;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.VisibleForTesting;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.settingslib.Utils;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.settingslib.utils.PowerUtil;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.volume.Events;

import java.io.PrintWriter;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

import javax.inject.Inject;

/**
 */
@SysUISingleton
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

    private static final String ACTION_SHOW_BATTERY_SAVER_SETTINGS = "PNW.batterySaverSettings";
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

    private static final String ACTION_ENABLE_SEVERE_BATTERY_DIALOG = "PNW.enableSevereDialog";

    public static final String BATTERY_SAVER_SCHEDULE_SCREEN_INTENT_ACTION =
            "com.android.settings.BATTERY_SAVER_SCHEDULE_SETTINGS";

    private static final String BATTERY_SAVER_DESCRIPTION_URL_KEY = "url";

    private static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();
    public static final String EXTRA_CONFIRM_ONLY = "extra_confirm_only";

    private final Context mContext;
    private final NotificationManager mNoMan;
    private final PowerManager mPowerMan;
    private final KeyguardManager mKeyguard;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Receiver mReceiver = new Receiver();
    private final Intent mOpenBatterySettings = settings(Intent.ACTION_POWER_USAGE_SUMMARY);
    private final Intent mOpenBatterySaverSettings =
            settings(Settings.ACTION_BATTERY_SAVER_SETTINGS);
    private final boolean mUseSevereDialog;

    private int mBatteryLevel;
    private int mBucket;
    private long mScreenOffTime;
    private int mShowing;

    private long mWarningTriggerTimeMs;
    private boolean mWarning;
    private boolean mShowAutoSaverSuggestion;
    private boolean mPlaySound;
    private boolean mInvalidCharger;
    private SystemUIDialog mSaverConfirmation;
    private SystemUIDialog mSaverEnabledConfirmation;
    private boolean mHighTempWarning;
    private SystemUIDialog mHighTempDialog;
    private SystemUIDialog mThermalShutdownDialog;
    @VisibleForTesting SystemUIDialog mUsbHighTempDialog;
    private BatteryStateSnapshot mCurrentBatterySnapshot;
    private ActivityStarter mActivityStarter;
    private final BroadcastSender mBroadcastSender;

    /**
     */
    @Inject
    public PowerNotificationWarnings(Context context, ActivityStarter activityStarter,
            BroadcastSender broadcastSender) {
        mContext = context;
        mNoMan = mContext.getSystemService(NotificationManager.class);
        mPowerMan = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mKeyguard = mContext.getSystemService(KeyguardManager.class);
        mReceiver.init();
        mActivityStarter = activityStarter;
        mBroadcastSender = broadcastSender;
        mUseSevereDialog = mContext.getResources().getBoolean(R.bool.config_severe_battery_dialog);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("mWarning="); pw.println(mWarning);
        pw.print("mPlaySound="); pw.println(mPlaySound);
        pw.print("mInvalidCharger="); pw.println(mInvalidCharger);
        pw.print("mShowing="); pw.println(SHOWING_STRINGS[mShowing]);
        pw.print("mSaverConfirmation="); pw.println(mSaverConfirmation != null ? "not null" : null);
        pw.print("mSaverEnabledConfirmation=");
        pw.print("mHighTempWarning="); pw.println(mHighTempWarning);
        pw.print("mHighTempDialog="); pw.println(mHighTempDialog != null ? "not null" : null);
        pw.print("mThermalShutdownDialog=");
        pw.println(mThermalShutdownDialog != null ? "not null" : null);
        pw.print("mUsbHighTempDialog=");
        pw.println(mUsbHighTempDialog != null ? "not null" : null);
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
    public void updateSnapshot(BatteryStateSnapshot snapshot) {
        mCurrentBatterySnapshot = snapshot;
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
            // Once we showed the notification, don't show it again until it goes SHOWING_NOTHING.
            // This shouldn't be needed, because we have a delete intent on this notification
            // so when it's dismissed we should notice it and clear mShowAutoSaverSuggestion,
            // However we double check here just in case the dismiss intent broadcast is delayed.
            if (mShowing != SHOWING_AUTO_SAVER_SUGGESTION) {
                showAutoSaverSuggestionNotification();
            }
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
        SystemUIApplication.overrideNotificationAppName(mContext, nb, false);
        final Notification n = nb.build();
        mNoMan.cancelAsUser(TAG_BATTERY, SystemMessage.NOTE_POWER_LOW, UserHandle.ALL);
        mNoMan.notifyAsUser(TAG_BATTERY, SystemMessage.NOTE_BAD_CHARGER, n, UserHandle.ALL);
    }

    protected void showWarningNotification() {
        if (showSevereLowBatteryDialog()) {
            mBroadcastSender.sendBroadcast(new Intent(ACTION_ENABLE_SEVERE_BATTERY_DIALOG)
                    .setPackage(mContext.getPackageName())
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND));
            // Reset the state once dialog been enabled
            dismissLowBatteryNotification();
            mPlaySound = false;
            return;
        }
        if (!showLowBatteryNotification()) {
            return;
        }

        final int warningLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        final String percentage = NumberFormat.getPercentInstance()
                .format((double) warningLevel / 100.0);
        final String title = mContext.getString(R.string.battery_low_title);
        final String contentText = mContext.getString(
                R.string.battery_low_description, percentage);

        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.BATTERY)
                        .setSmallIcon(R.drawable.ic_power_low)
                        // Bump the notification when the bucket dropped.
                        .setWhen(mWarningTriggerTimeMs)
                        .setShowWhen(false)
                        .setContentText(contentText)
                        .setContentTitle(title)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true)
                        .setStyle(new Notification.BigTextStyle().bigText(contentText))
                        .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (hasBatterySettings()) {
            nb.setContentIntent(pendingBroadcast(ACTION_SHOW_BATTERY_SAVER_SETTINGS));
        }
        // Make the notification red if the percentage goes below a certain amount or the time
        // remaining estimate is disabled
        if (!mCurrentBatterySnapshot.isHybrid() || mBucket < -1
                || mCurrentBatterySnapshot.getTimeRemainingMillis()
                        < mCurrentBatterySnapshot.getSevereThresholdMillis()) {
            nb.setColor(Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorError));
        }

        if (!mPowerMan.isPowerSaveMode()) {
            nb.addAction(0, mContext.getString(R.string.battery_saver_dismiss_action),
                    pendingBroadcast(ACTION_DISMISSED_WARNING));
            nb.addAction(0,
                    mContext.getString(R.string.battery_saver_start_action),
                    pendingBroadcast(ACTION_START_SAVER));
        }
        nb.setOnlyAlertOnce(!mPlaySound);
        mPlaySound = false;
        SystemUIApplication.overrideNotificationAppName(mContext, nb, false);
        final Notification n = nb.build();
        mNoMan.cancelAsUser(TAG_BATTERY, SystemMessage.NOTE_BAD_CHARGER, UserHandle.ALL);
        mNoMan.notifyAsUser(TAG_BATTERY, SystemMessage.NOTE_POWER_LOW, n, UserHandle.ALL);
    }

    private boolean showSevereLowBatteryDialog() {
        final boolean isSevereState = !mCurrentBatterySnapshot.isHybrid() || mBucket < -1;
        return isSevereState && mUseSevereDialog;
    }

    /**
     * Disable low battery warning notification if battery saver schedule mode set as
     * "Based on percentage".
     *
     * return {@code false} if scheduled by percentage.
     */
    private boolean showLowBatteryNotification() {
        final ContentResolver resolver = mContext.getContentResolver();
        final int mode = Settings.Global.getInt(resolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);

        // Return true if battery saver schedule mode will not trigger by percentage.
        if (mode != PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE) {
            return true;
        }

        // Return true if battery saver mode trigger percentage is less than 0, which means it is
        // set as "Based on routine" mode, otherwise it will be "Based on percentage" mode.
        final int threshold =
                Settings.Global.getInt(resolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
        return threshold <= 0;
    }

    private void showAutoSaverSuggestionNotification() {
        final CharSequence message = mContext.getString(R.string.auto_saver_text);
        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.HINTS)
                        .setSmallIcon(R.drawable.ic_power_saver)
                        .setWhen(0)
                        .setShowWhen(false)
                        .setContentTitle(mContext.getString(R.string.auto_saver_title))
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setContentText(message);
        nb.setContentIntent(pendingBroadcast(ACTION_ENABLE_AUTO_SAVER));
        nb.setDeleteIntent(pendingBroadcast(ACTION_DISMISS_AUTO_SAVER_SUGGESTION));
        nb.addAction(0,
                mContext.getString(R.string.no_auto_saver_action),
                pendingBroadcast(ACTION_AUTO_SAVER_NO_THANKS));

        SystemUIApplication.overrideNotificationAppName(mContext, nb, false);

        final Notification n = nb.build();
        mNoMan.notifyAsUser(
                TAG_AUTO_SAVER, SystemMessage.NOTE_AUTO_SAVER_SUGGESTION, n, UserHandle.ALL);
    }

    private String getHybridContentString(String percentage) {
        return PowerUtil.getBatteryRemainingStringFormatted(
                mContext,
                mCurrentBatterySnapshot.getTimeRemainingMillis(),
                percentage,
                mCurrentBatterySnapshot.isBasedOnUsage());
    }

    private PendingIntent pendingBroadcast(String action) {
        return PendingIntent.getBroadcastAsUser(
                mContext,
                0 /* request code */,
                new Intent(action)
                        .setPackage(mContext.getPackageName())
                        .setFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                FLAG_IMMUTABLE /* flags */,
                UserHandle.CURRENT);
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
        dismissHighTemperatureWarningInternal();
    }

    /**
     * Internal only version of {@link #dismissHighTemperatureWarning()} that simply dismisses
     * the notification. As such, the notification will not show again until
     * {@link #dismissHighTemperatureWarning()} is called.
     */
    private void dismissHighTemperatureWarningInternal() {
        mNoMan.cancelAsUser(TAG_TEMPERATURE, SystemMessage.NOTE_HIGH_TEMP, UserHandle.ALL);
        mHighTempWarning = false;
    }

    @Override
    public void showHighTemperatureWarning() {
        if (mHighTempWarning) {
            return;
        }
        mHighTempWarning = true;
        final String message = mContext.getString(R.string.high_temp_notif_message);
        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.ALERTS)
                        .setSmallIcon(R.drawable.ic_device_thermostat_24)
                        .setWhen(0)
                        .setShowWhen(false)
                        .setContentTitle(mContext.getString(R.string.high_temp_title))
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(pendingBroadcast(ACTION_CLICKED_TEMP_WARNING))
                        .setDeleteIntent(pendingBroadcast(ACTION_DISMISSED_TEMP_WARNING))
                        .setColor(Utils.getColorAttrDefaultColor(mContext,
                                android.R.attr.colorError));
        SystemUIApplication.overrideNotificationAppName(mContext, nb, false);
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
        final String url = mContext.getString(R.string.high_temp_dialog_help_url);
        if (!url.isEmpty()) {
            d.setNeutralButton(R.string.high_temp_dialog_help_text,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            final Intent helpIntent =
                                    new Intent(Intent.ACTION_VIEW)
                                            .setData(Uri.parse(url))
                                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            mActivityStarter.startActivity(helpIntent,
                                    true /* dismissShade */, resultCode -> {
                                        mHighTempDialog = null;
                                    });
                        }
                    });
        }
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
        final String url = mContext.getString(R.string.thermal_shutdown_dialog_help_url);
        if (!url.isEmpty()) {
            d.setNeutralButton(R.string.thermal_shutdown_dialog_help_text,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            final Intent helpIntent =
                                    new Intent(Intent.ACTION_VIEW)
                                            .setData(Uri.parse(url))
                                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            mActivityStarter.startActivity(helpIntent,
                                    true /* dismissShade */, resultCode -> {
                                        mThermalShutdownDialog = null;
                                    });
                        }
                    });
        }
        d.show();
        mThermalShutdownDialog = d;
    }

    @Override
    public void showThermalShutdownWarning() {
        final String message = mContext.getString(R.string.thermal_shutdown_message);
        final Notification.Builder nb =
                new Notification.Builder(mContext, NotificationChannels.ALERTS)
                        .setSmallIcon(R.drawable.ic_device_thermostat_24)
                        .setWhen(0)
                        .setShowWhen(false)
                        .setContentTitle(mContext.getString(R.string.thermal_shutdown_title))
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(pendingBroadcast(ACTION_CLICKED_THERMAL_SHUTDOWN_WARNING))
                        .setDeleteIntent(
                                pendingBroadcast(ACTION_DISMISSED_THERMAL_SHUTDOWN_WARNING))
                        .setColor(Utils.getColorAttrDefaultColor(mContext,
                                android.R.attr.colorError));
        SystemUIApplication.overrideNotificationAppName(mContext, nb, false);
        final Notification n = nb.build();
        mNoMan.notifyAsUser(
                TAG_TEMPERATURE, SystemMessage.NOTE_THERMAL_SHUTDOWN, n, UserHandle.ALL);
    }

    @Override
    public void showUsbHighTemperatureAlarm() {
        mHandler.post(() -> showUsbHighTemperatureAlarmInternal());
    }

    private void showUsbHighTemperatureAlarmInternal() {
        if (mUsbHighTempDialog != null) {
            return;
        }

        final SystemUIDialog d = new SystemUIDialog(mContext, R.style.Theme_SystemUI_Dialog_Alert);
        d.setCancelable(false);
        d.setIconAttribute(android.R.attr.alertDialogIcon);
        d.setTitle(R.string.high_temp_alarm_title);
        d.setShowForAllUsers(true);
        d.setMessage(mContext.getString(R.string.high_temp_alarm_notify_message, ""));
        d.setPositiveButton((com.android.internal.R.string.ok),
                (dialogInterface, which) -> mUsbHighTempDialog = null);
        d.setNegativeButton((R.string.high_temp_alarm_help_care_steps),
                (dialogInterface, which) -> {
                    final String contextString = mContext.getString(
                            R.string.high_temp_alarm_help_url);
                    final Intent helpIntent = new Intent();
                    helpIntent.setClassName("com.android.settings",
                            "com.android.settings.HelpTrampoline");
                    helpIntent.putExtra(Intent.EXTRA_TEXT, contextString);
                    mActivityStarter.startActivity(helpIntent,
                            true /* dismissShade */, resultCode -> {
                                mUsbHighTempDialog = null;
                            });
                });
        d.setOnDismissListener(dialogInterface -> {
            mUsbHighTempDialog = null;
            Events.writeEvent(Events.EVENT_DISMISS_USB_OVERHEAT_ALARM,
                    Events.DISMISS_REASON_USB_OVERHEAD_ALARM_CHANGED,
                    mKeyguard.isKeyguardLocked());
        });
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d.show();
        mUsbHighTempDialog = d;

        Events.writeEvent(Events.EVENT_SHOW_USB_OVERHEAT_ALARM,
                Events.SHOW_REASON_USB_OVERHEAD_ALARM_CHANGED,
                mKeyguard.isKeyguardLocked());
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

    private void showStartSaverConfirmation(Bundle extras) {
        if (mSaverConfirmation != null) return;
        final SystemUIDialog d = new SystemUIDialog(mContext);
        final boolean confirmOnly = extras.getBoolean(BatterySaverUtils.EXTRA_CONFIRM_TEXT_ONLY);
        final int batterySaverTriggerMode =
                extras.getInt(BatterySaverUtils.EXTRA_POWER_SAVE_MODE_TRIGGER,
                        PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        final int batterySaverTriggerLevel =
                extras.getInt(BatterySaverUtils.EXTRA_POWER_SAVE_MODE_TRIGGER_LEVEL, 0);
        d.setMessage(getBatterySaverDescription());

        // Sad hack for http://b/78261259 and http://b/78298335. Otherwise "Battery" may be split
        // into "Bat-tery".
        if (isEnglishLocale()) {
            d.setMessageHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        // We need to set LinkMovementMethod to make the link clickable.
        d.setMessageMovementMethod(LinkMovementMethod.getInstance());

        if (confirmOnly) {
            d.setTitle(R.string.battery_saver_confirmation_title_generic);
            d.setPositiveButton(com.android.internal.R.string.confirm_battery_saver,
                    (dialog, which) -> {
                        final ContentResolver resolver = mContext.getContentResolver();
                        Settings.Global.putInt(
                                resolver,
                                Global.AUTOMATIC_POWER_SAVE_MODE,
                                batterySaverTriggerMode);
                        Settings.Global.putInt(
                                resolver,
                                Global.LOW_POWER_MODE_TRIGGER_LEVEL,
                                batterySaverTriggerLevel);
                        Secure.putIntForUser(
                                resolver,
                                Secure.LOW_POWER_WARNING_ACKNOWLEDGED,
                                1, UserHandle.USER_CURRENT);
                    });
        } else {
            d.setTitle(R.string.battery_saver_confirmation_title);
            d.setPositiveButton(R.string.battery_saver_confirmation_ok,
                    (dialog, which) -> setSaverMode(true, false));
            d.setNegativeButton(android.R.string.cancel, null);
        }
        d.setShowForAllUsers(true);
        d.setOnDismissListener((dialog) -> mSaverConfirmation = null);
        d.show();
        mSaverConfirmation = d;
    }

    private boolean isEnglishLocale() {
        return Objects.equals(Locale.getDefault().getLanguage(),
                Locale.ENGLISH.getLanguage());
    }

    /**
     * Generates the message for the "want to start battery saver?" dialog with a "learn more" link.
     */
    private CharSequence getBatterySaverDescription() {
        final String learnMoreUrl = mContext.getText(
                R.string.help_uri_battery_saver_learn_more_link_target).toString();

        // If there's no link, use the string with no "learn more".
        if (TextUtils.isEmpty(learnMoreUrl)) {
            return mContext.getText(R.string.battery_low_intro);
        }

        // If we have a link, use the string with the "learn more" link.
        final CharSequence rawText = mContext.getText(
                com.android.internal.R.string.battery_saver_description_with_learn_more);
        final SpannableString message = new SpannableString(rawText);
        final SpannableStringBuilder builder = new SpannableStringBuilder(message);

        // Look for the "learn more" part of the string, and set a URL span on it.
        // We use a customized URLSpan to add FLAG_RECEIVER_FOREGROUND to the intent, and
        // also to close the dialog.
        for (Annotation annotation : message.getSpans(0, message.length(), Annotation.class)) {
            final String key = annotation.getValue();

            if (!BATTERY_SAVER_DESCRIPTION_URL_KEY.equals(key)) {
                continue;
            }
            final int start = message.getSpanStart(annotation);
            final int end = message.getSpanEnd(annotation);

            // Replace the "learn more" with a custom URL span, with
            // - No underline.
            // - When clicked, close the dialog and the notification shade.
            final URLSpan urlSpan = new URLSpan(learnMoreUrl) {
                @Override
                public void updateDrawState(TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setUnderlineText(false);
                }

                @Override
                public void onClick(View widget) {
                    // Close the parent dialog.
                    if (mSaverConfirmation != null) {
                        mSaverConfirmation.dismiss();
                    }
                    // Also close the notification shade, if it's open.
                    mBroadcastSender.sendBroadcast(
                            new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                                    .setFlags(Intent.FLAG_RECEIVER_FOREGROUND));

                    final Uri uri = Uri.parse(getURL());
                    Context context = widget.getContext();
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Log.w(TAG, "Activity was not found for intent, " + intent.toString());
                    }
                }
            };
            builder.setSpan(urlSpan, start, end, message.getSpanFlags(urlSpan));
        }
        return builder;
    }

    private void setSaverMode(boolean mode, boolean needFirstTimeWarning) {
        BatterySaverUtils.setPowerSaveMode(mContext, mode, needFirstTimeWarning);
    }

    private void startBatterySaverSchedulePage() {
        Intent intent = new Intent(BATTERY_SAVER_SCHEDULE_SCREEN_INTENT_ACTION);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    private final class Receiver extends BroadcastReceiver {

        public void init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SHOW_BATTERY_SAVER_SETTINGS);
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
                    android.Manifest.permission.DEVICE_POWER, mHandler, Context.RECEIVER_EXPORTED);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Slog.i(TAG, "Received " + action);
            if (action.equals(ACTION_SHOW_BATTERY_SAVER_SETTINGS)) {
                dismissLowBatteryNotification();
                mContext.startActivityAsUser(mOpenBatterySaverSettings, UserHandle.CURRENT);
            } else if (action.equals(ACTION_START_SAVER)) {
                setSaverMode(true, true);
                dismissLowBatteryNotification();
            } else if (action.equals(ACTION_SHOW_START_SAVER_CONFIRMATION)) {
                dismissLowBatteryNotification();
                showStartSaverConfirmation(intent.getExtras());
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
                startBatterySaverSchedulePage();
            } else if (ACTION_AUTO_SAVER_NO_THANKS.equals(action)) {
                dismissAutoSaverSuggestion();
                BatterySaverUtils.suppressAutoBatterySaver(context);
            }
        }
    }
}
