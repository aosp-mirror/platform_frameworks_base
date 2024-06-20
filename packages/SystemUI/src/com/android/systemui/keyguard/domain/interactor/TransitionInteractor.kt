/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import android.util.Log
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.sample
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Each TransitionInteractor is responsible for determining under which conditions to notify
 * [KeyguardTransitionRepository] to signal a transition. When (and if) the transition occurs is
 * determined by [KeyguardTransitionRepository].
 *
 * [name] field should be a unique identifiable string representing this state, used primarily for
 * logging
 *
 * MUST list implementing classes in dagger module [StartKeyguardTransitionModule] and also in the
 * 'when' clause of [KeyguardTransitionCoreStartable]
 */
sealed class TransitionInteractor(
    val fromState: KeyguardState,
    val transitionInteractor: KeyguardTransitionInteractor,
    val mainDispatcher: CoroutineDispatcher,
    val bgDispatcher: CoroutineDispatcher,
    val powerInteractor: PowerInteractor,
    val keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    val keyguardInteractor: KeyguardInteractor,
) {
    val name = this::class.simpleName ?: "UnknownTransitionInteractor"
    abstract val transitionRepository: KeyguardTransitionRepository
    abstract fun start()

    /* Use background dispatcher for all [KeyguardTransitionInteractor] flows. Necessary because
     * the [sample] utility internally runs a collect on the Unconfined dispatcher, resulting
     * in continuations on the main thread. We don't want that for classes that inherit from this.
     */
    val startedKeyguardTransitionStep =
        transitionInteractor.startedKeyguardTransitionStep.flowOn(bgDispatcher)
    // The following are MutableSharedFlows, and do not require flowOn
    val startedKeyguardState = transitionInteractor.startedKeyguardState
    val finishedKeyguardState = transitionInteractor.finishedKeyguardState
    val currentKeyguardState = transitionInteractor.currentKeyguardState

    suspend fun startTransitionTo(
        toState: KeyguardState,
        animator: ValueAnimator? = getDefaultAnimatorForTransitionsToState(toState),
        modeOnCanceled: TransitionModeOnCanceled = TransitionModeOnCanceled.LAST_VALUE,
        // Even more information about why the owner started this transition, if this is a dangerous
        // transition (*cough* occlusion) where you'd be sad to not have all the info you can get in
        // a bugreport.
        ownerReason: String = "",
    ): UUID? {
        if (fromState != transitionInteractor.currentTransitionInfoInternal.value.to) {
            Log.e(
                name,
                "Ignoring startTransition: This interactor asked to transition from " +
                    "$fromState -> $toState, but we last transitioned to " +
                    "${transitionInteractor.currentTransitionInfoInternal.value.to}, not " +
                    "$fromState. This should never happen - check currentTransitionInfoInternal " +
                    "or use filterRelevantKeyguardState before starting transitions."
            )

            if (fromState == transitionInteractor.finishedKeyguardState.replayCache.last()) {
                Log.e(
                    name,
                    "This transition would not have been ignored prior to ag/26681239, since we " +
                        "are FINISHED in $fromState (but have since started another transition). " +
                        "If ignoring this transition has caused a regression, fix it by ensuring " +
                        "that transitions are exclusively started from the most recently started " +
                        "state."
                )
            }
            return null
        }

        return transitionRepository.startTransition(
            TransitionInfo(
                name + if (ownerReason.isNotBlank()) "($ownerReason)" else "",
                fromState,
                toState,
                animator,
                modeOnCanceled,
            )
        )
    }

    /**
     * Check whether we need to transition to [KeyguardState.OCCLUDED], based on the presence of a
     * SHOW_WHEN_LOCKED activity, or back to [KeyguardState.GONE], for some power button launch
     * gesture cases. If so, start the transition.
     *
     * Returns true if a transition was started, false otherwise.
     */
    suspend fun maybeStartTransitionToOccludedOrInsecureCamera(): Boolean {
        // The refactor is required for the occlusion interactor to work.
        KeyguardWmStateRefactor.isUnexpectedlyInLegacyMode()

        // Check if we should start a transition from the power gesture.
        if (keyguardOcclusionInteractor.shouldTransitionFromPowerButtonGesture()) {
            // See if we handled the insecure power gesture. If not, then we'll be launching the
            // secure camera. Once KeyguardWmStateRefactor is fully enabled, we can clean up this
            // code path by pulling maybeHandleInsecurePowerGesture() into this conditional.
            if (!maybeHandleInsecurePowerGesture()) {
                // Otherwise, the double tap gesture occurred while not GONE and not dismissable,
                // which means we will launch the secure camera, which OCCLUDES the keyguard.
                startTransitionTo(
                    KeyguardState.OCCLUDED,
                    ownerReason = "Power button gesture on lockscreen"
                )
            }

            return true
        } else if (keyguardOcclusionInteractor.showWhenLockedActivityInfo.value.isOnTop) {
            // A SHOW_WHEN_LOCKED activity is on top of the task stack. Transition to OCCLUDED so
            // it's visible.
            // TODO(b/307976454) - Centralize transition to DREAMING here.
            startTransitionTo(
                KeyguardState.OCCLUDED,
                ownerReason = "SHOW_WHEN_LOCKED activity on top"
            )

            return true
        } else {
            // No transition needed, let the interactor figure out where to go.
            return false
        }
    }

    /**
     * Transition to [KeyguardState.GONE] for the insecure power button launch gesture, if the
     * conditions to do so are met.
     *
     * Called from [FromAodTransitionInteractor] if [KeyguardWmStateRefactor] is not enabled, or
     * [maybeStartTransitionToOccludedOrInsecureCamera] if it's enabled.
     */
    @Deprecated("Will be merged into maybeStartTransitionToOccludedOrInsecureCamera")
    suspend fun maybeHandleInsecurePowerGesture(): Boolean {
        if (keyguardOcclusionInteractor.shouldTransitionFromPowerButtonGesture()) {
            if (keyguardInteractor.isKeyguardDismissible.value) {
                startTransitionTo(
                    KeyguardState.GONE,
                    ownerReason = "Power button gesture while keyguard is dismissible"
                )

                return true
            } else if (keyguardOcclusionInteractor.occludingActivityWillDismissKeyguard.value) {
                // The double tap gesture occurred while not GONE (AOD/LOCKSCREEN/etc.), but the
                // keyguard is dismissable. The activity launch will dismiss the keyguard, so we
                // should transition to GONE.
                startTransitionTo(
                    KeyguardState.GONE,
                    ownerReason = "Power button gesture on dismissable keyguard"
                )

                return true
            }
        }

        return false
    }

    /**
     * Transition to the appropriate state when the device goes to sleep while in [from].
     *
     * We could also just use [fromState], but it's more readable in the From*TransitionInteractor
     * if you're explicitly declaring which state you're listening from. If you passed in the wrong
     * state, [startTransitionTo] would complain anyway.
     */
    suspend fun listenForSleepTransition(
        modeOnCanceledFromStartedStep: (TransitionStep) -> TransitionModeOnCanceled = {
            TransitionModeOnCanceled.LAST_VALUE
        }
    ) {
        powerInteractor.isAsleep
            .filter { isAsleep -> isAsleep }
            .filterRelevantKeyguardState()
            .sample(startedKeyguardTransitionStep)
            .map(modeOnCanceledFromStartedStep)
            .collect { modeOnCanceled ->
                startTransitionTo(
                    toState = transitionInteractor.asleepKeyguardState.value,
                    modeOnCanceled = modeOnCanceled,
                    ownerReason = "Sleep transition triggered"
                )
            }
    }

    /** This signal may come in before the occlusion signal, and can provide a custom transition */
    fun listenForTransitionToCamera(
        scope: CoroutineScope,
        keyguardInteractor: KeyguardInteractor,
    ) {
        if (!KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                keyguardInteractor.onCameraLaunchDetected.filterRelevantKeyguardState().collect {
                    if (!maybeHandleInsecurePowerGesture()) {
                        startTransitionTo(
                            toState = KeyguardState.OCCLUDED,
                            modeOnCanceled = TransitionModeOnCanceled.RESET,
                            ownerReason = "keyguardInteractor.onCameraLaunchDetected",
                        )
                    }
                }
            }
        }
    }

    /**
     * Whether we're in the KeyguardState relevant to this From*TransitionInteractor (which we know
     * from [fromState]).
     *
     * This uses [KeyguardTransitionInteractor.currentTransitionInfoInternal], which is more up to
     * date than [startedKeyguardState] as it does not wait for the emission of the first STARTED
     * step.
     */
    fun inOrTransitioningToRelevantKeyguardState(): Boolean {
        return transitionInteractor.currentTransitionInfoInternal.value.to == fromState
    }

    /**
     * Filters emissions whenever we're not in a KeyguardState relevant to this
     * From*TransitionInteractor (which we know from [fromState]).
     */
    fun <T> Flow<T>.filterRelevantKeyguardState(): Flow<T> {
        return filter { inOrTransitioningToRelevantKeyguardState() }
    }

    /**
     * Filters emissions whenever we're not in a KeyguardState relevant to this
     * From*TransitionInteractor (which we know from [fromState]).
     */
    fun <T> Flow<T>.filterRelevantKeyguardStateAnd(predicate: (T) -> Boolean): Flow<T> {
        return filter { inOrTransitioningToRelevantKeyguardState() && predicate.invoke(it) }
    }

    /**
     * Returns a ValueAnimator to be used for transitions to [toState], if one is not explicitly
     * passed to [startTransitionTo].
     */
    abstract fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator?
}
