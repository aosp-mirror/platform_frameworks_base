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

package com.android.systemui.statusbar;

import android.content.pm.UserInfo;
import android.service.notification.StatusBarNotification;
import android.util.SparseArray;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

public interface NotificationLockscreenUserManager {
    String PERMISSION_SELF = "com.android.systemui.permission.SELF";
    String NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION
            = "com.android.systemui.statusbar.work_challenge_unlocked_notification_action";

    boolean shouldAllowLockscreenRemoteInput();

    /**
     * @param userId user Id
     * @return true if we re on a secure lock screen
     */
    boolean isLockscreenPublicMode(int userId);

    void setUpWithPresenter(NotificationPresenter presenter);

    int getCurrentUserId();

    boolean isCurrentProfile(int userId);

    /** Adds a listener to be notified when the current user changes. */
    void addUserChangedListener(UserChangedListener listener);

    void destroy();

    SparseArray<UserInfo> getCurrentProfiles();

    void setLockscreenPublicMode(boolean isProfilePublic, int userId);

    boolean shouldShowLockscreenNotifications();

    boolean shouldHideNotifications(int userId);
    boolean shouldHideNotifications(String key);
    boolean shouldShowOnKeyguard(StatusBarNotification sbn);

    boolean isAnyProfilePublicMode();

    void updatePublicMode();

    boolean needsRedaction(NotificationEntry entry);

    boolean userAllowsPrivateNotificationsInPublic(int currentUserId);

    /** Notified when the current user changes. */
    interface UserChangedListener {
        void onUserChanged(int userId);
    }
}
