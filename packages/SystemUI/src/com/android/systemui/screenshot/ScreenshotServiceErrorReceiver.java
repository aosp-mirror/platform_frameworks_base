/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.Display;

import com.android.systemui.res.R;

/**
 * Receives errors related to screenshot.
 */
public class ScreenshotServiceErrorReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        // Show a message that we've failed to save the image to disk
        NotificationManager notificationManager = context.getSystemService(
                NotificationManager.class);
        DevicePolicyManager devicePolicyManager = context.getSystemService(
                DevicePolicyManager.class);
        ScreenshotNotificationsController controller = new ScreenshotNotificationsController(
                Display.DEFAULT_DISPLAY, context, notificationManager, devicePolicyManager);
        controller.notifyScreenshotError(R.string.screenshot_failed_to_save_unknown_text);
    }
}
