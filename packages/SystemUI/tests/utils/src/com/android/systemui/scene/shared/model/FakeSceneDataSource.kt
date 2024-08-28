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

package com.android.systemui.scene.shared.model

import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSceneDataSource(
    initialSceneKey: SceneKey,
) : SceneDataSource {

    private val _currentScene = MutableStateFlow(initialSceneKey)
    override val currentScene: StateFlow<SceneKey> = _currentScene.asStateFlow()

    private val _currentOverlays = MutableStateFlow<Set<OverlayKey>>(emptySet())
    override val currentOverlays: StateFlow<Set<OverlayKey>> = _currentOverlays.asStateFlow()

    var isPaused = false
        private set

    var pendingScene: SceneKey? = null
        private set

    var pendingOverlays: Set<OverlayKey>? = null
        private set

    override fun changeScene(toScene: SceneKey, transitionKey: TransitionKey?) {
        if (isPaused) {
            pendingScene = toScene
        } else {
            _currentScene.value = toScene
        }
    }

    override fun snapToScene(toScene: SceneKey) {
        changeScene(toScene)
    }

    override fun showOverlay(overlay: OverlayKey, transitionKey: TransitionKey?) {
        if (isPaused) {
            pendingOverlays = (pendingOverlays ?: currentOverlays.value) + overlay
        } else {
            _currentOverlays.value += overlay
        }
    }

    override fun hideOverlay(overlay: OverlayKey, transitionKey: TransitionKey?) {
        if (isPaused) {
            pendingOverlays = (pendingOverlays ?: currentOverlays.value) - overlay
        } else {
            _currentOverlays.value -= overlay
        }
    }

    override fun replaceOverlay(from: OverlayKey, to: OverlayKey, transitionKey: TransitionKey?) {
        hideOverlay(from, transitionKey)
        showOverlay(to, transitionKey)
    }

    /**
     * Pauses scene and overlay changes.
     *
     * Any following calls to [changeScene] or overlay changing functions will be conflated and the
     * last one will be remembered.
     */
    fun pause() {
        check(!isPaused) { "Can't pause what's already paused!" }

        isPaused = true
    }

    /**
     * Unpauses scene and overlay changes.
     *
     * If there were any calls to [changeScene] since [pause] was called, the latest of the bunch
     * will be replayed.
     *
     * If there were any calls to show, hide or replace overlays since [pause] was called, they will
     * all be applied at once.
     *
     * If [force] is `true`, there will be no check that [isPaused] is true.
     *
     * If [expectedScene] is provided, will assert that it's indeed the latest called.
     */
    fun unpause(
        force: Boolean = false,
        expectedScene: SceneKey? = null,
    ) {
        check(force || isPaused) { "Can't unpause what's already not paused!" }

        isPaused = false
        pendingScene?.let { _currentScene.value = it }
        pendingScene = null
        pendingOverlays?.let { _currentOverlays.value = it }
        pendingOverlays = null

        check(expectedScene == null || currentScene.value == expectedScene) {
            """
                Unexpected scene while unpausing.
                Expected $expectedScene but was $currentScene.
            """
                .trimIndent()
        }
    }
}
