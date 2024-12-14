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

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.systemui.CoreStartable
import com.android.systemui.Flags.communalSceneKtfRefactor
import com.android.systemui.communal.data.repository.CommunalSceneTransitionRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.InternalKeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.pairwise
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * This class listens to [SceneTransitionLayout] transitions and manages keyguard transition
 * framework (KTF) states accordingly for communal states.
 *
 * There are a few rules:
 * - There are only 2 communal scenes: [CommunalScenes.Communal] and [CommunalScenes.Blank]
 * - When scene framework is on [CommunalScenes.Blank], KTF is allowed to change its scenes freely
 * - When scene framework is on [CommunalScenes.Communal], KTF is locked into
 *   [KeyguardState.GLANCEABLE_HUB]
 */
@SysUISingleton
class CommunalSceneTransitionInteractor
@Inject
constructor(
    val transitionInteractor: KeyguardTransitionInteractor,
    val internalTransitionInteractor: InternalKeyguardTransitionInteractor,
    private val settingsInteractor: CommunalSettingsInteractor,
    @Application private val applicationScope: CoroutineScope,
    private val sceneInteractor: CommunalSceneInteractor,
    private val repository: CommunalSceneTransitionRepository,
    keyguardInteractor: KeyguardInteractor,
) : CoreStartable, CommunalSceneInteractor.OnSceneAboutToChangeListener {

    private var currentTransitionId: UUID? = null
    private var progressJob: Job? = null

    private val currentToState: KeyguardState
        get() = internalTransitionInteractor.currentTransitionInfoInternal.value.to

    /**
     * The next keyguard state to trigger when exiting [CommunalScenes.Communal]. This is only used
     * if the state is changed by user gesture or not explicitly defined by the caller when changing
     * scenes programmatically.
     *
     * This is needed because we do not always want to exit back to the KTF state we came from. For
     * example, when going from HUB (Communal) -> OCCLUDED (Blank) -> HUB (Communal) and then
     * closing the hub via gesture, we don't want to go back to OCCLUDED but instead either go to
     * DREAM or LOCKSCREEN depending on if there is a dream showing.
     */
    private val nextKeyguardStateInternal =
        combine(
                // Don't use delayed dreaming signal as otherwise we might go to occluded or lock
                // screen when closing hub if dream just started under the hub.
                keyguardInteractor.isDreamingWithOverlay,
                keyguardInteractor.isKeyguardOccluded,
                keyguardInteractor.isKeyguardGoingAway,
                keyguardInteractor.isKeyguardShowing,
            ) { dreaming, occluded, keyguardGoingAway, keyguardShowing ->
                if (keyguardGoingAway) {
                    KeyguardState.GONE
                } else if (occluded && !dreaming) {
                    KeyguardState.OCCLUDED
                } else if (dreaming) {
                    KeyguardState.DREAMING
                } else if (keyguardShowing) {
                    KeyguardState.LOCKSCREEN
                } else {
                    null
                }
            }
            .filterNotNull()

    private val nextKeyguardState: StateFlow<KeyguardState> =
        combine(
                repository.nextLockscreenTargetState,
                nextKeyguardStateInternal.onStart { emit(KeyguardState.LOCKSCREEN) },
            ) { override, nextState ->
                override ?: nextState
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = KeyguardState.LOCKSCREEN,
            )

    override fun start() {
        if (
            communalSceneKtfRefactor() &&
                settingsInteractor.isCommunalFlagEnabled() &&
                !SceneContainerFlag.isEnabled
        ) {
            sceneInteractor.registerSceneStateProcessor(this)
            listenForSceneTransitionProgress()
        }
    }

    /**
     * Called when the scene is programmatically changed, allowing callers to specify which KTF
     * state should be set when transitioning to [CommunalScenes.Blank]
     */
    override fun onSceneAboutToChange(toScene: SceneKey, keyguardState: KeyguardState?) {
        if (toScene != CommunalScenes.Blank || keyguardState == null) return
        repository.nextLockscreenTargetState.value = keyguardState
    }

    /** Monitors [SceneTransitionLayout] state and updates KTF state accordingly. */
    private fun listenForSceneTransitionProgress() {
        applicationScope.launch {
            sceneInteractor.transitionState
                .pairwise(ObservableTransitionState.Idle(CommunalScenes.Blank))
                .collect { (prevTransition, transition) ->
                    when (transition) {
                        is ObservableTransitionState.Idle -> handleIdle(prevTransition, transition)
                        is ObservableTransitionState.Transition ->
                            handleTransition(prevTransition, transition)
                    }
                }
        }
    }

    private suspend fun handleIdle(
        prevTransition: ObservableTransitionState,
        idle: ObservableTransitionState.Idle,
    ) {
        if (
            prevTransition is ObservableTransitionState.Transition &&
                currentTransitionId != null &&
                idle.currentScene == prevTransition.toContent
        ) {
            finishCurrentTransition()
        } else {
            // We may receive an Idle event without a corresponding Transition
            // event, such as when snapping to a scene without an animation.
            val targetState =
                if (idle.currentScene == CommunalScenes.Communal) {
                    KeyguardState.GLANCEABLE_HUB
                } else if (currentToState == KeyguardState.GLANCEABLE_HUB) {
                    nextKeyguardState.value
                } else {
                    // Do nothing as we are no longer in the hub state.
                    return
                }
            transitionKtfTo(targetState)
            repository.nextLockscreenTargetState.value = null
        }
    }

    private suspend fun finishCurrentTransition() {
        if (currentTransitionId == null) return
        internalTransitionInteractor.updateTransition(
            currentTransitionId!!,
            1f,
            TransitionState.FINISHED,
        )
        resetTransitionData()
    }

    private suspend fun finishReversedTransitionTo(state: KeyguardState) {
        val newTransition =
            TransitionInfo(
                ownerName = this::class.java.simpleName,
                from = internalTransitionInteractor.currentTransitionInfoInternal.value.to,
                to = state,
                animator = null,
                modeOnCanceled = TransitionModeOnCanceled.REVERSE,
            )
        currentTransitionId = internalTransitionInteractor.startTransition(newTransition)
        internalTransitionInteractor.updateTransition(
            currentTransitionId!!,
            1f,
            TransitionState.FINISHED,
        )
        resetTransitionData()
    }

    private fun resetTransitionData() {
        progressJob?.cancel()
        progressJob = null
        currentTransitionId = null
    }

    private suspend fun handleTransition(
        prevTransition: ObservableTransitionState,
        transition: ObservableTransitionState.Transition,
    ) {
        if (
            prevTransition.isTransitioning(from = transition.fromContent, to = transition.toContent)
        ) {
            // This is a new transition, but exactly the same as the previous state. Skip resetting
            // KTF for this case and just collect the new progress instead.
            collectProgress(transition)
        } else if (transition.toContent == CommunalScenes.Communal) {
            if (currentToState == KeyguardState.GLANCEABLE_HUB) {
                transitionKtfTo(transitionInteractor.startedKeyguardTransitionStep.value.from)
            }
            startTransitionToGlanceableHub()
            collectProgress(transition)
        } else if (transition.toContent == CommunalScenes.Blank) {
            // Another transition started before this one is completed. Transition to the
            // GLANCEABLE_HUB state so that we can properly transition away from it.
            transitionKtfTo(KeyguardState.GLANCEABLE_HUB)
            startTransitionFromGlanceableHub()
            collectProgress(transition)
        }
    }

    private suspend fun transitionKtfTo(state: KeyguardState) {
        val currentTransition = transitionInteractor.transitionState.value
        if (currentTransition.isFinishedIn(state)) {
            // This is already the state we want to be in
            resetTransitionData()
        } else if (currentTransition.isTransitioning(to = state)) {
            finishCurrentTransition()
        } else {
            finishReversedTransitionTo(state)
        }
    }

    private fun collectProgress(transition: ObservableTransitionState.Transition) {
        progressJob?.cancel()
        progressJob = applicationScope.launch { transition.progress.collect { updateProgress(it) } }
    }

    private suspend fun startTransitionFromGlanceableHub() {
        val newTransition =
            TransitionInfo(
                ownerName = this::class.java.simpleName,
                from = KeyguardState.GLANCEABLE_HUB,
                to = nextKeyguardState.value,
                animator = null,
                modeOnCanceled = TransitionModeOnCanceled.RESET,
            )
        repository.nextLockscreenTargetState.value = null
        startTransition(newTransition)
    }

    private suspend fun startTransitionToGlanceableHub() {
        val currentState = internalTransitionInteractor.currentTransitionInfoInternal.value.to
        val newTransition =
            TransitionInfo(
                ownerName = this::class.java.simpleName,
                from = currentState,
                to = KeyguardState.GLANCEABLE_HUB,
                animator = null,
                modeOnCanceled = TransitionModeOnCanceled.RESET,
            )
        startTransition(newTransition)
    }

    private suspend fun startTransition(transitionInfo: TransitionInfo) {
        if (currentTransitionId != null) {
            resetTransitionData()
        }
        currentTransitionId = internalTransitionInteractor.startTransition(transitionInfo)
    }

    private suspend fun updateProgress(progress: Float) {
        if (currentTransitionId == null) return
        internalTransitionInteractor.updateTransition(
            currentTransitionId!!,
            progress.coerceIn(0f, 1f),
            TransitionState.RUNNING,
        )
    }
}
