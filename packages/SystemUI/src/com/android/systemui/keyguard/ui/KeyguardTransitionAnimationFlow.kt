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
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Assists in creating sub-flows for a KeyguardTransition. Call [setup] once for a transition, and
 * then [sharedFlow] for each sub animation that should be trigged when the overall transition runs.
 */
@SysUISingleton
class KeyguardTransitionAnimationFlow
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val logger: KeyguardTransitionAnimationLogger,
) {

    /**
     * Invoke once per transition between FROM->TO states to get access to
     * [SharedFlowBuilder#sharedFlow].
     */
    fun setup(
        duration: Duration,
        stepFlow: Flow<TransitionStep>,
    ) = SharedFlowBuilder(duration, stepFlow)

    inner class SharedFlowBuilder(
        private val transitionDuration: Duration,
        private val stepFlow: Flow<TransitionStep>,
    ) {
        /**
         * Transitions will occur over a [transitionDuration] with [TransitionStep]s being emitted
         * in the range of [0, 1]. View animations should begin and end within a subset of this
         * range. This function maps the [startTime] and [duration] into [0, 1], when this subset is
         * valid.
         */
        fun sharedFlow(
            duration: Duration,
            onStep: (Float) -> Float,
            startTime: Duration = 0.milliseconds,
            onStart: (() -> Unit)? = null,
            onCancel: (() -> Float)? = null,
            onFinish: (() -> Float)? = null,
            interpolator: Interpolator = LINEAR,
            name: String? = null
        ): Flow<Float> {
            if (!duration.isPositive()) {
                throw IllegalArgumentException("duration must be a positive number: $duration")
            }
            if ((startTime + duration).compareTo(transitionDuration) > 0) {
                throw IllegalArgumentException(
                    "startTime($startTime) + duration($duration) must be" +
                        " <= transitionDuration($transitionDuration)"
                )
            }

            val start = (startTime / transitionDuration).toFloat()
            val chunks = (transitionDuration / duration).toFloat()
            logger.logCreate(name, start)
            var isComplete = true

            fun stepToValue(step: TransitionStep): Float? {
                val value = (step.value - start) * chunks
                return when (step.transitionState) {
                    // When starting, make sure to always emit. If a transition is started from the
                    // middle, it is possible this animation is being skipped but we need to inform
                    // the ViewModels of the last update
                    STARTED -> {
                        isComplete = false
                        onStart?.invoke()
                        max(0f, min(1f, value))
                    }
                    // Always send a final value of 1. Because of rounding, [value] may never be
                    // exactly 1.
                    RUNNING ->
                        if (isComplete) {
                            null
                        } else if (value >= 1f) {
                            isComplete = true
                            1f
                        } else if (value >= 0f) {
                            value
                        } else {
                            null
                        }
                    else -> null
                }?.let { onStep(interpolator.getInterpolation(it)) }
            }

            return stepFlow
                .map { step ->
                    val value =
                        when (step.transitionState) {
                            STARTED -> stepToValue(step)
                            RUNNING -> stepToValue(step)
                            CANCELED -> onCancel?.invoke()
                            FINISHED -> onFinish?.invoke()
                        }
                    logger.logTransitionStep(name, step, value)
                    value
                }
                .filterNotNull()
        }

        /**
         * Immediately (after 1ms) emits the given value for every step of the KeyguardTransition.
         */
        fun immediatelyTransitionTo(value: Float): Flow<Float> {
            return sharedFlow(duration = 1.milliseconds, onStep = { value }, onFinish = { value })
        }
    }
}
