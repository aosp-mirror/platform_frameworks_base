/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.icon.domain.interactor

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.statusbar.data.repository.NotificationListenerSettingsRepository
import com.android.systemui.statusbar.notification.data.repository.NotificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationIconInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn

/** Domain logic related to notification icons. */
class NotificationIconsInteractor
@Inject
constructor(
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val bubbles: Optional<Bubbles>,
    private val headsUpNotificationIconInteractor: HeadsUpNotificationIconInteractor,
    private val keyguardViewStateRepository: NotificationsKeyguardViewStateRepository,
) {
    /** Returns a subset of all active notifications based on the supplied filtration parameters. */
    fun filteredNotifSet(
        forceShowHeadsUp: Boolean = false,
        showAmbient: Boolean = true,
        showLowPriority: Boolean = true,
        showDismissed: Boolean = true,
        showRepliedMessages: Boolean = true,
        showPulsing: Boolean = true,
    ): Flow<Set<ActiveNotificationModel>> {
        return combine(
            activeNotificationsInteractor.topLevelRepresentativeNotifications,
            headsUpNotificationIconInteractor.isolatedNotification,
            keyguardViewStateRepository.areNotificationsFullyHidden,
        ) { notifications, isolatedNotifKey, notifsFullyHidden ->
            notifications
                .asSequence()
                .filter { model: ActiveNotificationModel ->
                    shouldShowNotificationIcon(
                        model = model,
                        forceShowHeadsUp = forceShowHeadsUp,
                        showAmbient = showAmbient,
                        showLowPriority = showLowPriority,
                        showDismissed = showDismissed,
                        showRepliedMessages = showRepliedMessages,
                        showPulsing = showPulsing,
                        isolatedNotifKey = isolatedNotifKey,
                        notifsFullyHidden = notifsFullyHidden,
                    )
                }
                .toSet()
        }
    }

    private fun shouldShowNotificationIcon(
        model: ActiveNotificationModel,
        forceShowHeadsUp: Boolean,
        showAmbient: Boolean,
        showLowPriority: Boolean,
        showDismissed: Boolean,
        showRepliedMessages: Boolean,
        showPulsing: Boolean,
        isolatedNotifKey: String?,
        notifsFullyHidden: Boolean,
    ): Boolean {
        return when {
            forceShowHeadsUp && model.key == isolatedNotifKey -> true
            !showAmbient && model.isAmbient -> false
            !showLowPriority && model.isSilent -> false
            !showDismissed && model.isRowDismissed -> false
            !showRepliedMessages && model.isLastMessageFromReply -> false
            !showAmbient && model.isSuppressedFromStatusBar -> false
            !showPulsing && model.isPulsing && !notifsFullyHidden -> false
            bubbles.getOrNull()?.isBubbleExpanded(model.key) == true -> false
            else -> true
        }
    }
}

/** Domain logic related to notification icons shown on the always-on display. */
class AlwaysOnDisplayNotificationIconsInteractor
@Inject
constructor(
    @Background bgContext: CoroutineContext,
    deviceEntryInteractor: DeviceEntryInteractor,
    iconsInteractor: NotificationIconsInteractor,
) {
    val aodNotifs: Flow<Set<ActiveNotificationModel>> =
        deviceEntryInteractor.isBypassEnabled
            .flatMapLatest { isBypassEnabled ->
                iconsInteractor.filteredNotifSet(
                    showAmbient = false,
                    showDismissed = false,
                    showRepliedMessages = false,
                    showPulsing = !isBypassEnabled,
                )
            }
            .flowOn(bgContext)
}

/** Domain logic related to notification icons shown in the status bar. */
class StatusBarNotificationIconsInteractor
@Inject
constructor(
    @Background bgContext: CoroutineContext,
    iconsInteractor: NotificationIconsInteractor,
    settingsRepository: NotificationListenerSettingsRepository,
) {
    val statusBarNotifs: Flow<Set<ActiveNotificationModel>> =
        settingsRepository.showSilentStatusIcons
            .flatMapLatest { showSilentIcons ->
                iconsInteractor.filteredNotifSet(
                    forceShowHeadsUp = true,
                    showAmbient = false,
                    showLowPriority = showSilentIcons,
                    showDismissed = false,
                    showRepliedMessages = false,
                )
            }
            .flowOn(bgContext)
}
