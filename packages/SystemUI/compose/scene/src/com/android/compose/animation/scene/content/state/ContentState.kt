/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.compose.animation.scene.content.state

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Stable
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.OverscrollScope
import com.android.compose.animation.scene.OverscrollSpecImpl
import com.android.compose.animation.scene.ProgressVisibilityThreshold
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TransformationSpec
import com.android.compose.animation.scene.TransformationSpecImpl
import com.android.compose.animation.scene.TransitionKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The state associated to one or more contents. */
@Stable
sealed interface ContentState<out T : ContentKey> {
    /** The [content] is idle, it does not animate. */
    sealed class Idle<T : ContentKey>(val content: T) : ContentState<T>

    /** The content is transitioning with another content. */
    sealed class Transition<out T : ContentKey>(
        val fromContent: T,
        val toContent: T,
        internal val replacedTransition: Transition<T>?,
    ) : ContentState<T> {
        /**
         * The key of this transition. This should usually be null, but it can be specified to use a
         * specific set of transformations associated to this transition.
         */
        open val key: TransitionKey? = null

        /**
         * The progress of the transition. This is usually in the `[0; 1]` range, but it can also be
         * less than `0` or greater than `1` when using transitions with a spring AnimationSpec or
         * when flinging quickly during a swipe gesture.
         */
        abstract val progress: Float

        /** The current velocity of [progress], in progress units. */
        abstract val progressVelocity: Float

        /** Whether the transition was triggered by user input rather than being programmatic. */
        abstract val isInitiatedByUserInput: Boolean

        /** Whether user input is currently driving the transition. */
        abstract val isUserInputOngoing: Boolean

        /**
         * The progress of the preview transition. This is usually in the `[0; 1]` range, but it can
         * also be less than `0` or greater than `1` when using transitions with a spring
         * AnimationSpec or when flinging quickly during a swipe gesture.
         */
        internal open val previewProgress: Float = 0f

        /** The current velocity of [previewProgress], in progress units. */
        internal open val previewProgressVelocity: Float = 0f

        /** Whether the transition is currently in the preview stage */
        internal open val isInPreviewStage: Boolean = false

        /**
         * The current [TransformationSpecImpl] and [OverscrollSpecImpl] associated to this
         * transition.
         *
         * Important: These will be set exactly once, when this transition is
         * [started][MutableSceneTransitionLayoutStateImpl.startTransition].
         */
        internal var transformationSpec: TransformationSpecImpl = TransformationSpec.Empty
        internal var previewTransformationSpec: TransformationSpecImpl? = null
        private var fromOverscrollSpec: OverscrollSpecImpl? = null
        private var toOverscrollSpec: OverscrollSpecImpl? = null

        /** The current [OverscrollSpecImpl], if this transition is currently overscrolling. */
        internal val currentOverscrollSpec: OverscrollSpecImpl?
            get() {
                if (this !is HasOverscrollProperties) return null
                val progress = progress
                val bouncingContent = bouncingContent
                return when {
                    progress < 0f || bouncingContent == fromContent -> fromOverscrollSpec
                    progress > 1f || bouncingContent == toContent -> toOverscrollSpec
                    else -> null
                }
            }

        /**
         * An animatable that animates from 1f to 0f. This will be used to nicely animate the sudden
         * jump of values when this transitions interrupts another one.
         */
        private var interruptionDecay: Animatable<Float, AnimationVector1D>? = null

        init {
            check(fromContent != toContent)
            check(
                replacedTransition == null ||
                    (replacedTransition.fromContent == fromContent &&
                        replacedTransition.toContent == toContent)
            )
        }

        /**
         * Force this transition to finish and animate to an [Idle] state.
         *
         * Important: Once this is called, the effective state of the transition should remain
         * unchanged. For instance, in the case of a [TransitionState.Transition], its
         * [currentScene][TransitionState.Transition.currentScene] should never change once [finish]
         * is called.
         *
         * @return the [Job] that animates to the idle state. It can be used to wait until the
         *   animation is complete or cancel it to snap the animation. Calling [finish] multiple
         *   times will return the same [Job].
         */
        abstract fun finish(): Job

        /**
         * Whether we are transitioning. If [from] or [to] is empty, we will also check that they
         * match the contents we are animating from and/or to.
         */
        fun isTransitioning(from: ContentKey? = null, to: ContentKey? = null): Boolean {
            return (from == null || fromContent == from) && (to == null || toContent == to)
        }

        /** Whether we are transitioning from [content] to [other], or from [other] to [content]. */
        fun isTransitioningBetween(content: ContentKey, other: ContentKey): Boolean {
            return isTransitioning(from = content, to = other) ||
                isTransitioning(from = other, to = content)
        }

        internal fun updateOverscrollSpecs(
            fromSpec: OverscrollSpecImpl?,
            toSpec: OverscrollSpecImpl?,
        ) {
            fromOverscrollSpec = fromSpec
            toOverscrollSpec = toSpec
        }

        /** Returns if the [progress] value of this transition can go beyond range `[0; 1]` */
        internal fun isWithinProgressRange(progress: Float): Boolean {
            // If the properties are missing we assume that every [Transition] can overscroll
            if (this !is HasOverscrollProperties) return true
            // [OverscrollSpec] for the current scene, even if it hasn't started overscrolling yet.
            val specForCurrentScene =
                when {
                    progress <= 0f -> fromOverscrollSpec
                    progress >= 1f -> toOverscrollSpec
                    else -> null
                } ?: return true

            return specForCurrentScene.transformationSpec.transformations.isNotEmpty()
        }

        internal open fun interruptionProgress(
            layoutImpl: SceneTransitionLayoutImpl,
        ): Float {
            if (!layoutImpl.state.enableInterruptions) {
                return 0f
            }

            if (replacedTransition != null) {
                return replacedTransition.interruptionProgress(layoutImpl)
            }

            fun create(): Animatable<Float, AnimationVector1D> {
                val animatable = Animatable(1f, visibilityThreshold = ProgressVisibilityThreshold)
                layoutImpl.coroutineScope.launch {
                    val swipeSpec = layoutImpl.state.transitions.defaultSwipeSpec
                    val progressSpec =
                        spring(
                            stiffness = swipeSpec.stiffness,
                            dampingRatio = swipeSpec.dampingRatio,
                            visibilityThreshold = ProgressVisibilityThreshold,
                        )
                    animatable.animateTo(0f, progressSpec)
                }

                return animatable
            }

            val animatable = interruptionDecay ?: create().also { interruptionDecay = it }
            return animatable.value
        }
    }

    interface HasOverscrollProperties {
        /**
         * The position of the [Transition.toContent].
         *
         * Used to understand the direction of the overscroll.
         */
        val isUpOrLeft: Boolean

        /**
         * The relative orientation between [Transition.fromContent] and [Transition.toContent].
         *
         * Used to understand the orientation of the overscroll.
         */
        val orientation: Orientation

        /**
         * Scope which can be used in the Overscroll DSL to define a transformation based on the
         * distance between [Transition.fromContent] and [Transition.toContent].
         */
        val overscrollScope: OverscrollScope

        /**
         * The content (scene or overlay) around which the transition is currently bouncing. When
         * not `null`, this transition is currently oscillating around this content and will soon
         * settle to that content.
         */
        val bouncingContent: ContentKey?

        companion object {
            const val DistanceUnspecified = 0f
        }
    }
}
