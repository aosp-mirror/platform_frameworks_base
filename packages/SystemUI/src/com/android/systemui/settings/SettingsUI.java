/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.SystemUI;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class SettingsUI extends SystemUI {
    private static final String TAG = "SettingsUI";
    private static final boolean DEBUG = false;

    private final Handler mHandler = new Handler();
    private BrightnessDialog mBrightnessDialog;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG)) {
                if (DEBUG) Log.d(TAG, "showing brightness dialog");

                if (mBrightnessDialog == null) {
                    mBrightnessDialog = new BrightnessDialog(mContext);
                    mBrightnessDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            mBrightnessDialog = null;
                        }
                    });
                }

                if (!mBrightnessDialog.isShowing()) {
                    mBrightnessDialog.show();
                }

            } else {
                Log.w(TAG, "unknown intent: " + intent);
            }
        }
    };

    public void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG);
        mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter, null, mHandler);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("mBrightnessDialog=");
        pw.println(mBrightnessDialog == null ? "null" : mBrightnessDialog.toString());
    }
}
