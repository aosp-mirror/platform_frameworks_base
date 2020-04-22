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

package com.android.systemui.statusbar.notification.interruption;

import static com.android.systemui.statusbar.NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller class for old pipeline heads up logic. It listens to {@link NotificationEntryManager}
 * entry events and appropriately binds or unbinds the heads up view and promotes it to the top
 * of the screen.
 */
@Singleton
public class HeadsUpController {
    private final HeadsUpViewBinder mHeadsUpViewBinder;
    private final NotificationInterruptStateProvider mInterruptStateProvider;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final VisualStabilityManager mVisualStabilityManager;
    private final StatusBarStateController mStatusBarStateController;
    private final NotificationListener mNotificationListener;
    private final HeadsUpManager mHeadsUpManager;

    @Inject
    HeadsUpController(
            HeadsUpViewBinder headsUpViewBinder,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            HeadsUpManager headsUpManager,
            NotificationRemoteInputManager remoteInputManager,
            StatusBarStateController statusBarStateController,
            VisualStabilityManager visualStabilityManager,
            NotificationListener notificationListener) {
        mHeadsUpViewBinder = headsUpViewBinder;
        mHeadsUpManager = headsUpManager;
        mInterruptStateProvider = notificationInterruptStateProvider;
        mRemoteInputManager = remoteInputManager;
        mStatusBarStateController = statusBarStateController;
        mVisualStabilityManager = visualStabilityManager;
        mNotificationListener = notificationListener;
    }

    /**
     * Attach this controller and add its listeners.
     */
    public void attach(
            NotificationEntryManager entryManager,
            HeadsUpManager headsUpManager) {
        entryManager.addCollectionListener(mCollectionListener);
        headsUpManager.addListener(mOnHeadsUpChangedListener);
    }

    private NotifCollectionListener mCollectionListener = new NotifCollectionListener() {
        @Override
        public void onEntryAdded(NotificationEntry entry) {
            if (mInterruptStateProvider.shouldHeadsUp(entry)) {
                mHeadsUpViewBinder.bindHeadsUpView(
                        entry, HeadsUpController.this::showAlertingView);
            }
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            updateHunState(entry);
        }

        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason) {
            stopAlerting(entry);
        }

        @Override
        public void onEntryCleanUp(NotificationEntry entry) {
            mHeadsUpViewBinder.abortBindCallback(entry);
        }
    };

    /**
     * Adds the entry to the HUN manager and show it for the first time.
     */
    private void showAlertingView(NotificationEntry entry) {
        mHeadsUpManager.showNotification(entry);
        if (!mStatusBarStateController.isDozing()) {
            // Mark as seen immediately
            setNotificationShown(entry.getSbn());
        }
    }

    private void updateHunState(NotificationEntry entry) {
        boolean hunAgain = alertAgain(entry, entry.getSbn().getNotification());
        // includes check for whether this notification should be filtered:
        boolean shouldHeadsUp = mInterruptStateProvider.shouldHeadsUp(entry);
        final boolean wasHeadsUp = mHeadsUpManager.isAlerting(entry.getKey());
        if (wasHeadsUp) {
            if (shouldHeadsUp) {
                mHeadsUpManager.updateNotification(entry.getKey(), hunAgain);
            } else if (!mHeadsUpManager.isEntryAutoHeadsUpped(entry.getKey())) {
                // We don't want this to be interrupting anymore, let's remove it
                mHeadsUpManager.removeNotification(entry.getKey(), false /* removeImmediately */);
            }
        } else if (shouldHeadsUp && hunAgain) {
            mHeadsUpViewBinder.bindHeadsUpView(entry, mHeadsUpManager::showNotification);
        }
    }

    private void setNotificationShown(StatusBarNotification n) {
        try {
            mNotificationListener.setNotificationsShown(new String[]{n.getKey()});
        } catch (RuntimeException e) {
            Log.d(TAG, "failed setNotificationsShown: ", e);
        }
    }

    private void stopAlerting(NotificationEntry entry) {
        // Attempt to remove notifications from their HUN manager.
        // Though the remove itself may fail, it lets the manager know to remove as soon as
        // possible.
        String key = entry.getKey();
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

    private OnHeadsUpChangedListener mOnHeadsUpChangedListener  = new OnHeadsUpChangedListener() {
        @Override
        public void onHeadsUpStateChanged(@NonNull NotificationEntry entry, boolean isHeadsUp) {
            if (!isHeadsUp && !entry.getRow().isRemoved()) {
                mHeadsUpViewBinder.unbindHeadsUpView(entry);
            }
        }
    };

    private static final String TAG = "HeadsUpBindController";
}
