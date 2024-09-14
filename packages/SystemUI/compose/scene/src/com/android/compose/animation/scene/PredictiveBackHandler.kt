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
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import com.android.compose.animation.scene.UserActionResult.ChangeScene
import com.android.compose.animation.scene.UserActionResult.HideOverlay
import com.android.compose.animation.scene.UserActionResult.ReplaceByOverlay
import com.android.compose.animation.scene.UserActionResult.ShowOverlay
import com.android.compose.animation.scene.transition.animateProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
