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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static com.android.systemui.statusbar.NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY;
import static com.android.systemui.statusbar.notification.interruption.HeadsUpController.alertAgain;

import android.annotation.Nullable;

import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Coordinates heads up notification (HUN) interactions with the notification pipeline based on
 * the HUN state reported by the {@link HeadsUpManager}. In this class we only consider one
 * notification, in particular the {@link HeadsUpManager#getTopEntry()}, to be HeadsUpping at a
 * time even though other notifications may be queued to heads up next.
 *
 * The current HUN, but not HUNs that are queued to heads up, will be:
 * - Lifetime extended until it's no longer heads upping.
 * - Promoted out of its group if it's a child of a group.
 * - In the HeadsUpCoordinatorSection. Ordering is configured in {@link NotifCoordinators}.
 * - Removed from HeadsUpManager if it's removed from the NotificationCollection.
 *
 * Note: The inflation callback in {@link PreparationCoordinator} handles showing HUNs.
 */
@Singleton
public class HeadsUpCoordinator implements Coordinator {
    private static final String TAG = "HeadsUpCoordinator";

    private final HeadsUpManager mHeadsUpManager;
    private final HeadsUpViewBinder mHeadsUpViewBinder;
    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final NotificationRemoteInputManager mRemoteInputManager;

    // tracks the current HeadUpNotification reported by HeadsUpManager
    private @Nullable NotificationEntry mCurrentHun;

    private NotifLifetimeExtender.OnEndLifetimeExtensionCallback mEndLifetimeExtension;
    private NotificationEntry mNotifExtendingLifetime; // notif we've extended the lifetime for

    @Inject
    public HeadsUpCoordinator(
            HeadsUpManager headsUpManager,
            HeadsUpViewBinder headsUpViewBinder,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            NotificationRemoteInputManager remoteInputManager) {
        mHeadsUpManager = headsUpManager;
        mHeadsUpViewBinder = headsUpViewBinder;
        mNotificationInterruptStateProvider = notificationInterruptStateProvider;
        mRemoteInputManager = remoteInputManager;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mHeadsUpManager.addListener(mOnHeadsUpChangedListener);
        pipeline.addCollectionListener(mNotifCollectionListener);
        pipeline.addPromoter(mNotifPromoter);
        pipeline.addNotificationLifetimeExtender(mLifetimeExtender);
    }

    @Override
    public NotifSection getSection() {
        return mNotifSection;
    }

    private void onHeadsUpViewBound(NotificationEntry entry) {
        mHeadsUpManager.showNotification(entry);
    }

    private final NotifCollectionListener mNotifCollectionListener = new NotifCollectionListener() {
        /**
         * Notification was just added and if it should heads up, bind the view and then show it.
         */
        @Override
        public void onEntryAdded(NotificationEntry entry) {
            if (mNotificationInterruptStateProvider.shouldHeadsUp(entry)) {
                mHeadsUpViewBinder.bindHeadsUpView(
                        entry,
                        HeadsUpCoordinator.this::onHeadsUpViewBound);
            }
        }

        /**
         * Notification could've updated to be heads up or not heads up. Even if it did update to
         * heads up, if the notification specified that it only wants to alert once, don't heads
         * up again.
         */
        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            boolean hunAgain = alertAgain(entry, entry.getSbn().getNotification());
            // includes check for whether this notification should be filtered:
            boolean shouldHeadsUp = mNotificationInterruptStateProvider.shouldHeadsUp(entry);
            final boolean wasHeadsUp = mHeadsUpManager.isAlerting(entry.getKey());
            if (wasHeadsUp) {
                if (shouldHeadsUp) {
                    mHeadsUpManager.updateNotification(entry.getKey(), hunAgain);
                } else if (!mHeadsUpManager.isEntryAutoHeadsUpped(entry.getKey())) {
                    // We don't want this to be interrupting anymore, let's remove it
                    mHeadsUpManager.removeNotification(
                            entry.getKey(), false /* removeImmediately */);
                }
            } else if (shouldHeadsUp && hunAgain) {
                // This notification was updated to be heads up, show it!
                mHeadsUpViewBinder.bindHeadsUpView(
                        entry,
                        HeadsUpCoordinator.this::onHeadsUpViewBound);
            }
        }

        /**
         * Stop alerting HUNs that are removed from the notification collection
         */
        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason) {
            final String entryKey = entry.getKey();
            if (mHeadsUpManager.isAlerting(entryKey)) {
                boolean removeImmediatelyForRemoteInput =
                        mRemoteInputManager.getController().isSpinning(entryKey)
                                && !FORCE_REMOTE_INPUT_HISTORY;
                mHeadsUpManager.removeNotification(entry.getKey(), removeImmediatelyForRemoteInput);
            }
        }

        @Override
        public void onEntryCleanUp(NotificationEntry entry) {
            mHeadsUpViewBinder.abortBindCallback(entry);
        }
    };

    private final NotifLifetimeExtender mLifetimeExtender = new NotifLifetimeExtender() {
        @Override
        public String getName() {
            return TAG;
        }

        @Override
        public void setCallback(OnEndLifetimeExtensionCallback callback) {
            mEndLifetimeExtension = callback;
        }

        @Override
        public boolean shouldExtendLifetime(NotificationEntry entry, int reason) {
            boolean isShowingHun = isCurrentlyShowingHun(entry);
            if (isShowingHun) {
                mNotifExtendingLifetime = entry;
            }
            return isShowingHun;
        }

        @Override
        public void cancelLifetimeExtension(NotificationEntry entry) {
            if (Objects.equals(mNotifExtendingLifetime, entry)) {
                mNotifExtendingLifetime = null;
            }
        }
    };

    private final NotifPromoter mNotifPromoter = new NotifPromoter(TAG) {
        @Override
        public boolean shouldPromoteToTopLevel(NotificationEntry entry) {
            return isCurrentlyShowingHun(entry);
        }
    };

    private final NotifSection mNotifSection = new NotifSection(TAG) {
        @Override
        public boolean isInSection(ListEntry entry) {
            return isCurrentlyShowingHun(entry);
        }
    };

    private final OnHeadsUpChangedListener mOnHeadsUpChangedListener =
            new OnHeadsUpChangedListener() {
        @Override
        public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
            NotificationEntry newHUN = mHeadsUpManager.getTopEntry();
            if (!Objects.equals(mCurrentHun, newHUN)) {
                endNotifLifetimeExtension();
                mCurrentHun = newHUN;
                mNotifPromoter.invalidateList();
                mNotifSection.invalidateList();
            }
            if (!isHeadsUp) {
                mHeadsUpViewBinder.unbindHeadsUpView(entry);
            }
        }
    };

    private boolean isCurrentlyShowingHun(ListEntry entry) {
        return mCurrentHun == entry.getRepresentativeEntry();
    }

    private void endNotifLifetimeExtension() {
        if (mNotifExtendingLifetime != null) {
            mEndLifetimeExtension.onEndLifetimeExtension(
                    mLifetimeExtender,
                    mNotifExtendingLifetime);
            mNotifExtendingLifetime = null;
        }
    }
}
