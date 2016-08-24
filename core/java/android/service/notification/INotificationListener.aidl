/**
 * Copyright (c) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.notification;

import android.service.notification.IStatusBarNotificationHolder;
import android.service.notification.StatusBarNotification;
import android.service.notification.NotificationRankingUpdate;

/** @hide */
oneway interface INotificationListener
{
    // listeners and rankers
    void onListenerConnected(in NotificationRankingUpdate update);
    void onNotificationPosted(in IStatusBarNotificationHolder notificationHolder,
            in NotificationRankingUpdate update);
    void onNotificationRemoved(in IStatusBarNotificationHolder notificationHolder,
            in NotificationRankingUpdate update);
    void onNotificationRankingUpdate(in NotificationRankingUpdate update);
    void onListenerHintsChanged(int hints);
    void onInterruptionFilterChanged(int interruptionFilter);

    // rankers only
    void onNotificationEnqueued(in IStatusBarNotificationHolder notificationHolder, int importance, boolean user);
    void onNotificationVisibilityChanged(String key, long time, boolean visible);
    void onNotificationClick(String key, long time);
    void onNotificationActionClick(String key, long time, int actionIndex);
    void onNotificationRemovedReason(String key, long time, int reason);
}
