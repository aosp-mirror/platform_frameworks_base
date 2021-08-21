/*
 * Copyright (C) 2021 The Android Open Source Project
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

import androidx.annotation.NonNull;

import com.android.systemui.communal.CommunalStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;

import javax.inject.Inject;

/**
 * {@link CommunalCoordinator} prevents notifications from showing on the keyguard when the communal
 * view is present.
 */
public class CommunalCoordinator implements Coordinator {
    final CommunalStateController mCommunalStateController;
    final NotificationEntryManager mNotificationEntryManager;
    final NotificationLockscreenUserManager mNotificationLockscreenUserManager;

    @Inject
    public CommunalCoordinator(NotificationEntryManager notificationEntryManager,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            CommunalStateController communalStateController) {
        mNotificationEntryManager = notificationEntryManager;
        mNotificationLockscreenUserManager = notificationLockscreenUserManager;
        mCommunalStateController = communalStateController;
    }

    final NotifFilter mFilter = new NotifFilter("CommunalCoordinator") {
        @Override
        public boolean shouldFilterOut(@NonNull NotificationEntry entry, long now) {
            return mCommunalStateController.getCommunalViewShowing();
        }
    };

    final CommunalStateController.Callback mStateCallback = new CommunalStateController.Callback() {
        @Override
        public void onCommunalViewShowingChanged() {
            mFilter.invalidateList();
            mNotificationEntryManager.updateNotifications("Communal mode state changed");
        }
    };

    @Override
    public void attach(@NonNull NotifPipeline pipeline) {
        pipeline.addPreGroupFilter(mFilter);
        mCommunalStateController.addCallback(mStateCallback);
        mNotificationLockscreenUserManager.addKeyguardNotificationSuppressor(
                entry -> mCommunalStateController.getCommunalViewShowing());
    }
}
