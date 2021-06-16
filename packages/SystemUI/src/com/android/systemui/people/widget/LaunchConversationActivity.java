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
import android.os.UserManager;
import android.service.notification.NotificationStats;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.people.PeopleSpaceUtils;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubble;

import java.util.Optional;

import javax.inject.Inject;

/** Proxy activity to launch ShortcutInfo's conversation. */
public class LaunchConversationActivity extends Activity {
    private static final String TAG = "PeopleSpaceLaunchConv";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;
    private UiEventLogger mUiEventLogger = new UiEventLoggerImpl();
    private NotificationEntryManager mNotificationEntryManager;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final UserManager mUserManager;
    private boolean mIsForTesting;
    private IStatusBarService mIStatusBarService;
    private CommandQueue mCommandQueue;
    private Bubble mBubble;
    private NotificationEntry mEntryToBubble;

    @Inject
    public LaunchConversationActivity(NotificationEntryManager notificationEntryManager,
            Optional<BubblesManager> bubblesManagerOptional, UserManager userManager,
            CommandQueue commandQueue) {
        super();
        mNotificationEntryManager = notificationEntryManager;
        mBubblesManagerOptional = bubblesManagerOptional;
        mUserManager = userManager;
        mCommandQueue = commandQueue;
        mCommandQueue.addCallback(new CommandQueue.Callbacks() {
            // (b/190833924) Wait for the app transition to finish before showing the bubble,
            // opening the bubble while the transition is happening can mess with the placement
            // of the  bubble's surface.
            @Override
            public void appTransitionFinished(int displayId) {
                if (mBubblesManagerOptional.isPresent()) {
                    if (mBubble != null) {
                        mBubblesManagerOptional.get().expandStackAndSelectBubble(mBubble);
                    } else if (mEntryToBubble != null) {
                        mBubblesManagerOptional.get().expandStackAndSelectBubble(mEntryToBubble);
                    }
                }
                mCommandQueue.removeCallback(this);
            }
        });
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

                if (mUserManager.isQuietModeEnabled(userHandle)) {
                    if (DEBUG) Log.d(TAG, "Cannot launch app when quieted");
                    final Intent dialogIntent =
                            UnlaunchableAppActivity.createInQuietModeDialogIntent(
                                    userHandle.getIdentifier());
                    this.getApplicationContext().startActivity(dialogIntent);
                    finish();
                    return;
                }

                // We can potentially bubble without a notification, so rather than rely on
                // notificationKey here (which could be null if there's no notification or if the
                // bubble is suppressing the notification), so we'll use the shortcutId for lookups.
                // This misses one specific case: a bubble that was never opened & still has a
                // visible notification, but the bubble was dismissed & aged out of the overflow.
                // So it wouldn't exist in the stack or overflow to be looked up BUT the notif entry
                // would still exist & be bubbleable. So if we don't get a bubble from the
                // shortcutId, fallback to notificationKey if it exists.
                if (mBubblesManagerOptional.isPresent()) {
                    mBubble = mBubblesManagerOptional.get().getBubbleWithShortcutId(tileId);
                    NotificationEntry entry = mNotificationEntryManager.getPendingOrActiveNotif(
                            notificationKey);
                    if (mBubble != null || (entry != null && entry.canBubble())) {
                        mEntryToBubble = entry;
                        if (DEBUG) {
                            Log.d(TAG,
                                    "Opening bubble: " + mBubble  + ", entry: " + mEntryToBubble);
                        }
                        // Just opt-out and don't cancel the notification for bubbles.
                        finish();
                        return;
                    }
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
