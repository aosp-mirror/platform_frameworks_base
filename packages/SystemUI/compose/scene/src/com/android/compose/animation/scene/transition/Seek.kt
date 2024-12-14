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

package com.android.compose.animation.scene.transition

import androidx.annotation.FloatRange
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.MutableSceneTransitionLayoutStateImpl
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SwipeAnimation
import com.android.compose.animation.scene.TransitionKey
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.createSwipeAnimation
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Seek to the given [scene] using [progress].
 *
 * This will start a transition from the
 * [current scene][MutableSceneTransitionLayoutState.currentScene] to [scene], driven by the
 * progress in [progress]. Once [progress] stops emitting, we will animate progress to 1f (using
 * [animationSpec]) if it stopped normally or to 0f if it stopped with a
 * [kotlin.coroutines.cancellation.CancellationException].
 */
suspend fun MutableSceneTransitionLayoutState.seekToScene(
    scene: SceneKey,
    @FloatRange(0.0, 1.0) progress: Flow<Float>,
    transitionKey: TransitionKey? = null,
    animationSpec: AnimationSpec<Float>? = null,
) {
    require(scene != currentScene) {
        "seekToScene($scene) has to be called with a different scene than the current scene"
    }

    seek(UserActionResult.ChangeScene(scene, transitionKey), progress, animationSpec)
}

/**
 * Seek to show the given [overlay] using [progress].
 *
 * This will start a transition to show [overlay] from the
 * [current scene][MutableSceneTransitionLayoutState.currentScene], driven by the progress in
 * [progress]. Once [progress] stops emitting, we will animate progress to 1f (using
 * [animationSpec]) if it stopped normally or to 0f if it stopped with a
 * [kotlin.coroutines.cancellation.CancellationException].
 */
suspend fun MutableSceneTransitionLayoutState.seekToShowOverlay(
    overlay: OverlayKey,
    @FloatRange(0.0, 1.0) progress: Flow<Float>,
    transitionKey: TransitionKey? = null,
    animationSpec: AnimationSpec<Float>? = null,
) {
    require(overlay in currentOverlays) {
        "seekToShowOverlay($overlay) can be called only when the overlay is in currentOverlays"
    }

    seek(UserActionResult.ShowOverlay(overlay, transitionKey), progress, animationSpec)
}

/**
 * Seek to hide the given [overlay] using [progress].
 *
 * This will start a transition to hide [overlay] to the
 * [current scene][MutableSceneTransitionLayoutState.currentScene], driven by the progress in
 * [progress]. Once [progress] stops emitting, we will animate progress to 1f (using
 * [animationSpec]) if it stopped normally or to 0f if it stopped with a
 * [kotlin.coroutines.cancellation.CancellationException].
 */
suspend fun MutableSceneTransitionLayoutState.seekToHideOverlay(
    overlay: OverlayKey,
    @FloatRange(0.0, 1.0) progress: Flow<Float>,
    transitionKey: TransitionKey? = null,
    animationSpec: AnimationSpec<Float>? = null,
) {
    require(overlay !in currentOverlays) {
        "seekToHideOverlay($overlay) can be called only when the overlay is *not* in " +
            "currentOverlays"
    }

    seek(UserActionResult.HideOverlay(overlay, transitionKey), progress, animationSpec)
}

private suspend fun MutableSceneTransitionLayoutState.seek(
    result: UserActionResult,
    progress: Flow<Float>,
    animationSpec: AnimationSpec<Float>?,
) {
    val layoutState =
        when (this) {
            is MutableSceneTransitionLayoutStateImpl -> this
        }

    val swipeAnimation =
        createSwipeAnimation(
            layoutState = layoutState,
            result = result,

            // We are animating progress, so distance is always 1f.
            distance = 1f,

            // The orientation and isUpOrLeft don't matter here given that they are only used during
            // overscroll, which is disabled for progress-based transitions.
            orientation = Orientation.Horizontal,
            isUpOrLeft = false,
        )

    animateProgress(
        state = layoutState,
        animation = swipeAnimation,
        progress = progress,
        commitSpec = animationSpec,
        cancelSpec = animationSpec,
    )
}

internal suspend fun <T : ContentKey> animateProgress(
    state: MutableSceneTransitionLayoutStateImpl,
    animation: SwipeAnimation<T>,
    progress: Flow<Float>,
    commitSpec: AnimationSpec<Float>?,
    cancelSpec: AnimationSpec<Float>?,
) {
    fun animateOffset(targetContent: T, spec: AnimationSpec<Float>?) {
        if (state.transitionState != animation.contentTransition || animation.isAnimatingOffset()) {
            return
        }

        animation.animateOffset(
            initialVelocity = 0f,
            targetContent = targetContent,

            // Important: we have to specify a spec that correctly animates *progress* (low
            // visibility threshold) and not *offset* (higher visibility threshold).
            spec = spec ?: animation.contentTransition.transformationSpec.progressSpec,
        )
    }

    coroutineScope {
        val collectionJob = launch {
            try {
                progress.collectLatest { progress ->
                    // Progress based animation should never overscroll given that the
                    // absoluteDistance exposed to overscroll builders is always 1f and will not
                    // lead to any noticeable transformation.
                    animation.dragOffset = progress.fastCoerceIn(0f, 1f)
                }

                // Transition committed.
                animateOffset(animation.toContent, commitSpec)
            } catch (e: CancellationException) {
                // Transition cancelled.
                animateOffset(animation.fromContent, cancelSpec)
            }
        }

        // Start the transition.
        state.startTransition(animation.contentTransition)

        // The transition is done. Cancel the collection in case the transition was finished because
        // it was interrupted by another transition.
        if (collectionJob.isActive) {
            collectionJob.cancel()
        }
    }
}
