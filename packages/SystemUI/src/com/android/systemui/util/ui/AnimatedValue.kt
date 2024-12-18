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

import com.android.systemui.util.ui.AnimatedValue.Animating
import com.android.systemui.util.ui.AnimatedValue.NotAnimating
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * A state comprised of a [value] of type [T] paired with a boolean indicating whether or not the
 * value [isAnimating][isAnimating] in the UI.
 */
sealed interface AnimatedValue<out T> {

    /** A [state][value] that is not actively animating in the UI. */
    data class NotAnimating<out T>(val value: T) : AnimatedValue<T>

    /**
     * A [state][value] that is actively animating in the UI. Invoking [onStopAnimating] will signal
     * the source of the state to stop animating.
     */
    data class Animating<out T>(
        val value: T,
        val onStopAnimating: () -> Unit,
    ) : AnimatedValue<T>
}

/** The state held in this [AnimatedValue]. */
inline val <T> AnimatedValue<T>.value: T
    get() =
        when (this) {
            is Animating -> value
            is NotAnimating -> value
        }

/** Returns whether or not this [AnimatedValue] is animating or not. */
inline val <T> AnimatedValue<T>.isAnimating: Boolean
    get() = this is Animating<T>

/**
 * If this [AnimatedValue] [isAnimating], then signal that the animation should be stopped.
 * Otherwise, do nothing.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun AnimatedValue<*>.stopAnimating() {
    if (this is Animating) onStopAnimating()
}

/**
 * An event comprised of a [value] of type [T] paired with a [boolean][startAnimating] indicating
 * whether or not this event should start an animation.
 */
data class AnimatableEvent<out T>(
    val value: T,
    val startAnimating: Boolean,
)

/**
 * Returns a [Flow] that tracks an [AnimatedValue] state. The input [Flow] is used to update the
 * [AnimatedValue.value], as well as [AnimatedValue.isAnimating] if the event's
 * [AnimatableEvent.startAnimating] value is `true`. When [completionEvents] emits a value, the
 * [AnimatedValue.isAnimating] will flip to `false`.
 */
fun <T> Flow<AnimatableEvent<T>>.toAnimatedValueFlow(): Flow<AnimatedValue<T>> =
    transformLatest { (value, startAnimating) ->
        if (startAnimating) {
            val onCompleted = CompletableDeferred<Unit>()
            emit(Animating(value) { onCompleted.complete(Unit) })
            // Wait for a completion now that we've started animating
            onCompleted.await()
        }
        emit(NotAnimating(value))
    }

/**
 * Zip two [AnimatedValue]s together into a single [AnimatedValue], using [block] to combine the
 * [value]s of each.
 *
 * If either [AnimatedValue] [isAnimating], then the result is also animating. Invoking
 * [stopAnimating] on the result is equivalent to invoking [stopAnimating] on each input.
 */
inline fun <A, B, Z> zip(
    valueA: AnimatedValue<A>,
    valueB: AnimatedValue<B>,
    block: (A, B) -> Z,
): AnimatedValue<Z> {
    val zippedValue = block(valueA.value, valueB.value)
    return when (valueA) {
        is Animating ->
            when (valueB) {
                is Animating ->
                    Animating(zippedValue) {
                        valueA.onStopAnimating()
                        valueB.onStopAnimating()
                    }
                is NotAnimating -> Animating(zippedValue, valueA.onStopAnimating)
            }
        is NotAnimating ->
            when (valueB) {
                is Animating -> Animating(zippedValue, valueB.onStopAnimating)
                is NotAnimating -> NotAnimating(zippedValue)
            }
    }
}

/**
 * Flattens a nested [AnimatedValue], the result of which holds the [value] of the inner
 * [AnimatedValue].
 *
 * If either the outer or inner [AnimatedValue] [isAnimating], then the flattened result is also
 * animating. Invoking [stopAnimating] on the result is equivalent to invoking [stopAnimating] on
 * both the outer and inner values.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun <T> AnimatedValue<AnimatedValue<T>>.flatten(): AnimatedValue<T> = flatMap { it }

/**
 * Returns an [AnimatedValue], the [value] of which is the result of the given [value] applied to
 * [block].
 */
inline fun <A, B> AnimatedValue<A>.map(block: (A) -> B): AnimatedValue<B> =
    when (this) {
        is Animating -> Animating(block(value), ::stopAnimating)
        is NotAnimating -> NotAnimating(block(value))
    }

/**
 * Returns an [AnimatedValue] from the result of [block] being invoked on the [value] original
 * [AnimatedValue].
 *
 * If either the input [AnimatedValue] or the result of [block] [isAnimating], then the flattened
 * result is also animating. Invoking [stopAnimating] on the result is equivalent to invoking
 * [stopAnimating] on both values.
 */
inline fun <A, B> AnimatedValue<A>.flatMap(block: (A) -> AnimatedValue<B>): AnimatedValue<B> =
    when (this) {
        is NotAnimating -> block(value)
        is Animating ->
            when (val inner = block(value)) {
                is Animating ->
                    Animating(inner.value) {
                        onStopAnimating()
                        inner.onStopAnimating()
                    }
                is NotAnimating -> Animating(inner.value, onStopAnimating)
            }
    }
