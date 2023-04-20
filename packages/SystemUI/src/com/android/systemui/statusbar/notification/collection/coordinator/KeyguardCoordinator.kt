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

import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
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
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxy
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Filters low priority and privacy-sensitive notifications from the lockscreen, and hides section
 * headers on the lockscreen.
 */
@CoordinatorScope
class KeyguardCoordinator
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
    private val keyguardRepository: KeyguardRepository,
    private val notifPipelineFlags: NotifPipelineFlags,
    @Application private val scope: CoroutineScope,
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider,
    private val secureSettings: SecureSettings,
    private val seenNotifsProvider: SeenNotificationsProviderImpl,
    private val statusBarStateController: StatusBarStateController,
) : Coordinator {

    private val unseenNotifications = mutableSetOf<NotificationEntry>()
    private var unseenFilterEnabled = false

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
        scope.launch { invalidateWhenUnseenSettingChanges() }
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

    private suspend fun invalidateWhenUnseenSettingChanges() {
        secureSettings
            // emit whenever the setting has changed
            .settingChangesForUser(
                Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
                UserHandle.USER_ALL,
            )
            // perform a query immediately
            .onStart { emit(Unit) }
            // for each change, lookup the new value
            .map {
                secureSettings.getBoolForUser(
                    Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
                    UserHandle.USER_CURRENT,
                )
            }
            // perform lookups on the bg thread pool
            .flowOn(bgDispatcher)
            // only track the most recent emission, if events are happening faster than they can be
            // consumed
            .conflate()
            // update local field and invalidate if necessary
            .collect { setting ->
                if (setting != unseenFilterEnabled) {
                    unseenFilterEnabled = setting
                    unseenNotifFilter.invalidateList("unseen setting changed")
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
                    // Don't apply filter if the setting is disabled
                    !unseenFilterEnabled -> false
                    // Don't apply filter if the keyguard isn't currently showing
                    !keyguardRepository.isKeyguardShowing() -> false
                    // Don't apply the filter if the notification is unseen
                    unseenNotifications.contains(entry) -> false
                    // Don't apply the filter to (non-promoted) group summaries
                    //  - summary will be pruned if necessary, depending on if children are filtered
                    entry.parent?.summary == entry -> false
                    // Check that the entry satisfies certain characteristics that would bypass the
                    // filter
                    shouldIgnoreUnseenCheck(entry) -> false
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

    private fun shouldIgnoreUnseenCheck(entry: NotificationEntry): Boolean =
        when {
            entry.isMediaNotification -> true
            entry.sbn.isOngoing -> true
            else -> false
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

private fun SettingsProxy.settingChangesForUser(name: String, userHandle: Int): Flow<Unit> =
    conflatedCallbackFlow {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
        registerContentObserverForUser(name, observer, userHandle)
        awaitClose { unregisterContentObserver(observer) }
    }
