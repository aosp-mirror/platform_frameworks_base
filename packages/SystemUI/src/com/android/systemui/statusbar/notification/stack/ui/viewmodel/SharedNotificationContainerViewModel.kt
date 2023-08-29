/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** View-model for the shared notification container, used by both the shade and keyguard spaces */
class SharedNotificationContainerViewModel
@Inject
constructor(
    interactor: SharedNotificationContainerInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    notificationStackSizeCalculator: NotificationStackSizeCalculator,
    controller: NotificationStackScrollLayoutController,
    shadeInteractor: ShadeInteractor,
) {
    private val statesForConstrainedNotifications =
        setOf(KeyguardState.LOCKSCREEN, KeyguardState.AOD, KeyguardState.DOZING)

    val configurationBasedDimensions: Flow<ConfigurationBasedDimensions> =
        interactor.configurationBasedDimensions
            .map {
                ConfigurationBasedDimensions(
                    marginStart = if (it.useSplitShade) 0 else it.marginHorizontal,
                    marginEnd = it.marginHorizontal,
                    marginBottom = it.marginBottom,
                    marginTop =
                        if (it.useLargeScreenHeader) it.marginTopLargeScreen else it.marginTop,
                    useSplitShade = it.useSplitShade,
                )
            }
            .distinctUntilChanged()

    /** If the user is visually on one of the unoccluded lockscreen states. */
    val isOnLockscreen: Flow<Boolean> =
        keyguardTransitionInteractor.finishedKeyguardState
            .map { statesForConstrainedNotifications.contains(it) }
            .distinctUntilChanged()

    /** Are we purely on the keyguard without the shade/qs? */
    val isOnLockscreenWithoutShade: Flow<Boolean> =
        combine(
                isOnLockscreen,
                // Shade with notifications
                shadeInteractor.shadeExpansion.map { it > 0f },
                // Shade without notifications, quick settings only (pull down from very top on
                // lockscreen)
                shadeInteractor.qsExpansion.map { it > 0f },
            ) { isKeyguard, isShadeVisible, qsExpansion ->
                isKeyguard && !(isShadeVisible || qsExpansion)
            }
            .distinctUntilChanged()

    /**
     * The container occupies the entire screen, and must be positioned relative to other elements.
     *
     * On keyguard, this generally fits below the clock and above the lock icon, or in split shade,
     * the top of the screen to the lock icon.
     *
     * When the shade is expanding, the position is controlled by... the shade.
     */
    val position: Flow<SharedNotificationContainerPosition> =
        isOnLockscreenWithoutShade.flatMapLatest { onLockscreen ->
            if (onLockscreen) {
                combine(
                    keyguardInteractor.sharedNotificationContainerPosition,
                    configurationBasedDimensions
                ) { position, config ->
                    if (config.useSplitShade) {
                        position.copy(top = 0f)
                    } else {
                        position
                    }
                }
            } else {
                interactor.topPosition.map { top ->
                    keyguardInteractor.sharedNotificationContainerPosition.value.copy(top = top)
                }
            }
        }

    /**
     * Under certain scenarios, such as swiping up on the lockscreen, the container will need to be
     * translated as the keyguard fades out.
     */
    val translationY: Flow<Float> =
        combine(isOnLockscreen, keyguardInteractor.keyguardTranslationY) {
            isOnLockscreen,
            translationY ->
            if (isOnLockscreen) {
                translationY
            } else {
                0f
            }
        }

    /**
     * When on keyguard, there is limited space to display notifications so calculate how many could
     * be shown. Otherwise, there is no limit since the vertical space will be scrollable.
     * TODO: b/296606746 - Need to rerun logic when notifs change
     */
    val maxNotifications: Flow<Int> =
        combine(isOnLockscreen, shadeInteractor.shadeExpansion, position) {
            onLockscreen,
            shadeExpansion,
            positionInfo ->
            if (onLockscreen && shadeExpansion < 1f) {
                notificationStackSizeCalculator.computeMaxKeyguardNotifications(
                    controller.getView(),
                    positionInfo.bottom - positionInfo.top,
                    0f, // Vertical space for shelf is already accounted for
                    controller.getShelfHeight().toFloat(),
                )
            } else {
                -1 // No limit
            }
        }

    data class ConfigurationBasedDimensions(
        val marginStart: Int,
        val marginTop: Int,
        val marginEnd: Int,
        val marginBottom: Int,
        val useSplitShade: Boolean,
    )
}
