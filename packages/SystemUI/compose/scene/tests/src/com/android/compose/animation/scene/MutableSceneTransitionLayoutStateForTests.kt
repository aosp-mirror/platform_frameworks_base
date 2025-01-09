/*
 * Copyright (C) 2025 The Android Open Source Project
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

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import com.android.compose.animation.scene.content.state.TransitionState

internal fun MutableSceneTransitionLayoutStateForTests(
    initialScene: SceneKey,
    transitions: SceneTransitions = SceneTransitions.Empty,
    initialOverlays: Set<OverlayKey> = emptySet(),
    canChangeScene: (SceneKey) -> Boolean = { true },
    canShowOverlay: (OverlayKey) -> Boolean = { true },
    canHideOverlay: (OverlayKey) -> Boolean = { true },
    canReplaceOverlay: (from: OverlayKey, to: OverlayKey) -> Boolean = { _, _ -> true },
    onTransitionStart: (TransitionState.Transition) -> Unit = {},
    onTransitionEnd: (TransitionState.Transition) -> Unit = {},
): MutableSceneTransitionLayoutStateImpl {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    return MutableSceneTransitionLayoutStateImpl(
        initialScene,
        motionScheme = MotionScheme.standard(),
        transitions,
        initialOverlays,
        canChangeScene,
        canShowOverlay,
        canHideOverlay,
        canReplaceOverlay,
        onTransitionStart,
        onTransitionEnd,
    )
}
