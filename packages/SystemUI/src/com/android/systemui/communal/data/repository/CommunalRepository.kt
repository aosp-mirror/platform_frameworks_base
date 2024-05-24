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

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.shared.flag.SceneContainerFlags
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

/** Encapsulates the state of communal mode. */
interface CommunalRepository {
    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through
     * [setDesiredScene].
     */
    val desiredScene: StateFlow<SceneKey>

    /** Exposes the transition state of the communal [SceneTransitionLayout]. */
    val transitionState: StateFlow<ObservableTransitionState>

    /** Updates the requested scene. */
    fun setDesiredScene(desiredScene: SceneKey)

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?)
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalRepositoryImpl
@Inject
constructor(
    @Background backgroundScope: CoroutineScope,
    sceneContainerFlags: SceneContainerFlags,
    sceneContainerRepository: SceneContainerRepository,
) : CommunalRepository {

    private val _desiredScene: MutableStateFlow<SceneKey> = MutableStateFlow(CommunalScenes.Default)
    override val desiredScene: StateFlow<SceneKey> = _desiredScene.asStateFlow()

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

    override fun setDesiredScene(desiredScene: SceneKey) {
        _desiredScene.value = desiredScene
    }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    override fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        _transitionState.value = transitionState
    }
}
