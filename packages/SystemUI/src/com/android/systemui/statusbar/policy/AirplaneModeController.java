/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import android.widget.CompoundButton;

public class AirplaneModeController extends BroadcastReceiver
        implements CompoundButton.OnCheckedChangeListener {
    private static final String TAG = "StatusBar.AirplaneModeController";

    private Context mContext;
    private CompoundButton mCheckBox;

    private boolean mAirplaneMode;

    public AirplaneModeController(Context context, CompoundButton checkbox) {
        mContext = context;
        mAirplaneMode = getAirplaneMode();
        mCheckBox = checkbox;
        checkbox.setChecked(mAirplaneMode);
        checkbox.setOnCheckedChangeListener(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        context.registerReceiver(this, filter);

    }

    public void release() {
        mContext.unregisterReceiver(this);
    }

    public void onCheckedChanged(CompoundButton view, boolean checked) {
        if (checked != mAirplaneMode) {
            mAirplaneMode = checked;
            unsafe(checked);
        }
    }

    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) {
            final boolean enabled = intent.getBooleanExtra("state", false);
            if (enabled != mAirplaneMode) {
                mAirplaneMode = enabled;
                mCheckBox.setChecked(enabled);
            }
        }
    }

    private boolean getAirplaneMode() {
        ContentResolver cr = mContext.getContentResolver();
        return 0 != Settings.System.getInt(cr, Settings.System.AIRPLANE_MODE_ON, 0);
    }

    // TODO: Fix this racy API by adding something better to TelephonyManager or
    // ConnectivityService.
    private void unsafe(final boolean enabled) {
        AsyncTask.execute(new Runnable() {
                public void run() {
                    Settings.System.putInt(
                            mContext.getContentResolver(),
                            Settings.System.AIRPLANE_MODE_ON,
                            enabled ? 1 : 0);
                    Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                    intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                    intent.putExtra("state", enabled);
                    mContext.sendBroadcast(intent);
                }
            });
    }
}

