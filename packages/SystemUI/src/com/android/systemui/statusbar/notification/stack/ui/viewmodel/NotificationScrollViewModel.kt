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

import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ObservableTransitionState.Idle
import com.android.compose.animation.scene.ObservableTransitionState.Transition
import com.android.compose.animation.scene.ObservableTransitionState.Transition.ChangeScene
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.domain.interactor.RemoteInputInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimClipping
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationTransitionThresholds.EXPANSION_FOR_DELAYED_STACK_FADE_IN
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationTransitionThresholds.EXPANSION_FOR_MAX_SCRIM_ALPHA
import com.android.systemui.util.kotlin.ActivatableFlowDumper
import com.android.systemui.util.kotlin.ActivatableFlowDumperImpl
import dagger.Lazy
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull

/** ViewModel which represents the state of the NSSL/Controller in the world of flexiglass */
class NotificationScrollViewModel
@AssistedInject
constructor(
    dumpManager: DumpManager,
    stackAppearanceInteractor: NotificationStackAppearanceInteractor,
    shadeInteractor: ShadeInteractor,
    private val remoteInputInteractor: RemoteInputInteractor,
    private val sceneInteractor: SceneInteractor,
    // TODO(b/336364825) Remove Lazy when SceneContainerFlag is released -
    // while the flag is off, creating this object too early results in a crash
    keyguardInteractor: Lazy<KeyguardInteractor>,
) :
    ActivatableFlowDumper by ActivatableFlowDumperImpl(dumpManager, "NotificationScrollViewModel"),
    ExclusiveActivatable() {

    override suspend fun onActivated(): Nothing {
        activateFlowDumper()
    }

    private fun expandedInScene(scene: SceneKey): Boolean {
        return when (scene) {
            Scenes.Lockscreen,
            Scenes.Shade,
            Scenes.QuickSettings -> true
            else -> false
        }
    }

    private fun fullyExpandedDuringSceneChange(change: ChangeScene): Boolean {
        // The lockscreen stack is visible during all transitions away from the lockscreen, so keep
        // the stack expanded until those transitions finish.
        return (expandedInScene(change.fromScene) && expandedInScene(change.toScene)) ||
            change.isBetween({ it == Scenes.Lockscreen }, { true })
    }

    private fun expandFractionDuringSceneChange(
        change: ChangeScene,
        shadeExpansion: Float,
        qsExpansion: Float,
    ): Float {
        return if (fullyExpandedDuringSceneChange(change)) {
            1f
        } else if (change.isBetween({ it == Scenes.Gone }, { it == Scenes.Shade })) {
            shadeExpansion
        } else if (change.isBetween({ it == Scenes.Gone }, { it == Scenes.QuickSettings })) {
            // during QS expansion, increase fraction at same rate as scrim alpha,
            // but start when scrim alpha is at EXPANSION_FOR_DELAYED_STACK_FADE_IN.
            (qsExpansion / EXPANSION_FOR_MAX_SCRIM_ALPHA - EXPANSION_FOR_DELAYED_STACK_FADE_IN)
                .coerceIn(0f, 1f)
        } else {
            // TODO(b/356596436): If notification shade overlay is open, we'll reach this point and
            //  the expansion fraction in that case should be `shadeExpansion`.
            0f
        }
    }

    private fun expandFractionDuringOverlayTransition(
        transition: Transition,
        currentScene: SceneKey,
        shadeExpansion: Float,
    ): Float {
        return if (currentScene == Scenes.Lockscreen) {
            1f
        } else if (transition.isTransitioningFromOrTo(Overlays.NotificationsShade)) {
            shadeExpansion
        } else {
            0f
        }
    }

    /**
     * The expansion fraction of the notification stack. It should go from 0 to 1 when transitioning
     * from Gone to Shade scenes, and remain at 1 when in Lockscreen or Shade scenes and while
     * transitioning from Shade to QuickSettings scenes.
     */
    val expandFraction: Flow<Float> =
        combine(
                shadeInteractor.shadeExpansion,
                shadeInteractor.shadeMode,
                shadeInteractor.qsExpansion,
                sceneInteractor.transitionState,
            ) { shadeExpansion, _, qsExpansion, transitionState ->
                when (transitionState) {
                    is Idle ->
                        if (
                            expandedInScene(transitionState.currentScene) ||
                                Overlays.NotificationsShade in transitionState.currentOverlays
                        ) {
                            1f
                        } else {
                            0f
                        }
                    is ChangeScene ->
                        expandFractionDuringSceneChange(
                            change = transitionState,
                            shadeExpansion = shadeExpansion,
                            qsExpansion = qsExpansion,
                        )
                    is Transition.ShowOrHideOverlay ->
                        expandFractionDuringOverlayTransition(
                            transition = transitionState,
                            currentScene = transitionState.currentScene,
                            shadeExpansion = shadeExpansion,
                        )
                    is Transition.ReplaceOverlay ->
                        expandFractionDuringOverlayTransition(
                            transition = transitionState,
                            currentScene = transitionState.currentScene,
                            shadeExpansion = shadeExpansion,
                        )
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("expandFraction")

    val qsExpandFraction: Flow<Float> =
        shadeInteractor.qsExpansion.dumpWhileCollecting("qsExpandFraction")

    /** Whether we should close any open notification guts. */
    val shouldCloseGuts: Flow<Boolean> = stackAppearanceInteractor.shouldCloseGuts

    val shouldResetStackTop: Flow<Boolean> =
        sceneInteractor.transitionState
            .mapNotNull { state -> state is Idle && state.currentScene == Scenes.Gone }
            .distinctUntilChanged()
            .dumpWhileCollecting("shouldResetStackTop")

    private operator fun SceneKey.contains(scene: SceneKey) =
        sceneInteractor.isSceneInFamily(scene, this)

    private val qsAllowsClipping: Flow<Boolean> =
        combine(shadeInteractor.shadeMode, shadeInteractor.qsExpansion) { shadeMode, qsExpansion ->
                qsExpansion < 0.5f || shadeMode != ShadeMode.Single
            }
            .distinctUntilChanged()

    /** The bounds of the notification stack in the current scene. */
    private val shadeScrimClipping: Flow<ShadeScrimClipping?> =
        combine(
                qsAllowsClipping,
                stackAppearanceInteractor.shadeScrimBounds,
                stackAppearanceInteractor.shadeScrimRounding,
            ) { qsAllowsClipping, bounds, rounding ->
                bounds?.takeIf { qsAllowsClipping }?.let { ShadeScrimClipping(it, rounding) }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("stackClipping")

    fun shadeScrimShape(
        cornerRadius: Flow<Int>,
        viewLeftOffset: Flow<Int>,
    ): Flow<ShadeScrimShape?> =
        combine(shadeScrimClipping, cornerRadius, viewLeftOffset) { clipping, radius, leftOffset ->
                if (clipping == null) return@combine null
                ShadeScrimShape(
                    bounds = clipping.bounds.minus(leftOffset = leftOffset),
                    topRadius = radius.takeIf { clipping.rounding.isTopRounded } ?: 0,
                    bottomRadius = radius.takeIf { clipping.rounding.isBottomRounded } ?: 0,
                )
            }
            .dumpWhileCollecting("shadeScrimShape")

    /**
     * Max alpha to apply directly to the view based on the compose placeholder.
     *
     * TODO(b/338590620): Migrate alphas from [SharedNotificationContainerViewModel] into this flow
     */
    val maxAlpha: Flow<Float> =
        stackAppearanceInteractor.alphaForBrightnessMirror.dumpValue("maxAlpha")

    /**
     * Whether the notification stack is scrolled to the top; i.e., it cannot be scrolled down any
     * further.
     */
    val scrolledToTop: Flow<Boolean> =
        stackAppearanceInteractor.scrolledToTop.dumpValue("scrolledToTop")

    /** Receives the amount (px) that the stack should scroll due to internal expansion. */
    val syntheticScrollConsumer: (Float) -> Unit = stackAppearanceInteractor::setSyntheticScroll

    /**
     * Receives whether the current touch gesture is overscroll as it has already been consumed by
     * the stack.
     */
    val currentGestureOverscrollConsumer: (Boolean) -> Unit =
        stackAppearanceInteractor::setCurrentGestureOverscroll

    /** Receives whether the current touch gesture is inside any open guts. */
    val currentGestureInGutsConsumer: (Boolean) -> Unit =
        stackAppearanceInteractor::setCurrentGestureInGuts

    /** Receives the bottom bound of the currently focused remote input notification row. */
    val remoteInputRowBottomBoundConsumer: (Float?) -> Unit =
        remoteInputInteractor::setRemoteInputRowBottomBound

    /** Whether the notification stack is scrollable or not. */
    val isScrollable: Flow<Boolean> =
        combine(sceneInteractor.currentScene, sceneInteractor.currentOverlays) {
                currentScene,
                currentOverlays ->
                currentScene.showsNotifications() || currentOverlays.any { it.showsNotifications() }
            }
            .dumpWhileCollecting("isScrollable")

    /** Whether the notification stack is displayed in doze mode. */
    val isDozing: Flow<Boolean> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            keyguardInteractor.get().isDozing.dumpWhileCollecting("isDozing")
        }
    }

    /** Whether the notification stack is displayed in pulsing mode. */
    val isPulsing: Flow<Boolean> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            keyguardInteractor.get().isPulsing.dumpWhileCollecting("isPulsing")
        }
    }

    val shouldAnimatePulse: StateFlow<Boolean> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            MutableStateFlow(false)
        } else {
            keyguardInteractor.get().isAodAvailable
        }
    }

    private fun ContentKey.showsNotifications(): Boolean {
        return when (this) {
            Overlays.NotificationsShade,
            Scenes.Lockscreen,
            Scenes.Shade -> true
            else -> false
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): NotificationScrollViewModel
    }
}

private fun ChangeScene.isBetween(a: (SceneKey) -> Boolean, b: (SceneKey) -> Boolean): Boolean =
    (a(fromScene) && b(toScene)) || (b(fromScene) && a(toScene))
