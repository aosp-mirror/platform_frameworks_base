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

import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.Scene
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TransitionState

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
) : Transformation

/** A transformation that changes the value of an element property, like its size or offset. */
internal sealed interface PropertyTransformation<T> : Transformation {
    /**
     * Transform [value], i.e. the value of the transformed property without this transformation.
     */
    // TODO(b/290184746): Figure out a public API for custom transformations that don't have access
    // to these internal classes.
    fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        scene: Scene,
        element: Element,
        sceneState: Element.SceneState,
        transition: TransitionState.Transition,
        value: T,
    ): T
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
            range.reversed()
        )
    }
}

/** The progress-based range of a [PropertyTransformation]. */
data class TransformationRange(
    val start: Float,
    val end: Float,
) {
    constructor(
        start: Float? = null,
        end: Float? = null
    ) : this(start ?: BoundUnspecified, end ?: BoundUnspecified)

    init {
        require(!start.isSpecified() || (start in 0f..1f))
        require(!end.isSpecified() || (end in 0f..1f))
        require(!start.isSpecified() || !end.isSpecified() || start <= end)
    }

    /** Reverse this range. */
    fun reversed() = TransformationRange(start = reverseBound(end), end = reverseBound(start))

    /** Get the progress of this range given the global [transitionProgress]. */
    fun progress(transitionProgress: Float): Float {
        return when {
            start.isSpecified() && end.isSpecified() ->
                ((transitionProgress - start) / (end - start)).fastCoerceIn(0f, 1f)
            !start.isSpecified() && !end.isSpecified() -> transitionProgress
            end.isSpecified() -> (transitionProgress / end).fastCoerceAtMost(1f)
            else -> ((transitionProgress - start) / (1f - start)).fastCoerceAtLeast(0f)
        }
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
