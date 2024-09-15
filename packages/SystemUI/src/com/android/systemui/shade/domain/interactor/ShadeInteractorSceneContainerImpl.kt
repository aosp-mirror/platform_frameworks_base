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
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ShadeInteractor implementation for Scene Container. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ShadeInteractorSceneContainerImpl
@Inject
constructor(
    @Application scope: CoroutineScope,
    private val sceneInteractor: SceneInteractor,
    private val shadeModeInteractor: ShadeModeInteractor,
) : BaseShadeInteractor {
    init {
        SceneContainerFlag.assertInNewMode()
    }

    override val shadeExpansion: StateFlow<Float> =
        shadeModeInteractor.shadeMode
            .flatMapLatest { shadeMode ->
                transitionProgressExpansion(shadeMode.notificationsContentKey)
            }
            .traceAsCounter("panel_expansion") { (it * 100f).toInt() }
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    override val qsExpansion: StateFlow<Float> =
        shadeModeInteractor.shadeMode
            .flatMapLatest { shadeMode -> transitionProgressExpansion(shadeMode.qsContentKey) }
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    override val isQsExpanded: StateFlow<Boolean> =
        qsExpansion.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isQsBypassingShade: Flow<Boolean> =
        shadeModeInteractor.shadeMode
            .flatMapLatestConflated { shadeMode ->
                sceneInteractor.transitionState
                    .map { state ->
                        when (state) {
                            is ObservableTransitionState.Idle -> false
                            is ObservableTransitionState.Transition ->
                                state.toContent == shadeMode.qsContentKey &&
                                    state.fromContent != shadeMode.notificationsContentKey
                        }
                    }
                    .distinctUntilChanged()
            }
            .distinctUntilChanged()

    override val isQsFullscreen: Flow<Boolean> =
        shadeModeInteractor.shadeMode
            .flatMapLatest { shadeMode ->
                when (shadeMode) {
                    ShadeMode.Single ->
                        sceneInteractor.transitionState
                            .map { state ->
                                when (state) {
                                    is ObservableTransitionState.Idle ->
                                        state.currentScene == Scenes.QuickSettings
                                    is ObservableTransitionState.Transition -> false
                                }
                            }
                            .distinctUntilChanged()
                    ShadeMode.Split,
                    ShadeMode.Dual -> flowOf(false)
                }
            }
            .distinctUntilChanged()

    override val anyExpansion: StateFlow<Float> =
        createAnyExpansionFlow(scope, shadeExpansion, qsExpansion)

    override val isAnyExpanded =
        anyExpansion.map { it > 0f }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isUserInteractingWithShade: Flow<Boolean> =
        shadeModeInteractor.shadeMode.flatMapLatest { shadeMode ->
            when (shadeMode) {
                ShadeMode.Single,
                ShadeMode.Split -> sceneBasedInteracting(sceneInteractor, Scenes.Shade)
                ShadeMode.Dual ->
                    overlayBasedInteracting(sceneInteractor, Overlays.NotificationsShade)
            }
        }

    override val isUserInteractingWithQs: Flow<Boolean> =
        shadeModeInteractor.shadeMode.flatMapLatest { shadeMode ->
            when (shadeMode) {
                ShadeMode.Single -> sceneBasedInteracting(sceneInteractor, Scenes.QuickSettings)
                ShadeMode.Split -> sceneBasedInteracting(sceneInteractor, Scenes.Shade)
                ShadeMode.Dual ->
                    overlayBasedInteracting(sceneInteractor, Overlays.QuickSettingsShade)
            }
        }

    override fun expandNotificationShade(loggingReason: String) {
        if (shadeModeInteractor.isDualShade) {
            if (Overlays.QuickSettingsShade in sceneInteractor.currentOverlays.value) {
                sceneInteractor.replaceOverlay(
                    from = Overlays.QuickSettingsShade,
                    to = Overlays.NotificationsShade,
                    loggingReason = loggingReason,
                )
            } else {
                sceneInteractor.showOverlay(
                    overlay = Overlays.NotificationsShade,
                    loggingReason = loggingReason,
                )
            }
        } else {
            sceneInteractor.changeScene(toScene = Scenes.Shade, loggingReason = loggingReason)
        }
    }

    override fun expandQuickSettingsShade(loggingReason: String) {
        if (shadeModeInteractor.isDualShade) {
            if (Overlays.NotificationsShade in sceneInteractor.currentOverlays.value) {
                sceneInteractor.replaceOverlay(
                    from = Overlays.NotificationsShade,
                    to = Overlays.QuickSettingsShade,
                    loggingReason = loggingReason,
                )
            } else {
                sceneInteractor.showOverlay(
                    overlay = Overlays.QuickSettingsShade,
                    loggingReason = loggingReason,
                )
            }
        } else {
            sceneInteractor.changeScene(
                toScene = Scenes.QuickSettings,
                loggingReason = loggingReason,
            )
        }
    }

    /**
     * Returns a flow that uses scene transition progress to and from a content to a 0-1 expansion
     * amount float.
     */
    private fun transitionProgressExpansion(contentKey: ContentKey): Flow<Float> {
        return when (contentKey) {
            is SceneKey -> sceneBasedExpansion(sceneInteractor, contentKey)
            is OverlayKey -> overlayBasedExpansion(sceneInteractor, contentKey)
        }
    }

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

    /**
     * Returns a flow that uses scene transition progress to and from [overlay] to a 0-1 expansion
     * amount float.
     */
    private fun overlayBasedExpansion(sceneInteractor: SceneInteractor, overlay: OverlayKey) =
        sceneInteractor.transitionState
            .flatMapLatestConflated { state ->
                when (state) {
                    is ObservableTransitionState.Idle ->
                        flowOf(if (overlay in state.currentOverlays) 1f else 0f)
                    is ObservableTransitionState.Transition ->
                        if (state.toContent == overlay) {
                            state.progress
                        } else if (state.fromContent == overlay) {
                            state.progress.map { progress -> 1 - progress }
                        } else {
                            flowOf(0f)
                        }
                }
            }
            .distinctUntilChanged()

    /**
     * Returns a flow that uses scene transition data to determine whether the user is interacting
     * with [overlay].
     */
    private fun overlayBasedInteracting(sceneInteractor: SceneInteractor, overlay: OverlayKey) =
        sceneInteractor.transitionState
            .map { state ->
                when (state) {
                    is ObservableTransitionState.Idle -> false
                    is ObservableTransitionState.Transition ->
                        state.isInitiatedByUserInput &&
                            (state.toContent == overlay || state.fromContent == overlay)
                }
            }
            .distinctUntilChanged()

    private val ShadeMode.notificationsContentKey: ContentKey
        get() {
            return when (this) {
                ShadeMode.Single,
                ShadeMode.Split -> Scenes.Shade
                ShadeMode.Dual -> Overlays.NotificationsShade
            }
        }

    private val ShadeMode.qsContentKey: ContentKey
        get() {
            return when (this) {
                ShadeMode.Single -> Scenes.QuickSettings
                ShadeMode.Split -> Scenes.Shade
                ShadeMode.Dual -> Overlays.QuickSettingsShade
            }
        }
}
