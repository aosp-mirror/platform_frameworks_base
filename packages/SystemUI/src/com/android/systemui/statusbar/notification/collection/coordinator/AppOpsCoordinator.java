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

import static android.app.NotificationManager.IMPORTANCE_MIN;

import android.app.Notification;
import android.service.notification.StatusBarNotification;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

/**
 * Handles ForegroundService and AppOp interactions with notifications.
 *  Tags notifications with appOps
 *  Lifetime extends notifications associated with an ongoing ForegroundService.
 *  Filters out notifications that represent foreground services that are no longer running
 *  Puts foreground service notifications into the FGS section. See {@link NotifCoordinators} for
 *      section ordering priority.
 *
 * Previously this logic lived in
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceController
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceNotificationListener
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceLifetimeExtender
 */
@SysUISingleton
public class AppOpsCoordinator implements Coordinator {
    private static final String TAG = "AppOpsCoordinator";

    private final ForegroundServiceController mForegroundServiceController;
    private final AppOpsController mAppOpsController;
    private final DelayableExecutor mMainExecutor;

    private NotifPipeline mNotifPipeline;

    @Inject
    public AppOpsCoordinator(
            ForegroundServiceController foregroundServiceController,
            AppOpsController appOpsController,
            @Main DelayableExecutor mainExecutor) {
        mForegroundServiceController = foregroundServiceController;
        mAppOpsController = appOpsController;
        mMainExecutor = mainExecutor;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mNotifPipeline = pipeline;

        // filter out foreground service notifications that aren't necessary anymore
        mNotifPipeline.addPreGroupFilter(mNotifFilter);

    }

    public NotifSectioner getSectioner() {
        return mNotifSectioner;
    }

    /**
     * Filters out notifications that represent foreground services that are no longer running or
     * that already have an app notification with the appOps tagged to
     */
    private final NotifFilter mNotifFilter = new NotifFilter(TAG) {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            StatusBarNotification sbn = entry.getSbn();

            // Filters out system-posted disclosure notifications when unneeded
            if (mForegroundServiceController.isDisclosureNotification(sbn)
                    && !mForegroundServiceController.isDisclosureNeededForUser(
                            sbn.getUser().getIdentifier())) {
                return true;
            }
            return false;
        }
    };

    /**
     * Puts foreground service notifications into its own section.
     */
    private final NotifSectioner mNotifSectioner = new NotifSectioner("ForegroundService",
            NotificationPriorityBucketKt.BUCKET_FOREGROUND_SERVICE) {
        @Override
        public boolean isInSection(ListEntry entry) {
            NotificationEntry notificationEntry = entry.getRepresentativeEntry();
            if (notificationEntry != null) {
                Notification notification = notificationEntry.getSbn().getNotification();
                return notification.isForegroundService()
                        && notification.isColorized()
                        && entry.getRepresentativeEntry().getImportance() > IMPORTANCE_MIN;
            }
            return false;
        }
    };
}
