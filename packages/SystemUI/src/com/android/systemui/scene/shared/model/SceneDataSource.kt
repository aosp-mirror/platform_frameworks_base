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
import kotlinx.coroutines.flow.StateFlow

/** Defines interface for classes that provide access to scene state. */
interface SceneDataSource {

    /**
     * The current scene, as seen by the real data source in the UI layer.
     *
     * During a transition between two scenes, the original scene will still be reflected in
     * [currentScene] until a time when the UI layer decides to commit the change, which is when
     * [currentScene] will have the value of the target/new scene.
     */
    val currentScene: StateFlow<SceneKey>

    /**
     * The current set of overlays to be shown (may be empty).
     *
     * Note that during a transition between overlays, a different set of overlays may be rendered -
     * but only the ones in this set are considered the current overlays.
     */
    val currentOverlays: StateFlow<Set<OverlayKey>>

    /**
     * Asks for an asynchronous scene switch to [toScene], which will use the corresponding
     * installed transition or the one specified by [transitionKey], if provided.
     */
    fun changeScene(
        toScene: SceneKey,
        transitionKey: TransitionKey? = null,
    )

    /**
     * Asks for an instant scene switch to [toScene], without an animated transition of any kind.
     */
    fun snapToScene(
        toScene: SceneKey,
    )

    /**
     * Request to show [overlay] so that it animates in from [currentScene] and ends up being
     * visible on screen.
     *
     * After this returns, this overlay will be included in [currentOverlays]. This does nothing if
     * [overlay] is already shown.
     */
    fun showOverlay(
        overlay: OverlayKey,
        transitionKey: TransitionKey? = null,
    )

    /**
     * Request to hide [overlay] so that it animates out to [currentScene] and ends up *not* being
     * visible on screen.
     *
     * After this returns, this overlay will not be included in [currentOverlays]. This does nothing
     * if [overlay] is already hidden.
     */
    fun hideOverlay(
        overlay: OverlayKey,
        transitionKey: TransitionKey? = null,
    )

    /**
     * Replace [from] by [to] so that [from] ends up not being visible on screen and [to] ends up
     * being visible.
     *
     * This throws if [from] is not currently shown or if [to] is already shown.
     */
    fun replaceOverlay(
        from: OverlayKey,
        to: OverlayKey,
        transitionKey: TransitionKey? = null,
    )
}
