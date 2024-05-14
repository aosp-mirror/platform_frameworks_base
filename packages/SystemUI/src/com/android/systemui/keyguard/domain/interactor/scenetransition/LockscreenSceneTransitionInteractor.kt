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

package com.android.systemui.keyguard.domain.interactor.scenetransition

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.LockscreenSceneTransitionRepository
import com.android.systemui.keyguard.data.repository.LockscreenSceneTransitionRepository.Companion.DEFAULT_STATE
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.UNDEFINED
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.pairwise
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * This class listens to scene framework scene transitions and manages keyguard transition framework
 * (KTF) states accordingly.
 *
 * There are a few rules:
 * - When scene framework is on a scene outside of Lockscreen, then KTF is in state UNDEFINED
 * - When scene framework is on Lockscreen, KTF is allowed to change its scenes freely
 * - When scene framework is transitioning away from Lockscreen, then KTF transitions to UNDEFINED
 *   and shares its progress.
 * - When scene framework is transitioning to Lockscreen, then KTF starts a transition to LOCKSCREEN
 *   but it is allowed to interrupt this transition and transition to other internal KTF states
 *
 * There are a few notable differences between SceneTransitionLayout (STL) and KTF that require
 * special treatment when synchronizing both state machines.
 * - STL does not emit cancelations as KTF does
 * - Both STL and KTF require state continuity, though the rules from where starting the next
 *   transition is allowed is different on each side:
 *     - STL has a concept of "currentScene" which can be chosen to be either A or B in a A -> B
 *       transition. The currentScene determines which transition can be started next. In KTF the
 *       currentScene is always the `to` state. Which means transitions can only be started from B.
 *       This also holds true when A -> B was canceled: the next transition needs to start from B.
 *     - KTF can not settle back in its from scene, instead it needs to cancel and start a reversed
 *       transition.
 */
@SysUISingleton
class LockscreenSceneTransitionInteractor
@Inject
constructor(
    val transitionInteractor: KeyguardTransitionInteractor,
    @Application private val applicationScope: CoroutineScope,
    private val sceneInteractor: SceneInteractor,
    private val repository: LockscreenSceneTransitionRepository,
) : CoreStartable, SceneInteractor.OnSceneAboutToChangeListener {

    private var currentTransitionId: UUID? = null
    private var progressJob: Job? = null

    override fun start() {
        sceneInteractor.registerSceneStateProcessor(this)
        listenForSceneTransitionProgress()
    }

    override fun onSceneAboutToChange(toScene: SceneKey, sceneState: Any?) {
        if (toScene != Scenes.Lockscreen || sceneState == null) return
        if (sceneState !is KeyguardState) {
            throw IllegalArgumentException("Lockscreen sceneState needs to be a KeyguardState.")
        }
        repository.nextLockscreenTargetState.value = sceneState
    }

    private fun listenForSceneTransitionProgress() {
        applicationScope.launch {
            sceneInteractor.transitionState
                .pairwise(ObservableTransitionState.Idle(Scenes.Lockscreen))
                .collect { (prevTransition, transition) ->
                    when (transition) {
                        is ObservableTransitionState.Idle -> handleIdle(prevTransition, transition)
                        is ObservableTransitionState.Transition -> handleTransition(transition)
                    }
                }
        }
    }

    private suspend fun handleIdle(
        prevTransition: ObservableTransitionState,
        idle: ObservableTransitionState.Idle
    ) {
        if (currentTransitionId == null) return
        if (prevTransition !is ObservableTransitionState.Transition) return

        if (idle.currentScene == prevTransition.toScene) {
            finishCurrentTransition()
        } else {
            val targetState =
                if (idle.currentScene == Scenes.Lockscreen) {
                    transitionInteractor.getStartedFromState()
                } else {
                    UNDEFINED
                }
            finishReversedTransitionTo(targetState)
        }
    }

    private fun finishCurrentTransition() {
        transitionInteractor.updateTransition(currentTransitionId!!, 1f, FINISHED)
        resetTransitionData()
    }

    private suspend fun finishReversedTransitionTo(state: KeyguardState) {
        val newTransition =
            TransitionInfo(
                ownerName = this::class.java.simpleName,
                from = transitionInteractor.getStartedState(),
                to = state,
                animator = null,
                modeOnCanceled = TransitionModeOnCanceled.REVERSE
            )
        currentTransitionId = transitionInteractor.startTransition(newTransition)
        transitionInteractor.updateTransition(currentTransitionId!!, 1f, FINISHED)
        resetTransitionData()
    }

    private fun resetTransitionData() {
        progressJob?.cancel()
        progressJob = null
        currentTransitionId = null
    }

    private suspend fun handleTransition(transition: ObservableTransitionState.Transition) {
        if (transition.fromScene == Scenes.Lockscreen) {
            if (currentTransitionId != null) {
                val currentToState = transitionInteractor.getStartedState()
                if (currentToState == UNDEFINED) {
                    transitionKtfTo(transitionInteractor.getStartedFromState())
                }
            }
            startTransitionFromLockscreen()
            collectProgress(transition)
        } else if (transition.toScene == Scenes.Lockscreen) {
            if (currentTransitionId != null) {
                transitionKtfTo(UNDEFINED)
            }
            startTransitionToLockscreen()
            collectProgress(transition)
        } else {
            transitionKtfTo(UNDEFINED)
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

    private suspend fun startTransitionToLockscreen() {
        val newTransition =
            TransitionInfo(
                ownerName = this::class.java.simpleName,
                from = UNDEFINED,
                to = repository.nextLockscreenTargetState.value,
                animator = null,
                modeOnCanceled = TransitionModeOnCanceled.RESET
            )
        repository.nextLockscreenTargetState.value = DEFAULT_STATE
        startTransition(newTransition)
    }

    private suspend fun startTransitionFromLockscreen() {
        val currentState = transitionInteractor.getStartedState()
        val newTransition =
            TransitionInfo(
                ownerName = this::class.java.simpleName,
                from = currentState,
                to = UNDEFINED,
                animator = null,
                modeOnCanceled = TransitionModeOnCanceled.RESET
            )
        startTransition(newTransition)
    }

    private suspend fun startTransition(transitionInfo: TransitionInfo) {
        if (currentTransitionId != null) {
            resetTransitionData()
        }
        currentTransitionId = transitionInteractor.startTransition(transitionInfo)
    }

    private fun updateProgress(progress: Float) {
        if (currentTransitionId == null) return
        transitionInteractor.updateTransition(
            currentTransitionId!!,
            progress.coerceIn(0f, 1f),
            RUNNING
        )
    }
}
