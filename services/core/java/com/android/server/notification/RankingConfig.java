/**
 * Copyright (c) 2014, The Android Open Source Project
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
package com.android.server.notification;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.pm.ParceledListSlice;
import android.os.UserHandle;

import java.util.Collection;

public interface RankingConfig {

    void setShowBadge(String packageName, int uid, boolean showBadge);
    boolean canShowBadge(String packageName, int uid);
    boolean badgingEnabled(UserHandle userHandle);
    int getBubblePreference(String packageName, int uid);
    /** Returns true when the bubbles feature is enabled for this user. */
    boolean bubblesEnabled(UserHandle userHandle);
    /** Returns true when feature is enabled that shows media notifications in quick settings. */
    boolean isMediaNotificationFilteringEnabled();
    boolean isGroupBlocked(String packageName, int uid, String groupId);
    boolean canShowNotificationsOnLockscreen(int userId);
    boolean canShowPrivateNotificationsOnLockScreen(int userId);

    Collection<NotificationChannelGroup> getNotificationChannelGroups(String pkg,
            int uid);
    void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group,
            boolean fromTargetApp, int callingUid, boolean isSystemOrSystemUi);
    boolean createNotificationChannel(String pkg, int uid, NotificationChannel channel,
            boolean fromTargetApp, boolean hasDndAccess, int callingUid,
            boolean isSystemOrSystemUi);
    void updateNotificationChannel(String pkg, int uid, NotificationChannel updatedChannel,
            boolean fromUser, int callingUid, boolean fromSystemOrSystemUi);
    NotificationChannel getNotificationChannel(String pkg, int uid, String channelId,
            boolean includeDeleted);
    NotificationChannel getConversationNotificationChannel(String pkg, int uid, String channelId,
            String conversationId, boolean returnParentIfNoConversationChannel,
            boolean includeDeleted);
    boolean deleteNotificationChannel(String pkg, int uid, String channelId,
            int callingUid, boolean fromSystemOrSystemUi);
    void permanentlyDeleteNotificationChannel(String pkg, int uid, String channelId);
    void permanentlyDeleteNotificationChannels(String pkg, int uid);
    ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg, int uid,
            boolean includeDeleted);
}
