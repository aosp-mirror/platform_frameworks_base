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

package com.android.systemui.scene.shared.model

import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Delegates calls to a runtime-provided [SceneDataSource] or to a no-op implementation if a
 * delegate isn't set.
 */
class SceneDataSourceDelegator(
    applicationScope: CoroutineScope,
    config: SceneContainerConfig,
) : SceneDataSource {
    private val noOpDelegate = NoOpSceneDataSource(config.initialSceneKey)
    private val delegateMutable = MutableStateFlow<SceneDataSource>(noOpDelegate)

    override val currentScene: StateFlow<SceneKey> =
        delegateMutable
            .flatMapLatest { delegate -> delegate.currentScene }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = config.initialSceneKey,
            )

    override val currentOverlays: StateFlow<Set<OverlayKey>> =
        delegateMutable
            .flatMapLatest { delegate -> delegate.currentOverlays }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptySet(),
            )

    override fun changeScene(toScene: SceneKey, transitionKey: TransitionKey?) {
        delegateMutable.value.changeScene(
            toScene = toScene,
            transitionKey = transitionKey,
        )
    }

    override fun snapToScene(toScene: SceneKey) {
        delegateMutable.value.snapToScene(
            toScene = toScene,
        )
    }

    override fun showOverlay(overlay: OverlayKey, transitionKey: TransitionKey?) {
        delegateMutable.value.showOverlay(
            overlay = overlay,
            transitionKey = transitionKey,
        )
    }

    override fun hideOverlay(overlay: OverlayKey, transitionKey: TransitionKey?) {
        delegateMutable.value.hideOverlay(
            overlay = overlay,
            transitionKey = transitionKey,
        )
    }

    override fun replaceOverlay(from: OverlayKey, to: OverlayKey, transitionKey: TransitionKey?) {
        delegateMutable.value.replaceOverlay(
            from = from,
            to = to,
            transitionKey = transitionKey,
        )
    }

    /**
     * Binds the current, dependency injection provided [SceneDataSource] to the given object.
     *
     * In other words: once this is invoked, the state and functionality of the [SceneDataSource]
     * will be served by the given [delegate].
     *
     * If `null` is passed in, the delegator will use a no-op implementation of [SceneDataSource].
     *
     * This removes any previously set delegate.
     */
    fun setDelegate(delegate: SceneDataSource?) {
        delegateMutable.value = delegate ?: noOpDelegate
    }

    private class NoOpSceneDataSource(
        initialSceneKey: SceneKey,
    ) : SceneDataSource {
        override val currentScene: StateFlow<SceneKey> =
            MutableStateFlow(initialSceneKey).asStateFlow()

        override val currentOverlays: StateFlow<Set<OverlayKey>> =
            MutableStateFlow(emptySet<OverlayKey>()).asStateFlow()

        override fun changeScene(toScene: SceneKey, transitionKey: TransitionKey?) = Unit

        override fun snapToScene(toScene: SceneKey) = Unit

        override fun showOverlay(overlay: OverlayKey, transitionKey: TransitionKey?) = Unit

        override fun hideOverlay(overlay: OverlayKey, transitionKey: TransitionKey?) = Unit

        override fun replaceOverlay(
            from: OverlayKey,
            to: OverlayKey,
            transitionKey: TransitionKey?
        ) = Unit
    }
}
