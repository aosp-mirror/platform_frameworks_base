/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.StatusBar.DEBUG;
import static com.android.systemui.statusbar.phone.StatusBar.MULTIUSER_DEBUG;

import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.collection.NotificationData.KeyguardEnvironment;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class KeyguardEnvironmentImpl implements KeyguardEnvironment {

    private static final String TAG = "KeyguardEnvironmentImpl";

    private final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);
    private final DeviceProvisionedController mDeviceProvisionedController =
            Dependency.get(DeviceProvisionedController.class);

    @Inject
    public KeyguardEnvironmentImpl() {
    }

    @Override  // NotificationData.KeyguardEnvironment
    public boolean isDeviceProvisioned() {
        return mDeviceProvisionedController.isDeviceProvisioned();
    }

    @Override  // NotificationData.KeyguardEnvironment
    public boolean isNotificationForCurrentProfiles(StatusBarNotification n) {
        final int notificationUserId = n.getUserId();
        if (DEBUG && MULTIUSER_DEBUG) {
            Log.v(TAG, String.format("%s: current userid: %d, notification userid: %d", n,
                    mLockscreenUserManager.getCurrentUserId(), notificationUserId));
        }
        return mLockscreenUserManager.isCurrentProfile(notificationUserId);
    }
}
