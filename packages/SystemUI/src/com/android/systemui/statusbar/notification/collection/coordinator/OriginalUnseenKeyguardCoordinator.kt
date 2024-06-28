/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.expansionChanges
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.NotificationMinimalismPrototype
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.headsUpEvents
import com.android.systemui.util.asIndenting
import com.android.systemui.util.indentIfPossible
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * If the setting is enabled, this will track and hide seen notifications on the lockscreen.
 *
 * This is the "original" unseen keyguard coordinator because this is the logic originally developed
 * for large screen devices where showing "seen" notifications on the lock screen was distracting.
 * Moreover, this file was created during a project that will replace this logic, so the
 * [LockScreenMinimalismCoordinator] is the expected replacement of this file.
 */
@CoordinatorScope
@SuppressLint("SharedFlowCreation")
class OriginalUnseenKeyguardCoordinator
@Inject
constructor(
    private val dumpManager: DumpManager,
    private val headsUpManager: HeadsUpManager,
    private val keyguardRepository: KeyguardRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val logger: KeyguardCoordinatorLogger,
    @Application private val scope: CoroutineScope,
    private val seenNotificationsInteractor: SeenNotificationsInteractor,
    private val statusBarStateController: StatusBarStateController,
    private val sceneInteractor: SceneInteractor,
) : Coordinator, Dumpable {

    private val unseenNotifications = mutableSetOf<NotificationEntry>()
    private val unseenEntryAdded = MutableSharedFlow<NotificationEntry>(extraBufferCapacity = 1)
    private val unseenEntryRemoved = MutableSharedFlow<NotificationEntry>(extraBufferCapacity = 1)
    private var unseenFilterEnabled = false

    override fun attach(pipeline: NotifPipeline) {
        NotificationMinimalismPrototype.assertInLegacyMode()
        pipeline.addFinalizeFilter(unseenNotifFilter)
        pipeline.addCollectionListener(collectionListener)
        scope.launch { trackUnseenFilterSettingChanges() }
        dumpManager.registerDumpable(this)
    }

    private suspend fun trackSeenNotifications() {
        // Whether or not keyguard is visible (or occluded).
        @Suppress("DEPRECATION")
        val isKeyguardPresentFlow: Flow<Boolean> =
            if (SceneContainerFlag.isEnabled) {
                    sceneInteractor.transitionState.map {
                        !it.isTransitioning(to = Scenes.Gone) && !it.isIdle(Scenes.Gone)
                    }
                } else {
                    keyguardTransitionInteractor.transitions.map { step ->
                        step.to != KeyguardState.GONE
                    }
                }
                .distinctUntilChanged()
                .onEach { trackingUnseen -> logger.logTrackingUnseen(trackingUnseen) }

        // Separately track seen notifications while the device is locked, applying once the device
        // is unlocked.
        val notificationsSeenWhileLocked = mutableSetOf<NotificationEntry>()

        // Use [collectLatest] to cancel any running jobs when [trackingUnseen] changes.
        isKeyguardPresentFlow.collectLatest { isKeyguardPresent: Boolean ->
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

    private fun unseenFeatureEnabled(): Flow<Boolean> {
        if (NotificationMinimalismPrototype.isEnabled) {
            // TODO(b/330387368): should this really just be turned off? If so, hide the setting.
            return flowOf(false)
        }
        return seenNotificationsInteractor.isLockScreenShowOnlyUnseenNotificationsEnabled()
    }

    private suspend fun trackUnseenFilterSettingChanges() {
        unseenFeatureEnabled().collectLatest { setting ->
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
    val unseenNotifFilter =
        object : NotifFilter(TAG) {

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

    private fun shouldIgnoreUnseenCheck(entry: NotificationEntry): Boolean =
        when {
            entry.isMediaNotification -> true
            entry.sbn.isOngoing -> true
            else -> false
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
        private const val TAG = "OriginalUnseenKeyguardCoordinator"
        private val SEEN_TIMEOUT = 5.seconds
    }
}
