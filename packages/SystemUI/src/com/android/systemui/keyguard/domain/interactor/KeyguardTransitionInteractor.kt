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

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING_LOCKSCREEN_HOSTED
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn

/** Encapsulates business-logic related to the keyguard transitions. */
@SysUISingleton
class KeyguardTransitionInteractor
@Inject
constructor(
    @Application val scope: CoroutineScope,
    private val repository: KeyguardTransitionRepository,
    private val keyguardInteractor: dagger.Lazy<KeyguardInteractor>,
    private val fromLockscreenTransitionInteractor: dagger.Lazy<FromLockscreenTransitionInteractor>,
    private val fromPrimaryBouncerTransitionInteractor:
        dagger.Lazy<FromPrimaryBouncerTransitionInteractor>,
) {
    private val TAG = this::class.simpleName

    /** (any)->GONE transition information */
    val anyStateToGoneTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == GONE }

    /** (any)->AOD transition information */
    val anyStateToAodTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == AOD }

    /** DREAMING->(any) transition information. */
    val fromDreamingTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.from == DREAMING }

    /** LOCKSCREEN->(any) transition information. */
    val fromLockscreenTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.from == LOCKSCREEN }

    /** (any)->Lockscreen transition information */
    val anyStateToLockscreenTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == LOCKSCREEN }

    /** (any)->Occluded transition information */
    val anyStateToOccludedTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == OCCLUDED }

    /** (any)->PrimaryBouncer transition information */
    val anyStateToPrimaryBouncerTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == PRIMARY_BOUNCER }

    /** (any)->Dreaming transition information */
    val anyStateToDreamingTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == DREAMING }

    /** (any)->AlternateBouncer transition information */
    val anyStateToAlternateBouncerTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == ALTERNATE_BOUNCER }

    /** AOD->LOCKSCREEN transition information. */
    val aodToLockscreenTransition: Flow<TransitionStep> = repository.transition(AOD, LOCKSCREEN)

    /** DREAMING->LOCKSCREEN transition information. */
    val dreamingToLockscreenTransition: Flow<TransitionStep> =
        repository.transition(DREAMING, LOCKSCREEN)

    /** DREAMING_LOCKSCREEN_HOSTED->LOCKSCREEN transition information. */
    val dreamingLockscreenHostedToLockscreenTransition: Flow<TransitionStep> =
        repository.transition(DREAMING_LOCKSCREEN_HOSTED, LOCKSCREEN)

    /** GONE->AOD transition information. */
    val goneToAodTransition: Flow<TransitionStep> = repository.transition(GONE, AOD)

    /** GONE->DREAMING transition information. */
    val goneToDreamingTransition: Flow<TransitionStep> = repository.transition(GONE, DREAMING)

    /** GONE->DREAMING_LOCKSCREEN_HOSTED transition information. */
    val goneToDreamingLockscreenHostedTransition: Flow<TransitionStep> =
        repository.transition(GONE, DREAMING_LOCKSCREEN_HOSTED)

    /** GONE->LOCKSCREEN transition information. */
    val goneToLockscreenTransition: Flow<TransitionStep> = repository.transition(GONE, LOCKSCREEN)

    /** LOCKSCREEN->AOD transition information. */
    val lockscreenToAodTransition: Flow<TransitionStep> = repository.transition(LOCKSCREEN, AOD)

    /** LOCKSCREEN->DOZING transition information. */
    val lockscreenToDozingTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, DOZING)

    /** LOCKSCREEN->DREAMING transition information. */
    val lockscreenToDreamingTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, DREAMING)

    /** LOCKSCREEN->DREAMING_LOCKSCREEN_HOSTED transition information. */
    val lockscreenToDreamingLockscreenHostedTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, DREAMING_LOCKSCREEN_HOSTED)

    /** LOCKSCREEN->GLANCEABLE_HUB transition information. */
    val lockscreenToGlanceableHubTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, GLANCEABLE_HUB)

    /** LOCKSCREEN->OCCLUDED transition information. */
    val lockscreenToOccludedTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, OCCLUDED)

    /** GLANCEABLE_HUB->LOCKSCREEN transition information. */
    val glanceableHubToLockscreenTransition: Flow<TransitionStep> =
        repository.transition(GLANCEABLE_HUB, LOCKSCREEN)

    /** OCCLUDED->LOCKSCREEN transition information. */
    val occludedToLockscreenTransition: Flow<TransitionStep> =
        repository.transition(OCCLUDED, LOCKSCREEN)

    /** PRIMARY_BOUNCER->GONE transition information. */
    val primaryBouncerToGoneTransition: Flow<TransitionStep> =
        repository.transition(PRIMARY_BOUNCER, GONE)

    /** OFF->LOCKSCREEN transition information. */
    val offToLockscreenTransition: Flow<TransitionStep> = repository.transition(OFF, LOCKSCREEN)

    /** DOZING->LOCKSCREEN transition information. */
    val dozingToLockscreenTransition: Flow<TransitionStep> =
        repository.transition(DOZING, LOCKSCREEN)

    val transitions = repository.transitions

    /** Receive all [TransitionStep] matching a filter of [from]->[to] */
    fun transition(from: KeyguardState, to: KeyguardState): Flow<TransitionStep> {
        return repository.transition(from, to)
    }

    /**
     * AOD<->LOCKSCREEN transition information, mapped to dozeAmount range of AOD (1f) <->
     * Lockscreen (0f).
     */
    val dozeAmountTransition: Flow<TransitionStep> =
        merge(
            aodToLockscreenTransition.map { step -> step.copy(value = 1f - step.value) },
            lockscreenToAodTransition,
        )

    /** The last [TransitionStep] with a [TransitionState] of STARTED */
    val startedKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.STARTED }

    /** The last [TransitionStep] with a [TransitionState] of CANCELED */
    val canceledKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.CANCELED }

    /** The last [TransitionStep] with a [TransitionState] of FINISHED */
    val finishedKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.FINISHED }

    /** The destination state of the last started transition. */
    val startedKeyguardState: SharedFlow<KeyguardState> =
        startedKeyguardTransitionStep
            .map { step -> step.to }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** The last completed [KeyguardState] transition */
    val finishedKeyguardState: SharedFlow<KeyguardState> =
        finishedKeyguardTransitionStep
            .map { step -> step.to }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /**
     * Whether we're currently in a transition to a new [KeyguardState] and haven't yet completed
     * it.
     */
    val isInTransitionToAnyState =
        combine(
            startedKeyguardTransitionStep,
            finishedKeyguardState,
        ) { startedStep, finishedState ->
            startedStep.to != finishedState
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
        return repository.transitions
            .filter { it.from == state || it.to == state }
            .map {
                if (it.from == state) {
                    1 - it.value
                } else {
                    it.value
                }
            }
    }

    fun transitionStepsFromState(fromState: KeyguardState): Flow<TransitionStep> {
        return repository.transitions.filter { step -> step.from == fromState }
    }

    fun transitionStepsToState(toState: KeyguardState): Flow<TransitionStep> {
        return repository.transitions.filter { step -> step.to == toState }
    }

    /**
     * Called to start a transition that will ultimately dismiss the keyguard from the current
     * state.
     */
    fun startDismissKeyguardTransition() {
        when (val startedState = startedKeyguardState.replayCache.last()) {
            LOCKSCREEN -> fromLockscreenTransitionInteractor.get().dismissKeyguard()
            PRIMARY_BOUNCER -> fromPrimaryBouncerTransitionInteractor.get().dismissPrimaryBouncer()
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
        return isInTransitionToStateWhere { it == state }
    }

    /**
     * Whether we're in a transition to a [KeyguardState] that matches the given predicate, but
     * haven't yet completed it.
     */
    fun isInTransitionToStateWhere(
        stateMatcher: (KeyguardState) -> Boolean,
    ): Flow<Boolean> {
        return isInTransitionWhere(fromStatePredicate = { true }, toStatePredicate = stateMatcher)
    }

    /**
     * Whether we're in a transition out of the given [KeyguardState], but haven't yet completed it.
     */
    fun isInTransitionFromState(
        state: KeyguardState,
    ): Flow<Boolean> {
        return isInTransitionFromStateWhere { it == state }
    }

    /**
     * Whether we're in a transition out of a [KeyguardState] that matches the given predicate, but
     * haven't yet completed it.
     */
    fun isInTransitionFromStateWhere(
        stateMatcher: (KeyguardState) -> Boolean,
    ): Flow<Boolean> {
        return isInTransitionWhere(fromStatePredicate = stateMatcher, toStatePredicate = { true })
    }

    /**
     * Whether we're in a transition between two [KeyguardState]s that match the given predicates,
     * but haven't yet completed it.
     */
    fun isInTransitionWhere(
        fromStatePredicate: (KeyguardState) -> Boolean,
        toStatePredicate: (KeyguardState) -> Boolean,
    ): Flow<Boolean> {
        return combine(
                startedKeyguardTransitionStep,
                finishedKeyguardState,
            ) { startedStep, finishedState ->
                fromStatePredicate(startedStep.from) &&
                    toStatePredicate(startedStep.to) &&
                    finishedState != startedStep.to
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
}
