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

package com.android.systemui.statusbar.notification;

import android.content.Intent;
import android.service.notification.StatusBarNotification;

import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * Component responsible for handling actions on a notification which cause activites to start.
 * (e.g. clicking on a notification, tapping on the settings icon in the notification guts)
 */
public interface NotificationActivityStarter {
    /** Called when the user clicks on the surface of a notification. */
    void onNotificationClicked(StatusBarNotification sbn, ExpandableNotificationRow row);

    /** Called when the user clicks on a button in the notification guts which fires an intent. */
    void startNotificationGutsIntent(Intent intent, int appUid,
            ExpandableNotificationRow row);

    default boolean isCollapsingToShowActivityOverLockscreen() {
        return false;
    }
}
