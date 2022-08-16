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

package com.android.systemui.statusbar.notification.collection.legacy;

import static android.service.notification.NotificationStats.DISMISS_SENTIMENT_NEUTRAL;

import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback;
import com.android.systemui.statusbar.policy.HeadsUpManager;

/**
 * Callback for when a user interacts with a {@see ExpandableNotificationRow}.
 */
public class OnUserInteractionCallbackImplLegacy implements OnUserInteractionCallback {
    private final NotificationEntryManager mNotificationEntryManager;
    private final NotificationVisibilityProvider mVisibilityProvider;
    private final HeadsUpManager mHeadsUpManager;
    private final StatusBarStateController mStatusBarStateController;
    private final VisualStabilityManager mVisualStabilityManager;
    private final GroupMembershipManager mGroupMembershipManager;

    public OnUserInteractionCallbackImplLegacy(
            NotificationEntryManager notificationEntryManager,
            NotificationVisibilityProvider visibilityProvider,
            HeadsUpManager headsUpManager,
            StatusBarStateController statusBarStateController,
            VisualStabilityManager visualStabilityManager,
            GroupMembershipManager groupMembershipManager
    ) {
        mNotificationEntryManager = notificationEntryManager;
        mVisibilityProvider = visibilityProvider;
        mHeadsUpManager = headsUpManager;
        mStatusBarStateController = statusBarStateController;
        mVisualStabilityManager = visualStabilityManager;
        mGroupMembershipManager = groupMembershipManager;
    }

    /**
     * Callback triggered when a user:
     * 1. Manually dismisses a notification {@see ExpandableNotificationRow}.
     * 2. Clicks on a notification with flag {@link android.app.Notification#FLAG_AUTO_CANCEL}.
     * {@see StatusBarNotificationActivityStarter}
     *
     * @param groupSummaryToDismiss the group summary that should be dismissed
     *                              along with this dismissal. If null, does not additionally
     *                              dismiss any notifications.
     */
    private void onDismiss(
            NotificationEntry entry,
            @NotificationListenerService.NotificationCancelReason int cancellationReason,
            @Nullable NotificationEntry groupSummaryToDismiss
    ) {
        int dismissalSurface = NotificationStats.DISMISSAL_SHADE;
        if (mHeadsUpManager.isAlerting(entry.getKey())) {
            dismissalSurface = NotificationStats.DISMISSAL_PEEK;
        } else if (mStatusBarStateController.isDozing()) {
            dismissalSurface = NotificationStats.DISMISSAL_AOD;
        }

        if (groupSummaryToDismiss != null) {
            onDismiss(groupSummaryToDismiss, cancellationReason, null);
        }

        mNotificationEntryManager.performRemoveNotification(
                entry.getSbn(),
                new DismissedByUserStats(
                        dismissalSurface,
                        DISMISS_SENTIMENT_NEUTRAL,
                        mVisibilityProvider.obtain(entry, true)),
                cancellationReason
        );

    }

    @Override
    public void onImportanceChanged(NotificationEntry entry) {
        mVisualStabilityManager.temporarilyAllowReordering();
    }

    /**
     * @param entry that is being dismissed
     * @return the group summary to dismiss along with this entry if this is the last entry in
     * the group. Else, returns null.
     */
    @Nullable
    private NotificationEntry getGroupSummaryToDismiss(NotificationEntry entry) {
        if (mGroupMembershipManager.isOnlyChildInGroup(entry)) {
            NotificationEntry groupSummary = mGroupMembershipManager.getLogicalGroupSummary(entry);
            return groupSummary.isDismissable() ? groupSummary : null;
        }
        return null;
    }

    @Override
    @NonNull
    public Runnable registerFutureDismissal(@NonNull NotificationEntry entry,
            @CancellationReason int cancellationReason) {
        NotificationEntry groupSummaryToDismiss = getGroupSummaryToDismiss(entry);
        return () -> onDismiss(entry, cancellationReason, groupSummaryToDismiss);
    }
}

