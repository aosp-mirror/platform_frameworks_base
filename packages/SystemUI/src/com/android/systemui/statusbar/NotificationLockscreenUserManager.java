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

import android.annotation.IntDef;
import android.content.pm.UserInfo;
import android.util.SparseArray;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public interface NotificationLockscreenUserManager {
    String PERMISSION_SELF = "com.android.systemui.permission.SELF";
    String NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION
            = "com.android.systemui.statusbar.work_challenge_unlocked_notification_action";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            prefix = {"REDACTION_TYPE_"},
            value = {
                    REDACTION_TYPE_NONE,
                    REDACTION_TYPE_PUBLIC,
                    REDACTION_TYPE_SENSITIVE_CONTENT})
    @interface RedactionType {}

    /**
     * Indicates that a notification requires no redaction
     */
    int REDACTION_TYPE_NONE = 0;

    /**
     * Indicates that a notification should have all content redacted, showing the public view.
     * Overrides all other redaction types.
     */
    int REDACTION_TYPE_PUBLIC = 1;

    /**
     * Indicates that a notification should have its main content redacted, due to detected
     * sensitive content, such as a One-Time Password
     */
    int REDACTION_TYPE_SENSITIVE_CONTENT = 1 << 1;

    /**
     * @param userId user Id
     * @return true if we're on a secure lock screen
     */
    boolean isLockscreenPublicMode(int userId);

    /**
     * Does a user require a separate work challenge? If so, the unlock mechanism is decoupled from
     * the current user and has to be solved separately.
     */
    default boolean needsSeparateWorkChallenge(int userId) {
        return false;
    }

    void setUpWithPresenter(NotificationPresenter presenter);

    int getCurrentUserId();

    boolean isCurrentProfile(int userId);

    /**
     *
     * @param userId user Id
     * @return true if user profile is running.
     */
    boolean isProfileAvailable(int userId);

    /** Adds a listener to be notified when the current user changes. */
    void addUserChangedListener(UserChangedListener listener);

    /**
     * Removes a listener previously registered with
     * {@link #addUserChangedListener(UserChangedListener)}
     */
    void removeUserChangedListener(UserChangedListener listener);

    SparseArray<UserInfo> getCurrentProfiles();

    boolean shouldShowLockscreenNotifications();

    boolean isAnyProfilePublicMode();

    void updatePublicMode();

    /**
     * Determine what type of redaction is needed, if any. Returns REDACTION_TYPE_NONE if no
     * redaction type is needed, REDACTION_TYPE_PUBLIC if private notifications are blocked, and
     * REDACTION_TYPE_SENSITIVE_CONTENT if sensitive content is detected, and REDACTION_TYPE_PUBLIC
     * doesn't apply.
     */
    @RedactionType int getRedactionType(NotificationEntry entry);

    /**
     * Has the given user chosen to allow their private (full) notifications to be shown even
     * when the lockscreen is in "public" (secure & locked) mode?
     */
    boolean userAllowsPrivateNotificationsInPublic(int currentUserId);

    /**
     * Has the given user chosen to allow notifications to be shown even when the lockscreen is in
     * "public" (secure & locked) mode?
     */
    boolean userAllowsNotificationsInPublic(int userId);

    /**
     * Adds a {@link NotificationStateChangedListener} to be notified of any state changes that
     * would affect presentation of notifications.
     */
    void addNotificationStateChangedListener(NotificationStateChangedListener listener);

    /**
     * Removes a {@link NotificationStateChangedListener} that was previously registered with
     * {@link #addNotificationStateChangedListener(NotificationStateChangedListener)}.
     */
    void removeNotificationStateChangedListener(NotificationStateChangedListener listener);

    /** Notified when the current user changes. */
    interface UserChangedListener {
        default void onUserChanged(int userId) {}
        default void onCurrentProfilesChanged(SparseArray<UserInfo> currentProfiles) {}
        default void onUserRemoved(int userId) {}
    }

    /**
     * Notified when any state pertaining to Notifications has changed; any methods pertaining to
     * notifications should be re-queried.
     */
    interface NotificationStateChangedListener {
        void onNotificationStateChanged();
    }
}
