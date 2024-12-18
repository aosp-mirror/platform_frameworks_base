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
import android.app.NotificationManager
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.NotificationMinimalismPrototype
import com.android.systemui.statusbar.notification.stack.BUCKET_TOP_ONGOING
import com.android.systemui.statusbar.notification.stack.BUCKET_TOP_UNSEEN
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printCollection
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * If the setting is enabled, this will track seen notifications and ensure that they only show in
 * the shelf on the lockscreen.
 *
 * This class is a replacement of the [OriginalUnseenKeyguardCoordinator].
 */
@CoordinatorScope
@SuppressLint("SharedFlowCreation")
class LockScreenMinimalismCoordinator
@Inject
constructor(
    private val dumpManager: DumpManager,
    private val headsUpInteractor: HeadsUpNotificationInteractor,
    private val logger: LockScreenMinimalismCoordinatorLogger,
    @Application private val scope: CoroutineScope,
    private val seenNotificationsInteractor: SeenNotificationsInteractor,
    private val statusBarStateController: StatusBarStateController,
    private val shadeInteractor: ShadeInteractor,
) : Coordinator, Dumpable {

    private val unseenNotifications = mutableSetOf<NotificationEntry>()
    private var isShadeVisible = false
    private var unseenFilterEnabled = false

    override fun attach(pipeline: NotifPipeline) {
        if (NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode()) {
            return
        }
        pipeline.addPromoter(unseenNotifPromoter)
        pipeline.addOnBeforeTransformGroupsListener(::pickOutTopUnseenNotifs)
        pipeline.addCollectionListener(collectionListener)
        scope.launch { trackUnseenFilterSettingChanges() }
        dumpManager.registerDumpable(this)
    }

    private suspend fun trackSeenNotifications() {
        coroutineScope {
            launch { clearUnseenNotificationsWhenShadeIsExpanded() }
            launch { markHeadsUpNotificationsAsSeen() }
        }
    }

    private suspend fun clearUnseenNotificationsWhenShadeIsExpanded() {
        shadeInteractor.isShadeFullyExpanded.collectLatest { isExpanded ->
            // Give keyguard events time to propagate, in case this expansion is part of the
            // keyguard transition and not the user expanding the shade
            delay(SHADE_VISIBLE_SEEN_TIMEOUT)
            isShadeVisible = isExpanded
            if (isExpanded) {
                logger.logShadeVisible(unseenNotifications.size)
                unseenNotifications.clear()
                // no need to invalidateList; filtering is inactive while shade is open
            } else {
                logger.logShadeHidden()
            }
        }
    }

    private suspend fun markHeadsUpNotificationsAsSeen() {
        headsUpInteractor.topHeadsUpRowIfPinned
            .map { it?.let { headsUpInteractor.notificationKey(it) } }
            .collectLatest { key ->
                if (key == null) {
                    logger.logTopHeadsUpRow(key = null, wasUnseenWhenPinned = false)
                } else {
                    val wasUnseenWhenPinned = unseenNotifications.any { it.key == key }
                    logger.logTopHeadsUpRow(key, wasUnseenWhenPinned)
                    if (wasUnseenWhenPinned) {
                        delay(HEADS_UP_SEEN_TIMEOUT)
                        val wasUnseenAfterDelay = unseenNotifications.removeIf { it.key == key }
                        logger.logHunHasBeenSeen(key, wasUnseenAfterDelay)
                        // no need to invalidateList; nothing should change until after heads up
                    }
                }
            }
    }

    private fun unseenFeatureEnabled(): Flow<Boolean> {
        // TODO(b/330387368): create LOCK_SCREEN_NOTIFICATION_MINIMALISM setting to use here?
        //  Or should we actually just repurpose using the existing setting?
        if (NotificationMinimalismPrototype.isEnabled) {
            return flowOf(true)
        }
        return seenNotificationsInteractor.isLockScreenShowOnlyUnseenNotificationsEnabled()
    }

    private suspend fun trackUnseenFilterSettingChanges() {
        unseenFeatureEnabled().collectLatest { isSettingEnabled ->
            // update local field and invalidate if necessary
            if (isSettingEnabled != unseenFilterEnabled) {
                unseenFilterEnabled = isSettingEnabled
                unseenNotifications.clear()
                unseenNotifPromoter.invalidateList("unseen setting changed")
            }
            // if the setting is enabled, then start tracking and filtering unseen notifications
            logger.logTrackingUnseen(isSettingEnabled)
            if (isSettingEnabled) {
                trackSeenNotifications()
            }
        }
    }

    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) {
                if (unseenFilterEnabled && !isShadeVisible) {
                    logger.logUnseenAdded(entry.key)
                    unseenNotifications.add(entry)
                }
            }

            override fun onEntryUpdated(entry: NotificationEntry) {
                if (unseenFilterEnabled && !isShadeVisible) {
                    logger.logUnseenUpdated(entry.key)
                    unseenNotifications.add(entry)
                }
            }

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                if (unseenFilterEnabled && unseenNotifications.remove(entry)) {
                    logger.logUnseenRemoved(entry.key)
                }
            }
        }

    private fun pickOutTopUnseenNotifs(list: List<ListEntry>) {
        if (NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode()) return
        if (!unseenFilterEnabled) return
        // Only ever elevate a top unseen notification on keyguard, not even locked shade
        if (statusBarStateController.state != StatusBarState.KEYGUARD) {
            seenNotificationsInteractor.setTopOngoingNotification(null)
            seenNotificationsInteractor.setTopUnseenNotification(null)
            return
        }
        // On keyguard pick the top-ranked unseen or ongoing notification to elevate
        val nonSummaryEntries: Sequence<NotificationEntry> =
            list
                .asSequence()
                .flatMap {
                    when (it) {
                        is NotificationEntry -> listOfNotNull(it)
                        is GroupEntry -> it.children
                        else -> error("unhandled type of $it")
                    }
                }
                .filter { it.importance >= NotificationManager.IMPORTANCE_DEFAULT }
        seenNotificationsInteractor.setTopOngoingNotification(
            nonSummaryEntries
                .filter { ColorizedFgsCoordinator.isRichOngoing(it) }
                .minByOrNull { it.ranking.rank }
        )
        seenNotificationsInteractor.setTopUnseenNotification(
            nonSummaryEntries
                .filter { !ColorizedFgsCoordinator.isRichOngoing(it) && it in unseenNotifications }
                .minByOrNull { it.ranking.rank }
        )
    }

    @VisibleForTesting
    val unseenNotifPromoter =
        object : NotifPromoter(TAG) {
            override fun shouldPromoteToTopLevel(child: NotificationEntry): Boolean =
                when {
                    NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode() -> false
                    seenNotificationsInteractor.isTopOngoingNotification(child) -> true
                    !NotificationMinimalismPrototype.ungroupTopUnseen -> false
                    else -> seenNotificationsInteractor.isTopUnseenNotification(child)
                }
        }

    val topOngoingSectioner =
        object : NotifSectioner("TopOngoing", BUCKET_TOP_ONGOING) {
            override fun isInSection(entry: ListEntry): Boolean {
                if (NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode()) return false
                return entry.anyEntry { notificationEntry ->
                    seenNotificationsInteractor.isTopOngoingNotification(notificationEntry)
                }
            }
        }

    val topUnseenSectioner =
        object : NotifSectioner("TopUnseen", BUCKET_TOP_UNSEEN) {
            override fun isInSection(entry: ListEntry): Boolean {
                if (NotificationMinimalismPrototype.isUnexpectedlyInLegacyMode()) return false
                return entry.anyEntry { notificationEntry ->
                    seenNotificationsInteractor.isTopUnseenNotification(notificationEntry)
                }
            }
        }

    private fun ListEntry.anyEntry(predicate: (NotificationEntry?) -> Boolean) =
        when {
            predicate(representativeEntry) -> true
            this !is GroupEntry -> false
            else -> children.any(predicate)
        }

    override fun dump(pw: PrintWriter, args: Array<out String>) =
        with(pw.asIndenting()) {
            seenNotificationsInteractor.dump(this)
            printCollection("unseen notifications", unseenNotifications) { println(it.key) }
        }

    companion object {
        private const val TAG = "LockScreenMinimalismCoordinator"
        private val SHADE_VISIBLE_SEEN_TIMEOUT = 0.25.seconds
        private val HEADS_UP_SEEN_TIMEOUT = 0.75.seconds
    }
}
