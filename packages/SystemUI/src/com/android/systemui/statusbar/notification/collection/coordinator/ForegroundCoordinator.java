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
import android.os.Handler;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifListBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles ForegroundService interactions with notifications.
 *  Tags notifications with appOps.
 *  Lifetime extends notifications associated with an ongoing ForegroundService.
 *  Filters out notifications that represent foreground services that are no longer running
 *
 * Previously this logic lived in
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceController
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceNotificationListener
 *  frameworks/base/packages/SystemUI/src/com/android/systemui/ForegroundServiceLifetimeExtender
 */
@Singleton
public class ForegroundCoordinator implements Coordinator {
    private static final String TAG = "ForegroundCoordinator";

    private final ForegroundServiceController mForegroundServiceController;
    private final AppOpsController mAppOpsController;
    private final Handler mMainHandler;

    private NotifCollection mNotifCollection;

    @Inject
    public ForegroundCoordinator(
            ForegroundServiceController foregroundServiceController,
            AppOpsController appOpsController,
            @Main Handler mainHandler) {
        mForegroundServiceController = foregroundServiceController;
        mAppOpsController = appOpsController;
        mMainHandler = mainHandler;
    }

    @Override
    public void attach(NotifCollection notifCollection, NotifListBuilder notifListBuilder) {
        mNotifCollection = notifCollection;

        // extend the lifetime of foreground notification services to show for at least 5 seconds
        mNotifCollection.addNotificationLifetimeExtender(mForegroundLifetimeExtender);

        // listen for new notifications to add appOps
        mNotifCollection.addCollectionListener(mNotifCollectionListener);

        // when appOps change, update any relevant notifications to update appOps for
        mAppOpsController.addCallback(ForegroundServiceController.APP_OPS, this::onAppOpsChanged);

        // filter out foreground service notifications that aren't necessary anymore
        notifListBuilder.addPreGroupFilter(mNotifFilter);
    }

    /**
     * Filters out notifications that represent foreground services that are no longer running.
     */
    private final NotifFilter mNotifFilter = new NotifFilter(TAG) {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            StatusBarNotification sbn = entry.getSbn();
            if (mForegroundServiceController.isDisclosureNotification(sbn)
                    && !mForegroundServiceController.isDisclosureNeededForUser(sbn.getUserId())) {
                return true;
            }

            if (mForegroundServiceController.isSystemAlertNotification(sbn)) {
                final String[] apps = sbn.getNotification().extras.getStringArray(
                        Notification.EXTRA_FOREGROUND_APPS);
                if (apps != null && apps.length >= 1) {
                    if (!mForegroundServiceController.isSystemAlertWarningNeeded(
                            sbn.getUserId(), apps[0])) {
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
        private Map<String, Runnable> mEndRunnables = new HashMap<>();

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
                if (!mEndRunnables.containsKey(entry.getKey())) {
                    final Runnable runnable = new Runnable() {
                        @Override
                        public void run() {
                            mEndCallback.onEndLifetimeExtension(mForegroundLifetimeExtender, entry);
                        }
                    };
                    mEndRunnables.put(entry.getKey(), runnable);
                    mMainHandler.postDelayed(runnable,
                            MIN_FGS_TIME_MS - (currTime - entry.getSbn().getPostTime()));
                }
            }

            return extendLife;
        }

        @Override
        public void cancelLifetimeExtension(NotificationEntry entry) {
            if (mEndRunnables.containsKey(entry.getKey())) {
                Runnable endRunnable = mEndRunnables.remove(entry.getKey());
                mMainHandler.removeCallbacks(endRunnable);
            }
        }
    };

    /**
     * Adds appOps to incoming and updating notifications
     */
    private NotifCollectionListener mNotifCollectionListener = new NotifCollectionListener() {
        @Override
        public void onEntryAdded(NotificationEntry entry) {
            tagForeground(entry);
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            tagForeground(entry);
        }

        private void tagForeground(NotificationEntry entry) {
            final StatusBarNotification sbn = entry.getSbn();
            // note: requires that the ForegroundServiceController is updating their appOps first
            ArraySet<Integer> activeOps = mForegroundServiceController.getAppOps(sbn.getUserId(),
                    sbn.getPackageName());
            if (activeOps != null) {
                synchronized (entry.mActiveAppOps) {
                    entry.mActiveAppOps.clear();
                    entry.mActiveAppOps.addAll(activeOps);
                }
            }
        }
    };

    /**
     * Update the appOp for the posted notification associated with the current foreground service
     * @param code code for appOp to add/remove
     * @param uid of user the notification is sent to
     * @param packageName package that created the notification
     * @param active whether the appOpCode is active or not
     */
    private void onAppOpsChanged(int code, int uid, String packageName, boolean active) {
        int userId = UserHandle.getUserId(uid);

        // Update appOp if there's an associated posted notification:
        final String foregroundKey = mForegroundServiceController.getStandardLayoutKey(userId,
                packageName);
        if (foregroundKey != null) {
            final NotificationEntry entry = findNotificationEntryWithKey(foregroundKey);
            if (entry != null
                    && uid == entry.getSbn().getUid()
                    && packageName.equals(entry.getSbn().getPackageName())) {
                boolean changed;
                synchronized (entry.mActiveAppOps) {
                    if (active) {
                        changed = entry.mActiveAppOps.add(code);
                    } else {
                        changed = entry.mActiveAppOps.remove(code);
                    }
                }
                if (changed) {
                    mMainHandler.post(mNotifFilter::invalidateList);
                }
            }
        }
    }

    private NotificationEntry findNotificationEntryWithKey(String key) {
        for (NotificationEntry entry : mNotifCollection.getNotifs()) {
            if (entry.getKey().equals(key)) {
                return entry;
            }
        }
        return null;
    }
}
