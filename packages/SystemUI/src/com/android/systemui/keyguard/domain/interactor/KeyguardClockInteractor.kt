/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.util.Log
import com.android.keyguard.ClockEventController
import com.android.keyguard.KeyguardClockSwitch
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardClockRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockId
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.util.kotlin.combine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val TAG = KeyguardClockInteractor::class.simpleName
/** Manages and encapsulates the clock components of the lockscreen root view. */
@SysUISingleton
class KeyguardClockInteractor
@Inject
constructor(
    mediaCarouselInteractor: MediaCarouselInteractor,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    shadeInteractor: ShadeInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    headsUpNotificationInteractor: HeadsUpNotificationInteractor,
    @Application private val applicationScope: CoroutineScope,
    val keyguardClockRepository: KeyguardClockRepository,
) {
    private val isOnAod: Flow<Boolean> =
        keyguardTransitionInteractor.currentKeyguardState.map { it == KeyguardState.AOD }

    val selectedClockSize: StateFlow<ClockSizeSetting> = keyguardClockRepository.selectedClockSize

    val currentClockId: Flow<ClockId> = keyguardClockRepository.currentClockId

    val currentClock: StateFlow<ClockController?> = keyguardClockRepository.currentClock

    val previewClock: Flow<ClockController> = keyguardClockRepository.previewClock

    val clockEventController: ClockEventController by keyguardClockRepository::clockEventController

    var clock: ClockController? by keyguardClockRepository.clockEventController::clock

    val clockSize: StateFlow<ClockSize> =
        if (SceneContainerFlag.isEnabled) {
            combine(
                    shadeInteractor.shadeMode,
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    mediaCarouselInteractor.hasActiveMediaOrRecommendation,
                    keyguardInteractor.isDozing,
                    isOnAod,
                ) { shadeMode, hasNotifs, hasMedia, isDozing, isOnAod ->
                    return@combine when {
                        keyguardClockRepository.shouldForceSmallClock && !isOnAod -> ClockSize.SMALL
                        shadeMode == ShadeMode.Single && (hasNotifs || hasMedia) -> ClockSize.SMALL
                        shadeMode == ShadeMode.Single -> ClockSize.LARGE
                        hasMedia && !isDozing -> ClockSize.SMALL
                        else -> ClockSize.LARGE
                    }
                }
                .stateIn(
                    scope = applicationScope,
                    started = SharingStarted.WhileSubscribed(),
                    initialValue = ClockSize.LARGE
                )
        } else {
            keyguardClockRepository.clockSize
        }

    val clockShouldBeCentered: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
            combine(
                shadeInteractor.shadeMode,
                activeNotificationsInteractor.areAnyNotificationsPresent,
                keyguardInteractor.isActiveDreamLockscreenHosted,
                isOnAod,
                headsUpNotificationInteractor.isHeadsUpOrAnimatingAway,
                keyguardInteractor.isDozing,
            ) {
                shadeMode,
                areAnyNotificationsPresent,
                isActiveDreamLockscreenHosted,
                isOnAod,
                isHeadsUp,
                isDozing ->
                when {
                    shadeMode != ShadeMode.Split -> true
                    !areAnyNotificationsPresent -> true
                    isActiveDreamLockscreenHosted -> true
                    // Pulsing notification appears on the right. Move clock left to avoid overlap.
                    isHeadsUp && isDozing -> false
                    else -> isOnAod
                }
            }
        } else {
            keyguardInteractor.clockShouldBeCentered
        }

    fun setClockSize(@KeyguardClockSwitch.ClockSize size: Int) =
        setClockSize(ClockSize.fromLegacy(size))

    fun setClockSize(size: ClockSize) {
        SceneContainerFlag.assertInLegacyMode()
        keyguardClockRepository.setClockSize(size)
    }

    val renderedClockId: ClockId
        get() {
            return clock?.let { clock -> clock.config.id }
                ?: run {
                    Log.e(TAG, "No clock is available")
                    KeyguardClockSwitch.MISSING_CLOCK_ID
                }
        }

    fun animateFoldToAod(foldFraction: Float) {
        clock?.let { clock ->
            clock.smallClock.animations.fold(foldFraction)
            clock.largeClock.animations.fold(foldFraction)
        }
    }
}
