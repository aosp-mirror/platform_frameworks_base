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

package com.android.systemui.statusbar.notification.collection.coordinator;

import android.app.Notification;
import android.content.pm.IPackageManager;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;

import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import javax.inject.Inject;

/**
 * Filters out most notifications when the device is unprovisioned.
 * Special notifications with extra permissions and tags won't be filtered out even when the
 * device is unprovisioned.
 */
@CoordinatorScope
public class DeviceProvisionedCoordinator implements Coordinator {
    private static final String TAG = "DeviceProvisionedCoordinator";

    private final DeviceProvisionedController mDeviceProvisionedController;
    private final IPackageManager mIPackageManager;

    @Inject
    public DeviceProvisionedCoordinator(DeviceProvisionedController deviceProvisionedController,
            IPackageManager packageManager) {
        mDeviceProvisionedController = deviceProvisionedController;
        mIPackageManager = packageManager;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);

        pipeline.addPreGroupFilter(mNotifFilter);
    }

    private final NotifFilter mNotifFilter = new NotifFilter(TAG) {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return !mDeviceProvisionedController.isDeviceProvisioned()
                    && !showNotificationEvenIfUnprovisioned(entry.getSbn());
        }
    };

    /**
     * Only notifications coming from packages with permission
     * android.permission.NOTIFICATION_DURING_SETUP that also have special tags
     * marking them as relevant for setup are allowed to show when device is unprovisioned
     */
    private boolean showNotificationEvenIfUnprovisioned(StatusBarNotification sbn) {
        // system_server checks the permission so systemui can just check whether the
        // extra exists
        return sbn.getNotification().extras.getBoolean(Notification.EXTRA_ALLOW_DURING_SETUP);
    }

    private int checkUidPermission(String permission, int uid) {
        try {
            return mIPackageManager.checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final DeviceProvisionedController.DeviceProvisionedListener mDeviceProvisionedListener =
            new DeviceProvisionedController.DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    mNotifFilter.invalidateList("onDeviceProvisionedChanged");
                }
            };
}
