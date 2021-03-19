/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.people.widget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.NotificationStats;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.people.PeopleSpaceUtils;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.wmshell.BubblesManager;

import java.util.Optional;

import javax.inject.Inject;

/** Proxy activity to launch ShortcutInfo's conversation. */
public class LaunchConversationActivity extends Activity {
    private static final String TAG = "PeopleSpaceLaunchConv";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;
    private UiEventLogger mUiEventLogger = new UiEventLoggerImpl();
    private NotificationEntryManager mNotificationEntryManager;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private boolean mIsForTesting;
    private IStatusBarService mIStatusBarService;

    @Inject
    public LaunchConversationActivity(NotificationEntryManager notificationEntryManager,
            Optional<BubblesManager> bubblesManagerOptional) {
        super();
        mNotificationEntryManager = notificationEntryManager;
        mBubblesManagerOptional = bubblesManagerOptional;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (!mIsForTesting) {
            super.onCreate(savedInstanceState);
        }
        if (DEBUG) Log.d(TAG, "onCreate called");

        Intent intent = getIntent();
        String tileId = intent.getStringExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID);
        String packageName = intent.getStringExtra(PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME);
        UserHandle userHandle = intent.getParcelableExtra(
                PeopleSpaceWidgetProvider.EXTRA_USER_HANDLE);
        String notificationKey =
                intent.getStringExtra(PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY);

        if (!TextUtils.isEmpty(tileId)) {
            if (DEBUG) {
                Log.d(TAG, "Launching conversation with shortcutInfo id " + tileId);
            }
            mUiEventLogger.log(PeopleSpaceUtils.PeopleSpaceWidgetEvent.PEOPLE_SPACE_WIDGET_CLICKED);
            try {
                NotificationEntry entry = mNotificationEntryManager.getPendingOrActiveNotif(
                        notificationKey);
                if (entry != null && entry.canBubble() && mBubblesManagerOptional.isPresent()) {
                    if (DEBUG) Log.d(TAG, "Open bubble for conversation");
                    mBubblesManagerOptional.get().expandStackAndSelectBubble(entry);
                    // Just opt-out and don't cancel the notification for bubbles.
                    return;
                }

                if (mIStatusBarService == null) {
                    mIStatusBarService = IStatusBarService.Stub.asInterface(
                            ServiceManager.getService(Context.STATUS_BAR_SERVICE));
                }
                clearNotificationIfPresent(notificationKey, packageName, userHandle);
                LauncherApps launcherApps =
                        getApplicationContext().getSystemService(LauncherApps.class);
                launcherApps.startShortcut(
                        packageName, tileId, null, null, userHandle);
            } catch (Exception e) {
                Log.e(TAG, "Exception:" + e);
            }
        } else {
            if (DEBUG) Log.d(TAG, "Trying to launch conversation with null shortcutInfo.");
        }
        finish();
    }

    void clearNotificationIfPresent(String notifKey, String packageName, UserHandle userHandle) {
        if (TextUtils.isEmpty(notifKey)) {
            if (DEBUG) Log.d(TAG, "Skipping clear notification: notification key is empty");
            return;
        }

        try {
            if (mIStatusBarService == null || mNotificationEntryManager == null) {
                if (DEBUG) {
                    Log.d(TAG, "Skipping clear notification: null services, key: " + notifKey);
                }
                return;
            }

            NotificationEntry entry = mNotificationEntryManager.getPendingOrActiveNotif(notifKey);
            if (entry == null || entry.getRanking() == null) {
                if (DEBUG) {
                    Log.d(TAG, "Skipping clear notification: NotificationEntry or its Ranking"
                            + " is null, key: " + notifKey);
                }
                return;
            }

            int count = mNotificationEntryManager.getActiveNotificationsCount();
            int rank = entry.getRanking().getRank();
            NotificationVisibility notifVisibility = NotificationVisibility.obtain(notifKey,
                    rank, count, true);

            if (DEBUG) Log.d(TAG, "Clearing notification, key: " + notifKey + ", rank: " + rank);
            mIStatusBarService.onNotificationClear(
                    packageName, userHandle.getIdentifier(), notifKey,
                    NotificationStats.DISMISSAL_OTHER,
                    NotificationStats.DISMISS_SENTIMENT_POSITIVE, notifVisibility);
        } catch (Exception e) {
            Log.e(TAG, "Exception cancelling notification:" + e);
        }
    }

    @VisibleForTesting
    void setIsForTesting(boolean isForTesting, IStatusBarService statusBarService) {
        mIsForTesting = isForTesting;
        mIStatusBarService = statusBarService;
    }
}
