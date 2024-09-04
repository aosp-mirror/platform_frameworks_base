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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import com.android.app.tracing.FlowTracing.traceAsCounter
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ShadeInteractor implementation for Scene Container. */
@SysUISingleton
class ShadeInteractorSceneContainerImpl
@Inject
constructor(
    @Application scope: CoroutineScope,
    sceneInteractor: SceneInteractor,
    shadeRepository: ShadeRepository,
) : BaseShadeInteractor {
    init {
        SceneContainerFlag.assertInNewMode()
    }

    override val shadeExpansion: StateFlow<Float> =
        sceneBasedExpansion(sceneInteractor, SceneFamilies.NotifShade)
            .traceAsCounter("panel_expansion") { (it * 100f).toInt() }
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    private val sceneBasedQsExpansion =
        sceneBasedExpansion(sceneInteractor, SceneFamilies.QuickSettings)

    override val qsExpansion: StateFlow<Float> =
        combine(
                shadeRepository.isShadeLayoutWide,
                shadeExpansion,
                sceneBasedQsExpansion,
            ) { isSplitShadeEnabled, shadeExpansion, qsExpansion ->
                if (isSplitShadeEnabled) {
                    shadeExpansion
                } else {
                    qsExpansion
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    override val isQsExpanded: StateFlow<Boolean> =
        qsExpansion
            .map { it > 0 }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val isQsBypassingShade: Flow<Boolean> =
        combine(
                sceneInteractor.resolveSceneFamily(SceneFamilies.QuickSettings),
                sceneInteractor.resolveSceneFamily(SceneFamilies.NotifShade),
                ::Pair
            )
            .flatMapLatestConflated { (quickSettingsScene, notificationsScene) ->
                sceneInteractor.transitionState
                    .map { state ->
                        when (state) {
                            is ObservableTransitionState.Idle -> false
                            is ObservableTransitionState.Transition ->
                                state.toContent == quickSettingsScene &&
                                    state.fromContent != notificationsScene
                        }
                    }
                    .distinctUntilChanged()
            }
            .distinctUntilChanged()

    override val isQsFullscreen: Flow<Boolean> =
        combine(
                shadeRepository.isShadeLayoutWide,
                sceneInteractor.resolveSceneFamily(SceneFamilies.QuickSettings),
                ::Pair
            )
            .flatMapLatestConflated { (isShadeLayoutWide, quickSettingsScene) ->
                sceneInteractor.transitionState
                    .map { state ->
                        when (state) {
                            is ObservableTransitionState.Idle ->
                                !isShadeLayoutWide && state.currentScene == quickSettingsScene
                            is ObservableTransitionState.Transition -> false
                        }
                    }
                    .distinctUntilChanged()
            }
            .distinctUntilChanged()

    override val anyExpansion: StateFlow<Float> =
        createAnyExpansionFlow(scope, shadeExpansion, qsExpansion)

    override val isAnyExpanded =
        anyExpansion
            .map { it > 0f }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val isUserInteractingWithShade: Flow<Boolean> =
        sceneBasedInteracting(sceneInteractor, SceneFamilies.NotifShade)

    override val isUserInteractingWithQs: Flow<Boolean> =
        sceneBasedInteracting(sceneInteractor, SceneFamilies.QuickSettings)

    /**
     * Returns a flow that uses scene transition progress to and from a scene that is pulled down
     * from the top of the screen to a 0-1 expansion amount float.
     */
    fun sceneBasedExpansion(sceneInteractor: SceneInteractor, sceneKey: SceneKey) =
        sceneInteractor
            .resolveSceneFamily(sceneKey)
            .flatMapLatestConflated { resolvedSceneKey ->
                sceneInteractor.transitionState
                    .flatMapLatestConflated { state ->
                        when (state) {
                            is ObservableTransitionState.Idle ->
                                if (state.currentScene == resolvedSceneKey) {
                                    flowOf(1f)
                                } else {
                                    flowOf(0f)
                                }
                            is ObservableTransitionState.Transition ->
                                if (state.toContent == resolvedSceneKey) {
                                    state.progress
                                } else if (state.fromContent == resolvedSceneKey) {
                                    state.progress.map { progress -> 1 - progress }
                                } else {
                                    flowOf(0f)
                                }
                        }
                    }
                    .distinctUntilChanged()
            }
            .distinctUntilChanged()

    /**
     * Returns a flow that uses scene transition data to determine whether the user is interacting
     * with a scene that is pulled down from the top of the screen.
     */
    fun sceneBasedInteracting(sceneInteractor: SceneInteractor, sceneKey: SceneKey) =
        sceneInteractor.transitionState
            .flatMapLatestConflated { state ->
                when (state) {
                    is ObservableTransitionState.Idle -> flowOf(false)
                    is ObservableTransitionState.Transition ->
                        sceneInteractor.resolveSceneFamily(sceneKey).map { resolvedSceneKey ->
                            state.isInitiatedByUserInput &&
                                (state.toContent == resolvedSceneKey ||
                                    state.fromContent == resolvedSceneKey)
                        }
                }
            }
            .distinctUntilChanged()
}
