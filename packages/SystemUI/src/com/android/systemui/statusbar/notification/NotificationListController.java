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

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

import java.util.Objects;

/**
 * Root controller for the list of notifications in the shade.
 *
 * TODO: Much of the code in NotificationPresenter should eventually move in here. It will proxy
 * domain-specific behavior (ARC, etc) to subcontrollers.
 */
public class NotificationListController {
    private final NotificationEntryManager mEntryManager;
    private final NotificationListContainer mListContainer;
    private final DeviceProvisionedController mDeviceProvisionedController;

    public NotificationListController(
            NotificationEntryManager entryManager,
            NotificationListContainer listContainer,
            DeviceProvisionedController deviceProvisionedController) {
        mEntryManager = Objects.requireNonNull(entryManager);
        mListContainer = Objects.requireNonNull(listContainer);
        mDeviceProvisionedController = Objects.requireNonNull(deviceProvisionedController);
    }

    /**
     * Causes the controller to register listeners on its dependencies. This method must be called
     * before the controller is ready to perform its duties.
     */
    public void bind() {
        mEntryManager.addNotificationEntryListener(mEntryListener);
        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final NotificationEntryListener mEntryListener = new NotificationEntryListener() {
        @Override
        public void onEntryRemoved(
                NotificationEntry entry,
                NotificationVisibility visibility,
                boolean removedByUser,
                int reason) {
            mListContainer.cleanUpViewStateForEntry(entry);
        }
    };

    // TODO: (b/145659174) remove after moving to NewNotifPipeline. Replaced by
    //  DeviceProvisionedCoordinator
    private final DeviceProvisionedListener mDeviceProvisionedListener =
            new DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    mEntryManager.updateNotifications("device provisioned changed");
                }
            };
}
