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
import android.app.NotificationChannelGroup;
import android.service.notification.DeviceEffectsApplier;

import java.util.Set;

public interface NotificationManagerInternal {
    NotificationChannel getNotificationChannel(String pkg, int uid, String channelId);
    NotificationChannelGroup getNotificationChannelGroup(String pkg, int uid, String channelId);
    void enqueueNotification(String pkg, String basePkg, int callingUid, int callingPid,
            String tag, int id, Notification notification, int userId);
    void enqueueNotification(String pkg, String basePkg, int callingUid, int callingPid,
            String tag, int id, Notification notification, int userId,
            boolean byForegroundService);
    void cancelNotification(String pkg, String basePkg, int callingUid, int callingPid,
            String tag, int id, int userId);

    /** is the given notification currently showing? */
    boolean isNotificationShown(String pkg, String tag, int notificationId, int userId);

    void removeForegroundServiceFlagFromNotification(String pkg, int notificationId, int userId);

    void removeUserInitiatedJobFlagFromNotification(String pkg, int notificationId, int userId);

    void onConversationRemoved(String pkg, int uid, Set<String> shortcuts);

    /** Get the number of notification channels for a given package */
    int getNumNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted);

    /** Does the specified package/uid have permission to post notifications? */
    boolean areNotificationsEnabledForPackage(String pkg, int uid);

    /** Send a notification to the user prompting them to review their notification permissions. */
    void sendReviewPermissionsNotification();

    void cleanupHistoryFiles();

    void removeBitmaps();

    /**
     * Sets the {@link DeviceEffectsApplier} that will be used to apply the different
     * {@link android.service.notification.ZenDeviceEffects} that are relevant for the platform
     * when {@link android.service.notification.ZenModeConfig.ZenRule} instances are activated and
     * deactivated.
     *
     * <p>This method is optional and needs only be called if the platform supports non-standard
     * effects (i.e. any that are not <em>public APIs</em> in
     * {@link android.service.notification.ZenDeviceEffects}, or if they must be applied in a
     * non-standard fashion. If not used, a {@link DefaultDeviceEffectsApplier} will be invoked,
     * which should be sufficient for most devices.
     *
     * <p>If this method is called, it <em>must</em> be during system startup and <em>before</em>
     * the {@link com.android.server.SystemService#PHASE_THIRD_PARTY_APPS_CAN_START} boot phase.
     * Otherwise an {@link IllegalStateException} will be thrown.
     */
    void setDeviceEffectsApplier(DeviceEffectsApplier applier);
}
