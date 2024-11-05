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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.ElementStateScope
import com.android.compose.animation.scene.content.state.TransitionState

/** A transformation applied to one or more elements during a transition. */
sealed interface Transformation {
    /**
     * The matcher that should match the element(s) to which this transformation should be applied.
     */
    val matcher: ElementMatcher

    /**
     * The range during which the transformation is applied. If it is `null`, then the
     * transformation will be applied throughout the whole scene transition.
     */
    // TODO(b/240432457): Move this back to PropertyTransformation.
    val range: TransformationRange?
        get() = null

    /*
     * Reverse this transformation. This is called when we use Transition(from = A, to = B) when
     * animating from B to A and there is no Transition(from = B, to = A) defined.
     */
    fun reversed(): Transformation = this
}

internal class SharedElementTransformation(
    override val matcher: ElementMatcher,
    internal val enabled: Boolean,
    internal val elevateInContent: ContentKey?,
) : Transformation

/** A transformation that changes the value of an element property, like its size or offset. */
interface PropertyTransformation<T> : Transformation {
    /**
     * Return the transformed value for the given property, i.e.:
     * - the value at progress = 0% for elements that are entering the layout (i.e. elements in the
     *   content we are transitioning to).
     * - the value at progress = 100% for elements that are leaving the layout (i.e. elements in the
     *   content we are transitioning from).
     *
     * The returned value will be interpolated using the [transition] progress and [value], the
     * value of the property when we are idle.
     */
    fun PropertyTransformationScope.transform(
        content: ContentKey,
        element: ElementKey,
        transition: TransitionState.Transition,
        value: T,
    ): T
}

interface PropertyTransformationScope : Density, ElementStateScope {
    /** The current [direction][LayoutDirection] of the layout. */
    val layoutDirection: LayoutDirection
}

/**
 * A [PropertyTransformation] associated to a range. This is a helper class so that normal
 * implementations of [PropertyTransformation] don't have to take care of reversing their range when
 * they are reversed.
 */
internal class RangedPropertyTransformation<T>(
    val delegate: PropertyTransformation<T>,
    override val range: TransformationRange,
) : PropertyTransformation<T> by delegate {
    override fun reversed(): Transformation {
        return RangedPropertyTransformation(
            delegate.reversed() as PropertyTransformation<T>,
            range.reversed(),
        )
    }
}

/** The progress-based range of a [PropertyTransformation]. */
data class TransformationRange(val start: Float, val end: Float, val easing: Easing) {
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
    fun reversed() =
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
        const val BoundUnspecified = Float.MIN_VALUE
    }
}
