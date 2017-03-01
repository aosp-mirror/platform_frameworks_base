/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.KeyChain.KeyChainConnection;
import android.util.Log;

import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MonitoringCertNotificationTask extends AsyncTask<Integer, Void, Void> {
    protected static final String LOG_TAG = DevicePolicyManagerService.LOG_TAG;
    protected static final int MONITORING_CERT_NOTIFICATION_ID = R.plurals.ssl_ca_cert_warning;

    private final DevicePolicyManagerService mService;
    private final DevicePolicyManagerService.Injector mInjector;

    public MonitoringCertNotificationTask(final DevicePolicyManagerService service,
            final DevicePolicyManagerService.Injector injector) {
        super();
        mService = service;
        mInjector = injector;
    }

    @Override
    protected Void doInBackground(Integer... params) {
        int userHandle = params[0];

        if (userHandle == UserHandle.USER_ALL) {
            for (UserInfo userInfo : mInjector.getUserManager().getUsers(true)) {
                repostOrClearNotification(userInfo.getUserHandle());
            }
        } else {
            repostOrClearNotification(UserHandle.of(userHandle));
        }
        return null;
    }

    private void repostOrClearNotification(UserHandle userHandle) {
        if (!mInjector.getUserManager().isUserUnlocked(userHandle.getIdentifier())) {
            return;
        }

        // Call out to KeyChain to check for CAs which are waiting for approval.
        final int pendingCertificateCount;
        try {
            pendingCertificateCount = mService.retainAcceptedCertificates(
                    userHandle, getInstalledCaCertificates(userHandle));
        } catch (RemoteException | RuntimeException e) {
            Log.e(LOG_TAG, "Could not retrieve certificates from KeyChain service", e);
            return;
        }

        if (pendingCertificateCount != 0) {
            showNotification(userHandle, pendingCertificateCount);
        } else {
            mInjector.getNotificationManager().cancelAsUser(
                    LOG_TAG, MONITORING_CERT_NOTIFICATION_ID, userHandle);
        }
    }

    private void showNotification(UserHandle userHandle, int pendingCertificateCount) {
        // Create a context for the target user.
        final Context userContext;
        try {
            userContext = mInjector.createContextAsUser(userHandle);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Create context as " + userHandle + " failed", e);
            return;
        }

        // Build and show a warning notification
        int smallIconId;
        String contentText;
        int parentUserId = userHandle.getIdentifier();
        Resources resources = mInjector.getResources();
        if (mService.getProfileOwner(userHandle.getIdentifier()) != null) {
            contentText = resources.getString(R.string.ssl_ca_cert_noti_managed,
                    mService.getProfileOwnerName(userHandle.getIdentifier()));
            smallIconId = R.drawable.stat_sys_certificate_info;
            parentUserId = mService.getProfileParentId(userHandle.getIdentifier());
        } else if (mService.getDeviceOwnerUserId() == userHandle.getIdentifier()) {
            contentText = resources.getString(R.string.ssl_ca_cert_noti_managed,
                    mService.getDeviceOwnerName());
            smallIconId = R.drawable.stat_sys_certificate_info;
        } else {
            contentText = resources.getString(R.string.ssl_ca_cert_noti_by_unknown);
            smallIconId = android.R.drawable.stat_sys_warning;
        }

        Intent dialogIntent = new Intent(Settings.ACTION_MONITORING_CERT_INFO);
        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // TODO this next line is taken from original notification code in
        // {@link DevicePolicyManagerService} but not a very good way of doing it. Do it better.
        dialogIntent.setPackage("com.android.settings");
        dialogIntent.putExtra(Settings.EXTRA_NUMBER_OF_CERTIFICATES, pendingCertificateCount);
        dialogIntent.putExtra(Intent.EXTRA_USER_ID, userHandle.getIdentifier());
        PendingIntent notifyIntent = PendingIntent.getActivityAsUser(userContext, 0,
                dialogIntent, PendingIntent.FLAG_UPDATE_CURRENT, null,
                UserHandle.of(parentUserId));

        final Notification noti =
                new Notification.Builder(userContext, SystemNotificationChannels.SECURITY)
                        .setSmallIcon(smallIconId)
                        .setContentTitle(resources.getQuantityText(R.plurals.ssl_ca_cert_warning,
                                pendingCertificateCount))
                        .setContentText(contentText)
                        .setContentIntent(notifyIntent)
                        .setShowWhen(false)
                        .setColor(R.color.system_notification_accent_color)
                        .build();

        mInjector.getNotificationManager().notifyAsUser(
                LOG_TAG, MONITORING_CERT_NOTIFICATION_ID, noti, userHandle);
    }

    private List<String> getInstalledCaCertificates(UserHandle userHandle)
            throws RemoteException, RuntimeException {
        try (KeyChainConnection conn = mInjector.keyChainBindAsUser(userHandle)) {
            return conn.getService().getUserCaAliases().getList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (AssertionError e) {
            throw new RuntimeException(e);
        }
    }
}
