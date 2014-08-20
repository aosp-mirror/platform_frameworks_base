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
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.io.PrintWriter;

public class PowerNotificationWarnings implements PowerUI.WarningsUI {
    private static final String TAG = PowerUI.TAG + ".Notification";
    private static final boolean DEBUG = PowerUI.DEBUG;

    private static final String TAG_NOTIFICATION = "low_battery";
    private static final int ID_NOTIFICATION = 100;
    private static final int AUTO_DISMISS_MS = 10000;

    private static final int SHOWING_NOTHING = 0;
    private static final int SHOWING_WARNING = 1;
    private static final int SHOWING_SAVER = 2;
    private static final int SHOWING_INVALID_CHARGER = 3;
    private static final String[] SHOWING_STRINGS = {
        "SHOWING_NOTHING",
        "SHOWING_WARNING",
        "SHOWING_SAVER",
        "SHOWING_INVALID_CHARGER",
    };

    private static final String ACTION_SHOW_FALLBACK_WARNING = "PNW.warningFallback";
    private static final String ACTION_SHOW_FALLBACK_CHARGER = "PNW.chargerFallback";
    private static final String ACTION_SHOW_BATTERY_SETTINGS = "PNW.batterySettings";
    private static final String ACTION_START_SAVER = "PNW.startSaver";
    private static final String ACTION_STOP_SAVER = "PNW.stopSaver";

    private static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    private final Context mContext;
    private final NotificationManager mNoMan;
    private final Handler mHandler = new Handler();
    private final PowerDialogWarnings mFallbackDialogs;
    private final Receiver mReceiver = new Receiver();
    private final Intent mOpenBatterySettings = settings(Intent.ACTION_POWER_USAGE_SUMMARY);
    private final Intent mOpenSaverSettings = settings(Settings.ACTION_BATTERY_SAVER_SETTINGS);

    private int mBatteryLevel;
    private int mBucket;
    private long mScreenOffTime;
    private int mShowing;

    private long mBucketDroppedNegativeTimeMs;

    private boolean mSaver;
    private boolean mWarning;
    private boolean mPlaySound;
    private boolean mInvalidCharger;
    private SystemUIDialog mSaverConfirmation;

    public PowerNotificationWarnings(Context context, PhoneStatusBar phoneStatusBar) {
        mContext = context;
        mNoMan = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mFallbackDialogs = new PowerDialogWarnings(context, phoneStatusBar);
        mReceiver.init();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("mSaver="); pw.println(mSaver);
        pw.print("mWarning="); pw.println(mWarning);
        pw.print("mPlaySound="); pw.println(mPlaySound);
        pw.print("mInvalidCharger="); pw.println(mInvalidCharger);
        pw.print("mShowing="); pw.println(SHOWING_STRINGS[mShowing]);
        pw.print("mSaverConfirmation="); pw.println(mSaverConfirmation != null ? "not null" : null);
    }

    @Override
    public void update(int batteryLevel, int bucket, long screenOffTime) {
        mBatteryLevel = batteryLevel;
        if (bucket >= 0) {
            mBucketDroppedNegativeTimeMs = 0;
        } else if (bucket < mBucket) {
            mBucketDroppedNegativeTimeMs = System.currentTimeMillis();
        }
        mBucket = bucket;
        mScreenOffTime = screenOffTime;
        mFallbackDialogs.update(batteryLevel, bucket, screenOffTime);
    }

    @Override
    public void showSaverMode(boolean mode) {
        mSaver = mode;
        if (mSaver && mSaverConfirmation != null) {
            mSaverConfirmation.dismiss();
        }
        updateNotification();
    }

    private void updateNotification() {
        if (DEBUG) Slog.d(TAG, "updateNotification mWarning=" + mWarning
                + " mSaver=" + mSaver + " mInvalidCharger=" + mInvalidCharger);
        if (mInvalidCharger) {
            showInvalidChargerNotification();
            mShowing = SHOWING_INVALID_CHARGER;
        } else if (mWarning) {
            showWarningNotification();
            mShowing = SHOWING_WARNING;
        } else if (mSaver) {
            showSaverNotification();
            mShowing = SHOWING_SAVER;
        } else {
            mNoMan.cancel(TAG_NOTIFICATION, ID_NOTIFICATION);
            mShowing = SHOWING_NOTHING;
        }
    }

