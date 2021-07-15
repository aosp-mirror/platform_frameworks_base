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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.screenshot.ScreenshotController.ACTION_TYPE_EDIT;
import static com.android.systemui.screenshot.ScreenshotController.ACTION_TYPE_SHARE;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_ACTION_INTENT;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_DISALLOW_ENTER_PIP;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_ID;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED;
import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_SCREENSHOT;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.RemoteAnimationAdapter;
import android.view.WindowManagerGlobal;

import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.Optional;

import javax.inject.Inject;

/**
 * Receiver to proxy the share or edit intent, used to clean up the notification and send
 * appropriate signals to the system (ie. to dismiss the keyguard if necessary).
 */
public class ActionProxyReceiver extends BroadcastReceiver {
    private static final String TAG = "ActionProxyReceiver";

    private final StatusBar mStatusBar;
    private final ActivityManagerWrapper mActivityManagerWrapper;
    private final ScreenshotSmartActions mScreenshotSmartActions;

    @Inject
    public ActionProxyReceiver(Optional<StatusBar> statusBar,
            ActivityManagerWrapper activityManagerWrapper,
            ScreenshotSmartActions screenshotSmartActions) {
        mStatusBar = statusBar.orElse(null);
        mActivityManagerWrapper = activityManagerWrapper;
        mScreenshotSmartActions = screenshotSmartActions;
    }

    @Override
    public void onReceive(Context context, final Intent intent) {
        Runnable startActivityRunnable = () -> {
            mActivityManagerWrapper.closeSystemWindows(SYSTEM_DIALOG_REASON_SCREENSHOT);

            PendingIntent actionIntent = intent.getParcelableExtra(EXTRA_ACTION_INTENT);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setDisallowEnterPictureInPictureWhileLaunching(
                    intent.getBooleanExtra(EXTRA_DISALLOW_ENTER_PIP, false));
            try {
                actionIntent.send(context, 0, null, null, null, null, opts.toBundle());
                if (intent.getBooleanExtra(ScreenshotController.EXTRA_OVERRIDE_TRANSITION, false)) {
                    RemoteAnimationAdapter runner = new RemoteAnimationAdapter(
                            ScreenshotController.SCREENSHOT_REMOTE_RUNNER, 0, 0);
                    try {
                        WindowManagerGlobal.getWindowManagerService()
                                .overridePendingAppTransitionRemote(runner, DEFAULT_DISPLAY);
                    } catch (Exception e) {
                        Log.e(TAG, "Error overriding screenshot app transition", e);
                    }
                }
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Pending intent canceled", e);
            }

        };

        if (mStatusBar != null) {
            mStatusBar.executeRunnableDismissingKeyguard(startActivityRunnable, null,
                    true /* dismissShade */, true /* afterKeyguardGone */,
                    true /* deferred */);
        } else {
            startActivityRunnable.run();
        }

        if (intent.getBooleanExtra(EXTRA_SMART_ACTIONS_ENABLED, false)) {
            String actionType = Intent.ACTION_EDIT.equals(intent.getAction())
                    ? ACTION_TYPE_EDIT
                    : ACTION_TYPE_SHARE;
            mScreenshotSmartActions.notifyScreenshotAction(
                    context, intent.getStringExtra(EXTRA_ID), actionType, false, null);
        }
    }
}
