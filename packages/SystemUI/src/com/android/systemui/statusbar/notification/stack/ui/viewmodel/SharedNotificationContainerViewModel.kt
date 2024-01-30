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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState.SHADE_LOCKED
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

/** View-model for the shared notification container, used by both the shade and keyguard spaces */
class SharedNotificationContainerViewModel
@Inject
constructor(
    private val interactor: SharedNotificationContainerInteractor,
    @Application applicationScope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val shadeInteractor: ShadeInteractor,
    occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    lockscreenToOccludedTransitionViewModel: LockscreenToOccludedTransitionViewModel,
) {
    private val statesForConstrainedNotifications =
        setOf(
            KeyguardState.AOD,
            KeyguardState.LOCKSCREEN,
            KeyguardState.DOZING,
            KeyguardState.ALTERNATE_BOUNCER,
            KeyguardState.PRIMARY_BOUNCER
        )

    val shadeCollapseFadeInComplete = MutableStateFlow(false)

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
                    paddingTop =
                        if (it.useSplitShade) {
                            // When in split shade, the margin is applied twice as the legacy shade
                            // code uses it to calculate padding.
                            it.keyguardSplitShadeTopMargin - 2 * it.marginTopLargeScreen
                        } else {
                            0
                        }
                )
            }
            .distinctUntilChanged()

    /** If the user is visually on one of the unoccluded lockscreen states. */
    val isOnLockscreen: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.finishedKeyguardState.map {
                    statesForConstrainedNotifications.contains(it)
                },
                keyguardTransitionInteractor
                    .transitionValue(KeyguardState.LOCKSCREEN)
                    .onStart { emit(0f) }
                    .map { it > 0 }
            ) { constrainedNotificationState, transitioningToOrFromLockscreen ->
                constrainedNotificationState || transitioningToOrFromLockscreen
            }
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

    /** Fade in only for use after the shade collapses */
    val shadeCollpaseFadeIn: Flow<Boolean> =
        flow {
                while (currentCoroutineContext().isActive) {
                    emit(false)
                    // Wait for shade to be fully expanded
                    keyguardInteractor.statusBarState.first { it == SHADE_LOCKED }
                    // ... and then for it to be collapsed
                    isOnLockscreenWithoutShade.first { it }
                    emit(true)
                    // ... and then for the animation to complete
                    shadeCollapseFadeInComplete.first { it }
                    shadeCollapseFadeInComplete.value = false
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /**
     * The container occupies the entire screen, and must be positioned relative to other elements.
     *
     * On keyguard, this generally fits below the clock and above the lock icon, or in split shade,
     * the top of the screen to the lock icon.
     *
     * When the shade is expanding, the position is controlled by... the shade.
     */
    val bounds: StateFlow<NotificationContainerBounds> =
        combine(
                isOnLockscreenWithoutShade,
                keyguardInteractor.notificationContainerBounds,
                configurationBasedDimensions,
                interactor.topPosition.sampleCombine(
                    keyguardTransitionInteractor.isInTransitionToAnyState,
                    shadeInteractor.qsExpansion,
                ),
            ) { onLockscreen, bounds, config, (top, isInTransitionToAnyState, qsExpansion) ->
                if (onLockscreen) {
                    bounds.copy(top = bounds.top + config.paddingTop)
                } else {
                    // When QS expansion > 0, it should directly set the top padding so do not
                    // animate it
                    val animate = qsExpansion == 0f && !isInTransitionToAnyState
                    keyguardInteractor.notificationContainerBounds.value.copy(
                        top = top,
                        isAnimated = animate,
                    )
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Lazily,
                initialValue = NotificationContainerBounds(),
            )

    val alpha: Flow<Float> =
        isOnLockscreenWithoutShade
            .flatMapLatest { isOnLockscreenWithoutShade ->
                combineTransform(
                    merge(
                        occludedToLockscreenTransitionViewModel.lockscreenAlpha,
                        lockscreenToOccludedTransitionViewModel.lockscreenAlpha,
                        keyguardInteractor.keyguardAlpha,
                    ),
                    shadeCollpaseFadeIn,
                ) { alpha, shadeCollpaseFadeIn ->
                    if (isOnLockscreenWithoutShade) {
                        if (!shadeCollpaseFadeIn) {
                            emit(alpha)
                        }
                    } else {
                        emit(1f)
                    }
                }
            }
            .distinctUntilChanged()

    /**
     * Under certain scenarios, such as swiping up on the lockscreen, the container will need to be
     * translated as the keyguard fades out.
     */
    val translationY: Flow<Float> =
        combine(
            isOnLockscreen,
            merge(
                keyguardInteractor.keyguardTranslationY,
                occludedToLockscreenTransitionViewModel.lockscreenTranslationY,
            )
        ) { isOnLockscreen, translationY ->
            if (isOnLockscreen) {
                translationY
            } else {
                0f
            }
        }

    /**
     * When on keyguard, there is limited space to display notifications so calculate how many could
     * be shown. Otherwise, there is no limit since the vertical space will be scrollable.
     *
     * When expanding or when the user is interacting with the shade, keep the count stable; do not
     * emit a value.
     */
    fun getMaxNotifications(calculateSpace: (Float, Boolean) -> Int): Flow<Int> {
        val showLimitedNotifications = isOnLockscreenWithoutShade
        val showUnlimitedNotifications =
            combine(
                isOnLockscreen,
                keyguardInteractor.statusBarState,
            ) { isOnLockscreen, statusBarState ->
                statusBarState == SHADE_LOCKED || !isOnLockscreen
            }

        return combineTransform(
                showLimitedNotifications,
                showUnlimitedNotifications,
                shadeInteractor.isUserInteracting,
                bounds,
                interactor.notificationStackChanged.onStart { emit(Unit) },
                interactor.useExtraShelfSpace,
            ) { flows ->
                val showLimitedNotifications = flows[0] as Boolean
                val showUnlimitedNotifications = flows[1] as Boolean
                val isUserInteracting = flows[2] as Boolean
                val bounds = flows[3] as NotificationContainerBounds
                val useExtraShelfSpace = flows[5] as Boolean

                if (!isUserInteracting) {
                    if (showLimitedNotifications) {
                        emit(calculateSpace(bounds.bottom - bounds.top, useExtraShelfSpace))
                    } else if (showUnlimitedNotifications) {
                        emit(-1)
                    }
                }
            }
            .distinctUntilChanged()
    }

    fun notificationStackChanged() {
        interactor.notificationStackChanged()
    }

    fun setShadeCollapseFadeInComplete(complete: Boolean) {
        shadeCollapseFadeInComplete.value = complete
    }

    data class ConfigurationBasedDimensions(
        val marginStart: Int,
        val marginTop: Int,
        val marginEnd: Int,
        val marginBottom: Int,
        val useSplitShade: Boolean,
        val paddingTop: Int,
    )
}
