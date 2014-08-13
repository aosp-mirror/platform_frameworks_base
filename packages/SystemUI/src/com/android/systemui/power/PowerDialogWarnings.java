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

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.io.PrintWriter;

public class PowerDialogWarnings implements PowerUI.WarningsUI {
    private static final String TAG = PowerUI.TAG + ".Dialog";
    private static final boolean DEBUG = PowerUI.DEBUG;

    private final Context mContext;
    private final PhoneStatusBar mPhoneStatusBar;

    private int mBatteryLevel;
    private int mBucket;
    private long mScreenOffTime;
    private boolean mSaver;

    private AlertDialog mInvalidChargerDialog;
    private AlertDialog mLowBatteryDialog;

    public PowerDialogWarnings(Context context, PhoneStatusBar phoneStatusBar) {
        mContext = new ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Light);
        mPhoneStatusBar = phoneStatusBar;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print("mInvalidChargerDialog=");
        pw.println(mInvalidChargerDialog == null ? "null" : mInvalidChargerDialog.toString());
        pw.print("mLowBatteryDialog=");
        pw.println(mLowBatteryDialog == null ? "null" : mLowBatteryDialog.toString());
    }

    @Override
    public void update(int batteryLevel, int bucket, long screenOffTime) {
        mBatteryLevel = batteryLevel;
        mBucket = bucket;
        mScreenOffTime = screenOffTime;
    }

    @Override
    public boolean isInvalidChargerWarningShowing() {
        return mInvalidChargerDialog != null;
    }

    @Override
    public void updateLowBatteryWarning() {
        if (mLowBatteryDialog != null) {
            showLowBatteryWarning(false /*playSound*/);
        }
    }

    @Override
    public void dismissLowBatteryWarning() {
        if (mLowBatteryDialog != null) {
            Slog.i(TAG, "closing low battery warning: level=" + mBatteryLevel);
            mLowBatteryDialog.dismiss();
        }
    }

    @Override
    public void showLowBatteryWarning(boolean playSound) {
        Slog.i(TAG,
                ((mLowBatteryDialog == null) ? "showing" : "updating")
                + " low battery warning: level=" + mBatteryLevel
                + " [" + mBucket + "]");

        final int textRes = mSaver ? R.string.battery_low_percent_format_saver_started
                : R.string.battery_low_percent_format;
        final CharSequence levelText = mContext.getString(textRes, mBatteryLevel);

        if (mLowBatteryDialog != null) {
            mLowBatteryDialog.setMessage(levelText);
        } else {
            AlertDialog.Builder b = new AlertDialog.Builder(mContext);
            b.setCancelable(true);
            b.setTitle(R.string.battery_low_title);
            b.setMessage(levelText);
            b.setPositiveButton(android.R.string.ok, null);

            final Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_HISTORY);
            if (intent.resolveActivity(mContext.getPackageManager()) != null) {
                b.setNegativeButton(R.string.battery_low_why,
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mPhoneStatusBar.startActivity(intent, true /* dismissShade */);
                        dismissLowBatteryWarning();
                    }
                });
            }

            AlertDialog d = b.create();
            d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        mLowBatteryDialog = null;
                    }
                });
            d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
            d.getWindow().getAttributes().privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            d.show();
            mLowBatteryDialog = d;
            if (playSound) {
                playLowBatterySound();
            }
        }
    }

    private void playLowBatterySound() {
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
                    final Ringtone sfx = RingtoneManager.getRingtone(mContext, soundUri);
                    if (sfx != null) {
                        sfx.setStreamType(AudioManager.STREAM_SYSTEM);
                        sfx.play();
                    }
                }
            }
        }
    }

    @Override
    public void dismissInvalidChargerWarning() {
        if (mInvalidChargerDialog != null) {
            mInvalidChargerDialog.dismiss();
        }
    }

    @Override
    public void showInvalidChargerWarning() {
        Slog.d(TAG, "showing invalid charger dialog");

        dismissLowBatteryWarning();

        AlertDialog.Builder b = new AlertDialog.Builder(mContext);
        b.setCancelable(true);
        b.setTitle(R.string.invalid_charger_title);
        b.setMessage(R.string.invalid_charger_text);
        b.setPositiveButton(android.R.string.ok, null);

        AlertDialog d = b.create();
            d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        mInvalidChargerDialog = null;
                    }
                });

        d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        d.getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        d.show();
        mInvalidChargerDialog = d;
    }

    @Override
    public void showSaverMode(boolean mode) {
        mSaver = mode;
    }
}
