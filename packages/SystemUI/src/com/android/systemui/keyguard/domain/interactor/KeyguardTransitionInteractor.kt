/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.annotation.SuppressLint
import android.util.Log
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.shared.model.KeyguardState.UNDEFINED
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.WithPrev
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Encapsulates business-logic related to the keyguard transitions. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardTransitionInteractor
@Inject
constructor(
    @Application val scope: CoroutineScope,
    private val repository: KeyguardTransitionRepository,
    private val sceneInteractor: SceneInteractor,
) {
    private val transitionMap = mutableMapOf<Edge.StateToState, MutableSharedFlow<TransitionStep>>()

    /**
     * Numerous flows are derived from, or care directly about, the transition value in and out of a
     * single state. This prevent the redundant filters from running.
     */
    private val transitionValueCache = mutableMapOf<KeyguardState, MutableSharedFlow<Float>>()

    @SuppressLint("SharedFlowCreation")
    private fun getTransitionValueFlow(state: KeyguardState): MutableSharedFlow<Float> {
        return transitionValueCache.getOrPut(state) {
            MutableSharedFlow<Float>(
                    replay = 1,
                    extraBufferCapacity = 2,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
                .also { it.tryEmit(0f) }
        }
    }

    @Deprecated("Not performant - Use something else in this class")
    val transitions = repository.transitions

    val transitionState: StateFlow<TransitionStep> =
        transitions.stateIn(scope, SharingStarted.Eagerly, TransitionStep())

    private val sceneTransitionPair =
        sceneInteractor.transitionState
            .pairwise()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                WithPrev(
                    sceneInteractor.transitionState.value,
                    sceneInteractor.transitionState.value,
                ),
            )

    /**
     * A pair of the most recent STARTED step, and the transition step immediately preceding it. The
     * transition framework enforces that the previous step is either a CANCELED or FINISHED step,
     * and that the previous step was *to* the state the STARTED step is *from*.
     *
     * This flow can be used to access the previous step to determine whether it was CANCELED or
     * FINISHED. In the case of a CANCELED step, we can also figure out which state we were coming
     * from when we were canceled.
     */
    @SuppressLint("SharedFlowCreation")
    val startedStepWithPrecedingStep =
        repository.transitions
            .pairwise()
            .filter { it.newValue.transitionState == TransitionState.STARTED }
            .shareIn(scope, SharingStarted.Eagerly)

    init {
        // Collect non-canceled steps and emit transition values.
        scope.launch {
            repository.transitions
                .filter { it.transitionState != TransitionState.CANCELED }
                .collect { step ->
                    val value =
                        if (step.transitionState == TransitionState.FINISHED) 1f else step.value
                    getTransitionValueFlow(step.from).emit(1f - value)
                    getTransitionValueFlow(step.to).emit(value)
                }
        }

        scope.launch {
            repository.transitions.collect {
                // FROM->TO
                transitionMap[Edge.create(it.from, it.to)]?.emit(it)
                // FROM->(ANY)
                transitionMap[Edge.create(it.from, null)]?.emit(it)
                // (ANY)->TO
                transitionMap[Edge.create(null, it.to)]?.emit(it)
            }
        }

        // If a transition from state A -> B is canceled in favor of a transition from B -> C, we
        // need to ensure we emit transitionValue(A) = 0f, since no further steps will be emitted
        // where the from or to states are A. This would leave transitionValue(A) stuck at an
        // arbitrary non-zero value.
        scope.launch {
            startedStepWithPrecedingStep.collect { (prevStep, startedStep) ->
                if (
                    prevStep.transitionState == TransitionState.CANCELED &&
                        startedStep.to != prevStep.from
                ) {
                    getTransitionValueFlow(prevStep.from).emit(0f)
                } else if (prevStep.transitionState == TransitionState.RUNNING) {
                    Log.e(
                        TAG,
                        "STARTED step ($startedStep) was preceded by a RUNNING step " +
                            "($prevStep), which should never happen. Things could go badly here.",
                    )
                }
            }
        }

        // Safety: When any transition is FINISHED, ensure all other transitionValue flows other
        // than the FINISHED state are reset to a value of 0f. There have been rare but severe
        // bugs that get the device stuck in a bad state when these are not properly reset.
        scope.launch {
            repository.transitions
                .filter { it.transitionState == TransitionState.FINISHED }
                .collect {
                    for (state in KeyguardState.entries) {
                        if (state != it.to) {
                            val flow = getTransitionValueFlow(state)
                            val replayCache = flow.replayCache
                            if (!replayCache.isEmpty() && replayCache.last() != 0f) {
                                flow.emit(0f)
                            }
                        }
                    }
                }
        }
    }

    fun transition(edge: Edge, edgeWithoutSceneContainer: Edge? = null): Flow<TransitionStep> {
        return transition(
            if (SceneContainerFlag.isEnabled || edgeWithoutSceneContainer == null) {
                edge
            } else {
                edgeWithoutSceneContainer
            }
        )
    }

    /** Given an [edge], return a Flow to collect only relevant [TransitionStep]s. */
    @SuppressLint("SharedFlowCreation")
    fun transition(edge: Edge): Flow<TransitionStep> {
        edge.verifyValidKeyguardStates()
        val mappedEdge = getMappedEdge(edge)

        val flow: Flow<TransitionStep> =
            transitionMap.getOrPut(mappedEdge) {
                MutableSharedFlow(
                    extraBufferCapacity = 10,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

        return if (SceneContainerFlag.isEnabled) {
            flow.filter { step ->
                val fromScene =
                    when (edge) {
                        is Edge.StateToState -> edge.from?.mapToSceneContainerScene()
                        is Edge.StateToScene -> edge.from?.mapToSceneContainerScene()
                        is Edge.SceneToState -> edge.from
                    }

                val toScene =
                    when (edge) {
                        is Edge.StateToState -> edge.to?.mapToSceneContainerScene()
                        is Edge.StateToScene -> edge.to
                        is Edge.SceneToState -> edge.to?.mapToSceneContainerScene()
                    }

                fun SceneKey?.isLockscreenOrNull() = this == Scenes.Lockscreen || this == null

                val isTransitioningBetweenLockscreenStates =
                    fromScene.isLockscreenOrNull() && toScene.isLockscreenOrNull()
                val isTransitioningBetweenDesiredScenes =
                    sceneInteractor.transitionState.value.isTransitioning(fromScene, toScene)

                // We can't compare the terminal step with the current sceneTransition because
                // a) STL has no guarantee that it will settle in Idle() when finished/canceled
                // b) Comparing to Idle(toScene) would make any other FINISHED step settling in
                //    toScene pass as well
                val terminalStepBelongsToPreviousTransition =
                    (step.transitionState == TransitionState.FINISHED ||
                        step.transitionState == TransitionState.CANCELED) &&
                        sceneTransitionPair.value.previousValue.isTransitioning(fromScene, toScene)

                return@filter isTransitioningBetweenLockscreenStates ||
                    isTransitioningBetweenDesiredScenes ||
                    terminalStepBelongsToPreviousTransition
            }
        } else {
            flow
        }
    }

    /**
     * Converts old KTF states to UNDEFINED when [SceneContainerFlag] is enabled.
     *
     * Does nothing otherwise.
     *
     * This method should eventually be removed when new code is only written for scene container.
     * Even when all edges are ported today, there is still development on going in production that
     * might utilize old states.
     */
    private fun getMappedEdge(edge: Edge): Edge.StateToState {
        if (!SceneContainerFlag.isEnabled) return edge as Edge.StateToState
        return when (edge) {
            is Edge.StateToState ->
                Edge.create(
                    from = edge.from?.mapToSceneContainerState(),
                    to = edge.to?.mapToSceneContainerState(),
                )
            is Edge.SceneToState -> Edge.create(UNDEFINED, edge.to)
            is Edge.StateToScene -> Edge.create(edge.from, UNDEFINED)
        }
    }

    fun transitionValue(
        scene: SceneKey? = null,
        stateWithoutSceneContainer: KeyguardState,
    ): Flow<Float> {
        return if (SceneContainerFlag.isEnabled && scene != null) {
            sceneInteractor.transitionProgress(scene)
        } else {
            transitionValue(stateWithoutSceneContainer)
        }
    }

    /**
     * The amount of transition into or out of the given [KeyguardState].
     *
     * The value will be `0` (or close to `0`, due to float point arithmetic) if not in this step or
     * `1` when fully in the given state.
     */
    fun transitionValue(state: KeyguardState): Flow<Float> {
        if (SceneContainerFlag.isEnabled && state != state.mapToSceneContainerState()) {
            Log.e(TAG, "SceneContainer is enabled but a deprecated state $state is used.")
            return transitionValue(state.mapToSceneContainerScene()!!, state)
        }
        return getTransitionValueFlow(state)
    }

    /** The last [TransitionStep] with a [TransitionState] of STARTED */
    val startedKeyguardTransitionStep: StateFlow<TransitionStep> =
        repository.transitions
            .filter { step -> step.transitionState == TransitionState.STARTED }
            .stateIn(scope, SharingStarted.Eagerly, TransitionStep())

    /**
     * The [KeyguardState] we're currently in.
     *
     * If we're not in transition, this is simply the [finishedKeyguardState]. If we're in
     * transition, this is the state we're transitioning *from*.
     *
     * Absent CANCELED transitions, [currentKeyguardState] and [finishedKeyguardState] are always
     * identical - if a transition FINISHES in a given state, the subsequent state we START a
     * transition *from* would always be that same previously FINISHED state.
     *
     * However, if a transition is CANCELED, the next transition will START from a state we never
     * FINISHED in. For example, if we transition from GONE -> DOZING, but CANCEL that transition in
     * favor of DOZING -> LOCKSCREEN, we've STARTED a transition *from* DOZING despite never
     * FINISHING in DOZING. Thus, the current state will be DOZING but the FINISHED state will still
     * be GONE.
     *
     * In this example, if there was DOZING-related state that needs to be set up in order to
     * properly render a DOZING -> LOCKSCREEN transition, it would never be set up if we were
     * listening for [finishedKeyguardState] to emit DOZING. However, [currentKeyguardState] would
     * emit DOZING immediately upon STARTING DOZING -> LOCKSCREEN, allowing us to set up the state.
     *
     * Whether you want to use [currentKeyguardState] or [finishedKeyguardState] depends on your
     * specific use case and how you want to handle cancellations. In general, if you're dealing
     * with state/UI present across multiple [KeyguardState]s, you probably want
     * [currentKeyguardState]. If you're dealing with state/UI encapsulated within a single state,
     * you likely want [finishedKeyguardState].
     *
     * As an example, let's say you want to animate in a message on the lockscreen UI after waking
     * up, and that TextView is not involved in animations between states. You'd want to collect
     * [finishedKeyguardState], so you'll only animate it in once we're settled on the lockscreen.
     * If you use [currentKeyguardState] in this case, a DOZING -> LOCKSCREEN transition that is
     * interrupted by a LOCKSCREEN -> GONE transition would cause the message to become visible
     * immediately upon LOCKSCREEN -> GONE STARTING, as the current state would become LOCKSCREEN in
     * that case. That's likely not what you want.
     *
     * On the other hand, let's say you're animating the smartspace from alpha 0f to 1f during
     * DOZING -> LOCKSCREEN, but the transition is interrupted by LOCKSCREEN -> GONE. LS -> GONE
     * needs the smartspace to be alpha=1f so that it can play the shared-element unlock animation.
     * In this case, we'd want to collect [currentKeyguardState] and ensure the smartspace is
     * visible when the current state is LOCKSCREEN. If you use [finishedKeyguardState] in this
     * case, the smartspace will never be set to alpha = 1f and you'll have a half-faded smartspace
     * during the LS -> GONE transition.
     *
     * As a helpful footnote, here's the values of [finishedKeyguardState] and
     * [currentKeyguardState] during a sequence with two cancellations:
     * 1. We're FINISHED in GONE. currentKeyguardState=GONE; finishedKeyguardState=GONE.
     * 2. We START a transition from GONE -> DOZING. currentKeyguardState=GONE;
     *    finishedKeyguardState=GONE.
     * 3. We CANCEL this transition and START a transition from DOZING -> LOCKSCREEN.
     *    currentKeyguardState=DOZING; finishedKeyguardState=GONE.
     * 4. We subsequently also CANCEL DOZING -> LOCKSCREEN and START LOCKSCREEN -> GONE.
     *    currentKeyguardState=LOCKSCREEN finishedKeyguardState=GONE.
     * 5. LOCKSCREEN -> GONE is allowed to FINISH. currentKeyguardState=GONE;
     *    finishedKeyguardState=GONE.
     */
    val currentKeyguardState: SharedFlow<KeyguardState> =
        repository.transitions
            .mapLatest {
                if (it.transitionState == TransitionState.FINISHED) {
                    it.to
                } else {
                    it.from
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, OFF)

    val isInTransition =
        combine(isInTransitionWhere({ true }, { true }), sceneInteractor.transitionState) {
            isKeyguardTransitioning,
            sceneTransitionState ->
            isKeyguardTransitioning ||
                (SceneContainerFlag.isEnabled && sceneTransitionState.isTransitioning())
        }

    /**
     * Whether we're in a transition to and from the given [KeyguardState]s, but haven't yet
     * completed it.
     *
     * Provide [edgeWithoutSceneContainer] when the edge is different from what it is without it. If
     * the edges are equal before and after the flag it is sufficient to provide just [edge].
     */
    fun isInTransition(edge: Edge, edgeWithoutSceneContainer: Edge? = null): Flow<Boolean> {
        return if (SceneContainerFlag.isEnabled) {
                if (edge.isSceneWildcardEdge()) {
                    sceneInteractor.transitionState.map {
                        when (edge) {
                            is Edge.StateToState ->
                                throw IllegalStateException("Should not be reachable.")
                            is Edge.SceneToState -> it.isTransitioning(from = edge.from)
                            is Edge.StateToScene -> it.isTransitioning(to = edge.to)
                        }
                    }
                } else {
                    transition(edge).mapLatest { it.transitionState.isTransitioning() }
                }
            } else {
                transition(edgeWithoutSceneContainer ?: edge).mapLatest {
                    it.transitionState.isTransitioning()
                }
            }
            .onStart { emit(false) }
            .distinctUntilChanged()
    }

    /**
     * Whether we're in a transition between two [KeyguardState]s that match the given predicates,
     * but haven't yet completed it.
     *
     * If you only care about a single state for both from and to, instead use the optimized
     * [isInTransition].
     */
    fun isInTransitionWhere(
        fromStatePredicate: (KeyguardState) -> Boolean = { true },
        toStatePredicate: (KeyguardState) -> Boolean = { true },
    ): Flow<Boolean> {
        return repository.transitions
            .filter { it.transitionState != TransitionState.CANCELED }
            .mapLatest {
                it.transitionState != TransitionState.FINISHED &&
                    fromStatePredicate(it.from) &&
                    toStatePredicate(it.to)
            }
            .distinctUntilChanged()
    }

    /** Whether we've FINISHED a transition to a state that matches the given predicate. */
    fun isFinishedInStateWhere(stateMatcher: (KeyguardState) -> Boolean): Flow<Boolean> {
        return finishedKeyguardState.map { stateMatcher(it) }.distinctUntilChanged()
    }

    fun isFinishedIn(scene: SceneKey, stateWithoutSceneContainer: KeyguardState): Flow<Boolean> {
        return if (SceneContainerFlag.isEnabled) {
                sceneInteractor.transitionState.map {
                    it.isIdle(scene) || it.isTransitioning(from = scene)
                }
            } else {
                isFinishedIn(stateWithoutSceneContainer)
            }
            .distinctUntilChanged()
    }

    /** Whether we've FINISHED a transition to a state */
    fun isFinishedIn(state: KeyguardState): Flow<Boolean> {
        state.checkValidState()
        return finishedKeyguardState.map { it == state }.distinctUntilChanged()
    }

    fun isCurrentlyIn(scene: SceneKey, stateWithoutSceneContainer: KeyguardState): Flow<Boolean> {
        return if (SceneContainerFlag.isEnabled) {
                // In STL there is no difference between finished/currentState
                isFinishedIn(scene, stateWithoutSceneContainer)
            } else {
                stateWithoutSceneContainer.checkValidState()
                currentKeyguardState.map { it == stateWithoutSceneContainer }
            }
            .distinctUntilChanged()
    }

    fun getCurrentState(): KeyguardState {
        return currentKeyguardState.replayCache.last()
    }

    fun getStartedState(): KeyguardState {
        return startedKeyguardTransitionStep.value.to
    }

    private val finishedKeyguardState: StateFlow<KeyguardState> =
        repository.transitions
            .filter { it.transitionState == TransitionState.FINISHED }
            .map { it.to }
            .stateIn(scope, SharingStarted.Eagerly, OFF)

    companion object {
        private val TAG = KeyguardTransitionInteractor::class.simpleName
    }
}
