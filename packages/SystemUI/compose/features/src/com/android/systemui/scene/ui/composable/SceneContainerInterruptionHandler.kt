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

package com.android.systemui.scene.ui.composable

import com.android.compose.animation.scene.InterruptionHandler
import com.android.compose.animation.scene.InterruptionResult
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.transitions.TO_BOUNCER_FADE_FRACTION

object SceneContainerInterruptionHandler : InterruptionHandler {
    override fun onInterruption(
        interrupted: TransitionState.Transition.ChangeScene,
        newTargetScene: SceneKey,
    ): InterruptionResult? {
        return handleTransitionToGoneDuringTransitionToBouncer(interrupted, newTargetScene)
    }

    /**
     * Handle the case where we start transitioning to Bouncer but then we are interrupted to
     * transition to Gone, for instance because face auth kicked in.
     */
    private fun handleTransitionToGoneDuringTransitionToBouncer(
        transition: TransitionState.Transition.ChangeScene,
        targetScene: SceneKey,
    ): InterruptionResult? {
        if (targetScene != Scenes.Gone || !transition.isTransitioningFromOrTo(Scenes.Bouncer)) {
            return null
        }

        // Animate Bouncer => Gone only when the bouncer is fully opaque, otherwise animate
        // OtherScene => Gone and reverse the OtherScene => Bouncer transition (note: OtherScene is
        // usually the Lockscreen scene).
        val otherScene: SceneKey
        val animatesFromBouncer =
            if (transition.isTransitioning(to = Scenes.Bouncer)) {
                otherScene = transition.fromScene
                transition.progress >= TO_BOUNCER_FADE_FRACTION
            } else {
                otherScene = transition.toScene
                transition.progress <= 1f - TO_BOUNCER_FADE_FRACTION
            }

        return if (animatesFromBouncer) {
            InterruptionResult(
                animateFrom = Scenes.Bouncer,

                // We don't want the content of the lockscreen to be shown during the Bouncer =>
                // Launcher transition. We disable chaining of the transitions so that only the
                // Bouncer and Launcher scenes are composed.
                chain = false,
            )
        } else {
            InterruptionResult(animateFrom = otherScene)
        }
    }
}
