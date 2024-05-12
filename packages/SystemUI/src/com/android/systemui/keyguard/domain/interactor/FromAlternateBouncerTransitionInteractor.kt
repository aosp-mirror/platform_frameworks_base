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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import com.android.wm.shell.animation.Interpolators
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
@SysUISingleton
class FromAlternateBouncerTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    private val communalInteractor: CommunalInteractor,
    powerInteractor: PowerInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.ALTERNATE_BOUNCER,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
    ) {

    override fun start() {
        listenForAlternateBouncerToGone()
        listenForAlternateBouncerToLockscreenHubAodOrDozing()
        listenForAlternateBouncerToPrimaryBouncer()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    val surfaceBehindVisibility: Flow<Boolean?> =
        combine(
                transitionInteractor.startedKeyguardTransitionStep,
                transitionInteractor.transitionStepsFromState(KeyguardState.ALTERNATE_BOUNCER)
            ) { startedStep, fromBouncerStep ->
                if (startedStep.to != KeyguardState.GONE) {
                    return@combine null
                }

                // The alt bouncer is pretty fast to hide, so start the surface behind animation
                // around 30%.
                fromBouncerStep.value > 0.3f
            }
            .onStart {
                // Default to null ("don't care, use a reasonable default").
                emit(null)
            }
            .distinctUntilChanged()

    private fun listenForAlternateBouncerToLockscreenHubAodOrDozing() {
        scope.launch {
            keyguardInteractor.alternateBouncerShowing
                // Add a slight delay, as alternateBouncer and primaryBouncer showing event changes
                // will arrive with a small gap in time. This prevents a transition to LOCKSCREEN
                // happening prematurely.
                // This should eventually be removed in favor of
                // [KeyguardTransitionInteractor#startDismissKeyguardTransition]
                .onEach { delay(150L) }
                .sampleCombine(
                    keyguardInteractor.primaryBouncerShowing,
                    powerInteractor.isAwake,
                    keyguardInteractor.isAodAvailable,
                    communalInteractor.isIdleOnCommunal,
                    keyguardInteractor.isKeyguardOccluded,
                )
                .filterRelevantKeyguardStateAnd {
                    (isAlternateBouncerShowing, isPrimaryBouncerShowing, _, _, _) ->
                    !isAlternateBouncerShowing && !isPrimaryBouncerShowing
                }
                .collect { (_, _, isAwake, isAodAvailable, isIdleOnCommunal, isOccluded) ->
                    val to =
                        if (!isAwake) {
                            if (isAodAvailable) {
                                KeyguardState.AOD
                            } else {
                                KeyguardState.DOZING
                            }
                        } else {
                            if (isIdleOnCommunal) {
                                KeyguardState.GLANCEABLE_HUB
                            } else if (isOccluded) {
                                KeyguardState.OCCLUDED
                            } else {
                                KeyguardState.LOCKSCREEN
                            }
                        }
                    startTransitionTo(to)
                }
        }
    }

    private fun listenForAlternateBouncerToGone() {
        // TODO(b/336576536): Check if adaptation for scene framework is needed
        if (SceneContainerFlag.isEnabled) return
        if (KeyguardWmStateRefactor.isEnabled) {
            // Handled via #dismissAlternateBouncer.
            return
        }

        scope.launch {
            merge(
                    keyguardInteractor.isKeyguardGoingAway.filter { it }.map {}, // map to Unit
                    keyguardInteractor.isKeyguardOccluded.flatMapLatest { keyguardOccluded ->
                        if (keyguardOccluded) {
                            primaryBouncerInteractor.keyguardAuthenticatedBiometricsHandled
                        } else {
                            emptyFlow()
                        }
                    }
                )
                .filterRelevantKeyguardState()
                .collect { startTransitionTo(KeyguardState.GONE) }
        }
    }

    private fun listenForAlternateBouncerToPrimaryBouncer() {
        // TODO(b/336576536): Check if adaptation for scene framework is needed
        if (SceneContainerFlag.isEnabled) return
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .filterRelevantKeyguardStateAnd { isPrimaryBouncerShowing ->
                    isPrimaryBouncerShowing
                }
                .collect { startTransitionTo(KeyguardState.PRIMARY_BOUNCER) }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.GONE -> TO_GONE_DURATION
                    else -> TRANSITION_DURATION_MS
                }.inWholeMilliseconds
        }
    }

    fun dismissAlternateBouncer() {
        scope.launch { startTransitionTo(KeyguardState.GONE) }
    }

    companion object {
        const val TAG = "FromAlternateBouncerTransitionInteractor"
        val TRANSITION_DURATION_MS = 300.milliseconds
        val TO_GONE_DURATION = 500.milliseconds
        val TO_AOD_DURATION = TRANSITION_DURATION_MS
        val TO_PRIMARY_BOUNCER_DURATION = TRANSITION_DURATION_MS
        val TO_DOZING_DURATION = TRANSITION_DURATION_MS
        val TO_OCCLUDED_DURATION = TRANSITION_DURATION_MS
    }
}
