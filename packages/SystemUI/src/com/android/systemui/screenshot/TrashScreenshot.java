/*
 * Copyright (C) 2012 The CyanogenMod Project (DvTonder)
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.android.systemui.screenshot.GlobalScreenshot;

public class TrashScreenshot extends BroadcastReceiver {
    private static final String LOG_TAG = "TrashScreenshot";

    // Intent bungle fields
    public static final String SCREENSHOT_URI =
            "com.android.systemui.SCREENSHOT_URI";

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = intent.getExtras();

        if (extras == null) {
            // We have nothing, abort
            return;
        }

        Uri screenshotUri = Uri.parse(extras.getString(SCREENSHOT_URI));
        if (screenshotUri != null) {
                context.getContentResolver().delete(screenshotUri, null, null);
        }

        // Dismiss the notification that brought us here.
        NotificationManager notificationManager =
                (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(GlobalScreenshot.SCREENSHOT_NOTIFICATION_ID);
    }

}
