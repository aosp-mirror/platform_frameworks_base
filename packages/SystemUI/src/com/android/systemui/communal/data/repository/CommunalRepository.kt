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

import com.android.systemui.Flags.communalHub
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.model.SceneKey
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Encapsulates the state of communal mode. */
interface CommunalRepository {
    /** Whether communal features are enabled. */
    val isCommunalEnabled: Boolean

    /** Whether the communal hub is showing. */
    val isCommunalHubShowing: Flow<Boolean>

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through
     * [setDesiredScene].
     */
    val desiredScene: StateFlow<CommunalSceneKey>

    /** Exposes the transition state of the communal [SceneTransitionLayout]. */
    val transitionState: StateFlow<ObservableCommunalTransitionState>

    /** Whether the CTA tile is visible in the hub under view mode. */
    val isCtaTileInViewModeVisible: Flow<Boolean>

    /** Updates the requested scene. */
    fun setDesiredScene(desiredScene: CommunalSceneKey)

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableCommunalTransitionState>?)

    /** Updates whether to display the CTA tile in the hub under view mode. */
    fun setCtaTileInViewModeVisibility(isVisible: Boolean)
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalRepositoryImpl
@Inject
constructor(
    @Background backgroundScope: CoroutineScope,
    private val featureFlagsClassic: FeatureFlagsClassic,
    sceneContainerFlags: SceneContainerFlags,
    sceneContainerRepository: SceneContainerRepository,
) : CommunalRepository {
    override val isCommunalEnabled: Boolean
        get() = featureFlagsClassic.isEnabled(Flags.COMMUNAL_SERVICE_ENABLED) && communalHub()

    private val _desiredScene: MutableStateFlow<CommunalSceneKey> =
        MutableStateFlow(CommunalSceneKey.DEFAULT)
    override val desiredScene: StateFlow<CommunalSceneKey> = _desiredScene.asStateFlow()

    private val defaultTransitionState =
        ObservableCommunalTransitionState.Idle(CommunalSceneKey.DEFAULT)
    private val _transitionState = MutableStateFlow<Flow<ObservableCommunalTransitionState>?>(null)
    override val transitionState: StateFlow<ObservableCommunalTransitionState> =
        _transitionState
            .flatMapLatest { innerFlowOrNull -> innerFlowOrNull ?: flowOf(defaultTransitionState) }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                initialValue = defaultTransitionState,
            )

    // TODO(b/313462210) - persist the value in local storage, so the tile won't show up again
    //  once dismissed.
    private val _isCtaTileInViewModeVisible: MutableStateFlow<Boolean> = MutableStateFlow(true)
    override val isCtaTileInViewModeVisible: Flow<Boolean> =
        _isCtaTileInViewModeVisible.asStateFlow()

    override fun setCtaTileInViewModeVisibility(isVisible: Boolean) {
        _isCtaTileInViewModeVisible.value = isVisible
    }

    override fun setDesiredScene(desiredScene: CommunalSceneKey) {
        _desiredScene.value = desiredScene
    }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    override fun setTransitionState(transitionState: Flow<ObservableCommunalTransitionState>?) {
        _transitionState.value = transitionState
    }

    override val isCommunalHubShowing: Flow<Boolean> =
        if (sceneContainerFlags.isEnabled()) {
            sceneContainerRepository.desiredScene.map { scene -> scene.key == SceneKey.Communal }
        } else {
            desiredScene.map { sceneKey -> sceneKey == CommunalSceneKey.Communal }
        }
}
