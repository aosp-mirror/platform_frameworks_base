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

package com.android.systemui.scene.ui.viewmodel

import android.view.MotionEvent
import androidx.compose.runtime.getValue
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.classifier.Classifier
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.lifecycle.SysUiViewModel
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.Scenes
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Models UI state for the scene container. */
class SceneContainerViewModel
@AssistedInject
constructor(
    private val sceneInteractor: SceneInteractor,
    private val falsingInteractor: FalsingInteractor,
    private val powerInteractor: PowerInteractor,
    private val logger: SceneLogger,
    @Assisted private val motionEventHandlerReceiver: (MotionEventHandler?) -> Unit,
) : SysUiViewModel, ExclusiveActivatable() {
    /**
     * Keys of all scenes in the container.
     *
     * The scenes will be sorted in z-order such that the last one is the one that should be
     * rendered on top of all previous ones.
     */
    val allSceneKeys: List<SceneKey> = sceneInteractor.allSceneKeys()

    /** The scene that should be rendered. */
    val currentScene: StateFlow<SceneKey> = sceneInteractor.currentScene

    private val hydrator = Hydrator()

    /** Whether the container is visible. */
    val isVisible: Boolean by hydrator.hydratedStateOf(sceneInteractor.isVisible)

    override suspend fun onActivated(): Nothing {
        try {
            // Sends a MotionEventHandler to the owner of the view-model so they can report
            // MotionEvents into the view-model.
            motionEventHandlerReceiver(
                object : MotionEventHandler {
                    override fun onMotionEvent(motionEvent: MotionEvent) {
                        this@SceneContainerViewModel.onMotionEvent(motionEvent)
                    }

                    override fun onMotionEventComplete() {
                        this@SceneContainerViewModel.onMotionEventComplete()
                    }
                }
            )

            hydrator.activate()
        } finally {
            // Clears the previously-sent MotionEventHandler so the owner of the view-model releases
            // their reference to it.
            motionEventHandlerReceiver(null)
        }
    }

    /**
     * Binds the given flow so the system remembers it.
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        sceneInteractor.setTransitionState(transitionState)
    }

    /**
     * Notifies that a [MotionEvent] is first seen at the top of the scene container UI.
     *
     * Call this before the [MotionEvent] starts to propagate through the UI hierarchy.
     */
    fun onMotionEvent(event: MotionEvent) {
        powerInteractor.onUserTouch()
        falsingInteractor.onTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            sceneInteractor.onUserInteractionFinished()
        }
    }

    /**
     * Notifies that a [MotionEvent] that was previously sent to [onMotionEvent] has passed through
     * the scene container UI.
     *
     * Call this after the [MotionEvent] propagates completely through the UI hierarchy.
     */
    fun onMotionEventComplete() {
        falsingInteractor.onMotionEventComplete()
    }

    /**
     * Returns `true` if a change to [toScene] is currently allowed; `false` otherwise.
     *
     * This is invoked only for user-initiated transitions. The goal is to check with the falsing
     * system whether the change from the current scene to the given scene should be rejected due to
     * it being a false touch.
     */
    fun canChangeScene(toScene: SceneKey): Boolean {
        val interactionTypeOrNull =
            when (toScene) {
                Scenes.Bouncer -> Classifier.BOUNCER_UNLOCK
                Scenes.Gone -> Classifier.UNLOCK
                Scenes.NotificationsShade -> Classifier.NOTIFICATION_DRAG_DOWN
                Scenes.Shade -> Classifier.NOTIFICATION_DRAG_DOWN
                Scenes.QuickSettings -> Classifier.QUICK_SETTINGS
                Scenes.QuickSettingsShade -> Classifier.QUICK_SETTINGS
                else -> null
            }

        val fromScene = currentScene.value
        val isAllowed =
            interactionTypeOrNull?.let { interactionType ->
                // It's important that the falsing system is always queried, even if no enforcement
                // will occur. This helps build up the right signal in the system.
                val isFalseTouch = falsingInteractor.isFalseTouch(interactionType)

                // Only enforce falsing if moving from the lockscreen scene to a new scene.
                val fromLockscreenScene = fromScene == Scenes.Lockscreen

                !fromLockscreenScene || !isFalseTouch
            } ?: true

        if (isAllowed) {
            // A scene change is guaranteed; log it.
            logger.logSceneChanged(
                from = fromScene,
                to = toScene,
                reason = "user interaction",
                isInstant = false,
            )
        }
        return isAllowed
    }

    /**
     * Immediately resolves any scene families present in [actionResultMap] to their current
     * resolution target.
     */
    fun resolveSceneFamilies(
        actionResultMap: Map<UserAction, UserActionResult>,
    ): Map<UserAction, UserActionResult> {
        return actionResultMap.mapValues { (_, actionResult) ->
            sceneInteractor.resolveSceneFamilyOrNull(actionResult.toScene)?.value?.let {
                actionResult.copy(toScene = it)
            } ?: actionResult
        }
    }

    /** Defines interface for classes that can handle externally-reported [MotionEvent]s. */
    interface MotionEventHandler {
        /** Notifies that a [MotionEvent] has occurred. */
        fun onMotionEvent(motionEvent: MotionEvent)

        /**
         * Notifies that the previous [MotionEvent] reported by [onMotionEvent] has finished
         * processing.
         */
        fun onMotionEventComplete()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            motionEventHandlerReceiver: (MotionEventHandler?) -> Unit,
        ): SceneContainerViewModel
    }
}
