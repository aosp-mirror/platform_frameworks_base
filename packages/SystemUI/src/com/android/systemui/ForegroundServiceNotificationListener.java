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

package com.android.systemui;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Updates foreground service notification state in response to notification data events. */
@Singleton
public class ForegroundServiceNotificationListener {

    private static final String TAG = "FgServiceController";
    private static final boolean DBG = false;

    private final Context mContext;
    private final ForegroundServiceController mForegroundServiceController;

    @Inject
    public ForegroundServiceNotificationListener(Context context,
            ForegroundServiceController foregroundServiceController,
            NotificationEntryManager notificationEntryManager) {
        mContext = context;
        mForegroundServiceController = foregroundServiceController;
        notificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onPendingEntryAdded(NotificationEntry entry) {
                addNotification(entry.notification, entry.importance);
            }

            @Override
            public void onPostEntryUpdated(NotificationEntry entry) {
                updateNotification(entry.notification, entry.importance);
            }

            @Override
            public void onEntryRemoved(
                    NotificationEntry entry,
                    NotificationVisibility visibility,
                    boolean removedByUser) {
                removeNotification(entry.notification);
            }
        });
    }

    /**
     * @param sbn notification that was just posted
     */
    private void addNotification(StatusBarNotification sbn, int importance) {
        updateNotification(sbn, importance);
    }

    /**
     * @param sbn notification that was just removed
     */
    private void removeNotification(StatusBarNotification sbn) {
        mForegroundServiceController.updateUserState(
                sbn.getUserId(),
                new ForegroundServiceController.UserStateUpdateCallback() {
                    @Override
                    public boolean updateUserState(ForegroundServicesUserState userState) {
                        if (mForegroundServiceController.isDisclosureNotification(sbn)) {
                            // if you remove the dungeon entirely, we take that to mean there are
                            // no running services
                            userState.setRunningServices(null, 0);
                            return true;
                        } else {
                            // this is safe to call on any notification, not just
                            // FLAG_FOREGROUND_SERVICE
                            return userState.removeNotification(sbn.getPackageName(), sbn.getKey());
                        }
                    }

                    @Override
                    public void userStateNotFound(int userId) {
                        if (DBG) {
                            Log.w(TAG, String.format(
                                    "user %d with no known notifications got removeNotification "
                                            + "for %s",
                                    sbn.getUserId(), sbn));
                        }
                    }
                },
                false /* don't create */);
    }

    /**
     * @param sbn notification that was just changed in some way
     */
    private void updateNotification(StatusBarNotification sbn, int newImportance) {
        mForegroundServiceController.updateUserState(
                sbn.getUserId(),
                userState -> {
                    if (mForegroundServiceController.isDisclosureNotification(sbn)) {
                        final Bundle extras = sbn.getNotification().extras;
                        if (extras != null) {
                            final String[] svcs = extras.getStringArray(
                                    Notification.EXTRA_FOREGROUND_APPS);
                            userState.setRunningServices(svcs, sbn.getNotification().when);
                        }
                    } else {
                        userState.removeNotification(sbn.getPackageName(), sbn.getKey());
                        if (0 != (sbn.getNotification().flags
                                & Notification.FLAG_FOREGROUND_SERVICE)) {
                            if (newImportance > NotificationManager.IMPORTANCE_MIN) {
                                userState.addImportantNotification(sbn.getPackageName(),
                                        sbn.getKey());
                            }
                            final Notification.Builder builder =
                                    Notification.Builder.recoverBuilder(
                                            mContext, sbn.getNotification());
                            if (builder.usesStandardHeader()) {
                                userState.addStandardLayoutNotification(
                                        sbn.getPackageName(), sbn.getKey());
                            }
                        }
                    }
                    return true;
                },
                true /* create if not found */);
    }
}
