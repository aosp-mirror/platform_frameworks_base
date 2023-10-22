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
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.expansionChanges
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.headsUpEvents
import com.android.systemui.util.asIndenting
import com.android.systemui.util.indentIfPossible
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Filters low priority and privacy-sensitive notifications from the lockscreen, and hides section
 * headers on the lockscreen. If enabled, it will also track and hide seen notifications on the
 * lockscreen.
 */
@CoordinatorScope
class KeyguardCoordinator
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val dumpManager: DumpManager,
    private val headsUpManager: HeadsUpManager,
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
    private val keyguardRepository: KeyguardRepository,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val logger: KeyguardCoordinatorLogger,
    @Application private val scope: CoroutineScope,
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider,
    private val secureSettings: SecureSettings,
    private val seenNotificationsInteractor: SeenNotificationsInteractor,
    private val statusBarStateController: StatusBarStateController,
) : Coordinator, Dumpable {

    private val unseenNotifications = mutableSetOf<NotificationEntry>()
    private val unseenEntryAdded = MutableSharedFlow<NotificationEntry>(extraBufferCapacity = 1)
    private val unseenEntryRemoved = MutableSharedFlow<NotificationEntry>(extraBufferCapacity = 1)
    private var unseenFilterEnabled = false

    override fun attach(pipeline: NotifPipeline) {
        setupInvalidateNotifListCallbacks()
        // Filter at the "finalize" stage so that views remain bound by PreparationCoordinator
        pipeline.addFinalizeFilter(notifFilter)
        keyguardNotificationVisibilityProvider.addOnStateChangedListener(::invalidateListFromFilter)
        updateSectionHeadersVisibility()
        attachUnseenFilter(pipeline)
    }

    private fun attachUnseenFilter(pipeline: NotifPipeline) {
        pipeline.addFinalizeFilter(unseenNotifFilter)
        pipeline.addCollectionListener(collectionListener)
        scope.launch { trackUnseenFilterSettingChanges() }
        dumpManager.registerDumpable(this)
    }

    private suspend fun trackSeenNotifications() {
        // Whether or not keyguard is visible (or occluded).
        val isKeyguardPresent: Flow<Boolean> =
            keyguardTransitionRepository.transitions
                .map { step -> step.to != KeyguardState.GONE }
                .distinctUntilChanged()
                .onEach { trackingUnseen -> logger.logTrackingUnseen(trackingUnseen) }

        // Separately track seen notifications while the device is locked, applying once the device
        // is unlocked.
        val notificationsSeenWhileLocked = mutableSetOf<NotificationEntry>()

        // Use [collectLatest] to cancel any running jobs when [trackingUnseen] changes.
        isKeyguardPresent.collectLatest { isKeyguardPresent: Boolean ->
            if (isKeyguardPresent) {
                // Keyguard is not gone, notifications need to be visible for a certain threshold
                // before being marked as seen
                trackSeenNotificationsWhileLocked(notificationsSeenWhileLocked)
            } else {
                // Mark all seen-while-locked notifications as seen for real.
                if (notificationsSeenWhileLocked.isNotEmpty()) {
                    unseenNotifications.removeAll(notificationsSeenWhileLocked)
                    logger.logAllMarkedSeenOnUnlock(
                        seenCount = notificationsSeenWhileLocked.size,
                        remainingUnseenCount = unseenNotifications.size
                    )
                    notificationsSeenWhileLocked.clear()
                }
                unseenNotifFilter.invalidateList("keyguard no longer showing")
                // Keyguard is gone, notifications can be immediately marked as seen when they
                // become visible.
                trackSeenNotificationsWhileUnlocked()
            }
        }
    }

    /**
     * Keep [notificationsSeenWhileLocked] updated to represent which notifications have actually
     * been "seen" while the device is on the keyguard.
     */
    private suspend fun trackSeenNotificationsWhileLocked(
        notificationsSeenWhileLocked: MutableSet<NotificationEntry>,
    ) = coroutineScope {
        // Remove removed notifications from the set
        launch {
            unseenEntryRemoved.collect { entry ->
                if (notificationsSeenWhileLocked.remove(entry)) {
                    logger.logRemoveSeenOnLockscreen(entry)
                }
            }
        }
        // Use collectLatest so that the timeout delay is cancelled if the device enters doze, and
        // is restarted when doze ends.
        keyguardRepository.isDozing.collectLatest { isDozing ->
            if (!isDozing) {
                trackSeenNotificationsWhileLockedAndNotDozing(notificationsSeenWhileLocked)
            }
        }
    }

    /**
     * Keep [notificationsSeenWhileLocked] updated to represent which notifications have actually
     * been "seen" while the device is on the keyguard and not dozing. Any new and existing unseen
     * notifications are not marked as seen until they are visible for the [SEEN_TIMEOUT] duration.
     */
    private suspend fun trackSeenNotificationsWhileLockedAndNotDozing(
        notificationsSeenWhileLocked: MutableSet<NotificationEntry>
    ) = coroutineScope {
        // All child tracking jobs will be cancelled automatically when this is cancelled.
        val trackingJobsByEntry = mutableMapOf<NotificationEntry, Job>()

        /**
         * Wait for the user to spend enough time on the lock screen before removing notification
         * from unseen set upon unlock.
         */
        suspend fun trackSeenDurationThreshold(entry: NotificationEntry) {
            if (notificationsSeenWhileLocked.remove(entry)) {
                logger.logResetSeenOnLockscreen(entry)
            }
            delay(SEEN_TIMEOUT)
            notificationsSeenWhileLocked.add(entry)
            trackingJobsByEntry.remove(entry)
            logger.logSeenOnLockscreen(entry)
        }

        /** Stop any unseen tracking when a notification is removed. */
        suspend fun stopTrackingRemovedNotifs(): Nothing =
            unseenEntryRemoved.collect { entry ->
                trackingJobsByEntry.remove(entry)?.let {
                    it.cancel()
                    logger.logStopTrackingLockscreenSeenDuration(entry)
                }
            }

        /** Start tracking new notifications when they are posted. */
        suspend fun trackNewUnseenNotifs(): Nothing = coroutineScope {
            unseenEntryAdded.collect { entry ->
                logger.logTrackingLockscreenSeenDuration(entry)
                // If this is an update, reset the tracking.
                trackingJobsByEntry[entry]?.let {
                    it.cancel()
                    logger.logResetSeenOnLockscreen(entry)
                }
                trackingJobsByEntry[entry] = launch { trackSeenDurationThreshold(entry) }
            }
        }

        // Start tracking for all notifications that are currently unseen.
        logger.logTrackingLockscreenSeenDuration(unseenNotifications)
        unseenNotifications.forEach { entry ->
            trackingJobsByEntry[entry] = launch { trackSeenDurationThreshold(entry) }
        }

        launch { trackNewUnseenNotifs() }
        launch { stopTrackingRemovedNotifs() }
    }

    // Track "seen" notifications, marking them as such when either shade is expanded or the
    // notification becomes heads up.
    private suspend fun trackSeenNotificationsWhileUnlocked() {
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
                logger.logShadeExpanded()
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
                logger.logUnseenHun(entry.key)
                unseenNotifications.remove(entry)
            }
        }
    }

    private suspend fun trackUnseenFilterSettingChanges() {
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
            // don't emit anything if nothing has changed
            .distinctUntilChanged()
            // perform lookups on the bg thread pool
            .flowOn(bgDispatcher)
            // only track the most recent emission, if events are happening faster than they can be
            // consumed
            .conflate()
            .collectLatest { setting ->
                // update local field and invalidate if necessary
                if (setting != unseenFilterEnabled) {
                    unseenFilterEnabled = setting
                    unseenNotifFilter.invalidateList("unseen setting changed")
                }
                // if the setting is enabled, then start tracking and filtering unseen notifications
                if (setting) {
                    trackSeenNotifications()
                }
            }
    }

    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) {
                if (
                    keyguardRepository.isKeyguardShowing() || !statusBarStateController.isExpanded
                ) {
                    logger.logUnseenAdded(entry.key)
                    unseenNotifications.add(entry)
                    unseenEntryAdded.tryEmit(entry)
                }
            }

            override fun onEntryUpdated(entry: NotificationEntry) {
                if (
                    keyguardRepository.isKeyguardShowing() || !statusBarStateController.isExpanded
                ) {
                    logger.logUnseenUpdated(entry.key)
                    unseenNotifications.add(entry)
                    unseenEntryAdded.tryEmit(entry)
                }
            }

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                if (unseenNotifications.remove(entry)) {
                    logger.logUnseenRemoved(entry.key)
                    unseenEntryRemoved.tryEmit(entry)
                }
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
                logger.logProviderHasFilteredOutSeenNotifs(hasFilteredAnyNotifs)
                seenNotificationsInteractor.setHasFilteredOutSeenNotifications(hasFilteredAnyNotifs)
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

    override fun dump(pw: PrintWriter, args: Array<out String>) =
        with(pw.asIndenting()) {
            println(
                "notificationListInteractor.hasFilteredOutSeenNotifications.value=" +
                    seenNotificationsInteractor.hasFilteredOutSeenNotifications.value
            )
            println("unseen notifications:")
            indentIfPossible {
                for (notification in unseenNotifications) {
                    println(notification.key)
                }
            }
        }

    companion object {
        private const val TAG = "KeyguardCoordinator"
        private val SEEN_TIMEOUT = 5.seconds
    }
}
