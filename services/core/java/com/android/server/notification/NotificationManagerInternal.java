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

package com.android.server.notification;

import android.app.Notification;
import android.app.NotificationChannel;

public interface NotificationManagerInternal {
    NotificationChannel getNotificationChannel(String pkg, int uid, String channelId);
    void enqueueNotification(String pkg, String basePkg, int callingUid, int callingPid,
            String tag, int id, Notification notification, int userId);

    void removeForegroundServiceFlagFromNotification(String pkg, int notificationId, int userId);
}
