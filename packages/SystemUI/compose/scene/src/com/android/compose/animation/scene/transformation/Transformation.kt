/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.animation.scene.transformation

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.ElementStateScope
import com.android.compose.animation.scene.Scale
import com.android.compose.animation.scene.content.state.TransitionState
import kotlinx.coroutines.CoroutineScope

/** A transformation applied to one or more elements during a transition. */
sealed interface Transformation {
    fun interface Factory {
        fun create(): Transformation
    }
}

// Important: SharedElementTransformation must be a data class because we check that we don't
// provide 2 different transformations for the same element in Element.kt
internal data class SharedElementTransformation(
    internal val enabled: Boolean,
    internal val elevateInContent: ContentKey?,
) : Transformation {
    class Factory(
        internal val matcher: ElementMatcher,
        internal val enabled: Boolean,
        internal val elevateInContent: ContentKey?,
    ) : Transformation.Factory {
        override fun create(): Transformation {
            return SharedElementTransformation(enabled, elevateInContent)
        }
    }
}

/**
 * A transformation that changes the value of an element [Property], like its [size][Property.Size]
 * or [offset][Property.Offset].
 */
sealed interface PropertyTransformation<T> : Transformation {
    /** The property to which this transformation is applied. */
    val property: Property<T>

    sealed class Property<T> {
        /** The size of an element. */
        data object Size : Property<IntSize>()

        /** The offset (position) of an element. */
        data object Offset : Property<androidx.compose.ui.geometry.Offset>()

        /** The alpha of an element. */
        data object Alpha : Property<Float>()

        /**
         * The drawing scale of an element. Animating the scale does not have any effect on the
         * layout.
         */
        data object Scale : Property<com.android.compose.animation.scene.Scale>()
    }
}

/**
 * A transformation to a target/transformed value that is automatically interpolated using the
 * transition progress and transformation range.
 */
interface InterpolatedPropertyTransformation<T> : PropertyTransformation<T> {
    /**
     * Return the transformed value for the given property, i.e.:
     * - the value at progress = 0% for elements that are entering the layout (i.e. elements in the
     *   content we are transitioning to).
     * - the value at progress = 100% for elements that are leaving the layout (i.e. elements in the
     *   content we are transitioning from).
     *
     * The returned value will be automatically interpolated using the [transition] progress, the
     * transformation range and [idleValue], the value of the property when we are idle.
     */
    fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        idleValue: T,
    ): T
}

interface CustomPropertyTransformation<T> : PropertyTransformation<T> {
    /**
     * Return the value that the property should have in the current frame for the given [content]
     * and [element].
     *
     * This transformation can use [transitionScope] to launch animations associated to
     * [transition], which will not finish until at least one animation/job is still running in the
     * scope.
     *
     * Important: Make sure to never launch long-running jobs in [transitionScope], otherwise
     * [transition] will never be considered as finished.
     */
    fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        transitionScope: CoroutineScope,
    ): T
}

interface PropertyTransformationScope : Density, ElementStateScope {
    /** The current [direction][LayoutDirection] of the layout. */
    val layoutDirection: LayoutDirection
}

/** Defines the transformation-type to be applied to all elements matching [matcher]. */
internal class TransformationMatcher(
    val matcher: ElementMatcher,
    val factory: Transformation.Factory,
    val range: TransformationRange?,
)

/** A pair consisting of a [transformation] and optional [range]. */
internal data class TransformationWithRange<out T : Transformation>(
    val transformation: T,
    val range: TransformationRange?,
) {
    fun reversed(): TransformationWithRange<T> {
        if (range == null) return this

        return TransformationWithRange(transformation = transformation, range = range.reversed())
    }
}

/** The progress-based range of a [PropertyTransformation]. */
internal data class TransformationRange(val start: Float, val end: Float, val easing: Easing) {
    constructor(
        start: Float? = null,
        end: Float? = null,
        easing: Easing = LinearEasing,
    ) : this(start ?: BoundUnspecified, end ?: BoundUnspecified, easing)

    init {
        require(!start.isSpecified() || (start in 0f..1f))
        require(!end.isSpecified() || (end in 0f..1f))
        require(!start.isSpecified() || !end.isSpecified() || start <= end)
    }

    /** Reverse this range. */
    internal fun reversed() =
        TransformationRange(start = reverseBound(end), end = reverseBound(start), easing = easing)

    /** Get the progress of this range given the global [transitionProgress]. */
    fun progress(transitionProgress: Float): Float {
        val progress =
            when {
                start.isSpecified() && end.isSpecified() ->
                    ((transitionProgress - start) / (end - start)).fastCoerceIn(0f, 1f)
                !start.isSpecified() && !end.isSpecified() -> transitionProgress
                end.isSpecified() -> (transitionProgress / end).fastCoerceAtMost(1f)
                else -> ((transitionProgress - start) / (1f - start)).fastCoerceAtLeast(0f)
            }
        return easing.transform(progress)
    }

    private fun Float.isSpecified() = this != BoundUnspecified

    private fun reverseBound(bound: Float): Float {
        return if (bound.isSpecified()) {
            1f - bound
        } else {
            BoundUnspecified
        }
    }

    companion object {
        internal const val BoundUnspecified = Float.MIN_VALUE
    }
}
