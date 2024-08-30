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
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Composable
internal fun PredictiveBackHandler(
    layoutImpl: SceneTransitionLayoutImpl,
    result: UserActionResult?,
) {
    PredictiveBackHandler(
        enabled = result != null,
    ) { progress: Flow<BackEventCompat> ->
        if (result == null) {
            // Note: We have to collect progress otherwise PredictiveBackHandler will throw.
            progress.first()
            return@PredictiveBackHandler
        }

        val animation =
            createSwipeAnimation(
                layoutImpl,
                layoutImpl.coroutineScope,
                result,
                isUpOrLeft = false,
                // Note that the orientation does not matter here given that it's only used to
                // compute the distance. In our case the distance is always 1f.
                orientation = Orientation.Horizontal,
                distance = 1f,
            )

        animate(layoutImpl, animation, progress)
    }
}

private suspend fun <T : ContentKey> animate(
    layoutImpl: SceneTransitionLayoutImpl,
    animation: SwipeAnimation<T>,
    progress: Flow<BackEventCompat>,
) {
    fun animateOffset(targetContent: T) {
        if (
            layoutImpl.state.transitionState != animation.contentTransition || animation.isFinishing
        ) {
            return
        }

        animation.animateOffset(
            initialVelocity = 0f,
            targetContent = targetContent,

            // TODO(b/350705972): Allow to customize or reuse the same customization endpoints as
            // the normal swipe transitions. We can't just reuse them here because other swipe
            // transitions animate pixels while this transition animates progress, so the visibility
            // thresholds will be completely different.
            spec = spring(),
        )
    }

    layoutImpl.state.startTransition(animation.contentTransition)
    try {
        progress.collect { backEvent -> animation.dragOffset = backEvent.progress }

        // Back gesture successful.
        animateOffset(animation.toContent)
    } catch (e: CancellationException) {
        // Back gesture cancelled.
        animateOffset(animation.fromContent)
    }
}
