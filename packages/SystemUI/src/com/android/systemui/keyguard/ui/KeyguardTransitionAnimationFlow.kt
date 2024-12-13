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
package com.android.systemui.keyguard.ui

import android.view.animation.Interpolator
import com.android.app.animation.Interpolators.LINEAR
import com.android.keyguard.logging.KeyguardTransitionAnimationLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

/**
 * Assists in creating sub-flows for a KeyguardTransition. Call [setup] once for a transition, and
 * then [sharedFlow] for each sub animation that should be trigged when the overall transition runs.
 */
@SysUISingleton
class KeyguardTransitionAnimationFlow
@Inject
constructor(
    private val transitionInteractor: KeyguardTransitionInteractor,
    private val logger: KeyguardTransitionAnimationLogger,
) {
    /** Invoke once per transition between FROM->TO states to get access to a shared flow. */
    fun setup(duration: Duration, edge: Edge): FlowBuilder {
        return FlowBuilder(duration, edge)
    }

    inner class FlowBuilder(private val transitionDuration: Duration, private val edge: Edge) {
        fun setupWithoutSceneContainer(edge: Edge.StateToState): FlowBuilder {
            if (SceneContainerFlag.isEnabled) return this
            return setup(this.transitionDuration, edge)
        }

        /**
         * Transitions will occur over a [transitionDuration] with [TransitionStep]s being emitted
         * in the range of [0, 1]. View animations should begin and end within a subset of this
         * range. This function maps the [startTime] and [duration] into [0, 1], when this subset is
         * valid.
         *
         * Note that [onCancel] isn't used when the scene framework is enabled.
         */
        fun sharedFlow(
            duration: Duration,
            onStep: (Float) -> Float,
            startTime: Duration = 0.milliseconds,
            onStart: (() -> Unit)? = null,
            onCancel: (() -> Float)? = null,
            onFinish: (() -> Float)? = null,
            interpolator: Interpolator = LINEAR,
            name: String? = null,
        ): Flow<Float> {
            return sharedFlowWithState(
                    duration = duration,
                    onStep = onStep,
                    startTime = startTime,
                    onStart = onStart,
                    onCancel = onCancel,
                    onFinish = onFinish,
                    interpolator = interpolator,
                    name = name,
                )
                .mapNotNull { stateToValue -> stateToValue.value }
        }

        /**
         * Transitions will occur over a [transitionDuration] with [TransitionStep]s being emitted
         * in the range of [0, 1]. View animations should begin and end within a subset of this
         * range. This function maps the [startTime] and [duration] into [0, 1], when this subset is
         * valid.
         *
         * Will return a [StateToValue], which encompasses the calculated value as well as the
         * transitionState that is associated with it.
         */
        fun sharedFlowWithState(
            duration: Duration,
            onStep: (Float) -> Float,
            startTime: Duration = 0.milliseconds,
            onStart: (() -> Unit)? = null,
            onCancel: (() -> Float)? = null,
            onFinish: (() -> Float)? = null,
            interpolator: Interpolator = LINEAR,
            name: String? = null,
        ): Flow<StateToValue> {
            if (!duration.isPositive()) {
                throw IllegalArgumentException("duration must be a positive number: $duration")
            }
            if ((startTime + duration) > transitionDuration) {
                throw IllegalArgumentException(
                    "startTime($startTime) + duration($duration) must be" +
                        " <= transitionDuration($transitionDuration)"
                )
            }

            val start = (startTime / transitionDuration).toFloat()
            val chunks = (transitionDuration / duration).toFloat()
            logger.logCreate(name, start)

            fun stepToValue(step: TransitionStep): Float? {
                val value = (step.value - start) * chunks
                return when (step.transitionState) {
                    // When starting, make sure to always emit. If a transition is started from the
                    // middle, it is possible this animation is being skipped but we need to inform
                    // the ViewModels of the last update
                    STARTED -> {
                        onStart?.invoke()
                        max(0f, min(1f, value))
                    }
                    // Always send a final value of 1. Because of rounding, [value] may never be
                    // exactly 1.
                    RUNNING ->
                        if (value >= 1f) {
                            1f
                        } else if (value >= 0f) {
                            value
                        } else {
                            null
                        }
                    else -> null
                }?.let { onStep(interpolator.getInterpolation(it)) }
            }

            return transitionInteractor
                .transition(edge)
                .mapNotNull { step ->
                    if (SceneContainerFlag.isEnabled && step.transitionState == CANCELED) {
                        // When the scene framework is enabled, there's no need to emit an alpha
                        // value when the keyguard transition animation is canceled because there's
                        // always going to be a new, reversed keyguard transition animation back to
                        // the original KeyguardState that starts right when this one was canceled.
                        //
                        // For example, if swiping up slightly on the Lockscreen scene and then
                        // releasing before the transition to the Bouncer scene is committed, the
                        // KTF transition of LOCKSCREEN -> PRIMARY_BOUNCER received a CANCELED and
                        // the scene framework immediately starts a reversed transition of
                        // PRIMARY_BOUNCER -> LOCKSCREEN, which picks up where the previous one left
                        // off.
                        //
                        // If it were allowed for the CANCELED from the original KTF transition to
                        // emit a value, a race condition could form where the value from CANCELED
                        // arrives downstream _after_ the reversed transition is finished, causing
                        // the transition to end up in an incorrect state at rest.
                        null
                    } else {
                        StateToValue(
                                from = step.from,
                                to = step.to,
                                transitionState = step.transitionState,
                                value =
                                    when (step.transitionState) {
                                        STARTED -> stepToValue(step)
                                        RUNNING -> stepToValue(step)
                                        CANCELED -> onCancel?.invoke()
                                        FINISHED -> onFinish?.invoke()
                                    },
                            )
                            .also { logger.logTransitionStep(name, step, it.value) }
                    }
                }
                .distinctUntilChanged()
        }

        /**
         * Immediately (after 1ms) emits the given value for every step of the KeyguardTransition.
         */
        fun immediatelyTransitionTo(value: Float): Flow<Float> {
            return sharedFlow(
                duration = 1.milliseconds,
                onStep = { value },
                onCancel = { value },
                onFinish = { value },
            )
        }
    }
}

data class StateToValue(
    val from: KeyguardState? = null,
    val to: KeyguardState? = null,
    val transitionState: TransitionState = TransitionState.FINISHED,
    val value: Float? = 0f,
) {
    fun isToOrFrom(state: KeyguardState) = from == state || to == state
}
