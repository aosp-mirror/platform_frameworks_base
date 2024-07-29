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
import com.android.compose.animation.scene.OverscrollScope
import com.android.compose.animation.scene.OverscrollSpecImpl
import com.android.compose.animation.scene.ProgressVisibilityThreshold
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TransformationSpec
import com.android.compose.animation.scene.TransformationSpecImpl
import com.android.compose.animation.scene.TransitionKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
sealed interface TransitionState {
    /**
     * The current effective scene. If a new transition was triggered, it would start from this
     * scene.
     *
     * For instance, when swiping from scene A to scene B, the [currentScene] is A when the swipe
     * gesture starts, but then if the user flings their finger and commits the transition to scene
     * B, then [currentScene] becomes scene B even if the transition is not finished yet and is
     * still animating to settle to scene B.
     */
    val currentScene: SceneKey

    /** No transition/animation is currently running. */
    data class Idle(override val currentScene: SceneKey) : TransitionState

    /** There is a transition animating between two scenes. */
    abstract class Transition(
        /** The scene this transition is starting from. Can't be the same as toScene */
        val fromScene: SceneKey,

        /** The scene this transition is going to. Can't be the same as fromScene */
        val toScene: SceneKey,

        /** The transition that `this` transition is replacing, if any. */
        internal val replacedTransition: Transition? = null,
    ) : TransitionState {
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

        /**
         * The progress of the preview transition. This is usually in the `[0; 1]` range, but it can
         * also be less than `0` or greater than `1` when using transitions with a spring
         * AnimationSpec or when flinging quickly during a swipe gesture.
         */
        open val previewProgress: Float = 0f

        /** The current velocity of [previewProgress], in progress units. */
        open val previewProgressVelocity: Float = 0f

        /** Whether the transition is currently in the preview stage */
        open val isInPreviewStage: Boolean = false

        /** Whether the transition was triggered by user input rather than being programmatic. */
        abstract val isInitiatedByUserInput: Boolean

        /** Whether user input is currently driving the transition. */
        abstract val isUserInputOngoing: Boolean

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
                val bouncingScene = bouncingScene
                return when {
                    progress < 0f || bouncingScene == fromScene -> fromOverscrollSpec
                    progress > 1f || bouncingScene == toScene -> toOverscrollSpec
                    else -> null
                }
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

        /**
         * An animatable that animates from 1f to 0f. This will be used to nicely animate the sudden
         * jump of values when this transitions interrupts another one.
         */
        private var interruptionDecay: Animatable<Float, AnimationVector1D>? = null

        init {
            check(fromScene != toScene)
            check(
                replacedTransition == null ||
                    (replacedTransition.fromScene == fromScene &&
                        replacedTransition.toScene == toScene)
            )
        }

        /**
         * Force this transition to finish and animate to [currentScene], so that this transition
         * progress will settle to either 0% (if [currentScene] == [fromScene]) or 100% (if
         * [currentScene] == [toScene]) in a finite amount of time.
         *
         * @return the [Job] that animates the progress to [currentScene]. It can be used to wait
         *   until the animation is complete or cancel it to snap to [currentScene]. Calling
         *   [finish] multiple times will return the same [Job].
         */
        abstract fun finish(): Job

        /**
         * Whether we are transitioning. If [from] or [to] is empty, we will also check that they
         * match the scenes we are animating from and/or to.
         */
        fun isTransitioning(from: SceneKey? = null, to: SceneKey? = null): Boolean {
            return (from == null || fromScene == from) && (to == null || toScene == to)
        }

        /** Whether we are transitioning from [scene] to [other], or from [other] to [scene]. */
        fun isTransitioningBetween(scene: SceneKey, other: SceneKey): Boolean {
            return isTransitioning(from = scene, to = other) ||
                isTransitioning(from = other, to = scene)
        }

        internal fun updateOverscrollSpecs(
            fromSpec: OverscrollSpecImpl?,
            toSpec: OverscrollSpecImpl?,
        ) {
            fromOverscrollSpec = fromSpec
            toOverscrollSpec = toSpec
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
         * The position of the [Transition.toScene].
         *
         * Used to understand the direction of the overscroll.
         */
        val isUpOrLeft: Boolean

        /**
         * The relative orientation between [Transition.fromScene] and [Transition.toScene].
         *
         * Used to understand the orientation of the overscroll.
         */
        val orientation: Orientation

        /**
         * Scope which can be used in the Overscroll DSL to define a transformation based on the
         * distance between [Transition.fromScene] and [Transition.toScene].
         */
        val overscrollScope: OverscrollScope

        /**
         * The scene around which the transition is currently bouncing. When not `null`, this
         * transition is currently oscillating around this scene and will soon settle to that scene.
         */
        val bouncingScene: SceneKey?

        companion object {
            const val DistanceUnspecified = 0f
        }
    }
}
