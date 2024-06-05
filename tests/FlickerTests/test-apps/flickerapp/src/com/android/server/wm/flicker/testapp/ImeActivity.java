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

package com.android.server.wm.flicker.testapp;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.server.wm.flicker.testapp.ActivityOptions.Ime.Default.ACTION_FINISH_ACTIVITY;
import static com.android.server.wm.flicker.testapp.ActivityOptions.Ime.Default.ACTION_START_DIALOG_THEMED_ACTIVITY;
import static com.android.server.wm.flicker.testapp.ActivityOptions.Ime.Default.ACTION_TOGGLE_ORIENTATION;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

public class ImeActivity extends Activity {

    private static final String TAG = "ImeActivity";

    /** Receiver used to handle actions coming from the test helper methods. */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ACTION_FINISH_ACTIVITY -> finish();
                case ACTION_START_DIALOG_THEMED_ACTIVITY -> startActivity(
                        new Intent(context, DialogThemedActivity.class));
                case ACTION_TOGGLE_ORIENTATION -> {
                    mIsPortrait = !mIsPortrait;
                    setRequestedOrientation(mIsPortrait
                            ? SCREEN_ORIENTATION_PORTRAIT
                            : SCREEN_ORIENTATION_UNSPECIFIED);
                }
                default -> Log.w(TAG, "Unhandled action=" + intent.getAction());
            }
        }
    };

    /**
     * Used to toggle activity orientation between portrait when {@code true} and
     * unspecified otherwise.
     */
    private boolean mIsPortrait = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(p);
        setContentView(R.layout.activity_ime);

        final var filter = new IntentFilter();
        filter.addAction(ACTION_FINISH_ACTIVITY);
        filter.addAction(ACTION_START_DIALOG_THEMED_ACTIVITY);
        filter.addAction(ACTION_TOGGLE_ORIENTATION);
        registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mBroadcastReceiver);
        super.onDestroy();
    }
}
