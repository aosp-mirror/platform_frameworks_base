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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.ui.composable

import androidx.compose.runtime.snapshotFlow
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.compose.animation.scene.observableTransitionState
import com.android.systemui.scene.shared.model.SceneDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * An implementation of [SceneDataSource] that's backed by a [MutableSceneTransitionLayoutState].
 */
class SceneTransitionLayoutDataSource(
    private val state: MutableSceneTransitionLayoutState,

    /**
     * The [CoroutineScope] of the @Composable that's using this, it's critical that this is *not*
     * the application scope.
     */
    private val coroutineScope: CoroutineScope,
) : SceneDataSource {
    override val currentScene: StateFlow<SceneKey> =
        state
            .observableTransitionState()
            .flatMapLatest { it.currentScene() }
            .stateIn(
                scope = coroutineScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = state.transitionState.currentScene,
            )

    override val currentOverlays: StateFlow<Set<OverlayKey>> =
        snapshotFlow { state.currentOverlays }
            .stateIn(
                scope = coroutineScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptySet(),
            )

    override fun changeScene(
        toScene: SceneKey,
        transitionKey: TransitionKey?,
    ) {
        state.setTargetScene(
            targetScene = toScene,
            transitionKey = transitionKey,
            animationScope = coroutineScope,
        )
    }

    override fun snapToScene(toScene: SceneKey) {
        state.snapToScene(
            scene = toScene,
        )
    }

    override fun showOverlay(overlay: OverlayKey, transitionKey: TransitionKey?) {
        state.showOverlay(
            overlay = overlay,
            animationScope = coroutineScope,
            transitionKey = transitionKey,
        )
    }

    override fun hideOverlay(overlay: OverlayKey, transitionKey: TransitionKey?) {
        state.hideOverlay(
            overlay = overlay,
            animationScope = coroutineScope,
            transitionKey = transitionKey,
        )
    }

    override fun replaceOverlay(from: OverlayKey, to: OverlayKey, transitionKey: TransitionKey?) {
        state.replaceOverlay(
            from = from,
            to = to,
            animationScope = coroutineScope,
            transitionKey = transitionKey,
        )
    }
}
