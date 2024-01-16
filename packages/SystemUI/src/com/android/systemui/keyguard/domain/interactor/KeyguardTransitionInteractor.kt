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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn

/** Encapsulates business-logic related to the keyguard transitions. */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /** The destination state of the last [TransitionState.STARTED] transition. */
    val startedKeyguardState: SharedFlow<KeyguardState> =
        startedKeyguardTransitionStep
            .map { step -> step.to }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

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
     * If you need special-case handling for cancellations (such as conditional handling depending
     * on which [KeyguardState] was canceled) you can collect [canceledKeyguardTransitionStep]
     * directly.
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
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** Whether we've currently STARTED a transition and haven't yet FINISHED it. */
    val isInTransitionToAnyState = isInTransitionWhere({ true }, { true })

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
