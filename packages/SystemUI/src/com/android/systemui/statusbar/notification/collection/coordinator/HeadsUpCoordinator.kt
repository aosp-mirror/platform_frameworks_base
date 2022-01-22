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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.util.ArraySet
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender.OnEndLifetimeExtensionCallback
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.dagger.IncomingHeader
import com.android.systemui.statusbar.notification.interruption.HeadsUpController
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider
import com.android.systemui.statusbar.notification.stack.BUCKET_HEADS_UP
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import com.android.systemui.util.concurrency.DelayableExecutor
import javax.inject.Inject

/**
 * Coordinates heads up notification (HUN) interactions with the notification pipeline based on
 * the HUN state reported by the [HeadsUpManager]. In this class we only consider one
 * notification, in particular the [HeadsUpManager.getTopEntry], to be HeadsUpping at a
 * time even though other notifications may be queued to heads up next.
 *
 * The current HUN, but not HUNs that are queued to heads up, will be:
 * - Lifetime extended until it's no longer heads upping.
 * - Promoted out of its group if it's a child of a group.
 * - In the HeadsUpCoordinatorSection. Ordering is configured in [NotifCoordinators].
 * - Removed from HeadsUpManager if it's removed from the NotificationCollection.
 *
 * Note: The inflation callback in [PreparationCoordinator] handles showing HUNs.
 */
@CoordinatorScope
class HeadsUpCoordinator @Inject constructor(
    private val mHeadsUpManager: HeadsUpManager,
    private val mHeadsUpViewBinder: HeadsUpViewBinder,
    private val mNotificationInterruptStateProvider: NotificationInterruptStateProvider,
    private val mRemoteInputManager: NotificationRemoteInputManager,
    @IncomingHeader private val mIncomingHeaderController: NodeController,
    @Main private val mExecutor: DelayableExecutor
) : Coordinator {
    private var mEndLifetimeExtension: OnEndLifetimeExtensionCallback? = null

    // notifs we've extended the lifetime for
    private val mNotifsExtendingLifetime = ArraySet<NotificationEntry>()

    override fun attach(pipeline: NotifPipeline) {
        mHeadsUpManager.addListener(mOnHeadsUpChangedListener)
        pipeline.addCollectionListener(mNotifCollectionListener)
        pipeline.addPromoter(mNotifPromoter)
        pipeline.addNotificationLifetimeExtender(mLifetimeExtender)
    }

    private fun onHeadsUpViewBound(entry: NotificationEntry) {
        mHeadsUpManager.showNotification(entry)
    }

    private val mNotifCollectionListener = object : NotifCollectionListener {
        /**
         * Notification was just added and if it should heads up, bind the view and then show it.
         */
        override fun onEntryAdded(entry: NotificationEntry) {
            if (mNotificationInterruptStateProvider.shouldHeadsUp(entry)) {
                mHeadsUpViewBinder.bindHeadsUpView(entry) { entry -> onHeadsUpViewBound(entry) }
            }
        }

        /**
         * Notification could've updated to be heads up or not heads up. Even if it did update to
         * heads up, if the notification specified that it only wants to alert once, don't heads
         * up again.
         */
        override fun onEntryUpdated(entry: NotificationEntry) {
            val hunAgain = HeadsUpController.alertAgain(entry, entry.sbn.notification)
            // includes check for whether this notification should be filtered:
            val shouldHeadsUp = mNotificationInterruptStateProvider.shouldHeadsUp(entry)
            val wasHeadsUp = mHeadsUpManager.isAlerting(entry.key)
            if (wasHeadsUp) {
                if (shouldHeadsUp) {
                    mHeadsUpManager.updateNotification(entry.key, hunAgain)
                } else if (!mHeadsUpManager.isEntryAutoHeadsUpped(entry.key)) {
                    // We don't want this to be interrupting anymore, let's remove it
                    mHeadsUpManager.removeNotification(
                        entry.key, false /* removeImmediately */
                    )
                }
            } else if (shouldHeadsUp && hunAgain) {
                // This notification was updated to be heads up, show it!
                mHeadsUpViewBinder.bindHeadsUpView(entry) { entry -> onHeadsUpViewBound(entry) }
            }
        }

        /**
         * Stop alerting HUNs that are removed from the notification collection
         */
        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            val entryKey = entry.key
            if (mHeadsUpManager.isAlerting(entryKey)) {
                val removeImmediatelyForRemoteInput = (mRemoteInputManager.isSpinning(entryKey) &&
                        !NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY)
                mHeadsUpManager.removeNotification(entry.key, removeImmediatelyForRemoteInput)
            }
        }

        override fun onEntryCleanUp(entry: NotificationEntry) {
            mHeadsUpViewBinder.abortBindCallback(entry)
        }
    }

    private val mLifetimeExtender = object : NotifLifetimeExtender {
        override fun getName() = TAG

        override fun setCallback(callback: OnEndLifetimeExtensionCallback) {
            mEndLifetimeExtension = callback
        }

        override fun maybeExtendLifetime(entry: NotificationEntry, reason: Int): Boolean {
            if (mHeadsUpManager.canRemoveImmediately(entry.key)) {
                return false
            }
            if (isSticky(entry)) {
                val removeAfterMillis = mHeadsUpManager.getEarliestRemovalTime(entry.key)
                mExecutor.executeDelayed({
                    val canStillRemove = mHeadsUpManager.canRemoveImmediately(entry.key)
                    if (mNotifsExtendingLifetime.contains(entry) && canStillRemove) {
                        mHeadsUpManager.removeNotification(entry.key, /* releaseImmediately */ true)
                    }
                }, removeAfterMillis)
            } else {
                mExecutor.execute {
                    mHeadsUpManager.removeNotification(entry.key, /* releaseImmediately */ false)
                }
            }
            mNotifsExtendingLifetime.add(entry)
            return true
        }

        override fun cancelLifetimeExtension(entry: NotificationEntry) {
            mNotifsExtendingLifetime.remove(entry)
        }
    }

    private val mNotifPromoter = object : NotifPromoter(TAG) {
        override fun shouldPromoteToTopLevel(entry: NotificationEntry): Boolean =
            isCurrentlyShowingHun(entry)
    }

    val sectioner = object : NotifSectioner("HeadsUp", BUCKET_HEADS_UP) {
        override fun isInSection(entry: ListEntry): Boolean = isCurrentlyShowingHun(entry)

        override fun getHeaderNodeController(): NodeController? =
            // TODO: remove SHOW_ALL_SECTIONS, this redundant method, and mIncomingHeaderController
            if (RankingCoordinator.SHOW_ALL_SECTIONS) mIncomingHeaderController else null
    }

    private val mOnHeadsUpChangedListener = object : OnHeadsUpChangedListener {
        override fun onHeadsUpStateChanged(entry: NotificationEntry, isHeadsUp: Boolean) {
            if (!isHeadsUp) {
                mHeadsUpViewBinder.unbindHeadsUpView(entry)
                endNotifLifetimeExtensionIfExtended(entry)
            }
        }
    }

    private fun isSticky(entry: NotificationEntry) = mHeadsUpManager.isSticky(entry.key)

    private fun isCurrentlyShowingHun(entry: ListEntry) = mHeadsUpManager.isAlerting(entry.key)

    private fun endNotifLifetimeExtensionIfExtended(entry: NotificationEntry) {
        if (mNotifsExtendingLifetime.remove(entry)) {
            mEndLifetimeExtension?.onEndLifetimeExtension(mLifetimeExtender, entry)
        }
    }

    companion object {
        private const val TAG = "HeadsUpCoordinator"
    }
}