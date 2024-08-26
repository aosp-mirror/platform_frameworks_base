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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun CoroutineScope.animateContent(
    layoutState: MutableSceneTransitionLayoutStateImpl,
    transition: TransitionState.Transition,
    oneOffAnimation: OneOffAnimation,
    targetProgress: Float,
    chain: Boolean = true,
) {
    // Start the transition. This will compute the TransformationSpec associated to [transition],
    // which we need to initialize the Animatable that will actually animate it.
    layoutState.startTransition(transition, chain)

    // The transition now contains the transformation spec that we should use to instantiate the
    // Animatable.
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

    // Animate the progress to its target value.
    //
    // Important: We start atomically to make sure that we start the coroutine even if it is
    // cancelled right after it is launched, so that finishTransition() is correctly called.
    // Otherwise, this transition will never be stopped and we will never settle to Idle.
    oneOffAnimation.job =
        launch(start = CoroutineStart.ATOMIC) {
            try {
                animatable.animateTo(targetProgress, animationSpec, initialVelocity)
            } finally {
                layoutState.finishTransition(transition)
            }
        }
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

    /** The job that is animating [animatable]. */
    lateinit var job: Job

    val progress: Float
        get() = animatable.value

    val progressVelocity: Float
        get() = animatable.velocity

    fun finish(): Job = job
}

// TODO(b/290184746): Compute a good default visibility threshold that depends on the layout size
// and screen density.
internal const val ProgressVisibilityThreshold = 1e-3f
