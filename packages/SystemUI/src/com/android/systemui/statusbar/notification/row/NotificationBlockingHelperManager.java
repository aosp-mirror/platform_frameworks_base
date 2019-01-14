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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_NEGATIVE;

import android.content.Context;
import android.metrics.LogMaker;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.logging.NotificationCounters;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manager for the notification blocking helper - tracks and helps create the blocking helper
 * affordance.
 */
@Singleton
public class NotificationBlockingHelperManager {
    /** Enables debug logging and always makes the blocking helper show up after a dismiss. */
    private static final boolean DEBUG = false;
    private static final String TAG = "BlockingHelper";

    private final Context mContext;
    /** Row that the blocking helper will be shown in (via {@link NotificationGuts}. */
    private ExpandableNotificationRow mBlockingHelperRow;
    private Set<String> mNonBlockablePkgs;

    /**
     * Whether the notification shade/stack is expanded - used to determine blocking helper
     * eligibility.
     */
    private boolean mIsShadeExpanded;

    private MetricsLogger mMetricsLogger = new MetricsLogger();

    @Inject
    public NotificationBlockingHelperManager(Context context) {
        mContext = context;
        mNonBlockablePkgs = new HashSet<>();
        Collections.addAll(mNonBlockablePkgs, mContext.getResources().getStringArray(
                com.android.internal.R.array.config_nonBlockableNotificationPackages));
    }

    /**
     * Potentially shows the blocking helper, represented via the {@link NotificationInfo} menu
     * item, in the current row if user sentiment is negative.
     *
     * @param row row to render the blocking helper in
     * @param menuRow menu used to generate the {@link NotificationInfo} view that houses the
     *                blocking helper UI
     * @return whether we're showing a blocking helper in the given notification row
     */
    boolean perhapsShowBlockingHelper(
            ExpandableNotificationRow row, NotificationMenuRowPlugin menuRow) {
        // We only show the blocking helper if:
        // - User sentiment is negative (DEBUG flag can bypass)
        // - The notification shade is fully expanded (guarantees we're not touching a HUN).
        // - The row is blockable (i.e. not non-blockable)
        // - The dismissed row is a valid group (>1 or 0 children) or the only child in the group
        if ((row.getEntry().userSentiment == USER_SENTIMENT_NEGATIVE || DEBUG)
                && mIsShadeExpanded
                && !row.getIsNonblockable()
                && (!row.isChildInGroup() || row.isOnlyChildInGroup())) {
            // Dismiss any current blocking helper before continuing forward (only one can be shown
            // at a given time).
            dismissCurrentBlockingHelper();

            if (DEBUG) {
                Log.d(TAG, "Manager.perhapsShowBlockingHelper: Showing new blocking helper");
            }
            NotificationGutsManager manager = Dependency.get(NotificationGutsManager.class);

            // Enable blocking helper on the row before moving forward so everything in the guts is
            // correctly prepped.
            mBlockingHelperRow = row;
            mBlockingHelperRow.setBlockingHelperShowing(true);

            // Log triggering of blocking helper by the system. This log line
            // should be emitted before the "display" log line.
            mMetricsLogger.write(
                    getLogMaker().setSubtype(MetricsEvent.BLOCKING_HELPER_TRIGGERED_BY_SYSTEM));

            // We don't care about the touch origin (x, y) since we're opening guts without any
            // explicit user interaction.
            manager.openGuts(mBlockingHelperRow, 0, 0, menuRow.getLongpressMenuItem(mContext));

            Dependency.get(MetricsLogger.class)
                    .count(NotificationCounters.BLOCKING_HELPER_SHOWN, 1);
            return true;
        }
        return false;
    }

    /**
     * Dismiss the currently showing blocking helper, if any, through a notification update.
     *
     * @return whether the blocking helper was dismissed
     */
    boolean dismissCurrentBlockingHelper() {
        if (!isBlockingHelperRowNull()) {
            if (DEBUG) {
                Log.d(TAG, "Manager.dismissCurrentBlockingHelper: Dismissing current helper");
            }
            if (!mBlockingHelperRow.isBlockingHelperShowing()) {
                Log.e(TAG, "Manager.dismissCurrentBlockingHelper: "
                        + "Non-null row is not showing a blocking helper");
            }

            mBlockingHelperRow.setBlockingHelperShowing(false);
            if (mBlockingHelperRow.isAttachedToWindow()) {
                Dependency.get(NotificationEntryManager.class).updateNotifications();
            }
            mBlockingHelperRow = null;
            return true;
        }
        return false;
    }

    /**
     * Update the expansion status of the notification shade/stack.
     *
     * @param expandedHeight how much the shade is expanded ({code 0} indicating it's collapsed)
     */
    public void setNotificationShadeExpanded(float expandedHeight) {
        mIsShadeExpanded = expandedHeight > 0.0f;
    }

    /**
     * Returns whether the given package name is in the list of non-blockable packages.
     */
    public boolean isNonblockable(String packageName, String channelName) {
        return mNonBlockablePkgs.contains(packageName)
                || mNonBlockablePkgs.contains(makeChannelKey(packageName, channelName));
    }

    private LogMaker getLogMaker() {
        return mBlockingHelperRow.getStatusBarNotification()
            .getLogMaker()
            .setCategory(MetricsEvent.NOTIFICATION_ITEM)
            .setType(MetricsEvent.NOTIFICATION_BLOCKING_HELPER);
    }

    // Format must stay in sync with frameworks/base/core/res/res/values/config.xml
    // config_nonBlockableNotificationPackages
    private String makeChannelKey(String pkg, String channel) {
        return pkg + ":" + channel;
    }

    @VisibleForTesting
    boolean isBlockingHelperRowNull() {
        return mBlockingHelperRow == null;
    }

    @VisibleForTesting
    void setBlockingHelperRowForTest(ExpandableNotificationRow blockingHelperRowForTest) {
        mBlockingHelperRow = blockingHelperRowForTest;
    }

    @VisibleForTesting
    void setNonBlockablePkgs(String[] pkgsAndChannels) {
        mNonBlockablePkgs = new HashSet<>();
        Collections.addAll(mNonBlockablePkgs, pkgsAndChannels);
    }
}
