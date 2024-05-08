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

package com.android.systemui.scene.domain.interactor

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.pairwiseBy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Generic business logic and app state accessors for the scene framework.
 *
 * Note that this class should not depend on state or logic of other modules or features. Instead,
 * other feature modules should depend on and call into this class when their parts of the
 * application state change.
 */
@SysUISingleton
class SceneInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: SceneContainerRepository,
    private val logger: SceneLogger,
    private val deviceUnlockedInteractor: DeviceUnlockedInteractor,
) {

    /**
     * The current scene.
     *
     * Note that during a transition between scenes, more than one scene might be rendered but only
     * one is considered the committed/current scene.
     */
    val currentScene: StateFlow<SceneKey> =
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
                started = SharingStarted.WhileSubscribed(),
                initialValue = repository.currentScene.value,
            )

    /**
     * The current state of the transition.
     *
     * Consumers should use this state to know:
     * 1. Whether there is an ongoing transition or if the system is at rest.
     * 2. When transitioning, which scenes are being transitioned between.
     * 3. When transitioning, what the progress of the transition is.
     */
    val transitionState: StateFlow<ObservableTransitionState> = repository.transitionState

    /**
     * The key of the scene that the UI is currently transitioning to or `null` if there is no
     * active transition at the moment.
     *
     * This is a convenience wrapper around [transitionState], meant for flow-challenged consumers
     * like Java code.
     */
    val transitioningTo: StateFlow<SceneKey?> =
        transitionState
            .map { state -> (state as? ObservableTransitionState.Transition)?.toScene }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    /**
     * Whether user input is ongoing for the current transition. For example, if the user is swiping
     * their finger to transition between scenes, this value will be true while their finger is on
     * the screen, then false for the rest of the transition.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isTransitionUserInputOngoing: StateFlow<Boolean> =
        transitionState
            .flatMapLatest {
                when (it) {
                    is ObservableTransitionState.Transition -> it.isUserInputOngoing
                    is ObservableTransitionState.Idle -> flowOf(false)
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false
            )

    /** Whether the scene container is visible. */
    val isVisible: StateFlow<Boolean> =
        combine(
                repository.isVisible,
                repository.isRemoteUserInteractionOngoing,
            ) { isVisible, isRemoteUserInteractionOngoing ->
                isVisibleInternal(
                    raw = isVisible,
                    isRemoteUserInteractionOngoing = isRemoteUserInteractionOngoing,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = isVisibleInternal()
            )

    /**
     * Returns the keys of all scenes in the container.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    fun allSceneKeys(): List<SceneKey> {
        return repository.allSceneKeys()
    }

    /**
     * Requests a scene change to the given scene.
     *
     * The change is animated. Therefore, it will be some time before the UI will switch to the
     * desired scene. Once enough of the transition has occurred, the [currentScene] will become
     * [toScene] (unless the transition is canceled by user action or another call to this method).
     */
    @JvmOverloads
    fun changeScene(
        toScene: SceneKey,
        loggingReason: String,
        transitionKey: TransitionKey? = null,
    ) {
        val currentSceneKey = currentScene.value
        if (
            !validateSceneChange(
                from = currentSceneKey,
                to = toScene,
                loggingReason = loggingReason,
            )
        ) {
            return
        }

        logger.logSceneChangeRequested(
            from = currentSceneKey,
            to = toScene,
            reason = loggingReason,
            isInstant = false,
        )

        repository.changeScene(toScene, transitionKey)
    }

    /**
     * Requests a scene change to the given scene.
     *
     * The change is instantaneous and not animated; it will be observable in the next frame and
     * there will be no transition animation.
     */
    fun snapToScene(
        toScene: SceneKey,
        loggingReason: String,
    ) {
        val currentSceneKey = currentScene.value
        if (
            !validateSceneChange(
                from = currentSceneKey,
                to = toScene,
                loggingReason = loggingReason,
            )
        ) {
            return
        }

        logger.logSceneChangeRequested(
            from = currentSceneKey,
            to = toScene,
            reason = loggingReason,
            isInstant = true,
        )

        repository.snapToScene(toScene)
    }

    /**
     * Sets the visibility of the container.
     *
     * Please do not call this from outside of the scene framework. If you are trying to force the
     * visibility to visible or invisible, prefer making changes to the existing caller of this
     * method or to upstream state used to calculate [isVisible]; for an example of the latter,
     * please see [onRemoteUserInteractionStarted] and [onUserInteractionFinished].
     */
    fun setVisible(isVisible: Boolean, loggingReason: String) {
        val wasVisible = repository.isVisible.value
        if (wasVisible == isVisible) {
            return
        }

        logger.logVisibilityChange(
            from = wasVisible,
            to = isVisible,
            reason = loggingReason,
        )
        return repository.setVisible(isVisible)
    }

    /**
     * Notifies that a remote user interaction has begun.
     *
     * This is a user interaction that originates outside of the UI of the scene container and
     * possibly outside of the System UI process itself.
     *
     * As an example, consider the dragging that can happen in the launcher that expands the shade.
     * This is a user interaction that begins remotely (as it starts in the launcher process) and is
     * then rerouted by window manager to System UI. While the user interaction definitely continues
     * within the System UI process and code, it also originates remotely.
     */
    fun onRemoteUserInteractionStarted(loggingReason: String) {
        logger.logRemoteUserInteractionStarted(loggingReason)
        repository.isRemoteUserInteractionOngoing.value = true
    }

    /**
     * Notifies that the current user interaction (internally or remotely started, see
     * [onRemoteUserInteractionStarted]) has finished.
     */
    fun onUserInteractionFinished() {
        logger.logUserInteractionFinished()
        repository.isRemoteUserInteractionOngoing.value = false
    }

    /**
     * Binds the given flow so the system remembers it.
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        repository.setTransitionState(transitionState)
    }

    private fun isVisibleInternal(
        raw: Boolean = repository.isVisible.value,
        isRemoteUserInteractionOngoing: Boolean = repository.isRemoteUserInteractionOngoing.value,
    ): Boolean {
        return raw || isRemoteUserInteractionOngoing
    }

    /**
     * Validates that the given scene change is allowed.
     *
     * Will throw a runtime exception for illegal states (for example, attempting to change to a
     * scene that's not part of the current scene framework configuration).
     *
     * @param from The current scene being transitioned away from
     * @param to The desired destination scene to transition to
     * @param loggingReason The reason why the transition is requested, for logging purposes
     * @return `true` if the scene change is valid; `false` if it shouldn't happen
     */
    private fun validateSceneChange(
        from: SceneKey,
        to: SceneKey,
        loggingReason: String,
    ): Boolean {
        if (!repository.allSceneKeys().contains(to)) {
            return false
        }

        check(to != Scenes.Gone || deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked) {
            "Cannot change to the Gone scene while the device is locked. Logging reason for scene" +
                " change was: $loggingReason"
        }

        return from != to
    }
}
