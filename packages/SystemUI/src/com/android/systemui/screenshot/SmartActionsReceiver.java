/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.LogConfig.DEBUG_ACTIONS;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_ACTION_INTENT;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_ACTION_TYPE;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_ID;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import javax.inject.Inject;


/**
 * Executes the smart action tapped by the user in the notification.
 */
public class SmartActionsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmartActionsReceiver";

    private final ScreenshotSmartActions mScreenshotSmartActions;

    @Inject
    SmartActionsReceiver(ScreenshotSmartActions screenshotSmartActions) {
        mScreenshotSmartActions = screenshotSmartActions;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingIntent pendingIntent = intent.getParcelableExtra(EXTRA_ACTION_INTENT);
        String actionType = intent.getStringExtra(EXTRA_ACTION_TYPE);
        if (DEBUG_ACTIONS) {
            Log.d(TAG, "Executing smart action [" + actionType + "]:" + pendingIntent.getIntent());
        }
        ActivityOptions opts = ActivityOptions.makeBasic();

        try {
            pendingIntent.send(context, 0, null, null, null, null, opts.toBundle());
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Pending intent canceled", e);
        }

        mScreenshotSmartActions.notifyScreenshotAction(
                intent.getStringExtra(EXTRA_ID), actionType, true,
                pendingIntent.getIntent());
    }
}
