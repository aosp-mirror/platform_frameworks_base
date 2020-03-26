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

import static com.android.internal.util.FrameworkStatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_MISSING;
import static com.android.internal.util.FrameworkStatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_NOT_RESIZABLE;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Determines whether a bubble can be shown for this notification
 */
public class BubbleExtractor implements NotificationSignalExtractor {
    private static final String TAG = "BubbleExtractor";
    private static final boolean DBG = false;

    private BubbleChecker mBubbleChecker;
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

        if (mBubbleChecker == null) {
            if (DBG) Slog.d(TAG, "missing bubble checker");
            return null;
        }

        boolean appCanShowBubble =
                mConfig.areBubblesAllowed(record.getSbn().getPackageName(), record.getSbn().getUid());
        if (!mConfig.bubblesEnabled() || !appCanShowBubble) {
            record.setAllowBubble(false);
        } else {
            if (record.getChannel() != null) {
                record.setAllowBubble(record.getChannel().canBubble() && appCanShowBubble);
            } else {
                record.setAllowBubble(appCanShowBubble);
            }
        }
        final boolean applyFlag = mBubbleChecker.isNotificationAppropriateToBubble(record);
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

    /**
     * Expected to be called after {@link #setConfig(RankingConfig)} has occurred.
     */
    void setShortcutHelper(ShortcutHelper helper) {
        if (mConfig == null) {
            if (DBG) Slog.d(TAG, "setting shortcut helper prior to setConfig");
            return;
        }
        mBubbleChecker = new BubbleChecker(mContext, helper, mConfig, mActivityManager);
    }

    @VisibleForTesting
    void setBubbleChecker(BubbleChecker checker) {
        mBubbleChecker = checker;
    }

    /**
     * Encapsulates special checks to see if a notification can be flagged as a bubble. This
     * makes testing a bit easier.
     */
    public static class BubbleChecker {

        private ActivityManager mActivityManager;
        private RankingConfig mRankingConfig;
        private Context mContext;
        private ShortcutHelper mShortcutHelper;

        BubbleChecker(Context context, ShortcutHelper helper, RankingConfig config,
                ActivityManager activityManager) {
            mContext = context;
            mActivityManager = activityManager;
            mShortcutHelper = helper;
            mRankingConfig = config;
        }

        /**
         * @return whether the provided notification record is allowed to be represented as a
         * bubble, accounting for user choice & policy.
         */
        public boolean isNotificationAppropriateToBubble(NotificationRecord r) {
            final String pkg = r.getSbn().getPackageName();
            final int userId = r.getSbn().getUser().getIdentifier();
            Notification notification = r.getNotification();
            if (!canBubble(r, pkg, userId)) {
                // no log: canBubble has its own
                return false;
            }

            if (mActivityManager.isLowRamDevice()) {
                logBubbleError(r.getKey(), "low ram device");
                return false;
            }

            boolean isMessageStyle = Notification.MessagingStyle.class.equals(
                    notification.getNotificationStyle());
            if (!isMessageStyle) {
                logBubbleError(r.getKey(), "must be Notification.MessageStyle");
                return false;
            }
            return true;
        }

        /**
         * @return whether the user has enabled the provided notification to bubble, and if the
         * developer has provided valid information for the notification to bubble.
         */
        @VisibleForTesting
        boolean canBubble(NotificationRecord r, String pkg, int userId) {
            Notification notification = r.getNotification();
            Notification.BubbleMetadata metadata = notification.getBubbleMetadata();
            if (metadata == null) {
                // no log: no need to inform dev if they didn't attach bubble metadata
                return false;
            }
            if (!mRankingConfig.bubblesEnabled()) {
                logBubbleError(r.getKey(), "bubbles disabled for user: " + userId);
                return false;
            }
            if (!mRankingConfig.areBubblesAllowed(pkg, userId)) {
                logBubbleError(r.getKey(),
                        "bubbles for package: " + pkg + " disabled for user: " + userId);
                return false;
            }
            if (!r.getChannel().canBubble()) {
                logBubbleError(r.getKey(),
                        "bubbles for channel " + r.getChannel().getId() + " disabled");
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
                return true;
            }
            // no log: canLaunch method has the failure log
            return canLaunchInActivityView(mContext, metadata.getIntent(), pkg);
        }

        /**
         * Whether an intent is properly configured to display in an {@link
         * android.app.ActivityView}.
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
}
