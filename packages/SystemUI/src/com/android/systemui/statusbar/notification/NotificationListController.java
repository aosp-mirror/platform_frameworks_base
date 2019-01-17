/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

/**
 * Root controller for the list of notifications in the shade.
 *
 * TODO: Much of the code in NotificationPresenter should eventually move in here. It will proxy
 * domain-specific behavior (ARC, etc) to subcontrollers.
 */
public class NotificationListController {
    private final NotificationEntryManager mEntryManager;
    private final NotificationListContainer mListContainer;
    private final ForegroundServiceController mForegroundServiceController;
    private final DeviceProvisionedController mDeviceProvisionedController;

    public NotificationListController(
            NotificationEntryManager entryManager,
            NotificationListContainer listContainer,
            ForegroundServiceController foregroundServiceController,
            DeviceProvisionedController deviceProvisionedController) {
        mEntryManager = checkNotNull(entryManager);
        mListContainer = checkNotNull(listContainer);
        mForegroundServiceController = checkNotNull(foregroundServiceController);
        mDeviceProvisionedController = checkNotNull(deviceProvisionedController);
    }

    /**
     * Causes the controller to register listeners on its dependencies. This method must be called
     * before the controller is ready to perform its duties.
     */
    public void bind() {
        mEntryManager.addNotificationEntryListener(mEntryListener);
        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);
    }

    /** Should be called when the list controller is being destroyed. */
    public void destroy() {
        mDeviceProvisionedController.removeCallback(mDeviceProvisionedListener);
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final NotificationEntryListener mEntryListener = new NotificationEntryListener() {
        @Override
        public void onEntryRemoved(
                NotificationEntry entry,
                NotificationVisibility visibility,
                boolean removedByUser) {
            mListContainer.cleanUpViewStateForEntry(entry);
        }

        @Override
        public void onBeforeNotificationAdded(NotificationEntry entry) {
            tagForeground(entry.notification);
        }
    };

    private final DeviceProvisionedListener mDeviceProvisionedListener =
            new DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    mEntryManager.updateNotifications();
                }
            };

    // TODO: This method is horrifically inefficient
    private void tagForeground(StatusBarNotification notification) {
        ArraySet<Integer> activeOps =
                mForegroundServiceController.getAppOps(
                        notification.getUserId(), notification.getPackageName());
        if (activeOps != null) {
            int len = activeOps.size();
            for (int i = 0; i < len; i++) {
                updateNotificationsForAppOp(activeOps.valueAt(i), notification.getUid(),
                        notification.getPackageName(), true);
            }
        }
    }

    /** When an app op changes, propagate that change to notifications. */
    public void updateNotificationsForAppOp(int appOp, int uid, String pkg, boolean showIcon) {
        String foregroundKey =
                mForegroundServiceController.getStandardLayoutKey(UserHandle.getUserId(uid), pkg);
        if (foregroundKey != null) {
            mEntryManager
                    .getNotificationData().updateAppOp(appOp, uid, pkg, foregroundKey, showIcon);
            mEntryManager.updateNotifications();
        }
    }
}
