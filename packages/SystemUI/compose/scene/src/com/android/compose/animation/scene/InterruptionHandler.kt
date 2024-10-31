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

package com.android.compose.animation.scene

import com.android.compose.animation.scene.content.state.TransitionState

/**
 * A handler to specify how a transition should be interrupted.
 *
 * @see DefaultInterruptionHandler
 * @see SceneTransitionsBuilder.interruptionHandler
 */
interface InterruptionHandler {
    /**
     * This function is called when [interrupted] is interrupted: it is currently animating between
     * [interrupted.fromScene] and [interrupted.toScene], and we will now animate to
     * [newTargetScene].
     *
     * If this returns `null`, then the [default behavior][DefaultInterruptionHandler] will be used:
     * we will animate from [interrupted.currentScene] and chaining will be enabled (see
     * [InterruptionResult] for more information about chaining).
     *
     * @see InterruptionResult
     */
    fun onInterruption(
        interrupted: TransitionState.Transition.ChangeScene,
        newTargetScene: SceneKey,
    ): InterruptionResult?
}

/**
 * The result of an interruption that specifies how we should handle a transition A => B now that we
 * have to animate to C.
 *
 * For instance, if the interrupted transition was A => B and currentScene = B:
 * - animateFrom = B && chain = true => there will be 2 transitions running in parallel, A => B and
 *   B => C.
 * - animateFrom = A && chain = true => there will be 2 transitions running in parallel, B => A and
 *   A => C.
 * - animateFrom = B && chain = false => there will be 1 transition running, B => C.
 * - animateFrom = A && chain = false => there will be 1 transition running, A => C.
 */
class InterruptionResult(
    /**
     * The scene we should animate from when transitioning to C.
     *
     * Important: This **must** be either [TransitionState.Transition.fromScene] or
     * [TransitionState.Transition.toScene] of the transition that was interrupted.
     */
    val animateFrom: SceneKey,

    /**
     * Whether chaining is enabled, i.e. if the new transition to C should run in parallel with the
     * previous one(s) or if it should be the only remaining transition that is running.
     */
    val chain: Boolean = true,
)

/**
 * The default interruption handler: we animate from [TransitionState.Transition.currentScene] and
 * chaining is enabled.
 */
object DefaultInterruptionHandler : InterruptionHandler {
    override fun onInterruption(
        interrupted: TransitionState.Transition.ChangeScene,
        newTargetScene: SceneKey,
    ): InterruptionResult {
        return InterruptionResult(animateFrom = interrupted.currentScene, chain = true)
    }
}
