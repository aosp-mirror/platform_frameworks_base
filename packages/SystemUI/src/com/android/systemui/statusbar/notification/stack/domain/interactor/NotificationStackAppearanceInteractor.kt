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

package com.android.systemui.statusbar.notification.stack.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.stack.data.repository.NotificationPlaceholderRepository
import com.android.systemui.statusbar.notification.stack.data.repository.NotificationViewHeightRepository
import com.android.systemui.statusbar.notification.stack.shared.model.AccessibilityScrollEvent
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimRounding
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrollState
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** An interactor which controls the appearance of the NSSL */
@SysUISingleton
class NotificationStackAppearanceInteractor
@Inject
constructor(
    private val viewHeightRepository: NotificationViewHeightRepository,
    private val placeholderRepository: NotificationPlaceholderRepository,
    sceneInteractor: SceneInteractor,
    shadeInteractor: ShadeInteractor,
) {
    /** The bounds of the notification stack in the current scene. */
    val shadeScrimBounds: StateFlow<ShadeScrimBounds?> =
        placeholderRepository.shadeScrimBounds.asStateFlow()

    /**
     * Whether the stack is expanding from GONE-with-HUN to SHADE
     *
     * TODO(b/296118689): implement this to match legacy QSController logic
     */
    private val isExpandingFromHeadsUp: Flow<Boolean> = flowOf(false)

    /** The rounding of the notification stack. */
    val shadeScrimRounding: Flow<ShadeScrimRounding> =
        combine(shadeInteractor.shadeMode, isExpandingFromHeadsUp) {
                shadeMode,
                isExpandingFromHeadsUp ->
                ShadeScrimRounding(
                    isTopRounded = !(shadeMode == ShadeMode.Split && isExpandingFromHeadsUp),
                    isBottomRounded = shadeMode != ShadeMode.Single,
                )
            }
            .distinctUntilChanged()

    /** The alpha of the Notification Stack for the brightness mirror */
    val alphaForBrightnessMirror: StateFlow<Float> =
        placeholderRepository.alphaForBrightnessMirror.asStateFlow()

    /** The alpha of the Notification Stack for lockscreen fade-in */
    val alphaForLockscreenFadeIn: StateFlow<Float> =
        placeholderRepository.alphaForLockscreenFadeIn.asStateFlow()

    /** The height of the keyguard's available space bounds */
    val constrainedAvailableSpace: StateFlow<Int> =
        placeholderRepository.constrainedAvailableSpace.asStateFlow()

    /** Scroll state of the notification shade. */
    val shadeScrollState: StateFlow<ShadeScrollState> =
        placeholderRepository.shadeScrollState.asStateFlow()

    /**
     * The amount in px that the notification stack should scroll due to internal expansion. This
     * should only happen when a notification expansion hits the bottom of the screen, so it is
     * necessary to scroll up to keep expanding the notification.
     */
    val syntheticScroll: Flow<Float> = viewHeightRepository.syntheticScroll.asStateFlow()

    /**
     * Whether the current touch gesture is overscroll. If true, it means the NSSL has already
     * consumed part of the gesture.
     */
    val isCurrentGestureOverscroll: Flow<Boolean> =
        viewHeightRepository.isCurrentGestureOverscroll.asStateFlow()

    /** Whether we should close any notification guts that are currently open. */
    val shouldCloseGuts: Flow<Boolean> =
        combine(
            sceneInteractor.isSceneContainerUserInputOngoing,
            viewHeightRepository.isCurrentGestureInGuts,
        ) { isUserInputOngoing, isCurrentGestureInGuts ->
            isUserInputOngoing && !isCurrentGestureInGuts
        }

    /** Sets the alpha to apply to the NSSL for the brightness mirror */
    fun setAlphaForBrightnessMirror(alpha: Float) {
        placeholderRepository.alphaForBrightnessMirror.value = alpha
    }

    /** Sets the alpha to apply to the NSSL for fade-in on lockscreen */
    fun setAlphaForLockscreenFadeIn(alpha: Float) {
        placeholderRepository.alphaForLockscreenFadeIn.value = alpha
    }

    /** Sets the position of the notification stack in the current scene. */
    fun setShadeScrimBounds(bounds: ShadeScrimBounds?) {
        check(bounds == null || bounds.top <= bounds.bottom) { "Invalid bounds: $bounds" }
        placeholderRepository.shadeScrimBounds.value = bounds
    }

    /** Updates the current scroll state of the notification shade. */
    fun setScrollState(shadeScrollState: ShadeScrollState) {
        placeholderRepository.shadeScrollState.value = shadeScrollState
    }

    /** Sets the amount (px) that the notification stack should scroll due to internal expansion. */
    fun setSyntheticScroll(delta: Float) {
        viewHeightRepository.syntheticScroll.value = delta
    }

    /** Sends an [AccessibilityScrollEvent] to scroll the stack up or down. */
    fun sendAccessibilityScrollEvent(accessibilityScrollEvent: AccessibilityScrollEvent) {
        placeholderRepository.accessibilityScrollEventConsumer?.accept(accessibilityScrollEvent)
    }

    /** Set a consumer for the [AccessibilityScrollEvent]s to be handled by the placeholder. */
    fun setAccessibilityScrollEventConsumer(consumer: Consumer<AccessibilityScrollEvent>?) {
        placeholderRepository.accessibilityScrollEventConsumer = consumer
    }

    /** Sets whether the current touch gesture is overscroll. */
    fun setCurrentGestureOverscroll(isOverscroll: Boolean) {
        viewHeightRepository.isCurrentGestureOverscroll.value = isOverscroll
    }

    fun setCurrentGestureInGuts(isInGuts: Boolean) {
        viewHeightRepository.isCurrentGestureInGuts.value = isInGuts
    }

    fun setConstrainedAvailableSpace(height: Int) {
        placeholderRepository.constrainedAvailableSpace.value = height
    }
}
