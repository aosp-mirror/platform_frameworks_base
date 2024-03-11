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

import androidx.compose.foundation.gestures.Orientation
import kotlinx.coroutines.Job

/** A utility to easily create a [TransitionState.Transition] in tests. */
fun transition(
    from: SceneKey,
    to: SceneKey,
    progress: () -> Float = { 0f },
    isInitiatedByUserInput: Boolean = false,
    isUserInputOngoing: Boolean = false,
    isUpOrLeft: Boolean = false,
    orientation: Orientation = Orientation.Horizontal,
): TransitionState.Transition {
    return object : TransitionState.Transition(from, to), TransitionState.HasOverscrollProperties {
        override val currentScene: SceneKey = from
        override val progress: Float
            get() = progress()

        override val isInitiatedByUserInput: Boolean = isInitiatedByUserInput
        override val isUserInputOngoing: Boolean = isUserInputOngoing
        override val isUpOrLeft: Boolean = isUpOrLeft
        override val orientation: Orientation = orientation

        override fun finish(): Job {
            error("finish() is not supported in test transitions")
        }
    }
}
