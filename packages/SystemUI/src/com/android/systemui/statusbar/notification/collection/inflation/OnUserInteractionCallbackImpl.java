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

package com.android.systemui.statusbar.notification.collection.inflation;

import static android.service.notification.NotificationStats.DISMISS_SENTIMENT_NEUTRAL;

import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.VisualStabilityCoordinator;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback;
import com.android.systemui.statusbar.policy.HeadsUpManager;

/**
 * Callback for when a user interacts with a {@see ExpandableNotificationRow}. Sends relevant
 * information about the interaction to the notification pipeline.
 */
public class OnUserInteractionCallbackImpl implements OnUserInteractionCallback {
    private final NotifPipeline mNotifPipeline;
    private final NotifCollection mNotifCollection;
    private final HeadsUpManager mHeadsUpManager;
    private final StatusBarStateController mStatusBarStateController;
    private final VisualStabilityCoordinator mVisualStabilityCoordinator;

    public OnUserInteractionCallbackImpl(
            NotifPipeline notifPipeline,
            NotifCollection notifCollection,
            HeadsUpManager headsUpManager,
            StatusBarStateController statusBarStateController,
            VisualStabilityCoordinator visualStabilityCoordinator
    ) {
        mNotifPipeline = notifPipeline;
        mNotifCollection = notifCollection;
        mHeadsUpManager = headsUpManager;
        mStatusBarStateController = statusBarStateController;
        mVisualStabilityCoordinator = visualStabilityCoordinator;
    }

    /**
     * Callback triggered when a user:
     * 1. Manually dismisses a notification {@see ExpandableNotificationRow}.
     * 2. Clicks on a notification with flag {@link android.app.Notification#FLAG_AUTO_CANCEL}.
     * {@see StatusBarNotificationActivityStarter}
     */
    @Override
    public void onDismiss(
            NotificationEntry entry,
            @NotificationListenerService.NotificationCancelReason int cancellationReason
    ) {
        int dismissalSurface = NotificationStats.DISMISSAL_SHADE;
        if (mHeadsUpManager.isAlerting(entry.getKey())) {
            dismissalSurface = NotificationStats.DISMISSAL_PEEK;
        } else if (mStatusBarStateController.isDozing()) {
            dismissalSurface = NotificationStats.DISMISSAL_AOD;
        }

        mNotifCollection.dismissNotification(
                entry,
                new DismissedByUserStats(
                    dismissalSurface,
                    DISMISS_SENTIMENT_NEUTRAL,
                    NotificationVisibility.obtain(
                            entry.getKey(),
                            entry.getRanking().getRank(),
                            mNotifPipeline.getShadeListCount(),
                            true,
                            NotificationLogger.getNotificationLocation(entry)))
        );
    }

    @Override
    public void onImportanceChanged(NotificationEntry entry) {
        mVisualStabilityCoordinator.temporarilyAllowSectionChanges(
                entry,
                SystemClock.uptimeMillis());
    }
}
