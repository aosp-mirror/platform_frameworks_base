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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.provider.Settings;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SystemUI;

public class PowerUI extends SystemUI {
    static final String TAG = "PowerUI";

    Handler mHandler = new Handler();

    AlertDialog mLowBatteryDialog;
    int mBatteryLevel;
    TextView mBatteryLevelTextView;

    public void start() {
        // Register for Intent broadcasts for...
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                mBatteryLevel = intent.getIntExtra("level", -1);
            } else if (action.equals(Intent.ACTION_BATTERY_LOW)) {
                showLowBatteryWarning();
            } else if (action.equals(Intent.ACTION_BATTERY_OKAY)
                    || action.equals(Intent.ACTION_POWER_CONNECTED)) {
                if (mLowBatteryDialog != null) {
                    mLowBatteryDialog.dismiss();
                }
            } else {
                Slog.w(TAG, "unknown intent: " + intent);
            }
        }
    };

    void showLowBatteryWarning() {
        CharSequence levelText = mContext.getString(
                R.string.battery_low_percent_format, mBatteryLevel);

        if (mBatteryLevelTextView != null) {
            mBatteryLevelTextView.setText(levelText);
        } else {
            View v = View.inflate(mContext, R.layout.battery_low, null);
            mBatteryLevelTextView = (TextView)v.findViewById(R.id.level_percent);

            mBatteryLevelTextView.setText(levelText);

            AlertDialog.Builder b = new AlertDialog.Builder(mContext);
                b.setCancelable(true);
                b.setTitle(R.string.battery_low_title);
                b.setView(v);
                b.setIcon(android.R.drawable.ic_dialog_alert);
                b.setPositiveButton(android.R.string.ok, null);

                final Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_HISTORY);
                if (intent.resolveActivity(mContext.getPackageManager()) != null) {
                    b.setNegativeButton(R.string.battery_low_why,
                            new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            mContext.startActivity(intent);
                            if (mLowBatteryDialog != null) {
                                mLowBatteryDialog.dismiss();
                            }
                        }
                    });
                }

            AlertDialog d = b.create();
            d.setOnDismissListener(mLowBatteryListener);
            d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            d.show();
            mLowBatteryDialog = d;
        }

        final ContentResolver cr = mContext.getContentResolver();
        if (Settings.System.getInt(cr, Settings.System.POWER_SOUNDS_ENABLED, 1) == 1) {
            final String soundPath = Settings.System.getString(cr,
                    Settings.System.LOW_BATTERY_SOUND);
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

    private DialogInterface.OnDismissListener mLowBatteryListener
            = new DialogInterface.OnDismissListener() {
        public void onDismiss(DialogInterface dialog) {
            mLowBatteryDialog = null;
            mBatteryLevelTextView = null;
        }
    };

    
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (false) {
            pw.println("args=" + Arrays.toString(args));
        }
        if (args == null || args.length == 0) {
            pw.print("mLowBatteryDialog=");
            pw.println(mLowBatteryDialog == null ? "null" : mLowBatteryDialog.toString());
            pw.print("mBatteryLevel=");
            pw.println(Integer.toString(mBatteryLevel));
        }

        // DO NOT SUBMIT with this turned on.
        if (false) {
            if (args.length == 3 && "level".equals(args[1])) {
                try {
                    final int level = Integer.parseInt(args[2]);
                    Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
                    intent.putExtra("level", level);
                    mIntentReceiver.onReceive(mContext, intent);
                } catch (NumberFormatException ex) {
                    pw.println(ex);
                }
            } else if (args.length == 2 && "low".equals(args[1])) {
                Intent intent = new Intent(Intent.ACTION_BATTERY_LOW);
                mIntentReceiver.onReceive(mContext, intent);
            } else if (args.length == 2 && "ok".equals(args[1])) {
                Intent intent = new Intent(Intent.ACTION_BATTERY_OKAY);
                mIntentReceiver.onReceive(mContext, intent);
            }
        }
    }
}

