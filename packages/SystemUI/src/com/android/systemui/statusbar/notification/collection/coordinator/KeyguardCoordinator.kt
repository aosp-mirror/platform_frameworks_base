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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.collection.coordinator

import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.expansionChanges
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.collection.provider.SeenNotificationsProviderImpl
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.headsUpEvents
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Filters low priority and privacy-sensitive notifications from the lockscreen, and hides section
 * headers on the lockscreen.
 */
@CoordinatorScope
class KeyguardCoordinator
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val headsUpManager: HeadsUpManager,
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
    private val keyguardRepository: KeyguardRepository,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
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
        scope.launch { trackUnseenNotificationsWhileUnlocked() }
        scope.launch { invalidateWhenUnseenSettingChanges() }
    }

    private suspend fun trackUnseenNotificationsWhileUnlocked() {
        // Whether or not we're actively tracking unseen notifications to mark them as seen when
        // appropriate.
        val isTrackingUnseen: Flow<Boolean> =
            keyguardRepository.isKeyguardShowing
                // transformLatest so that we can cancel listening to keyguard transitions once
                // isKeyguardShowing changes (after a successful transition to the keyguard).
                .transformLatest { isShowing ->
                    if (isShowing) {
                        // If the keyguard is showing, we're not tracking unseen.
                        emit(false)
                    } else {
                        // If the keyguard stops showing, then start tracking unseen notifications.
                        emit(true)
                        // If the screen is turning off, stop tracking, but if that transition is
                        // cancelled, then start again.
                        emitAll(
                            keyguardTransitionRepository.transitions
                                .map { step -> !step.isScreenTurningOff }
                        )
                    }
                }
                // Prevent double emit of `false` caused by transition to AOD, followed by keyguard
                // showing
                .distinctUntilChanged()

        // Use collectLatest so that trackUnseenNotifications() is cancelled when the keyguard is
        // showing again
        var clearUnseenOnBeginTracking = false
        isTrackingUnseen.collectLatest { trackingUnseen ->
            if (!trackingUnseen) {
                // Wait for the user to spend enough time on the lock screen before clearing unseen
                // set when unlocked
                awaitTimeSpentNotDozing(SEEN_TIMEOUT)
                clearUnseenOnBeginTracking = true
            } else {
                if (clearUnseenOnBeginTracking) {
                    clearUnseenOnBeginTracking = false
                    unseenNotifications.clear()
                }
                unseenNotifFilter.invalidateList("keyguard no longer showing")
                trackUnseenNotifications()
            }
        }
    }

    private suspend fun awaitTimeSpentNotDozing(duration: Duration) {
        keyguardRepository.isDozing
            // Use transformLatest so that the timeout delay is cancelled if the device enters doze,
            // and is restarted when doze ends.
            .transformLatest { isDozing ->
                if (!isDozing) {
                    delay(duration)
                    // Signal timeout has completed
                    emit(Unit)
                }
            }
            // Suspend until the first emission
            .first()
    }

    private suspend fun trackUnseenNotifications() {
        coroutineScope {
            launch { clearUnseenNotificationsWhenShadeIsExpanded() }
            launch { markHeadsUpNotificationsAsSeen() }
        }
    }

    private suspend fun clearUnseenNotificationsWhenShadeIsExpanded() {
        statusBarStateController.expansionChanges.collectLatest { isExpanded ->
            // Give keyguard events time to propagate, in case this expansion is part of the
            // keyguard transition and not the user expanding the shade
            yield()
            if (isExpanded) {
                unseenNotifications.clear()
            }
        }
    }

    private suspend fun markHeadsUpNotificationsAsSeen() {
        headsUpManager.allEntries
            .filter { it.isRowPinned }
            .forEach { unseenNotifications.remove(it) }
        headsUpManager.headsUpEvents.collect { (entry, isHun) ->
            if (isHun) {
                unseenNotifications.remove(entry)
            }
        }
    }

    private suspend fun invalidateWhenUnseenSettingChanges() {
        secureSettings
            // emit whenever the setting has changed
            .observerFlow(
                UserHandle.USER_ALL,
                Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
            )
            // perform a query immediately
            .onStart { emit(Unit) }
            // for each change, lookup the new value
            .map {
                secureSettings.getIntForUser(
                    Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
                    UserHandle.USER_CURRENT,
                ) == 1
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
                if (
                    keyguardRepository.isKeyguardShowing() || !statusBarStateController.isExpanded
                ) {
                    unseenNotifications.add(entry)
                }
            }

            override fun onEntryUpdated(entry: NotificationEntry) {
                if (
                    keyguardRepository.isKeyguardShowing() || !statusBarStateController.isExpanded
                ) {
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

private val TransitionStep.isScreenTurningOff: Boolean get() =
    transitionState == TransitionState.STARTED && to != KeyguardState.GONE