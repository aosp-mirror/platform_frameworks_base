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
import com.android.systemui.animation.Interpolators.LINEAR
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * For the given transition params, construct a flow using [createFlow] for the specified portion of
 * the overall transition.
 */
class KeyguardTransitionAnimationFlow(
    private val transitionDuration: Duration,
    private val transitionFlow: Flow<TransitionStep>,
) {
    /**
     * Transitions will occur over a [transitionDuration] with [TransitionStep]s being emitted in
     * the range of [0, 1]. View animations should begin and end within a subset of this range. This
     * function maps the [startTime] and [duration] into [0, 1], when this subset is valid.
     */
    fun createFlow(
        duration: Duration,
        onStep: (Float) -> Float,
        startTime: Duration = 0.milliseconds,
        onStart: (() -> Unit)? = null,
        onCancel: (() -> Float)? = null,
        onFinish: (() -> Float)? = null,
        interpolator: Interpolator = LINEAR,
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

        return transitionFlow
            .map { step ->
                when (step.transitionState) {
                    STARTED -> stepToValue(step)
                    RUNNING -> stepToValue(step)
                    CANCELED -> onCancel?.invoke()
                    FINISHED -> onFinish?.invoke()
                }
            }
            .filterNotNull()
    }
}
