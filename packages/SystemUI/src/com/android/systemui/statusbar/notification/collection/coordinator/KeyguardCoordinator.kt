/*
 * Copyright (C) 2022 The Android Open Source Project
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

import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.collection.provider.SeenNotificationsProviderImpl
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Filters low priority and privacy-sensitive notifications from the lockscreen, and hides section
 * headers on the lockscreen.
 */
@CoordinatorScope
class KeyguardCoordinator
@Inject
constructor(
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
    private val keyguardRepository: KeyguardRepository,
    private val notifPipelineFlags: NotifPipelineFlags,
    @Application private val scope: CoroutineScope,
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider,
    private val seenNotifsProvider: SeenNotificationsProviderImpl,
    private val statusBarStateController: StatusBarStateController,
) : Coordinator {

    private val unseenNotifications = mutableSetOf<NotificationEntry>()

    override fun attach(pipeline: NotifPipeline) {
        setupInvalidateNotifListCallbacks()
        // Filter at the "finalize" stage so that views remain bound by PreparationCoordinator
        pipeline.addFinalizeFilter(notifFilter)
        keyguardNotificationVisibilityProvider.addOnStateChangedListener(::invalidateListFromFilter)
        updateSectionHeadersVisibility()
        if (notifPipelineFlags.shouldFilterUnseenNotifsOnKeyguard) {
            attachUnseenFilter(pipeline)
        }
    }

    private fun attachUnseenFilter(pipeline: NotifPipeline) {
        pipeline.addFinalizeFilter(unseenNotifFilter)
        pipeline.addCollectionListener(collectionListener)
        scope.launch { clearUnseenWhenKeyguardIsDismissed() }
    }

    private suspend fun clearUnseenWhenKeyguardIsDismissed() {
        // Use collectLatest so that the suspending block is cancelled if isKeyguardShowing changes
        // during the timeout period
        keyguardRepository.isKeyguardShowing.collectLatest { isKeyguardShowing ->
            if (!isKeyguardShowing) {
                unseenNotifFilter.invalidateList("keyguard no longer showing")
                delay(SEEN_TIMEOUT)
                unseenNotifications.clear()
            }
        }
    }

    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) {
                if (keyguardRepository.isKeyguardShowing()) {
                    unseenNotifications.add(entry)
                }
            }

            override fun onEntryUpdated(entry: NotificationEntry) {
                if (keyguardRepository.isKeyguardShowing()) {
                    unseenNotifications.add(entry)
                }
            }

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                unseenNotifications.remove(entry)
            }
        }

    @VisibleForTesting
    internal val unseenNotifFilter =
        object : NotifFilter("$TAG-unseen") {

            var hasFilteredAnyNotifs = false

            override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean =
                when {
                    // Don't apply filter if the keyguard isn't currently showing
                    !keyguardRepository.isKeyguardShowing() -> false
                    // Don't apply the filter if the notification is unseen
                    unseenNotifications.contains(entry) -> false
                    // Don't apply the filter to (non-promoted) group summaries
                    //  - summary will be pruned if necessary, depending on if children are filtered
                    entry.parent?.summary == entry -> false
                    else -> true
                }.also { hasFiltered -> hasFilteredAnyNotifs = hasFilteredAnyNotifs || hasFiltered }

            override fun onCleanup() {
                seenNotifsProvider.hasFilteredOutSeenNotifications = hasFilteredAnyNotifs
                hasFilteredAnyNotifs = false
            }
        }

    private val notifFilter: NotifFilter =
        object : NotifFilter(TAG) {
            override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean =
                keyguardNotificationVisibilityProvider.shouldHideNotification(entry)
        }

    // TODO(b/206118999): merge this class with SensitiveContentCoordinator which also depends on
    //  these same updates
    private fun setupInvalidateNotifListCallbacks() {}

    private fun invalidateListFromFilter(reason: String) {
        updateSectionHeadersVisibility()
        notifFilter.invalidateList(reason)
    }

    private fun updateSectionHeadersVisibility() {
        val onKeyguard = statusBarStateController.state == StatusBarState.KEYGUARD
        val neverShowSections = sectionHeaderVisibilityProvider.neverShowSectionHeaders
        val showSections = !onKeyguard && !neverShowSections
        sectionHeaderVisibilityProvider.sectionHeadersVisible = showSections
    }

    companion object {
        private const val TAG = "KeyguardCoordinator"
        private val SEEN_TIMEOUT = 5.seconds
    }
}
