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

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun PredictiveBackHandler(
    state: BaseSceneTransitionLayoutState,
    coroutineScope: CoroutineScope,
    targetSceneForBack: SceneKey? = null,
) {
    PredictiveBackHandler(
        enabled = targetSceneForBack != null,
    ) { progress: Flow<BackEventCompat> ->
        val fromScene = state.transitionState.currentScene
        if (targetSceneForBack == null || targetSceneForBack == fromScene) {
            // Note: We have to collect progress otherwise PredictiveBackHandler will throw.
            progress.first()
            return@PredictiveBackHandler
        }

        val transition =
            PredictiveBackTransition(state, coroutineScope, fromScene, toScene = targetSceneForBack)
        state.startTransition(transition)
        try {
            progress.collect { backEvent -> transition.dragProgress = backEvent.progress }

            // Back gesture successful.
            transition.animateTo(
                if (state.canChangeScene(targetSceneForBack)) {
                    targetSceneForBack
                } else {
                    fromScene
                }
            )
        } catch (e: CancellationException) {
            // Back gesture cancelled.
            transition.animateTo(fromScene)
        }
    }
}

private class PredictiveBackTransition(
    val state: BaseSceneTransitionLayoutState,
    val coroutineScope: CoroutineScope,
    fromScene: SceneKey,
    toScene: SceneKey,
) : TransitionState.Transition(fromScene, toScene) {
    override var currentScene by mutableStateOf(fromScene)
        private set

    /** The animated progress once the gesture was committed or cancelled. */
    private var progressAnimatable by mutableStateOf<Animatable<Float, AnimationVector1D>?>(null)
    var dragProgress: Float by mutableFloatStateOf(0f)

    override val progress: Float
        get() = progressAnimatable?.value ?: dragProgress

    override val progressVelocity: Float
        get() = progressAnimatable?.velocity ?: 0f

    override val isInitiatedByUserInput: Boolean
        get() = true

    override val isUserInputOngoing: Boolean
        get() = progressAnimatable == null

    private var animationJob: Job? = null

    override fun finish(): Job = animateTo(currentScene)

    fun animateTo(scene: SceneKey): Job {
        check(scene == fromScene || scene == toScene)
        animationJob?.let {
            return it
        }

        currentScene = scene
        val targetProgress =
            when (scene) {
                fromScene -> 0f
                toScene -> 1f
                else -> error("scene $scene should be either $fromScene or $toScene")
            }

        val animatable = Animatable(dragProgress).also { progressAnimatable = it }

        // Important: We start atomically to make sure that we start the coroutine even if it is
        // cancelled right after it is launched, so that finishTransition() is correctly called.
        return coroutineScope
            .launch(start = CoroutineStart.ATOMIC) {
                try {
                    animatable.animateTo(targetProgress)
                } finally {
                    state.finishTransition(this@PredictiveBackTransition, scene)
                }
            }
            .also { animationJob = it }
    }
}
