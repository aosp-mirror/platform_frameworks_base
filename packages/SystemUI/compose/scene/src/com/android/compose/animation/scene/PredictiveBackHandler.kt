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
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.ui.util.fastCoerceIn
import com.android.compose.animation.scene.UserActionResult.ChangeScene
import com.android.compose.animation.scene.UserActionResult.HideOverlay
import com.android.compose.animation.scene.UserActionResult.ReplaceByOverlay
import com.android.compose.animation.scene.UserActionResult.ShowOverlay
import com.android.mechanics.ProvidedGestureContext
import com.android.mechanics.spec.InputDirection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
internal fun PredictiveBackHandler(
    layoutImpl: SceneTransitionLayoutImpl,
    result: UserActionResult?,
) {
    PredictiveBackHandler(enabled = result != null) { events: Flow<BackEventCompat> ->
        if (result == null) {
            // Note: We have to collect progress otherwise PredictiveBackHandler will throw.
            events.first()
            return@PredictiveBackHandler
        }

        if (result is ShowOverlay) {
            layoutImpl.hideOverlays(result.hideCurrentOverlays)
        }

        val animation =
            createSwipeAnimation(
                layoutImpl,
                if (result.transitionKey != null) {
                    result
                } else {
                    result.copy(transitionKey = TransitionKey.PredictiveBack)
                },
                isUpOrLeft = false,
                // Note that the orientation does not matter here given that it's only used to
                // compute the distance. In our case the distance is always 1f.
                orientation = Orientation.Horizontal,
                distance = 1f,
                gestureContext =
                    ProvidedGestureContext(dragOffset = 0f, direction = InputDirection.Max),
                decayAnimationSpec = layoutImpl.decayAnimationSpec,
            )

        animateProgress(
            state = layoutImpl.state,
            animation = animation,
            progress = events.map { it.progress },

            // Use the transformationSpec.progressSpec. We will lazily access it later once the
            // transition has been started, because at this point the transformation spec of the
            // transition is not computed yet.
            commitSpec = null,

            // The predictive back APIs will automatically animate the progress for us in this case
            // so there is no need to animate it.
            cancelSpec = snap(),
            animationScope = layoutImpl.animationScope,
        )
    }
}

private fun UserActionResult.copy(
    transitionKey: TransitionKey? = this.transitionKey
): UserActionResult {
    return when (this) {
        is ChangeScene -> copy(transitionKey = transitionKey)
        is ShowOverlay -> copy(transitionKey = transitionKey)
        is HideOverlay -> copy(transitionKey = transitionKey)
        is ReplaceByOverlay -> copy(transitionKey = transitionKey)
    }
}

private suspend fun <T : ContentKey> animateProgress(
    state: MutableSceneTransitionLayoutStateImpl,
    animation: SwipeAnimation<T>,
    progress: Flow<Float>,
    commitSpec: AnimationSpec<Float>?,
    cancelSpec: AnimationSpec<Float>?,
    animationScope: CoroutineScope? = null,
) {
    suspend fun animateOffset(targetContent: T, spec: AnimationSpec<Float>?) {
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
        animationScope?.launch { startTransition(state, animation, collectionJob) }
            ?: startTransition(state, animation, collectionJob)
    }
}

private suspend fun <T : ContentKey> startTransition(
    state: MutableSceneTransitionLayoutStateImpl,
    animation: SwipeAnimation<T>,
    progressCollectionJob: Job,
) {
    state.startTransition(animation.contentTransition)
    // The transition is done. Cancel the collection in case the transition was finished
    // because it was interrupted by another transition.
    if (progressCollectionJob.isActive) {
        progressCollectionJob.cancel()
    }
}
