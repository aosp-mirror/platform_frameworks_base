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
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.util.Assert;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles ForegroundService and AppOp interactions with notifications.
 *  Tags notifications with appOps
 *  Lifetime extends notifications associated with an ongoing ForegroundService.
 *  Filters out notifications that represent foreground services that are no longer running
 *
 * Previously this logic lived in
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceController
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceNotificationListener
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceLifetimeExtender
 */
@Singleton
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

        // extend the lifetime of foreground notification services to show for at least 5 seconds
        mNotifPipeline.addNotificationLifetimeExtender(mForegroundLifetimeExtender);

        // listen for new notifications to add appOps
        mNotifPipeline.addCollectionListener(mNotifCollectionListener);

        // filter out foreground service notifications that aren't necessary anymore
        mNotifPipeline.addPreGroupFilter(mNotifFilter);

        // when appOps change, update any relevant notifications to update appOps for
        mAppOpsController.addCallback(ForegroundServiceController.APP_OPS, this::onAppOpsChanged);
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

            // Filters out system alert notifications when unneeded
            if (mForegroundServiceController.isSystemAlertNotification(sbn)) {
                final String[] apps = sbn.getNotification().extras.getStringArray(
                        Notification.EXTRA_FOREGROUND_APPS);
                if (apps != null && apps.length >= 1) {
                    if (!mForegroundServiceController.isSystemAlertWarningNeeded(
                            sbn.getUser().getIdentifier(), apps[0])) {
                        return true;
                    }
                }
            }
            return false;
        }
    };

    /**
     * Extends the lifetime of foreground notification services such that they show for at least
     * five seconds
     */
    private final NotifLifetimeExtender mForegroundLifetimeExtender =
            new NotifLifetimeExtender() {
        private static final int MIN_FGS_TIME_MS = 5000;
        private OnEndLifetimeExtensionCallback mEndCallback;
        private Map<NotificationEntry, Runnable> mEndRunnables = new HashMap<>();

        @Override
        public String getName() {
            return TAG;
        }

        @Override
        public void setCallback(OnEndLifetimeExtensionCallback callback) {
            mEndCallback = callback;
        }

        @Override
        public boolean shouldExtendLifetime(NotificationEntry entry, int reason) {
            if ((entry.getSbn().getNotification().flags
                    & Notification.FLAG_FOREGROUND_SERVICE) == 0) {
                return false;
            }

            final long currTime = System.currentTimeMillis();
            final boolean extendLife = currTime - entry.getSbn().getPostTime() < MIN_FGS_TIME_MS;

            if (extendLife) {
                if (!mEndRunnables.containsKey(entry)) {
                    final Runnable endExtensionRunnable = () -> {
                        mEndRunnables.remove(entry);
                        mEndCallback.onEndLifetimeExtension(
                                mForegroundLifetimeExtender,
                                entry);
                    };

                    final Runnable cancelRunnable = mMainExecutor.executeDelayed(
                            endExtensionRunnable,
                            MIN_FGS_TIME_MS - (currTime - entry.getSbn().getPostTime()));
                    mEndRunnables.put(entry, cancelRunnable);
                }
            }

            return extendLife;
        }

        @Override
        public void cancelLifetimeExtension(NotificationEntry entry) {
            Runnable cancelRunnable = mEndRunnables.remove(entry);
            if (cancelRunnable != null) {
                cancelRunnable.run();
            }
        }
    };

    /**
     * Adds appOps to incoming and updating notifications
     */
    private NotifCollectionListener mNotifCollectionListener = new NotifCollectionListener() {
        @Override
        public void onEntryAdded(NotificationEntry entry) {
            tagAppOps(entry);
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            tagAppOps(entry);
        }

        private void tagAppOps(NotificationEntry entry) {
            final StatusBarNotification sbn = entry.getSbn();
            // note: requires that the ForegroundServiceController is updating their appOps first
            ArraySet<Integer> activeOps =
                    mForegroundServiceController.getAppOps(
                            sbn.getUser().getIdentifier(),
                            sbn.getPackageName());

            entry.mActiveAppOps.clear();
            if (activeOps != null) {
                entry.mActiveAppOps.addAll(activeOps);
            }
        }
    };

    private void onAppOpsChanged(int code, int uid, String packageName, boolean active) {
        mMainExecutor.execute(() -> handleAppOpsChanged(code, uid, packageName, active));
    }

    /**
     * Update the appOp for the posted notification associated with the current foreground service
     *
     * @param code code for appOp to add/remove
     * @param uid of user the notification is sent to
     * @param packageName package that created the notification
     * @param active whether the appOpCode is active or not
     */
    private void handleAppOpsChanged(int code, int uid, String packageName, boolean active) {
        Assert.isMainThread();

        int userId = UserHandle.getUserId(uid);

        // Update appOps of the app's posted notifications with standard layouts
        final ArraySet<String> notifKeys =
                mForegroundServiceController.getStandardLayoutKeys(userId, packageName);
        if (notifKeys != null) {
            boolean changed = false;
            for (int i = 0; i < notifKeys.size(); i++) {
                final NotificationEntry entry = findNotificationEntryWithKey(notifKeys.valueAt(i));
                if (entry != null
                        && uid == entry.getSbn().getUid()
                        && packageName.equals(entry.getSbn().getPackageName())) {
                    if (active) {
                        changed |= entry.mActiveAppOps.add(code);
                    } else {
                        changed |= entry.mActiveAppOps.remove(code);
                    }
                }
            }
            if (changed) {
                mNotifFilter.invalidateList();
            }
        }
    }

    private NotificationEntry findNotificationEntryWithKey(String key) {
        for (NotificationEntry entry : mNotifPipeline.getAllNotifs()) {
            if (entry.getKey().equals(key)) {
                return entry;
            }
        }
        return null;
    }
}
