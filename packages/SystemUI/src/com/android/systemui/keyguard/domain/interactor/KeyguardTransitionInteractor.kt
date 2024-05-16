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

import android.annotation.FloatRange
import android.annotation.SuppressLint
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.pairwise
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val keyguardRepository: KeyguardRepository,
    private val repository: KeyguardTransitionRepository,
    private val fromLockscreenTransitionInteractor: dagger.Lazy<FromLockscreenTransitionInteractor>,
    private val fromPrimaryBouncerTransitionInteractor:
        dagger.Lazy<FromPrimaryBouncerTransitionInteractor>,
    private val fromAodTransitionInteractor: dagger.Lazy<FromAodTransitionInteractor>,
    private val fromAlternateBouncerTransitionInteractor:
        dagger.Lazy<FromAlternateBouncerTransitionInteractor>,
    private val fromDozingTransitionInteractor: dagger.Lazy<FromDozingTransitionInteractor>,
) {
    private val transitionMap = mutableMapOf<Edge, MutableSharedFlow<TransitionStep>>()

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
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
                .also { it.tryEmit(0f) }
        }
    }

    @Deprecated("Not performant - Use something else in this class")
    val transitions = repository.transitions

    val transitionState: StateFlow<TransitionStep> =
        transitions.stateIn(scope, SharingStarted.Eagerly, TransitionStep())

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
                    getTransitionValueFlow(step.from).emit(1f - step.value)
                    getTransitionValueFlow(step.to).emit(step.value)
                }
        }

        scope.launch {
            repository.transitions.collect {
                // FROM->TO
                transitionMap[Edge(it.from, it.to)]?.emit(it)
                // FROM->(ANY)
                transitionMap[Edge(it.from, null)]?.emit(it)
                // (ANY)->TO
                transitionMap[Edge(null, it.to)]?.emit(it)
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
                }
            }
        }
    }

    /** Given an [edge], return a SharedFlow to collect only relevant [TransitionStep]. */
    @SuppressLint("SharedFlowCreation")
    fun getOrCreateFlow(edge: Edge): MutableSharedFlow<TransitionStep> {
        return transitionMap.getOrPut(edge) {
            MutableSharedFlow(
                extraBufferCapacity = 10,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
    }

    /**
     * Receive all [TransitionStep] matching a filter of [from]->[to]. Allow nulls in order to match
     * any transition, for instance (any)->GONE.
     */
    fun transition(from: KeyguardState? = null, to: KeyguardState? = null): Flow<TransitionStep> {
        if (from == null && to == null) {
            throw IllegalArgumentException("from and to cannot both be null")
        }
        return getOrCreateFlow(Edge(from = from, to = to))
    }

    /**
     * The amount of transition into or out of the given [KeyguardState].
     *
     * The value will be `0` (or close to `0`, due to float point arithmetic) if not in this step or
     * `1` when fully in the given state.
     */
    fun transitionValue(
        state: KeyguardState,
    ): Flow<Float> {
        return getTransitionValueFlow(state)
    }

    /**
     * AOD<->* transition information, mapped to dozeAmount range of AOD (1f) <->
     * * (0f).
     */
    @SuppressLint("SharedFlowCreation")
    val dozeAmountTransition: Flow<TransitionStep> =
        repository.transitions
            .filter { step -> step.from == AOD || step.to == AOD }
            .map { step ->
                if (step.from == AOD) {
                    step.copy(value = 1 - step.value)
                } else {
                    step
                }
            }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** The last [TransitionStep] with a [TransitionState] of STARTED */
    val startedKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.STARTED }

    /** The last [TransitionStep] with a [TransitionState] of FINISHED */
    val finishedKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.FINISHED }

    /** The destination state of the last [TransitionState.STARTED] transition. */
    @SuppressLint("SharedFlowCreation")
    val startedKeyguardState: SharedFlow<KeyguardState> =
        startedKeyguardTransitionStep
            .map { step -> step.to }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val currentTransitionInfo: StateFlow<TransitionInfo> = repository.currentTransitionInfoInternal

    /** The from state of the last [TransitionState.STARTED] transition. */
    // TODO: is it performant to have several SharedFlows side by side instead of one?
    @SuppressLint("SharedFlowCreation")
    val startedKeyguardFromState: SharedFlow<KeyguardState> =
        startedKeyguardTransitionStep
            .map { step -> step.from }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** Which keyguard state to use when the device goes to sleep. */
    val asleepKeyguardState: StateFlow<KeyguardState> =
        keyguardRepository.isAodAvailable
            .map { aodAvailable -> if (aodAvailable) AOD else DOZING }
            .stateIn(scope, SharingStarted.Eagerly, DOZING)

    /**
     * The last [KeyguardState] to which we [TransitionState.FINISHED] a transition.
     *
     * WARNING: This will NOT emit a value if a transition is CANCELED, and will also not emit a
     * value when a subsequent transition is STARTED. It will *only* emit once we have finally
     * FINISHED in a state. This can have unintuitive implications.
     *
     * For example, if we're transitioning from GONE -> DOZING, and that transition is CANCELED in
     * favor of a DOZING -> LOCKSCREEN transition, the FINISHED state is still GONE, and will remain
     * GONE throughout the DOZING -> LOCKSCREEN transition until the DOZING -> LOCKSCREEN transition
     * finishes (at which point we'll be FINISHED in LOCKSCREEN).
     *
     * Since there's no real limit to how many consecutive transitions can be canceled, it's even
     * possible for the FINISHED state to be the same as the STARTED state while still
     * transitioning.
     *
     * For example:
     * 1. We're finished in GONE.
     * 2. The user presses the power button, starting a GONE -> DOZING transition. We're still
     *    FINISHED in GONE.
     * 3. The user changes their mind, pressing the power button to wake up; this starts a DOZING ->
     *    LOCKSCREEN transition. We're still FINISHED in GONE.
     * 4. The user quickly swipes away the lockscreen prior to DOZING -> LOCKSCREEN finishing; this
     *    starts a LOCKSCREEN -> GONE transition. We're still FINISHED in GONE, but we've also
     *    STARTED a transition *to* GONE.
     * 5. We'll emit KeyguardState.GONE again once the transition finishes.
     *
     * If you just need to know when we eventually settle into a state, this flow is likely
     * sufficient. However, if you're having issues with state *during* transitions started after
     * one or more canceled transitions, you probably need to use [currentKeyguardState].
     */
    @SuppressLint("SharedFlowCreation")
    val finishedKeyguardState: SharedFlow<KeyguardState> =
        finishedKeyguardTransitionStep
            .map { step -> step.to }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

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
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, KeyguardState.OFF)

    /**
     * The [TransitionInfo] of the most recent call to
     * [KeyguardTransitionRepository.startTransition].
     *
     * This should only be used by keyguard transition internals (From*TransitionInteractor and
     * related classes). Other consumers of keyguard state in System UI should use
     * [startedKeyguardState], [currentKeyguardState], and related flows.
     *
     * Keyguard internals use this to determine the most up-to-date KeyguardState that we've
     * requested a transition to, even if the animator running the transition on the main thread has
     * not yet emitted the STARTED TransitionStep.
     *
     * For example: if we're finished in GONE and press the power button twice very quickly, we may
     * request a transition to AOD, but then receive the second power button press prior to the
     * STARTED -> AOD transition step emitting. We still need the FromAodTransitionInteractor to
     * request a transition from AOD -> LOCKSCREEN in response to the power press, even though the
     * main thread animator hasn't emitted STARTED > AOD yet (which means [startedKeyguardState] is
     * still GONE, which is not relevant to FromAodTransitionInteractor). In this case, the
     * interactor can use this current transition info to determine that a STARTED -> AOD step
     * *will* be emitted, and therefore that it can safely request an AOD -> LOCKSCREEN transition
     * which will subsequently cancel GONE -> AOD.
     */
    internal val currentTransitionInfoInternal: StateFlow<TransitionInfo> =
        repository.currentTransitionInfoInternal

    /** Whether we've currently STARTED a transition and haven't yet FINISHED it. */
    val isInTransitionToAnyState = isInTransitionWhere({ true }, { true })

    fun transitionStepsFromState(fromState: KeyguardState): Flow<TransitionStep> {
        return getOrCreateFlow(Edge(from = fromState, to = null))
    }

    fun transitionStepsToState(toState: KeyguardState): Flow<TransitionStep> {
        return getOrCreateFlow(Edge(from = null, to = toState))
    }

    /**
     * Called to start a transition that will ultimately dismiss the keyguard from the current
     * state.
     */
    fun startDismissKeyguardTransition() {
        // TODO(b/336576536): Check if adaptation for scene framework is needed
        if (SceneContainerFlag.isEnabled) return
        when (val startedState = startedKeyguardState.replayCache.last()) {
            LOCKSCREEN -> fromLockscreenTransitionInteractor.get().dismissKeyguard()
            PRIMARY_BOUNCER -> fromPrimaryBouncerTransitionInteractor.get().dismissPrimaryBouncer()
            ALTERNATE_BOUNCER ->
                fromAlternateBouncerTransitionInteractor.get().dismissAlternateBouncer()
            AOD -> fromAodTransitionInteractor.get().dismissAod()
            DOZING -> fromDozingTransitionInteractor.get().dismissFromDozing()
            else ->
                Log.e(
                    "KeyguardTransitionInteractor",
                    "We don't know how to dismiss keyguard from state $startedState."
                )
        }
    }

    /** Whether we're in a transition to the given [KeyguardState], but haven't yet completed it. */
    fun isInTransitionToState(
        state: KeyguardState,
    ): Flow<Boolean> {
        return getOrCreateFlow(Edge(from = null, to = state))
            .mapLatest { it.transitionState.isTransitioning() }
            .onStart { emit(false) }
            .distinctUntilChanged()
    }

    /**
     * Whether we're in a transition to and from the given [KeyguardState]s, but haven't yet
     * completed it.
     */
    fun isInTransition(
        from: KeyguardState,
        to: KeyguardState,
    ): Flow<Boolean> {
        return getOrCreateFlow(Edge(from = from, to = to))
            .mapLatest { it.transitionState.isTransitioning() }
            .onStart { emit(false) }
            .distinctUntilChanged()
    }

    /**
     * Whether we're in a transition out of the given [KeyguardState], but haven't yet completed it.
     */
    fun isInTransitionFromState(
        state: KeyguardState,
    ): Flow<Boolean> {
        return getOrCreateFlow(Edge(from = state, to = null))
            .mapLatest { it.transitionState.isTransitioning() }
            .onStart { emit(false) }
            .distinctUntilChanged()
    }

    /**
     * Whether we're in a transition to a [KeyguardState] that matches the given predicate, but
     * haven't yet completed it.
     *
     * If you only care about a single state, instead use the optimized [isInTransitionToState].
     */
    fun isInTransitionToStateWhere(
        stateMatcher: (KeyguardState) -> Boolean,
    ): Flow<Boolean> {
        return isInTransitionWhere(fromStatePredicate = { true }, toStatePredicate = stateMatcher)
    }

    /**
     * Whether we're in a transition out of a [KeyguardState] that matches the given predicate, but
     * haven't yet completed it.
     *
     * If you only care about a single state, instead use the optimized [isInTransitionFromState].
     */
    fun isInTransitionFromStateWhere(
        stateMatcher: (KeyguardState) -> Boolean,
    ): Flow<Boolean> {
        return isInTransitionWhere(fromStatePredicate = stateMatcher, toStatePredicate = { true })
    }

    /**
     * Whether we're in a transition between two [KeyguardState]s that match the given predicates,
     * but haven't yet completed it.
     *
     * If you only care about a single state for both from and to, instead use the optimized
     * [isInTransition].
     */
    fun isInTransitionWhere(
        fromStatePredicate: (KeyguardState) -> Boolean,
        toStatePredicate: (KeyguardState) -> Boolean,
    ): Flow<Boolean> {
        return isInTransitionWhere { from, to -> fromStatePredicate(from) && toStatePredicate(to) }
    }

    /**
     * Whether we're in a transition between two [KeyguardState]s that match the given predicates,
     * but haven't yet completed it.
     *
     * If you only care about a single state for both from and to, instead use the optimized
     * [isInTransition].
     */
    fun isInTransitionWhere(
        fromToStatePredicate: (KeyguardState, KeyguardState) -> Boolean
    ): Flow<Boolean> {
        return repository.transitions
            .filter { it.transitionState != TransitionState.CANCELED }
            .mapLatest {
                it.transitionState != TransitionState.FINISHED &&
                    fromToStatePredicate(it.from, it.to)
            }
            .distinctUntilChanged()
    }

    /** Whether we've FINISHED a transition to a state that matches the given predicate. */
    fun isFinishedInStateWhere(stateMatcher: (KeyguardState) -> Boolean): Flow<Boolean> {
        return finishedKeyguardState.map { stateMatcher(it) }.distinctUntilChanged()
    }

    /** Whether we've FINISHED a transition to a state that matches the given predicate. */
    fun isFinishedInState(state: KeyguardState): Flow<Boolean> {
        return finishedKeyguardState.map { it == state }.distinctUntilChanged()
    }

    /**
     * Whether we've FINISHED a transition to a state that matches the given predicate. Consider
     * using [isFinishedInStateWhere] whenever possible instead
     */
    fun isFinishedInStateWhereValue(stateMatcher: (KeyguardState) -> Boolean) =
        stateMatcher(finishedKeyguardState.replayCache.last())

    fun getCurrentState(): KeyguardState {
        return currentKeyguardState.replayCache.last()
    }

    fun getStartedState(): KeyguardState {
        return startedKeyguardState.replayCache.last()
    }

    fun getStartedFromState(): KeyguardState {
        return startedKeyguardFromState.replayCache.last()
    }

    fun getFinishedState(): KeyguardState {
        return finishedKeyguardState.replayCache.last()
    }

    suspend fun startTransition(info: TransitionInfo) = repository.startTransition(info)

    fun updateTransition(
        transitionId: UUID,
        @FloatRange(from = 0.0, to = 1.0) value: Float,
        state: TransitionState
    ) = repository.updateTransition(transitionId, value, state)
}
