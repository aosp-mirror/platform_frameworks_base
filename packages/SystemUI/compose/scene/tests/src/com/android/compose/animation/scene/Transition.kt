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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope

/** A utility to easily create a [TransitionState.Transition] in tests. */
fun transition(
    from: SceneKey,
    to: SceneKey,
    current: () -> SceneKey = { from },
    progress: () -> Float = { 0f },
    progressVelocity: () -> Float = { 0f },
    interruptionProgress: () -> Float = { 100f },
    isInitiatedByUserInput: Boolean = false,
    isUserInputOngoing: Boolean = false,
    isUpOrLeft: Boolean = false,
    bouncingScene: SceneKey? = null,
    orientation: Orientation = Orientation.Horizontal,
    onFinish: ((TransitionState.Transition) -> Job)? = null,
): TransitionState.Transition {
    return object : TransitionState.Transition(from, to), TransitionState.HasOverscrollProperties {
        override val currentScene: SceneKey
            get() = current()

        override val progress: Float
            get() = progress()

        override val progressVelocity: Float
            get() = progressVelocity()

        override val isInitiatedByUserInput: Boolean = isInitiatedByUserInput
        override val isUserInputOngoing: Boolean = isUserInputOngoing
        override val isUpOrLeft: Boolean = isUpOrLeft
        override val bouncingScene: SceneKey? = bouncingScene
        override val orientation: Orientation = orientation
        override val overscrollScope: OverscrollScope =
            object : OverscrollScope {
                override val density: Float = 1f
                override val fontScale: Float = 1f
                override val absoluteDistance = 0f
            }

        override fun finish(): Job {
            val onFinish =
                onFinish
                    ?: error(
                        "onFinish() must be provided if finish() is called on test transitions"
                    )

            return onFinish(this)
        }

        override fun interruptionProgress(layoutImpl: SceneTransitionLayoutImpl): Float {
            return interruptionProgress()
        }
    }
}

/**
 * Return a onFinish lambda that can be used with [transition] so that the transition never
 * finishes. This allows to keep the transition in the current transitions list.
 */
fun TestScope.neverFinish(): (TransitionState.Transition) -> Job {
    return {
        backgroundScope.launch {
            // Try to acquire a locked mutex so that this code never completes.
            Mutex(locked = true).withLock {}
        }
    }
}
