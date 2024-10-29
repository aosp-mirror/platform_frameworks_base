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

package com.android.systemui.keyguard.domain.interactor

import com.android.keyguard.logging.ScrimLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.DEFAULT_REVEAL_DURATION
import com.android.systemui.keyguard.data.repository.LightRevealScrimRepository
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.android.app.tracing.coroutines.launchTraced as launch

@SysUISingleton
class LightRevealScrimInteractor
@Inject
constructor(
    private val transitionInteractor: KeyguardTransitionInteractor,
    private val repository: LightRevealScrimRepository,
    @Application private val scope: CoroutineScope,
    private val scrimLogger: ScrimLogger,
    private val powerInteractor: Lazy<PowerInteractor>,
) {
    init {
        listenForStartedKeyguardTransitionStep()
    }

    private fun listenForStartedKeyguardTransitionStep() {
        scope.launch {
            transitionInteractor.startedKeyguardTransitionStep.collect {
                scrimLogger.d(TAG, "listenForStartedKeyguardTransitionStep", it)
                val animationDuration =
                    if (it.to == KeyguardState.AOD && isLastSleepDueToFold) {
                        // Do not animate the scrim when folding as we want to cover the screen
                        // with the scrim immediately while displays are switching.
                        // This is needed to play the fold to AOD animation which starts with
                        // fully black screen (see FoldAodAnimationController)
                        0L
                    } else {
                        DEFAULT_REVEAL_DURATION
                    }

                repository.startRevealAmountAnimator(
                    willBeRevealedInState(it.to),
                    duration = animationDuration,
                )
            }
        }
    }

    private val isLastSleepDueToFold: Boolean
        get() =
            powerInteractor.get().detailedWakefulness.value.lastSleepReason == WakeSleepReason.FOLD

    /**
     * Whenever a keyguard transition starts, sample the latest reveal effect from the repository
     * and use that for the starting transition.
     *
     * We can't simply use the nextRevealEffect since the effect may change midway through a
     * transition, but we don't want to change effects part way through. For example, if we're using
     * a CircleReveal to animate a biometric unlock, but the biometric unlock mode changes to NONE
     * from WAKE_AND_UNLOCK before the unlock animation ends, we don't want to end up switching to a
     * LiftReveal.
     */
    val lightRevealEffect: Flow<LightRevealEffect> =
        transitionInteractor.startedKeyguardTransitionStep.sample(repository.revealEffect)

    /** Limit the max alpha for the scrim to allow for some transparency */
    val maxAlpha: Flow<Float> =
        anyOf(
                transitionInteractor.isInTransition(
                    edge = Edge.create(Scenes.Gone, KeyguardState.AOD),
                    edgeWithoutSceneContainer = Edge.create(KeyguardState.GONE, KeyguardState.AOD),
                ),
                transitionInteractor.isInTransition(
                    Edge.create(KeyguardState.OCCLUDED, KeyguardState.AOD)
                ),
            )
            .flatMapLatest { isInTransition ->
                // During transitions like GONE->AOD, surfaces like the launcher may be visible
                // until WM is told to hide them, which occurs at the end of the animation. Use an
                // opaque scrim until this transition is complete.
                if (isInTransition) {
                    flowOf(1f)
                } else {
                    repository.maxAlpha
                }
            }

    val revealAmount =
        repository.revealAmount.filter {
            // When the screen is off we do not want to keep producing frames as this is causing
            // (invisible) jank. However, we need to still pass through 1f and 0f to ensure that the
            // correct end states are respected even if the screen turned off (or was still off)
            // when the animation finished
            screenIsShowingContent() || it == 1f || it == 0f
        }

    private fun screenIsShowingContent() =
        powerInteractor.get().screenPowerState.value != ScreenPowerState.SCREEN_OFF &&
            powerInteractor.get().screenPowerState.value != ScreenPowerState.SCREEN_TURNING_ON

    val isAnimating: Boolean
        get() = repository.isAnimating

    /** If the wallpaper supports ambient mode, allow partial transparency */
    fun setWallpaperSupportsAmbientMode(supportsAmbientMode: Boolean) {
        repository.maxAlpha.value =
            if (supportsAmbientMode) {
                0.7f
            } else {
                1f
            }
    }

    /**
     * Whether the light reveal scrim will be fully revealed (revealAmount = 1.0f) in the given
     * state after the transition is complete. If false, scrim will be fully hidden.
     */
    private fun willBeRevealedInState(state: KeyguardState): Boolean {
        return when (state) {
            KeyguardState.OFF -> false
            KeyguardState.DOZING -> false
            KeyguardState.AOD -> false
            KeyguardState.DREAMING -> true
            KeyguardState.GLANCEABLE_HUB -> true
            KeyguardState.ALTERNATE_BOUNCER -> true
            KeyguardState.PRIMARY_BOUNCER -> true
            KeyguardState.LOCKSCREEN -> true
            KeyguardState.GONE -> true
            KeyguardState.OCCLUDED -> true
            KeyguardState.UNDEFINED -> true
        }
    }

    companion object {
        val TAG = LightRevealScrimInteractor::class.simpleName!!
    }
}
