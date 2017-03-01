/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;

import android.content.Context;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.SystemUI;

import java.util.Arrays;

public class NotificationChannels extends SystemUI {
    public static String ALERTS      = "ALR";
    public static String SCREENSHOTS = "SCN";
    public static String GENERAL     = "GEN";
    public static String STORAGE     = "DSK";

    @VisibleForTesting
    static void createAll(Context context) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannels(Arrays.asList(
                new NotificationChannel(
                        ALERTS,
                        R.string.notification_channel_alerts,
                        NotificationManager.IMPORTANCE_HIGH),
                new NotificationChannel(
                        SCREENSHOTS,
                        R.string.notification_channel_screenshot,
                        NotificationManager.IMPORTANCE_LOW),
                new NotificationChannel(
                        GENERAL,
                        R.string.notification_channel_general,
                        NotificationManager.IMPORTANCE_MIN),
                new NotificationChannel(
                        STORAGE,
                        R.string.notification_channel_storage,
                        NotificationManager.IMPORTANCE_LOW)
                ));
    }

    @Override
    public void start() {
        createAll(mContext);
    }
}
