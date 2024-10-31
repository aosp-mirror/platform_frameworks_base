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

package com.android.compose.animation.scene

import androidx.compose.runtime.snapshotFlow
import com.android.compose.animation.scene.content.state.TransitionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * A scene transition state.
 *
 * This models the same thing as [TransitionState], with the following distinctions:
 * 1. [TransitionState] values are backed by the Snapshot system (Compose State objects) and can be
 *    used by callers tracking State reads, for instance in Compose code during the composition,
 *    layout or Compose drawing phases.
 * 2. [ObservableTransitionState] values are backed by Kotlin [Flow]s and can be collected by
 *    non-Compose code to observe value changes.
 */
sealed interface ObservableTransitionState {
    /**
     * The current effective scene. If a new transition was triggered, it would start from this
     * scene.
     */
    fun currentScene(): Flow<SceneKey> {
        return when (this) {
            is Idle -> flowOf(currentScene)
            is Transition.ChangeScene -> currentScene
            is Transition.ShowOrHideOverlay -> flowOf(currentScene)
            is Transition.ReplaceOverlay -> flowOf(currentScene)
        }
    }

    /** The current overlays. */
    fun currentOverlays(): Flow<Set<OverlayKey>> {
        return when (this) {
            is Idle -> flowOf(currentOverlays)
            is Transition.ChangeScene -> flowOf(currentOverlays)
            is Transition.OverlayTransition -> currentOverlays
        }
    }

    /** No transition/animation is currently running. */
    data class Idle
    @JvmOverloads
    constructor(val currentScene: SceneKey, val currentOverlays: Set<OverlayKey> = emptySet()) :
        ObservableTransitionState

    /** There is a transition animating between two scenes. */
    sealed class Transition(
        val fromContent: ContentKey,
        val toContent: ContentKey,
        val progress: Flow<Float>,

        /**
         * Whether the transition was originally triggered by user input rather than being
         * programmatic. If this value is initially true, it will remain true until the transition
         * fully completes, even if the user input that triggered the transition has ended. Any
         * sub-transitions launched by this one will inherit this value. For example, if the user
         * drags a pointer but does not exceed the threshold required to transition to another
         * scene, this value will remain true after the pointer is no longer touching the screen and
         * will be true in any transition created to animate back to the original position.
         */
        val isInitiatedByUserInput: Boolean,

        /**
         * Whether user input is currently driving the transition. For example, if a user is
         * dragging a pointer, this emits true. Once they lift their finger, this emits false while
         * the transition completes/settles.
         */
        val isUserInputOngoing: Flow<Boolean>,

        /** Current progress of the preview part of the transition */
        val previewProgress: Flow<Float>,

        /** Whether the transition is currently in the preview stage or not */
        val isInPreviewStage: Flow<Boolean>,
    ) : ObservableTransitionState {
        override fun toString(): String =
            """Transition
                |(from=$fromContent,
                | to=$toContent,
                | isInitiatedByUserInput=$isInitiatedByUserInput,
                | isUserInputOngoing=$isUserInputOngoing
                |)"""
                .trimMargin()

        /** A transition animating between [fromScene] and [toScene]. */
        class ChangeScene(
            val fromScene: SceneKey,
            val toScene: SceneKey,
            val currentScene: Flow<SceneKey>,
            val currentOverlays: Set<OverlayKey>,
            progress: Flow<Float>,
            isInitiatedByUserInput: Boolean,
            isUserInputOngoing: Flow<Boolean>,
            previewProgress: Flow<Float>,
            isInPreviewStage: Flow<Boolean>,
        ) :
            Transition(
                fromScene,
                toScene,
                progress,
                isInitiatedByUserInput,
                isUserInputOngoing,
                previewProgress,
                isInPreviewStage,
            )

        /**
         * A transition that is animating one or more overlays and for which [currentOverlays] will
         * change over the course of the transition.
         */
        sealed class OverlayTransition(
            fromContent: ContentKey,
            toContent: ContentKey,
            val currentScene: SceneKey,
            val currentOverlays: Flow<Set<OverlayKey>>,
            progress: Flow<Float>,
            isInitiatedByUserInput: Boolean,
            isUserInputOngoing: Flow<Boolean>,
            previewProgress: Flow<Float>,
            isInPreviewStage: Flow<Boolean>,
        ) :
            Transition(
                fromContent,
                toContent,
                progress,
                isInitiatedByUserInput,
                isUserInputOngoing,
                previewProgress,
                isInPreviewStage,
            )

        /** The [overlay] is either showing from [currentScene] or hiding into [currentScene]. */
        class ShowOrHideOverlay(
            val overlay: OverlayKey,
            fromContent: ContentKey,
            toContent: ContentKey,
            currentScene: SceneKey,
            currentOverlays: Flow<Set<OverlayKey>>,
            progress: Flow<Float>,
            isInitiatedByUserInput: Boolean,
            isUserInputOngoing: Flow<Boolean>,
            previewProgress: Flow<Float>,
            isInPreviewStage: Flow<Boolean>,
        ) :
            OverlayTransition(
                fromContent,
                toContent,
                currentScene,
                currentOverlays,
                progress,
                isInitiatedByUserInput,
                isUserInputOngoing,
                previewProgress,
                isInPreviewStage,
            )

        /** We are transitioning from [fromOverlay] to [toOverlay]. */
        class ReplaceOverlay(
            val fromOverlay: OverlayKey,
            val toOverlay: OverlayKey,
            currentScene: SceneKey,
            currentOverlays: Flow<Set<OverlayKey>>,
            progress: Flow<Float>,
            isInitiatedByUserInput: Boolean,
            isUserInputOngoing: Flow<Boolean>,
            previewProgress: Flow<Float>,
            isInPreviewStage: Flow<Boolean>,
        ) :
            OverlayTransition(
                fromOverlay,
                toOverlay,
                currentScene,
                currentOverlays,
                progress,
                isInitiatedByUserInput,
                isUserInputOngoing,
                previewProgress,
                isInPreviewStage,
            )

        companion object {
            operator fun invoke(
                fromScene: SceneKey,
                toScene: SceneKey,
                currentScene: Flow<SceneKey>,
                progress: Flow<Float>,
                isInitiatedByUserInput: Boolean,
                isUserInputOngoing: Flow<Boolean>,
                previewProgress: Flow<Float> = flowOf(0f),
                isInPreviewStage: Flow<Boolean> = flowOf(false),
                currentOverlays: Set<OverlayKey> = emptySet(),
            ): ChangeScene {
                return ChangeScene(
                    fromScene,
                    toScene,
                    currentScene,
                    currentOverlays,
                    progress,
                    isInitiatedByUserInput,
                    isUserInputOngoing,
                    previewProgress,
                    isInPreviewStage,
                )
            }
        }
    }

