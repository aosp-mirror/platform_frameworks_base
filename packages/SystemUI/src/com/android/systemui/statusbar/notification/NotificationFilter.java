/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.systemui.media.MediaDataManagerKt.isMediaNotification;

import android.Manifest;
import android.app.AppGlobals;
import android.app.Notification;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.media.MediaFeatureFlag;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import javax.inject.Inject;

/** Component which manages the various reasons a notification might be filtered out.*/
// TODO: delete NotificationFilter.java after migrating to new NotifPipeline b/145659174.
//  Notification filtering is taken care of across the different Coordinators (mostly
//  KeyguardCoordinator.java)
@SysUISingleton
public class NotificationFilter {

    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardEnvironment mKeyguardEnvironment;
    private final ForegroundServiceController mForegroundServiceController;
    private final NotificationLockscreenUserManager mUserManager;
    private final Boolean mIsMediaFlagEnabled;

    @Inject
    public NotificationFilter(
            StatusBarStateController statusBarStateController,
            KeyguardEnvironment keyguardEnvironment,
            ForegroundServiceController foregroundServiceController,
            NotificationLockscreenUserManager userManager,
            MediaFeatureFlag mediaFeatureFlag) {
        mStatusBarStateController = statusBarStateController;
        mKeyguardEnvironment = keyguardEnvironment;
        mForegroundServiceController = foregroundServiceController;
        mUserManager = userManager;
        mIsMediaFlagEnabled = mediaFeatureFlag.getEnabled();
    }

    /**
     * @return true if the provided notification should NOT be shown right now.
     */
    public boolean shouldFilterOut(NotificationEntry entry) {
        final StatusBarNotification sbn = entry.getSbn();
        if (!(mKeyguardEnvironment.isDeviceProvisioned()
                || showNotificationEvenIfUnprovisioned(sbn))) {
            return true;
        }

        if (!mKeyguardEnvironment.isNotificationForCurrentProfiles(sbn)) {
            return true;
        }

        if (mUserManager.isLockscreenPublicMode(sbn.getUserId())
                && (sbn.getNotification().visibility == Notification.VISIBILITY_SECRET
                        || mUserManager.shouldHideNotifications(sbn.getUserId())
                        || mUserManager.shouldHideNotifications(sbn.getKey()))) {
            return true;
        }

        if (mStatusBarStateController.isDozing() && entry.shouldSuppressAmbient()) {
            return true;
        }

        if (!mStatusBarStateController.isDozing() && entry.shouldSuppressNotificationList()) {
            return true;
        }

        if (entry.getRanking().isSuspended()) {
            return true;
        }

        if (mForegroundServiceController.isDisclosureNotification(sbn)
                && !mForegroundServiceController.isDisclosureNeededForUser(sbn.getUserId())) {
            // this is a foreground-service disclosure for a user that does not need to show one
            return true;
        }

        if (mIsMediaFlagEnabled && isMediaNotification(sbn)) {
            return true;
        }
        return false;
    }

    // Q: What kinds of notifications should show during setup?
    // A: Almost none! Only things coming from packages with permission
    // android.permission.NOTIFICATION_DURING_SETUP that also have special "kind" tags marking them
    // as relevant for setup (see below).
    private static boolean showNotificationEvenIfUnprovisioned(StatusBarNotification sbn) {
        return showNotificationEvenIfUnprovisioned(AppGlobals.getPackageManager(), sbn);
    }

    @VisibleForTesting
    static boolean showNotificationEvenIfUnprovisioned(IPackageManager packageManager,
            StatusBarNotification sbn) {
        return checkUidPermission(packageManager, Manifest.permission.NOTIFICATION_DURING_SETUP,
                sbn.getUid()) == PackageManager.PERMISSION_GRANTED
                && sbn.getNotification().extras.getBoolean(Notification.EXTRA_ALLOW_DURING_SETUP);
    }

    private static int checkUidPermission(IPackageManager packageManager, String permission,
            int uid) {
        try {
            return packageManager.checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
