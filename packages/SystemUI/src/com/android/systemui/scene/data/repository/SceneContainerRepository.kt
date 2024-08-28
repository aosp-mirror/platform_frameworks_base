/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.scene.data.repository

import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSource
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
/** Source of truth for scene framework application state. */
class SceneContainerRepository
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    config: SceneContainerConfig,
    private val dataSource: SceneDataSource,
) {
    /**
     * The keys of all scenes and overlays in the container.
     *
     * They will be sorted in z-order such that the last one is the one that should be rendered on
     * top of all previous ones.
     */
    val allContentKeys: List<ContentKey> = config.sceneKeys + config.overlayKeys

    val currentScene: StateFlow<SceneKey> = dataSource.currentScene

    /**
     * The current set of overlays to be shown (may be empty).
     *
     * Note that during a transition between overlays, a different set of overlays may be rendered -
     * but only the ones in this set are considered the current overlays.
     */
    val currentOverlays: StateFlow<Set<OverlayKey>> = dataSource.currentOverlays

    private val _isVisible = MutableStateFlow(true)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    /**
     * Whether there's an ongoing remotely-initiated user interaction.
     *
     * For more information see the logic in `SceneInteractor` that mutates this.
     */
    val isRemoteUserInputOngoing = MutableStateFlow(false)

    /** Whether there's ongoing user input on the scene container Composable hierarchy */
    val isSceneContainerUserInputOngoing = MutableStateFlow(false)

    private val defaultTransitionState = ObservableTransitionState.Idle(config.initialSceneKey)
    private val _transitionState = MutableStateFlow<Flow<ObservableTransitionState>?>(null)
    val transitionState: StateFlow<ObservableTransitionState> =
        _transitionState
            .flatMapLatest { innerFlowOrNull -> innerFlowOrNull ?: flowOf(defaultTransitionState) }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = defaultTransitionState,
            )

    fun changeScene(
        toScene: SceneKey,
        transitionKey: TransitionKey? = null,
    ) {
        dataSource.changeScene(
            toScene = toScene,
            transitionKey = transitionKey,
        )
    }

    fun snapToScene(
        toScene: SceneKey,
    ) {
        dataSource.snapToScene(
            toScene = toScene,
        )
    }

    /**
     * Request to show [overlay] so that it animates in from [currentScene] and ends up being
     * visible on screen.
     *
     * After this returns, this overlay will be included in [currentOverlays]. This does nothing if
     * [overlay] is already shown.
     */
    fun showOverlay(overlay: OverlayKey, transitionKey: TransitionKey? = null) {
        dataSource.showOverlay(
            overlay = overlay,
            transitionKey = transitionKey,
        )
    }

    /**
     * Request to hide [overlay] so that it animates out to [currentScene] and ends up *not* being
     * visible on screen.
     *
     * After this returns, this overlay will not be included in [currentOverlays]. This does nothing
     * if [overlay] is already hidden.
     */
    fun hideOverlay(overlay: OverlayKey, transitionKey: TransitionKey? = null) {
        dataSource.hideOverlay(
            overlay = overlay,
            transitionKey = transitionKey,
        )
    }

    /**
     * Replace [from] by [to] so that [from] ends up not being visible on screen and [to] ends up
     * being visible.
     *
     * This throws if [from] is not currently shown or if [to] is already shown.
     */
    fun replaceOverlay(from: OverlayKey, to: OverlayKey, transitionKey: TransitionKey? = null) {
        dataSource.replaceOverlay(
            from = from,
            to = to,
            transitionKey = transitionKey,
        )
    }

    /** Sets whether the container is visible. */
    fun setVisible(isVisible: Boolean) {
        _isVisible.value = isVisible
    }

    /**
     * Binds the given flow so the system remembers it.
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        _transitionState.value = transitionState
    }
}
