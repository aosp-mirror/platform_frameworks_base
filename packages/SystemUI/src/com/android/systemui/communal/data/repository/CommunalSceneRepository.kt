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

package com.android.systemui.communal.data.repository

import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.communal.dagger.Communal
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.scene.shared.model.SceneDataSource
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Encapsulates the state of communal mode. */
interface CommunalSceneRepository {
    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through [changeScene].
     */
    val currentScene: StateFlow<SceneKey>

    /** Exposes the transition state of the communal [SceneTransitionLayout]. */
    val transitionState: StateFlow<ObservableTransitionState>

    /** Updates the requested scene. */
    fun changeScene(toScene: SceneKey, transitionKey: TransitionKey? = null)

    /** Immediately snaps to the desired scene. */
    fun snapToScene(toScene: SceneKey)

    /** Shows the hub from a power button press. */
    suspend fun showHubFromPowerButton()

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?)
}

@SysUISingleton
class CommunalSceneRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background backgroundScope: CoroutineScope,
    @Communal private val sceneDataSource: SceneDataSource,
    @Communal private val delegator: SceneDataSourceDelegator,
) : CommunalSceneRepository {

    override val currentScene: StateFlow<SceneKey> = sceneDataSource.currentScene

    private val defaultTransitionState = ObservableTransitionState.Idle(CommunalScenes.Default)
    private val _transitionState = MutableStateFlow<Flow<ObservableTransitionState>?>(null)
    override val transitionState: StateFlow<ObservableTransitionState> =
        _transitionState
            .flatMapLatest { innerFlowOrNull -> innerFlowOrNull ?: flowOf(defaultTransitionState) }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                initialValue = defaultTransitionState,
            )

    override fun changeScene(toScene: SceneKey, transitionKey: TransitionKey?) {
        applicationScope.launch {
            // SceneTransitionLayout state updates must be triggered on the thread the STL was
            // created on.
            sceneDataSource.changeScene(toScene, transitionKey)
        }
    }

    override fun snapToScene(toScene: SceneKey) {
        applicationScope.launch {
            // SceneTransitionLayout state updates must be triggered on the thread the STL was
            // created on.
            sceneDataSource.snapToScene(toScene)
        }
    }

    override suspend fun showHubFromPowerButton() {
        // If keyguard is not showing yet, the hub view is not ready and the
        // [SceneDataSourceDelegator] will still be using the default [NoOpSceneDataSource]
        // and initial key, which is Blank. This means that when the hub container loads, it
        // will default to not showing the hub. Attempting to set the scene in this state
        // is simply ignored by the [NoOpSceneDataSource]. Instead, we temporarily override
        // it with a new one that defaults to Communal. This delegate will be overwritten
        // once the [CommunalContainer] loads.
        // TODO(b/392969914): show the hub first instead of forcing the scene.
        delegator.setDelegate(NoOpSceneDataSource(CommunalScenes.Communal))
    }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    override fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        _transitionState.value = transitionState
    }

    /** Noop implementation of a scene data source that always returns the initial [SceneKey]. */
    private class NoOpSceneDataSource(initialSceneKey: SceneKey) : SceneDataSource {
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
            transitionKey: TransitionKey?,
        ) = Unit
    }
}
