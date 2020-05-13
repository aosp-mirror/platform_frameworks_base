/**
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
package com.android.server.notification;

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationChannel.ALLOW_BUBBLE_OFF;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_NONE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_SELECTED;

import static com.android.internal.util.FrameworkStatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_MISSING;
import static com.android.internal.util.FrameworkStatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_NOT_RESIZABLE;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Determines whether a bubble can be shown for this notification.
 */
public class BubbleExtractor implements NotificationSignalExtractor {
    private static final String TAG = "BubbleExtractor";
    private static final boolean DBG = false;

    private ShortcutHelper mShortcutHelper;
    private RankingConfig mConfig;
    private ActivityManager mActivityManager;
    private Context mContext;

    public void initialize(Context context, NotificationUsageStats usageStats) {
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        mContext = context;
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }

        if (mConfig == null) {
            if (DBG) Slog.d(TAG, "missing config");
            return null;
        }

        if (mShortcutHelper == null) {
            if (DBG) Slog.d(TAG, "missing shortcut helper");
            return null;
        }

        int bubblePreference =
                mConfig.getBubblePreference(
                        record.getSbn().getPackageName(), record.getSbn().getUid());
        NotificationChannel recordChannel = record.getChannel();
        boolean canPresentAsBubble = canPresentAsBubble(record)
                && !mActivityManager.isLowRamDevice()
                && record.isConversation()
                && (record.getNotification().flags & FLAG_FOREGROUND_SERVICE) == 0;

        if (!mConfig.bubblesEnabled()
                || bubblePreference == BUBBLE_PREFERENCE_NONE
                || !canPresentAsBubble) {
            record.setAllowBubble(false);
            if (!canPresentAsBubble) {
                // clear out bubble metadata since it can't be used
                record.getNotification().setBubbleMetadata(null);
            }
        } else if (recordChannel == null) {
            // the app is allowed but there's no channel to check
            record.setAllowBubble(true);
        } else if (bubblePreference == BUBBLE_PREFERENCE_ALL) {
            record.setAllowBubble(recordChannel.getAllowBubbles() != ALLOW_BUBBLE_OFF);
        } else if (bubblePreference == BUBBLE_PREFERENCE_SELECTED) {
            record.setAllowBubble(recordChannel.canBubble());
        }
        if (DBG) {
            Slog.d(TAG, "record: " + record.getKey()
                    + " appPref: " + bubblePreference
                    + " canBubble: " + record.canBubble()
                    + " canPresentAsBubble: " + canPresentAsBubble
                    + " flagRemoved: " + record.isFlagBubbleRemoved());
        }

        final boolean applyFlag = record.canBubble() && !record.isFlagBubbleRemoved();
        if (applyFlag) {
            record.getNotification().flags |= FLAG_BUBBLE;
        } else {
            record.getNotification().flags &= ~FLAG_BUBBLE;
        }
        return null;
    }

    @Override
    public void setConfig(RankingConfig config) {
        mConfig = config;
    }

    @Override
    public void setZenHelper(ZenModeHelper helper) {
    }

    public void setShortcutHelper(ShortcutHelper helper) {
        mShortcutHelper = helper;
    }

    @VisibleForTesting
    public void setActivityManager(ActivityManager manager) {
        mActivityManager = manager;
    }

    /**
     * @return whether there is valid information for the notification to bubble.
     */
    @VisibleForTesting
    boolean canPresentAsBubble(NotificationRecord r) {
        Notification notification = r.getNotification();
        Notification.BubbleMetadata metadata = notification.getBubbleMetadata();
        String pkg = r.getSbn().getPackageName();
        if (metadata == null) {
            return false;
        }

        String shortcutId = metadata.getShortcutId();
        String notificationShortcutId = r.getShortcutInfo() != null
                ? r.getShortcutInfo().getId()
                : null;
        boolean shortcutValid = false;
        if (notificationShortcutId != null && shortcutId != null) {
            // NoMan already checks validity of shortcut, just check if they match.
            shortcutValid = shortcutId.equals(notificationShortcutId);
        } else if (shortcutId != null) {
            shortcutValid =
                    mShortcutHelper.getValidShortcutInfo(shortcutId, pkg, r.getUser()) != null;
        }
        if (metadata.getIntent() == null && !shortcutValid) {
            // Should have a shortcut if intent is null
            logBubbleError(r.getKey(),
                    "couldn't find valid shortcut for bubble with shortcutId: " + shortcutId);
            return false;
        }
        if (shortcutValid) {
            // TODO: check the shortcut intent / ensure it can show in activity view
            return true;
        }
        return canLaunchInActivityView(mContext, metadata.getIntent(), pkg);
    }

    /**
     * Whether an intent is properly configured to display in an {@link
     * android.app.ActivityView} for bubbling.
     *
     * @param context       the context to use.
     * @param pendingIntent the pending intent of the bubble.
     * @param packageName   the notification package name for this bubble.
     */
    // Keep checks in sync with BubbleController#canLaunchInActivityView.
    @VisibleForTesting
    protected boolean canLaunchInActivityView(Context context, PendingIntent pendingIntent,
            String packageName) {
        if (pendingIntent == null) {
            Slog.w(TAG, "Unable to create bubble -- no intent");
            return false;
        }

        Intent intent = pendingIntent.getIntent();
        ActivityInfo info = intent != null
                ? intent.resolveActivityInfo(context.getPackageManager(), 0)
                : null;
        if (info == null) {
            FrameworkStatsLog.write(FrameworkStatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED,
                    packageName,
                    BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_MISSING);
            Slog.w(TAG, "Unable to send as bubble -- couldn't find activity info for intent: "
                    + intent);
            return false;
        }
        if (!ActivityInfo.isResizeableMode(info.resizeMode)) {
            FrameworkStatsLog.write(FrameworkStatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED,
                    packageName,
                    BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_NOT_RESIZABLE);
            Slog.w(TAG, "Unable to send as bubble -- activity is not resizable for intent: "
                    + intent);
            return false;
        }
        return true;
    }

    private void logBubbleError(String key, String failureMessage) {
        if (DBG) {
            Slog.w(TAG, "Bubble notification: " + key + " failed: " + failureMessage);
        }
    }
}
