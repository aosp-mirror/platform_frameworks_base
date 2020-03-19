/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import android.content.pm.UserInfo;
import android.util.SparseArray;

import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;

import javax.inject.Inject;

/**
 * A coordinator that filters out notifications for other users
 *
 * The NotifCollection contains the notifs for ALL users, so we need to remove any notifications
 * that have been posted specifically to other users. Note that some system notifications are not
 * posted to any particular user, and so must be shown to everyone.
 *
 * TODO: The NotificationLockscreenUserManager currently maintains the list of active user profiles.
 *  We should spin that off into a standalone section at some point.
 */
public class HideNotifsForOtherUsersCoordinator implements Coordinator {
    private final NotificationLockscreenUserManager mLockscreenUserManager;

    @Inject
    public HideNotifsForOtherUsersCoordinator(
            NotificationLockscreenUserManager lockscreenUserManager) {
        mLockscreenUserManager = lockscreenUserManager;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        pipeline.addPreGroupFilter(mFilter);
        mLockscreenUserManager.addUserChangedListener(mUserChangedListener);
    }

    private final NotifFilter mFilter = new NotifFilter("NotCurrentUserFilter") {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return !mLockscreenUserManager
                    .isCurrentProfile(entry.getSbn().getUser().getIdentifier());
        }
    };

    private final UserChangedListener mUserChangedListener = new UserChangedListener() {
        @Override
        public void onCurrentProfilesChanged(SparseArray<UserInfo> currentProfiles) {
            mFilter.invalidateList();
        }
    };
}
