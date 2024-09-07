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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import com.android.compose.animation.scene.content.state.TransitionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal fun CoroutineScope.animateContent(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    transition: TransitionState.Transition,
    oneOffAnimation: OneOffAnimation,
    targetProgress: Float,
    chain: Boolean = true,
): Job {
    oneOffAnimation.onRun = {
        // Animate the progress to its target value.
        val animationSpec = transition.transformationSpec.progressSpec
        val visibilityThreshold =
            (animationSpec as? SpringSpec)?.visibilityThreshold ?: ProgressVisibilityThreshold
        val replacedTransition = transition.replacedTransition
        val initialProgress = replacedTransition?.progress ?: 0f
        val initialVelocity = replacedTransition?.progressVelocity ?: 0f
        val animatable =
            Animatable(initialProgress, visibilityThreshold = visibilityThreshold).also {
                oneOffAnimation.animatable = it
            }

        animatable.animateTo(targetProgress, animationSpec, initialVelocity)
    }

    return layoutState.startTransitionImmediately(animationScope = this, transition, chain)
}

internal class OneOffAnimation {
    /**
     * The animatable used to animate this transition.
     *
     * Note: This is lateinit because we need to first create this object so that
     * [SceneTransitionLayoutState] can compute the transformations and animation spec associated to
     * the transition, which is needed to initialize this Animatable.
     */
    lateinit var animatable: Animatable<Float, AnimationVector1D>

    /** The runnable to run for this animation. */
    lateinit var onRun: suspend () -> Unit

    val progress: Float
        get() = animatable.value

    val progressVelocity: Float
        get() = animatable.velocity

    suspend fun run() {
        onRun()
    }

    fun freezeAndAnimateToCurrentState() {
        // Do nothing, the state of one-off animations never change and we directly animate to it.
    }
}

// TODO(b/290184746): Compute a good default visibility threshold that depends on the layout size
// and screen density.
internal const val ProgressVisibilityThreshold = 1e-3f
