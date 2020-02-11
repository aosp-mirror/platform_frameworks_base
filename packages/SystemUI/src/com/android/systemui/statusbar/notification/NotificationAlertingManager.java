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

package com.android.systemui.statusbar.notification;

import static com.android.systemui.statusbar.NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_HEADS_UP;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationFlag;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/** Handles heads-up and pulsing behavior driven by notification changes. */
@Singleton
public class NotificationAlertingManager {

    private static final String TAG = "NotifAlertManager";

    private final NotificationRemoteInputManager mRemoteInputManager;
    private final VisualStabilityManager mVisualStabilityManager;
    private final Lazy<ShadeController> mShadeController;
    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    private final NotificationListener mNotificationListener;

    private HeadsUpManager mHeadsUpManager;

    @Inject
    public NotificationAlertingManager(
            NotificationEntryManager notificationEntryManager,
            NotificationRemoteInputManager remoteInputManager,
            VisualStabilityManager visualStabilityManager,
            Lazy<ShadeController> shadeController,
            NotificationInterruptionStateProvider notificationInterruptionStateProvider,
            NotificationListener notificationListener) {
        mRemoteInputManager = remoteInputManager;
        mVisualStabilityManager = visualStabilityManager;
        mShadeController = shadeController;
        mNotificationInterruptionStateProvider = notificationInterruptionStateProvider;
        mNotificationListener = notificationListener;

        notificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onEntryInflated(NotificationEntry entry, int inflatedFlags) {
                showAlertingView(entry, inflatedFlags);
            }

            @Override
            public void onPostEntryUpdated(NotificationEntry entry) {
                updateAlertState(entry);
            }

            @Override
            public void onEntryRemoved(
                    NotificationEntry entry,
                    NotificationVisibility visibility,
                    boolean removedByUser) {
                stopAlerting(entry.key);
            }
        });
    }

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    /**
     * Adds the entry to the respective alerting manager if the content view was inflated and
     * the entry should still alert.
     *
     * @param entry         entry to add
     * @param inflatedFlags flags representing content views that were inflated
     */
    private void showAlertingView(NotificationEntry entry, @InflationFlag int inflatedFlags) {
        if ((inflatedFlags & FLAG_CONTENT_VIEW_HEADS_UP) != 0) {
            // Possible for shouldHeadsUp to change between the inflation starting and ending.
            // If it does and we no longer need to heads up, we should free the view.
            if (mNotificationInterruptionStateProvider.shouldHeadsUp(entry)) {
                mHeadsUpManager.showNotification(entry);
                if (!mShadeController.get().isDozing()) {
                    // Mark as seen immediately
                    setNotificationShown(entry.notification);
                }
            } else {
                entry.freeContentViewWhenSafe(FLAG_CONTENT_VIEW_HEADS_UP);
            }
        }
    }

    private void updateAlertState(NotificationEntry entry) {
        boolean alertAgain = alertAgain(entry, entry.notification.getNotification());
        boolean shouldAlert;
        shouldAlert = mNotificationInterruptionStateProvider.shouldHeadsUp(entry);
        final boolean wasAlerting = mHeadsUpManager.isAlerting(entry.key);
        if (wasAlerting) {
            if (shouldAlert) {
                mHeadsUpManager.updateNotification(entry.key, alertAgain);
            } else if (!mHeadsUpManager.isEntryAutoHeadsUpped(entry.key)) {
                // We don't want this to be interrupting anymore, let's remove it
                mHeadsUpManager.removeNotification(entry.key, false /* removeImmediately */);
            }
        } else if (shouldAlert && alertAgain) {
            // This notification was updated to be alerting, show it!
            mHeadsUpManager.showNotification(entry);
        }
    }

    /**
     * Checks whether an update for a notification warrants an alert for the user.
     *
     * @param oldEntry the entry for this notification.
     * @param newNotification the new notification for this entry.
     * @return whether this notification should alert the user.
     */
    public static boolean alertAgain(
            NotificationEntry oldEntry, Notification newNotification) {
        return oldEntry == null || !oldEntry.hasInterrupted()
                || (newNotification.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0;
    }

    private void setNotificationShown(StatusBarNotification n) {
        try {
            mNotificationListener.setNotificationsShown(new String[]{n.getKey()});
        } catch (RuntimeException e) {
            Log.d(TAG, "failed setNotificationsShown: ", e);
        }
    }

    private void stopAlerting(final String key) {
        // Attempt to remove notifications from their alert manager.
        // Though the remove itself may fail, it lets the manager know to remove as soon as
        // possible.
        if (mHeadsUpManager.isAlerting(key)) {
            // A cancel() in response to a remote input shouldn't be delayed, as it makes the
            // sending look longer than it takes.
            // Also we should not defer the removal if reordering isn't allowed since otherwise
            // some notifications can't disappear before the panel is closed.
            boolean ignoreEarliestRemovalTime =
                    mRemoteInputManager.getController().isSpinning(key)
                            && !FORCE_REMOTE_INPUT_HISTORY
                            || !mVisualStabilityManager.isReorderingAllowed();
            mHeadsUpManager.removeNotification(key, ignoreEarliestRemovalTime);
        }
    }
}
