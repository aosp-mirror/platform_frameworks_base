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

package com.android.systemui.statusbar.chips.notification.domain.interactor

import android.annotation.SuppressLint
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.notification.domain.model.NotificationChipModel
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor.Companion.isOngoingCallNotification
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** An interactor for the notification chips shown in the status bar. */
@SysUISingleton
class StatusBarNotificationChipsInteractor
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    private val systemClock: SystemClock,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val singleNotificationChipInteractorFactory: SingleNotificationChipInteractor.Factory,
    @StatusBarChipsLog private val logBuffer: LogBuffer,
) : CoreStartable {
    private val logger = Logger(logBuffer, "AllNotifs".pad())

    // Each chip tap is an individual event, *not* a state, which is why we're using SharedFlow not
    // StateFlow. There shouldn't be multiple updates per frame, which should avoid performance
    // problems.
    @SuppressLint("SharedFlowCreation")
    private val _promotedNotificationChipTapEvent = MutableSharedFlow<String>()

    /**
     * SharedFlow that emits each time a promoted notification's status bar chip is tapped. The
     * emitted value is the promoted notification's key.
     */
    val promotedNotificationChipTapEvent: SharedFlow<String> =
        _promotedNotificationChipTapEvent.asSharedFlow()

    suspend fun onPromotedNotificationChipTapped(key: String) {
        StatusBarNotifChips.assertInNewMode()
        _promotedNotificationChipTapEvent.emit(key)
    }

    /**
     * A cache of interactors. Each currently-promoted notification should have a corresponding
     * interactor in this map.
     */
    private val promotedNotificationInteractorMap =
        mutableMapOf<String, SingleNotificationChipInteractor>()

    /**
     * A list of interactors. Each currently-promoted notification should have a corresponding
     * interactor in this list.
     */
    private val promotedNotificationInteractors =
        MutableStateFlow<List<SingleNotificationChipInteractor>>(emptyList())

    /**
     * The notifications that are promoted and ongoing.
     *
     * Explicitly does *not* include any ongoing call notifications, even if the call notifications
     * meet the promotion criteria. Those call notifications will be handled by
     * [com.android.systemui.statusbar.chips.call.domain.CallChipInteractor] instead. See
     * b/388521980.
     */
    private val promotedOngoingNotifications =
        activeNotificationsInteractor.promotedOngoingNotifications.map { notifs ->
            notifs.filterNot { it.isOngoingCallNotification() }
        }

    override fun start() {
        if (!StatusBarNotifChips.isEnabled) {
            return
        }

        backgroundScope.launch("StatusBarNotificationChipsInteractor") {
            promotedOngoingNotifications.pairwise(initialValue = emptyList()).collect {
                (oldNotifs, currentNotifs) ->
                val removedNotifKeys =
                    oldNotifs.map { it.key }.minus(currentNotifs.map { it.key }.toSet())
                removedNotifKeys.forEach { removedNotifKey ->
                    val wasRemoved = promotedNotificationInteractorMap.remove(removedNotifKey)
                    if (wasRemoved == null) {
                        logger.w({
                            "Attempted to remove $str1 from interactor map but it wasn't present"
                        }) {
                            str1 = removedNotifKey
                        }
                    }
                }

                currentNotifs.forEach { notif ->
                    val interactor =
                        promotedNotificationInteractorMap.computeIfAbsent(notif.key) {
                            singleNotificationChipInteractorFactory.create(
                                notif,
                                creationTime = systemClock.currentTimeMillis(),
                            )
                        }
                    interactor.setNotification(notif)
                }
                logger.d({ "Interactors: $str1" }) {
                    str1 = promotedNotificationInteractorMap.keys.joinToString(separator = " /// ")
                }
                promotedNotificationInteractors.value =
                    promotedNotificationInteractorMap.values.toList()
            }
        }
    }

    /**
     * A flow modeling the notifications that should be shown as chips in the status bar. Emits an
     * empty list if there are no notifications that should show a status bar chip.
     */
    val notificationChips: Flow<List<NotificationChipModel>> =
        if (StatusBarNotifChips.isEnabled) {
            // For all our current interactors...
            promotedNotificationInteractors.flatMapLatest { intrs ->
                // Stable-sort the promoted notifications by when they first appeared so that:
                // 1) The chips don't switch places if the older chip gets a notification update.
                // 2) The chips don't switch places when the second chip is tapped. (Whichever
                // notification is showing heads-up is considered to be the top notification, which
                // means tapping the second chip would move it to be the first chip if we didn't
                // sort by appearance time here.)
                // 3) Older chips get hidden if there's not enough room for all chips.
                val interactors = intrs.sortedByDescending { it.creationTime }
                if (interactors.isNotEmpty()) {
                    // Combine each interactor's [notificationChip] flow...
                    val allNotificationChips: List<Flow<NotificationChipModel?>> =
                        interactors.map { interactor -> interactor.notificationChip }
                    combine(allNotificationChips) {
                        // ... and emit just the non-null chips
                        it.filterNotNull()
                    }
                } else {
                    flowOf(emptyList())
                }
            }
        } else {
            flowOf(emptyList())
        }
}
