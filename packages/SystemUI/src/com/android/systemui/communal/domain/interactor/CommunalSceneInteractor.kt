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

package com.android.systemui.communal.domain.interactor

import com.android.app.tracing.coroutines.launch
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.communal.data.repository.CommunalSceneRepository
import com.android.systemui.communal.domain.model.CommunalTransitionProgressModel
import com.android.systemui.communal.shared.log.CommunalSceneLogger
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalScenes.toSceneContainerSceneKey
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.pairwiseBy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalSceneInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: CommunalSceneRepository,
    private val logger: CommunalSceneLogger,
    private val sceneInteractor: SceneInteractor,
) {
    private val _isLaunchingWidget = MutableStateFlow(false)

    /** Whether a widget launch is currently in progress. */
    val isLaunchingWidget: StateFlow<Boolean> = _isLaunchingWidget.asStateFlow()

    fun setIsLaunchingWidget(launching: Boolean) {
        _isLaunchingWidget.value = launching
    }

    fun interface OnSceneAboutToChangeListener {
        /** Notifies that the scene is about to change to [toScene]. */
        fun onSceneAboutToChange(toScene: SceneKey, keyguardState: KeyguardState?)
    }

    private val onSceneAboutToChangeListener = mutableSetOf<OnSceneAboutToChangeListener>()

    /**
     * Registers a listener which is called when the scene is about to change.
     *
     * This API is for legacy communal container scenes, and should not be used when
     * [SceneContainerFlag] is enabled.
     */
    fun registerSceneStateProcessor(processor: OnSceneAboutToChangeListener) {
        SceneContainerFlag.assertInLegacyMode()
        onSceneAboutToChangeListener.add(processor)
    }

    /**
     * Asks for an asynchronous scene witch to [newScene], which will use the corresponding
     * installed transition or the one specified by [transitionKey], if provided.
     */
    fun changeScene(
        newScene: SceneKey,
        loggingReason: String,
        transitionKey: TransitionKey? = null,
        keyguardState: KeyguardState? = null,
    ) {
        if (SceneContainerFlag.isEnabled) {
            return sceneInteractor.changeScene(
                toScene = newScene.toSceneContainerSceneKey(),
                loggingReason = loggingReason,
                transitionKey = transitionKey,
                sceneState = keyguardState,
            )
        }

        applicationScope.launch("$TAG#changeScene") {
            if (currentScene.value == newScene) return@launch
            logger.logSceneChangeRequested(
                from = currentScene.value,
                to = newScene,
                reason = loggingReason,
                isInstant = false,
            )
            notifyListeners(newScene, keyguardState)
            repository.changeScene(newScene, transitionKey)
        }
    }

    /** Immediately snaps to the new scene. */
    fun snapToScene(
        newScene: SceneKey,
        loggingReason: String,
        delayMillis: Long = 0,
        keyguardState: KeyguardState? = null
    ) {
        if (SceneContainerFlag.isEnabled) {
            return sceneInteractor.snapToScene(
                toScene = newScene.toSceneContainerSceneKey(),
                loggingReason = loggingReason,
            )
        }

        applicationScope.launch("$TAG#snapToScene") {
            delay(delayMillis)
            if (currentScene.value == newScene) return@launch
            logger.logSceneChangeRequested(
                from = currentScene.value,
                to = newScene,
                reason = loggingReason,
                isInstant = true,
            )
            notifyListeners(newScene, keyguardState)
            repository.snapToScene(newScene)
        }
    }

    private fun notifyListeners(newScene: SceneKey, keyguardState: KeyguardState?) {
        onSceneAboutToChangeListener.forEach { it.onSceneAboutToChange(newScene, keyguardState) }
    }

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through [changeScene].
     */
    val currentScene: StateFlow<SceneKey> =
        if (SceneContainerFlag.isEnabled) {
            sceneInteractor.currentScene
        } else {
            repository.currentScene
                .pairwiseBy(initialValue = repository.currentScene.value) { from, to ->
                    logger.logSceneChangeCommitted(
                        from = from,
                        to = to,
                    )
                    to
                }
                .stateIn(
                    scope = applicationScope,
                    started = SharingStarted.Eagerly,
                    initialValue = repository.currentScene.value,
                )
        }

    private val _editModeState = MutableStateFlow<EditModeState?>(null)
    /**
     * Current state for glanceable hub edit mode, used to chain the animations when transitioning
     * between communal scene and the edit mode activity.
     */
    val editModeState = _editModeState.asStateFlow()

    fun setEditModeState(value: EditModeState?) {
        _editModeState.value = value
    }

    /** Transition state of the hub mode. */
    val transitionState: StateFlow<ObservableTransitionState> =
        if (SceneContainerFlag.isEnabled) {
            sceneInteractor.transitionState
        } else {
            repository.transitionState
                .onEach { logger.logSceneTransition(it) }
                .stateIn(
                    scope = applicationScope,
                    started = SharingStarted.Eagerly,
                    initialValue = repository.transitionState.value,
                )
        }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        if (SceneContainerFlag.isEnabled) {
            sceneInteractor.setTransitionState(transitionState)
        } else {
            repository.setTransitionState(transitionState)
        }
    }

    /**
     * Returns a flow that tracks the progress of transitions to the given scene from 0-1.
     *
     * This API is for legacy communal container scenes, and should not be used when
     * [SceneContainerFlag] is enabled.
     */
    fun transitionProgressToScene(targetScene: SceneKey) =
        transitionState
            .flatMapLatest { state ->
                when (state) {
                    is ObservableTransitionState.Idle ->
                        flowOf(CommunalTransitionProgressModel.Idle(state.currentScene))
                    is ObservableTransitionState.Transition ->
                        if (state.toContent == targetScene) {
                            state.progress.map {
                                CommunalTransitionProgressModel.Transition(
                                    // Clamp the progress values between 0 and 1 as actual progress
                                    // values can be higher than 0 or lower than 1 due to a fling.
                                    progress = it.coerceIn(0.0f, 1.0f)
                                )
                            }
                        } else {
                            flowOf(CommunalTransitionProgressModel.OtherTransition)
                        }
                }
            }
            .distinctUntilChanged()
            .onStart { SceneContainerFlag.assertInLegacyMode() }

    /**
     * Flow that emits a boolean if the communal UI is fully visible and not in transition.
     *
     * This will not be true while transitioning to the hub and will turn false immediately when a
     * swipe to exit the hub starts.
     */
    val isIdleOnCommunal: StateFlow<Boolean> =
        transitionState
            .map {
                it is ObservableTransitionState.Idle &&
                    (it.currentScene ==
                        if (SceneContainerFlag.isEnabled) Scenes.Communal
                        else CommunalScenes.Communal)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /** This flow will be true when idle on the hub and not transitioning to edit mode. */
    val isIdleOnCommunalNotEditMode: Flow<Boolean> =
        allOf(isIdleOnCommunal, editModeState.map { it == null })

    /**
     * Flow that emits a boolean if any portion of the communal UI is visible at all.
     *
     * This flow will be true during any transition and when idle on the communal scene.
     */
    val isCommunalVisible: StateFlow<Boolean> =
        transitionState
            .map {
                if (SceneContainerFlag.isEnabled)
                    it is ObservableTransitionState.Idle && it.currentScene == Scenes.Communal ||
                        (it is ObservableTransitionState.Transition &&
                            (it.fromContent == Scenes.Communal || it.toContent == Scenes.Communal))
                else
                    !(it is ObservableTransitionState.Idle &&
                        it.currentScene == CommunalScenes.Blank)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    private companion object {
        const val TAG = "CommunalSceneInteractor"
    }
}
