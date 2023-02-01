/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.job;

import static android.app.job.JobService.JOB_END_NOTIFICATION_POLICY_DETACH;
import static android.app.job.JobService.JOB_END_NOTIFICATION_POLICY_REMOVE;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.job.JobService;
import android.content.pm.UserPackage;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseSetArray;

import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;

class JobNotificationCoordinator {
    private static final String TAG = "JobNotificationCoordinator";

    /**
     * Mapping of UserPackage -> {notificationId -> List<JobServiceContext>} to track which jobs
     * are associated with each app's notifications.
     */
    private final ArrayMap<UserPackage, SparseSetArray<JobServiceContext>> mCurrentAssociations =
            new ArrayMap<>();
    /**
     * Set of NotificationDetails for each running job.
     */
    private final ArrayMap<JobServiceContext, NotificationDetails> mNotificationDetails =
            new ArrayMap<>();

    private static final class NotificationDetails {
        @NonNull
        public final UserPackage userPackage;
        public final int notificationId;
        public final int appPid;
        public final int appUid;
        @JobService.JobEndNotificationPolicy
        public final int jobEndNotificationPolicy;

        NotificationDetails(@NonNull UserPackage userPackage, int appPid, int appUid,
                int notificationId,
                @JobService.JobEndNotificationPolicy int jobEndNotificationPolicy) {
            this.userPackage = userPackage;
            this.notificationId = notificationId;
            this.appPid = appPid;
            this.appUid = appUid;
            this.jobEndNotificationPolicy = jobEndNotificationPolicy;
        }
    }

    private final NotificationManagerInternal mNotificationManagerInternal;

    JobNotificationCoordinator() {
        mNotificationManagerInternal = LocalServices.getService(NotificationManagerInternal.class);
    }

    void enqueueNotification(@NonNull JobServiceContext hostingContext, @NonNull String packageName,
            int callingPid, int callingUid, int notificationId, @NonNull Notification notification,
            @JobService.JobEndNotificationPolicy int jobEndNotificationPolicy) {
        validateNotification(packageName, callingUid, notification, jobEndNotificationPolicy);
        final NotificationDetails oldDetails = mNotificationDetails.get(hostingContext);
        if (oldDetails != null && oldDetails.notificationId != notificationId) {
            // App is switching notification IDs. Remove association with the old one.
            removeNotificationAssociation(hostingContext);
        }
        final int userId = UserHandle.getUserId(callingUid);
        // TODO(260848384): ensure apps can't cancel the notification for user-initiated job
        //       eg., by calling NotificationManager.cancel/All or deleting the notification channel
        mNotificationManagerInternal.enqueueNotification(
                packageName, packageName, callingUid, callingPid, /* tag */ null,
                notificationId, notification, userId);
        final UserPackage userPackage = UserPackage.of(userId, packageName);
        final NotificationDetails details = new NotificationDetails(
                userPackage, callingPid, callingUid, notificationId, jobEndNotificationPolicy);
        SparseSetArray<JobServiceContext> appNotifications = mCurrentAssociations.get(userPackage);
        if (appNotifications == null) {
            appNotifications = new SparseSetArray<>();
            mCurrentAssociations.put(userPackage, appNotifications);
        }
        appNotifications.add(notificationId, hostingContext);
        mNotificationDetails.put(hostingContext, details);
    }

    void removeNotificationAssociation(@NonNull JobServiceContext hostingContext) {
        final NotificationDetails details = mNotificationDetails.remove(hostingContext);
        if (details == null) {
            return;
        }
        final SparseSetArray<JobServiceContext> associations =
                mCurrentAssociations.get(details.userPackage);
        if (associations == null || !associations.remove(details.notificationId, hostingContext)) {
            Slog.wtf(TAG, "Association data structures not in sync");
            return;
        }
        ArraySet<JobServiceContext> associatedContexts = associations.get(details.notificationId);
        if (associatedContexts == null || associatedContexts.isEmpty()) {
            // No more jobs using this notification. Apply the final job stop policy.
            if (details.jobEndNotificationPolicy == JOB_END_NOTIFICATION_POLICY_REMOVE) {
                final String packageName = details.userPackage.packageName;
                mNotificationManagerInternal.cancelNotification(
                        packageName, packageName, details.appUid, details.appPid, /* tag */ null,
                        details.notificationId, UserHandle.getUserId(details.appUid));
            }
        }
    }

    private void validateNotification(@NonNull String packageName, int callingUid,
            @NonNull Notification notification,
            @JobService.JobEndNotificationPolicy int jobEndNotificationPolicy) {
        if (notification == null) {
            throw new NullPointerException("notification");
        }
        if (notification.getSmallIcon() == null) {
            throw new IllegalArgumentException("small icon required");
        }
        if (null == mNotificationManagerInternal.getNotificationChannel(
                packageName, callingUid, notification.getChannelId())) {
            throw new IllegalArgumentException("invalid notification channel");
        }
        if (jobEndNotificationPolicy != JOB_END_NOTIFICATION_POLICY_DETACH
                && jobEndNotificationPolicy != JOB_END_NOTIFICATION_POLICY_REMOVE) {
            throw new IllegalArgumentException("invalid job end notification policy");
        }
    }
}