    private void showInvalidChargerNotification() {
        final Notification.Builder nb = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_power_low)
                .setWhen(0)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentTitle(mContext.getString(R.string.invalid_charger_title))
                .setContentText(mContext.getString(R.string.invalid_charger_text))
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingBroadcast(ACTION_SHOW_FALLBACK_CHARGER), true)
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        final Notification n = nb.build();
        if (n.headsUpContentView != null) {
            n.headsUpContentView.setViewVisibility(com.android.internal.R.id.right_icon, View.GONE);
        }
        mNoMan.notifyAsUser(TAG_NOTIFICATION, ID_NOTIFICATION, n, UserHandle.CURRENT);
    }

    private void showWarningNotification() {
        final int textRes = mSaver ? R.string.battery_low_percent_format_saver_started
                : R.string.battery_low_percent_format;
        final Notification.Builder nb = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_power_low)
                // Bump the notification when the bucket dropped.
                .setWhen(mBucketDroppedNegativeTimeMs)
                .setShowWhen(false)
                .setContentTitle(mContext.getString(R.string.battery_low_title))
                .setContentText(mContext.getString(textRes, mBatteryLevel))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingBroadcast(ACTION_SHOW_FALLBACK_WARNING), true)
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.battery_saver_mode_color));
        if (hasBatterySettings()) {
            nb.setContentIntent(pendingBroadcast(ACTION_SHOW_BATTERY_SETTINGS));
        }
        if (!mSaver) {
            nb.addAction(0,
                    mContext.getString(R.string.battery_saver_start_action),
                    pendingBroadcast(ACTION_START_SAVER));
        } else {
            addStopSaverAction(nb);
        }
        if (mPlaySound) {
            attachLowBatterySound(nb);
        }
        final Notification n = nb.build();
        if (n.headsUpContentView != null) {
            n.headsUpContentView.setViewVisibility(com.android.internal.R.id.right_icon, View.GONE);
        }
        mNoMan.notifyAsUser(TAG_NOTIFICATION, ID_NOTIFICATION, n, UserHandle.CURRENT);
    }

    private void showSaverNotification() {
        final Notification.Builder nb = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_power_saver)
                .setContentTitle(mContext.getString(R.string.battery_saver_notification_title))
                .setContentText(mContext.getString(R.string.battery_saver_notification_text))
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(mContext.getResources().getColor(
                        com.android.internal.R.color.battery_saver_mode_color));
        addStopSaverAction(nb);
        if (hasSaverSettings()) {
            nb.setContentIntent(pendingActivity(mOpenSaverSettings));
        }
        mNoMan.notifyAsUser(TAG_NOTIFICATION, ID_NOTIFICATION, nb.build(), UserHandle.CURRENT);
    }

    private void addStopSaverAction(Notification.Builder nb) {
        nb.addAction(0,
                mContext.getString(R.string.battery_saver_notification_action_text),
                pendingBroadcast(ACTION_STOP_SAVER));
    }

    private void dismissSaverNotification() {
        if (mSaver) Slog.i(TAG, "dismissing saver notification");
        mSaver = false;
        updateNotification();
    }

    private PendingIntent pendingActivity(Intent intent) {
        return PendingIntent.getActivityAsUser(mContext,
                0, intent, 0, null, UserHandle.CURRENT);
    }

    private PendingIntent pendingBroadcast(String action) {
        return PendingIntent.getBroadcastAsUser(mContext,
                0, new Intent(action), 0, UserHandle.CURRENT);
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
    public void updateLowBatteryWarning() {
        updateNotification();
        mFallbackDialogs.updateLowBatteryWarning();
    }

    @Override
    public void dismissLowBatteryWarning() {
        if (DEBUG) Slog.d(TAG, "dismissing low battery warning: level=" + mBatteryLevel);
        dismissLowBatteryNotification();
        mFallbackDialogs.dismissLowBatteryWarning();
    }

    private void dismissLowBatteryNotification() {
        if (mWarning) Slog.i(TAG, "dismissing low battery notification");
        mWarning = false;
        updateNotification();
    }

    private boolean hasBatterySettings() {
        return mOpenBatterySettings.resolveActivity(mContext.getPackageManager()) != null;
    }

    private boolean hasSaverSettings() {
        return mOpenSaverSettings.resolveActivity(mContext.getPackageManager()) != null;
    }

    @Override
    public void showLowBatteryWarning(boolean playSound) {
        Slog.i(TAG,
                "show low battery warning: level=" + mBatteryLevel
                + " [" + mBucket + "]");
        mPlaySound = playSound;
        mWarning = true;
        updateNotification();
        mHandler.removeCallbacks(mDismissLowBatteryNotification);
        mHandler.postDelayed(mDismissLowBatteryNotification, AUTO_DISMISS_MS);
    }

    private void attachLowBatterySound(Notification.Builder b) {
        final ContentResolver cr = mContext.getContentResolver();

        final int silenceAfter = Settings.Global.getInt(cr,
                Settings.Global.LOW_BATTERY_SOUND_TIMEOUT, 0);
        final long offTime = SystemClock.elapsedRealtime() - mScreenOffTime;
        if (silenceAfter > 0
                && mScreenOffTime > 0
                && offTime > silenceAfter) {
            Slog.i(TAG, "screen off too long (" + offTime + "ms, limit " + silenceAfter
                    + "ms): not waking up the user with low battery sound");
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "playing low battery sound. pick-a-doop!"); // WOMP-WOMP is deprecated
        }

        if (Settings.Global.getInt(cr, Settings.Global.POWER_SOUNDS_ENABLED, 1) == 1) {
            final String soundPath = Settings.Global.getString(cr,
                    Settings.Global.LOW_BATTERY_SOUND);
            if (soundPath != null) {
                final Uri soundUri = Uri.parse("file://" + soundPath);
                if (soundUri != null) {
                    b.setSound(soundUri, AUDIO_ATTRIBUTES);
                    if (DEBUG) Slog.d(TAG, "playing sound " + soundUri);
                }
            }
        }
    }

    @Override
    public void dismissInvalidChargerWarning() {
        dismissInvalidChargerNotification();
        mFallbackDialogs.dismissInvalidChargerWarning();
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

    private void showStartSaverConfirmation() {
        if (mSaverConfirmation != null) return;
        final SystemUIDialog d = new SystemUIDialog(mContext);
        d.setTitle(R.string.battery_saver_confirmation_title);
        d.setMessage(com.android.internal.R.string.battery_saver_description);
        d.setNegativeButton(android.R.string.cancel, null);
        d.setPositiveButton(R.string.battery_saver_confirmation_ok, mStartSaverMode);
        d.setShowForAllUsers(true);
        d.setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                mSaverConfirmation = null;
            }
        });
        d.show();
        mSaverConfirmation = d;
    }

    private void setSaverSetting(boolean mode) {
        final int val = mode ? 1 : 0;
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.LOW_POWER_MODE, val);
    }

    private final class Receiver extends BroadcastReceiver {

        public void init() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SHOW_FALLBACK_WARNING);
            filter.addAction(ACTION_SHOW_FALLBACK_CHARGER);
            filter.addAction(ACTION_SHOW_BATTERY_SETTINGS);
            filter.addAction(ACTION_START_SAVER);
            filter.addAction(ACTION_STOP_SAVER);
            mContext.registerReceiver(this, filter, null, mHandler);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Slog.i(TAG, "Received " + action);
            if (action.equals(ACTION_SHOW_FALLBACK_WARNING)) {
                dismissLowBatteryNotification();
                mFallbackDialogs.showLowBatteryWarning(false /*playSound*/);
            } else if (action.equals(ACTION_SHOW_FALLBACK_CHARGER)) {
                dismissInvalidChargerNotification();
                mFallbackDialogs.showInvalidChargerWarning();
            } else if (action.equals(ACTION_SHOW_BATTERY_SETTINGS)) {
                dismissLowBatteryNotification();
                mContext.startActivityAsUser(mOpenBatterySettings, UserHandle.CURRENT);
            } else if (action.equals(ACTION_START_SAVER)) {
                dismissLowBatteryNotification();
                showStartSaverConfirmation();
            } else if (action.equals(ACTION_STOP_SAVER)) {
                dismissSaverNotification();
                dismissLowBatteryNotification();
                setSaverSetting(false);
            }
        }
    }

    private final OnClickListener mStartSaverMode = new OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    setSaverSetting(true);
                }
            });
        }
    };

    private final Runnable mDismissLowBatteryNotification = new Runnable() {
        @Override
        public void run() {
            dismissLowBatteryNotification();
        }
    };
}