    fun isIdle(scene: SceneKey? = null): Boolean {
        return this is Idle && (scene == null || this.currentScene == scene)
    }

    fun isTransitioning(from: ContentKey? = null, to: ContentKey? = null): Boolean {
        return this is Transition &&
            (from == null || this.fromContent == from) &&
            (to == null || this.toContent == to)
    }

    /** Whether we are transitioning from [content] to [other], or from [other] to [content]. */
    fun isTransitioningBetween(content: ContentKey, other: ContentKey): Boolean {
        return isTransitioning(from = content, to = other) ||
            isTransitioning(from = other, to = content)
    }

    /** Whether we are transitioning from or to [content]. */
    fun isTransitioningFromOrTo(content: ContentKey): Boolean {
        return isTransitioning(from = content) || isTransitioning(to = content)
    }
}

/**
 * The current [ObservableTransitionState]. This models the same thing as
 * [SceneTransitionLayoutState.transitionState], except that it is backed by Flows and can be used
 * by non-Compose code to observe state changes.
 */
fun SceneTransitionLayoutState.observableTransitionState(): Flow<ObservableTransitionState> {
    return snapshotFlow {
            when (val state = transitionState) {
                is TransitionState.Idle ->
                    ObservableTransitionState.Idle(state.currentScene, state.currentOverlays)
                is TransitionState.Transition.ChangeScene -> {
                    ObservableTransitionState.Transition.ChangeScene(
                        fromScene = state.fromScene,
                        toScene = state.toScene,
                        currentScene = snapshotFlow { state.currentScene },
                        currentOverlays = state.currentOverlays,
                        progress = snapshotFlow { state.progress },
                        isInitiatedByUserInput = state.isInitiatedByUserInput,
                        isUserInputOngoing = snapshotFlow { state.isUserInputOngoing },
                        previewProgress = snapshotFlow { state.previewProgress },
                        isInPreviewStage = snapshotFlow { state.isInPreviewStage },
                    )
                }
                is TransitionState.Transition.ShowOrHideOverlay -> {
                    check(state.fromOrToScene == state.currentScene)
                    ObservableTransitionState.Transition.ShowOrHideOverlay(
                        overlay = state.overlay,
                        fromContent = state.fromContent,
                        toContent = state.toContent,
                        currentScene = state.currentScene,
                        currentOverlays = snapshotFlow { state.currentOverlays },
                        progress = snapshotFlow { state.progress },
                        isInitiatedByUserInput = state.isInitiatedByUserInput,
                        isUserInputOngoing = snapshotFlow { state.isUserInputOngoing },
                        previewProgress = snapshotFlow { state.previewProgress },
                        isInPreviewStage = snapshotFlow { state.isInPreviewStage },
                    )
                }
                is TransitionState.Transition.ReplaceOverlay -> {
                    ObservableTransitionState.Transition.ReplaceOverlay(
                        fromOverlay = state.fromOverlay,
                        toOverlay = state.toOverlay,
                        currentScene = state.currentScene,
                        currentOverlays = snapshotFlow { state.currentOverlays },
                        progress = snapshotFlow { state.progress },
                        isInitiatedByUserInput = state.isInitiatedByUserInput,
                        isUserInputOngoing = snapshotFlow { state.isUserInputOngoing },
                        previewProgress = snapshotFlow { state.previewProgress },
                        isInPreviewStage = snapshotFlow { state.isInPreviewStage },
                    )
                }
            }
        }
        .distinctUntilChanged()
}
