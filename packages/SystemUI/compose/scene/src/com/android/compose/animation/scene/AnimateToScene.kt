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

import com.android.compose.animation.scene.content.state.TransitionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Transition to [target] using a canned animation. This function will try to be smart and take over
 * the currently running transition, if there is one.
 */
internal fun CoroutineScope.animateToScene(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    target: SceneKey,
    transitionKey: TransitionKey?,
): Pair<TransitionState.Transition.ChangeScene, Job>? {
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
        return null
    }

    return when (transitionState) {
        is TransitionState.Idle,
        is TransitionState.Transition.ShowOrHideOverlay,
        is TransitionState.Transition.ReplaceOverlay -> {
            animateToScene(
                layoutState,
                target,
                transitionKey,
                isInitiatedByUserInput = false,
                fromScene = transitionState.currentScene,
                replacedTransition = null,
            )
        }
        is TransitionState.Transition.ChangeScene -> {
            val isInitiatedByUserInput = transitionState.isInitiatedByUserInput

            // A transition is currently running: first check whether `transition.toScene` or
            // `transition.fromScene` is the same as our target scene, in which case the transition
            // can be accelerated or reversed to end up in the target state.

            if (transitionState.toScene == target) {
                // The user is currently swiping to [target] but didn't release their pointer yet:
                // animate the progress to `1`.
                check(transitionState.fromScene == transitionState.currentScene)

                // The transition is in progress: start the canned animation at the same
                // progress as it was in.
                animateToScene(
                    layoutState,
                    target,
                    transitionKey,
                    isInitiatedByUserInput,
                    replacedTransition = transitionState,
                )
            } else if (transitionState.fromScene == target) {
                // There is a transition from [target] to another scene: simply animate the same
                // transition progress to `0`.
                check(transitionState.toScene == transitionState.currentScene)

                animateToScene(
                    layoutState,
                    target,
                    transitionKey,
                    isInitiatedByUserInput,
                    reversed = true,
                    replacedTransition = transitionState,
                )
            } else {
                // Generic interruption; the current transition is neither from or to [target].
                val interruptionResult =
                    layoutState.transitions.interruptionHandler.onInterruption(
                        transitionState,
                        target,
                    ) ?: DefaultInterruptionHandler.onInterruption(transitionState, target)

                val animateFrom = interruptionResult.animateFrom
                if (
                    animateFrom != transitionState.toScene &&
                        animateFrom != transitionState.fromScene
                ) {
                    error(
                        "InterruptionResult.animateFrom must be either the fromScene " +
                            "(${transitionState.fromScene.debugName}) or the toScene " +
                            "(${transitionState.toScene.debugName}) of the interrupted transition."
                    )
                }

                // If we were A => B and that we are now animating A => C, add a transition B => A
                // to the list of transitions so that B "disappears back to A".
                val chain = interruptionResult.chain
                if (chain && animateFrom != transitionState.currentScene) {
                    animateToScene(layoutState, animateFrom, transitionKey = null)
                }

                animateToScene(
                    layoutState,
                    target,
                    transitionKey,
                    isInitiatedByUserInput,
                    fromScene = animateFrom,
                    chain = chain,
                    replacedTransition = null,
                )
            }
        }
    }
}

private fun CoroutineScope.animateToScene(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    targetScene: SceneKey,
    transitionKey: TransitionKey?,
    isInitiatedByUserInput: Boolean,
    replacedTransition: TransitionState.Transition?,
    reversed: Boolean = false,
    fromScene: SceneKey = layoutState.transitionState.currentScene,
    chain: Boolean = true,
): Pair<TransitionState.Transition.ChangeScene, Job> {
    val oneOffAnimation = OneOffAnimation()
    val targetProgress = if (reversed) 0f else 1f
    val transition =
        if (reversed) {
            OneOffSceneTransition(
                key = transitionKey,
                fromScene = targetScene,
                toScene = fromScene,
                currentScene = targetScene,
                isInitiatedByUserInput = isInitiatedByUserInput,
                replacedTransition = replacedTransition,
                oneOffAnimation = oneOffAnimation,
            )
        } else {
            OneOffSceneTransition(
                key = transitionKey,
                fromScene = fromScene,
                toScene = targetScene,
                currentScene = targetScene,
                isInitiatedByUserInput = isInitiatedByUserInput,
                replacedTransition = replacedTransition,
                oneOffAnimation = oneOffAnimation,
            )
        }

    val job =
        animateContent(
            layoutState = layoutState,
            transition = transition,
            oneOffAnimation = oneOffAnimation,
            targetProgress = targetProgress,
            chain = chain,
        )

    return transition to job
}

private class OneOffSceneTransition(
    override val key: TransitionKey?,
    fromScene: SceneKey,
    toScene: SceneKey,
    override val currentScene: SceneKey,
    override val isInitiatedByUserInput: Boolean,
    replacedTransition: TransitionState.Transition?,
    private val oneOffAnimation: OneOffAnimation,
) : TransitionState.Transition.ChangeScene(fromScene, toScene, replacedTransition) {
    override val progress: Float
        get() = oneOffAnimation.progress

    override val progressVelocity: Float
        get() = oneOffAnimation.progressVelocity

    override val isUserInputOngoing: Boolean = false

    override suspend fun run() {
        oneOffAnimation.run()
    }

    override fun freezeAndAnimateToCurrentState() {
        oneOffAnimation.freezeAndAnimateToCurrentState()
    }
}
