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
 */
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.util.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * A state comprised of a [value] of type [T] paired with a boolean indicating whether or not the
 * [value] [isAnimating] in the UI.
 */
data class AnimatedValue<T>(
    val value: T,
    val isAnimating: Boolean,
)

/**
 * An event comprised of a [value] of type [T] paired with a [boolean][startAnimating] indicating
 * whether or not this event should start an animation.
 */
data class AnimatableEvent<T>(
    val value: T,
    val startAnimating: Boolean,
)

/**
 * Returns a [Flow] that tracks an [AnimatedValue] state. The input [Flow] is used to update the
 * [AnimatedValue.value], as well as [AnimatedValue.isAnimating] if the event's
 * [AnimatableEvent.startAnimating] value is `true`. When [completionEvents] emits a value, the
 * [AnimatedValue.isAnimating] will flip to `false`.
 */
fun <T> Flow<AnimatableEvent<T>>.toAnimatedValueFlow(
    completionEvents: Flow<Any?>,
): Flow<AnimatedValue<T>> = transformLatest { (value, startAnimating) ->
    emit(AnimatedValue(value, isAnimating = startAnimating))
    if (startAnimating) {
        // Wait for a completion now that we've started animating
        completionEvents
            .map { Unit } // replace the event so that it's never `null`
            .firstOrNull() // `null` indicates an empty flow
            // emit the new state if the flow was not empty.
            ?.run { emit(AnimatedValue(value, isAnimating = false)) }
    }
}
