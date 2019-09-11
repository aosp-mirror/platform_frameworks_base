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

import android.Manifest;
import android.app.AppGlobals;
import android.app.Notification;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Component which manages the various reasons a notification might be filtered out. */
@Singleton
public class NotificationFilter {

    private final NotificationGroupManager mGroupManager = Dependency.get(
            NotificationGroupManager.class);

    private NotificationData.KeyguardEnvironment mEnvironment;
    private ShadeController mShadeController;
    private ForegroundServiceController mFsc;
    private NotificationLockscreenUserManager mUserManager;

    @Inject
    public NotificationFilter() {}

    private NotificationData.KeyguardEnvironment getEnvironment() {
        if (mEnvironment == null) {
            mEnvironment = Dependency.get(NotificationData.KeyguardEnvironment.class);
        }
        return mEnvironment;
    }

    private ShadeController getShadeController() {
        if (mShadeController == null) {
            mShadeController = Dependency.get(ShadeController.class);
        }
        return mShadeController;
    }

    private ForegroundServiceController getFsc() {
        if (mFsc == null) {
            mFsc = Dependency.get(ForegroundServiceController.class);
        }
        return mFsc;
    }

    private NotificationLockscreenUserManager getUserManager() {
        if (mUserManager == null) {
            mUserManager = Dependency.get(NotificationLockscreenUserManager.class);
        }
        return mUserManager;
    }


    /**
     * @return true if the provided notification should NOT be shown right now.
     */
    public boolean shouldFilterOut(NotificationEntry entry) {
        final StatusBarNotification sbn = entry.notification;
        if (!(getEnvironment().isDeviceProvisioned()
                || showNotificationEvenIfUnprovisioned(sbn))) {
            return true;
        }

        if (!getEnvironment().isNotificationForCurrentProfiles(sbn)) {
            return true;
        }

        if (getUserManager().isLockscreenPublicMode(sbn.getUserId())
                && (sbn.getNotification().visibility == Notification.VISIBILITY_SECRET
                        || getUserManager().shouldHideNotifications(sbn.getUserId())
                        || getUserManager().shouldHideNotifications(sbn.getKey()))) {
            return true;
        }

        if (getShadeController().isDozing() && entry.shouldSuppressAmbient()) {
            return true;
        }

        if (!getShadeController().isDozing() && entry.shouldSuppressNotificationList()) {
            return true;
        }

        if (entry.isSuspended()) {
            return true;
        }

        if (!StatusBar.ENABLE_CHILD_NOTIFICATIONS
                && mGroupManager.isChildInGroupWithSummary(sbn)) {
            return true;
        }

        if (getFsc().isDisclosureNotification(sbn)
                && !getFsc().isDisclosureNeededForUser(sbn.getUserId())) {
            // this is a foreground-service disclosure for a user that does not need to show one
            return true;
        }
        if (getFsc().isSystemAlertNotification(sbn)) {
            final String[] apps = sbn.getNotification().extras.getStringArray(
                    Notification.EXTRA_FOREGROUND_APPS);
            if (apps != null && apps.length >= 1) {
                if (!getFsc().isSystemAlertWarningNeeded(sbn.getUserId(), apps[0])) {
                    return true;
                }
            }
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
