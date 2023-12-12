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

package com.android.compose.animation.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Transition to [target] using a canned animation. This function will try to be smart and take over
 * the currently running transition, if there is one.
 */
internal fun CoroutineScope.animateToScene(
    layoutState: SceneTransitionLayoutStateImpl,
    target: SceneKey,
) {
    val transitionState = layoutState.transitionState
    if (transitionState.currentScene == target) {
        // This can happen in 3 different situations, for which there isn't anything else to do:
        //  1. There is no ongoing transition and [target] is already the current scene.
        //  2. The user is swiping to [target] from another scene and released their pointer such
        //     that the gesture was committed and the transition is animating to [scene] already.
        //  3. The user is swiping from [target] to another scene and either:
        //     a. didn't release their pointer yet.
        //     b. released their pointer such that the swipe gesture was cancelled and the
        //        transition is currently animating back to [target].
        return
    }

    when (transitionState) {
        is TransitionState.Idle -> animate(layoutState, target)
        is TransitionState.Transition -> {
            // A transition is currently running: first check whether `transition.toScene` or
            // `transition.fromScene` is the same as our target scene, in which case the transition
            // can be accelerated or reversed to end up in the target state.

            if (transitionState.toScene == target) {
                // The user is currently swiping to [target] but didn't release their pointer yet:
                // animate the progress to `1`.

                check(transitionState.fromScene == transitionState.currentScene)
                val progress = transitionState.progress
                if ((1f - progress).absoluteValue < ProgressVisibilityThreshold) {
                    // The transition is already finished (progress ~= 1): no need to animate. We
                    // finish the current transition early to make sure that the current state
                    // change is committed.
                    layoutState.finishTransition(transitionState, transitionState.currentScene)
                } else {
                    // The transition is in progress: start the canned animation at the same
                    // progress as it was in.
                    // TODO(b/290184746): Also take the current velocity into account.
                    animate(layoutState, target, startProgress = progress)
                }

                return
            }

            if (transitionState.fromScene == target) {
                // There is a transition from [target] to another scene: simply animate the same
                // transition progress to `0`.

                check(transitionState.toScene == transitionState.currentScene)
                val progress = transitionState.progress
                if (progress.absoluteValue < ProgressVisibilityThreshold) {
                    // The transition is at progress ~= 0: no need to animate.We finish the current
                    // transition early to make sure that the current state change is committed.
                    layoutState.finishTransition(transitionState, transitionState.currentScene)
                } else {
                    // TODO(b/290184746): Also take the current velocity into account.
                    animate(layoutState, target, startProgress = progress, reversed = true)
                }

                return
            }

            // Generic interruption; the current transition is neither from or to [target].
            // TODO(b/290930950): Better handle interruptions here.
            animate(layoutState, target)
        }
    }
}

private fun CoroutineScope.animate(
    layoutState: SceneTransitionLayoutStateImpl,
    target: SceneKey,
    startProgress: Float = 0f,
    reversed: Boolean = false,
) {
    val fromScene = layoutState.transitionState.currentScene
    val isUserInput =
        (layoutState.transitionState as? TransitionState.Transition)?.isInitiatedByUserInput
            ?: false

    val targetProgress = if (reversed) 0f else 1f
    val transition =
        if (reversed) {
            OneOffTransition(
                fromScene = target,
                toScene = fromScene,
                currentScene = target,
                isInitiatedByUserInput = isUserInput,
                isUserInputOngoing = false,
            )
        } else {
            OneOffTransition(
                fromScene = fromScene,
                toScene = target,
                currentScene = target,
                isInitiatedByUserInput = isUserInput,
                isUserInputOngoing = false,
            )
        }

    // Change the current layout state to start this new transition. This will compute the
    // TransformationSpec associated to this transition, which we need to initialize the Animatable
    // that will actually animate it.
    layoutState.startTransition(transition)

    // The transformation now contains the spec that we should use to instantiate the Animatable.
    val animationSpec = layoutState.transformationSpec.progressSpec
    val visibilityThreshold =
        (animationSpec as? SpringSpec)?.visibilityThreshold ?: ProgressVisibilityThreshold
    val animatable =
        Animatable(startProgress, visibilityThreshold = visibilityThreshold).also {
            transition.animatable = it
        }

    // Animate the progress to its target value.
    launch {
        animatable.animateTo(targetProgress, animationSpec)
        layoutState.finishTransition(transition, target)
    }
}

private class OneOffTransition(
    fromScene: SceneKey,
    toScene: SceneKey,
    override val currentScene: SceneKey,
    override val isInitiatedByUserInput: Boolean,
    override val isUserInputOngoing: Boolean,
) : TransitionState.Transition(fromScene, toScene) {
    /**
     * The animatable used to animate this transition.
     *
     * Note: This is lateinit because we need to first create this Transition object so that
     * [SceneTransitionLayoutState] can compute the transformations and animation spec associated to
     * it, which is need to initialize this Animatable.
     */
    lateinit var animatable: Animatable<Float, AnimationVector1D>

    override val progress: Float
        get() = animatable.value
}

// TODO(b/290184746): Compute a good default visibility threshold that depends on the layout size
// and screen density.
private const val ProgressVisibilityThreshold = 1e-3f
