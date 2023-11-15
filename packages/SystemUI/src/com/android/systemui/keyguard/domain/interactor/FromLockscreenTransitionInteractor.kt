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
import com.android.app.animation.Interpolators
import com.android.app.tracing.TraceUtils.Companion.launch
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardSurfaceBehindModel
import com.android.systemui.keyguard.shared.model.StatusBarState.KEYGUARD
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@SysUISingleton
class FromLockscreenTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val transitionInteractor: KeyguardTransitionInteractor,
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val flags: FeatureFlags,
    private val shadeRepository: ShadeRepository,
    private val powerInteractor: PowerInteractor,
    inWindowLauncherUnlockAnimationInteractor: Lazy<InWindowLauncherUnlockAnimationInteractor>,
) :
    TransitionInteractor(
        fromState = KeyguardState.LOCKSCREEN,
    ) {

    override fun start() {
        listenForLockscreenToGone()
        listenForLockscreenToGoneDragging()
        listenForLockscreenToOccluded()
        listenForLockscreenToAodOrDozing()
        listenForLockscreenToPrimaryBouncer()
        listenForLockscreenToDreaming()
        listenForLockscreenToPrimaryBouncerDragging()
        listenForLockscreenToAlternateBouncer()
        listenForLockscreenTransitionToCamera()
    }

    /**
     * Whether we want the surface behind the keyguard visible for the transition from LOCKSCREEN,
     * or null if we don't care and should just use a reasonable default.
     *
     * [KeyguardSurfaceBehindInteractor] will switch to this flow whenever a transition from
     * LOCKSCREEN is running.
     */
    val surfaceBehindVisibility: Flow<Boolean?> =
        transitionInteractor.startedKeyguardTransitionStep
            .map { startedStep ->
                if (startedStep.to != KeyguardState.GONE) {
                    // LOCKSCREEN to anything but GONE does not require any special surface
                    // visibility handling.
                    return@map null
                }

                true // TODO(b/278086361): Implement continuous swipe to unlock.
            }
            .onStart {
                // Default to null ("don't care, use a reasonable default").
                emit(null)
            }
            .distinctUntilChanged()

    /**
     * The surface behind view params to use for the transition from LOCKSCREEN, or null if we don't
     * care and should use a reasonable default.
     */
    val surfaceBehindModel: Flow<KeyguardSurfaceBehindModel?> =
        combine(
                transitionInteractor.startedKeyguardTransitionStep,
                transitionInteractor.transitionStepsFromState(KeyguardState.LOCKSCREEN),
                inWindowLauncherUnlockAnimationInteractor
                    .get()
                    .transitioningToGoneWithInWindowAnimation,
            ) { startedStep, fromLockscreenStep, transitioningToGoneWithInWindowAnimation ->
                if (startedStep.to != KeyguardState.GONE) {
                    // Only LOCKSCREEN -> GONE has specific surface params (for the unlock
                    // animation).
                    return@combine null
                } else if (transitioningToGoneWithInWindowAnimation) {
                    // If we're prepared for the in-window unlock, we're going to play an animation
                    // in the window. Make it fully visible.
                    KeyguardSurfaceBehindModel(
                        alpha = 1f,
                    )
                } else if (fromLockscreenStep.value > 0.5f) {
                    // Start the animation once we're 50% transitioned to GONE.
                    KeyguardSurfaceBehindModel(
                        animateFromAlpha = 0f,
                        alpha = 1f,
                        animateFromTranslationY = 500f,
                        translationY = 0f
                    )
                } else {
                    KeyguardSurfaceBehindModel(
                        alpha = 0f,
                    )
                }
            }
            .onStart {
                // Default to null ("don't care, use a reasonable default").
                emit(null)
            }
            .distinctUntilChanged()

    private fun listenForLockscreenTransitionToCamera() {
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    private fun listenForLockscreenToDreaming() {
        val invalidFromStates = setOf(KeyguardState.AOD, KeyguardState.DOZING)
        scope.launch("$TAG#listenForLockscreenToDreaming") {
            keyguardInteractor.isAbleToDream
                .sample(
                    combine(
                        transitionInteractor.startedKeyguardTransitionStep,
                        transitionInteractor.finishedKeyguardState,
                        keyguardInteractor.isActiveDreamLockscreenHosted,
                        ::Triple
                    ),
                    ::toQuad
                )
                .collect {
                    (
                        isAbleToDream,
                        lastStartedTransition,
                        finishedKeyguardState,
                        isActiveDreamLockscreenHosted) ->
                    val isOnLockscreen = finishedKeyguardState == KeyguardState.LOCKSCREEN
                    val isTransitionInterruptible =
                        lastStartedTransition.to == KeyguardState.LOCKSCREEN &&
                            !invalidFromStates.contains(lastStartedTransition.from)
                    if (isAbleToDream && (isOnLockscreen || isTransitionInterruptible)) {
                        if (isActiveDreamLockscreenHosted) {
                            startTransitionTo(KeyguardState.DREAMING_LOCKSCREEN_HOSTED)
                        } else {
                            startTransitionTo(KeyguardState.DREAMING)
                        }
                    }
                }
        }
    }

    private fun listenForLockscreenToPrimaryBouncer() {
        scope.launch("$TAG#listenForLockscreenToPrimaryBouncer") {
            keyguardInteractor.primaryBouncerShowing
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isBouncerShowing, lastStartedTransitionStep) = pair
                    if (
                        isBouncerShowing && lastStartedTransitionStep.to == KeyguardState.LOCKSCREEN
                    ) {
                        startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                    }
                }
        }
    }

    private fun listenForLockscreenToAlternateBouncer() {
        scope.launch("$TAG#listenForLockscreenToAlternateBouncer") {
            keyguardInteractor.alternateBouncerShowing
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isAlternateBouncerShowing, lastStartedTransitionStep) = pair
                    if (
                        isAlternateBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.LOCKSCREEN
                    ) {
                        startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
                    }
                }
        }
    }

    /* Starts transitions when manually dragging up the bouncer from the lockscreen. */
    private fun listenForLockscreenToPrimaryBouncerDragging() {
        var transitionId: UUID? = null
        scope.launch("$TAG#listenForLockscreenToPrimaryBouncerDragging") {
            shadeRepository.shadeModel
                .sample(
                    combine(
                        transitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.statusBarState,
                        keyguardInteractor.isKeyguardUnlocked,
                        ::Triple
                    ),
                    ::toQuad
                )
                .collect { (shadeModel, keyguardState, statusBarState, isKeyguardUnlocked) ->
                    val id = transitionId
                    if (id != null) {
                        if (keyguardState.to == KeyguardState.PRIMARY_BOUNCER) {
                            // An existing `id` means a transition is started, and calls to
                            // `updateTransition` will control it until FINISHED or CANCELED
                            var nextState =
                                if (shadeModel.expansionAmount == 0f) {
                                    TransitionState.FINISHED
                                } else if (shadeModel.expansionAmount == 1f) {
                                    TransitionState.CANCELED
                                } else {
                                    TransitionState.RUNNING
                                }
                            transitionRepository.updateTransition(
                                id,
                                1f - shadeModel.expansionAmount,
                                nextState,
                            )

                            if (
                                nextState == TransitionState.CANCELED ||
                                    nextState == TransitionState.FINISHED
                            ) {
                                transitionId = null
                            }

                            // If canceled, just put the state back
                            // TODO(b/278086361): This logic should happen in
                            //  FromPrimaryBouncerInteractor.
                            if (nextState == TransitionState.CANCELED) {
                                transitionRepository.startTransition(
                                    TransitionInfo(
                                        ownerName = name,
                                        from = KeyguardState.PRIMARY_BOUNCER,
                                        to = KeyguardState.LOCKSCREEN,
                                        animator =
                                            getDefaultAnimatorForTransitionsToState(
                                                    KeyguardState.LOCKSCREEN
                                                )
                                                .apply { duration = 0 }
                                    )
                                )
                            }
                        }
                    } else {
                        // TODO (b/251849525): Remove statusbarstate check when that state is
                        // integrated into KeyguardTransitionRepository
                        if (
                            keyguardState.to == KeyguardState.LOCKSCREEN &&
                                shadeModel.isUserDragging &&
                                !isKeyguardUnlocked &&
                                statusBarState == KEYGUARD
                        ) {
                            transitionId =
                                startTransitionTo(
                                    toState = KeyguardState.PRIMARY_BOUNCER,
                                    animator = null, // transition will be manually controlled
                                )
                        }
                    }
                }
        }
    }

    fun dismissKeyguard() {
        startTransitionTo(KeyguardState.GONE)
    }

    private fun listenForLockscreenToGone() {
        if (flags.isEnabled(Flags.KEYGUARD_WM_STATE_REFACTOR)) {
            return
        }

        scope.launch("$TAG#listenForLockscreenToGone") {
            keyguardInteractor.isKeyguardGoingAway
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isKeyguardGoingAway, lastStartedStep) = pair
                    if (isKeyguardGoingAway && lastStartedStep.to == KeyguardState.LOCKSCREEN) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForLockscreenToGoneDragging() {
        if (flags.isEnabled(Flags.KEYGUARD_WM_STATE_REFACTOR)) {
            return
        }

        scope.launch("$TAG#listenForLockscreenToGoneDragging") {
            keyguardInteractor.isKeyguardGoingAway
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isKeyguardGoingAway, lastStartedStep) = pair
                    if (isKeyguardGoingAway && lastStartedStep.to == KeyguardState.LOCKSCREEN) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForLockscreenToOccluded() {
        scope.launch("$TAG#listenForLockscreenToOccluded") {
            keyguardInteractor.isKeyguardOccluded
                .sample(transitionInteractor.startedKeyguardState, ::Pair)
                .collect { (isOccluded, keyguardState) ->
                    if (isOccluded && keyguardState == KeyguardState.LOCKSCREEN) {
                        startTransitionTo(KeyguardState.OCCLUDED)
                    }
                }
        }
    }

    private fun listenForLockscreenToAodOrDozing() {
        scope.launch("$TAG#listenForLockscreenToAodOrDozing") {
            powerInteractor.isAsleep
                .sample(
                    combine(
                        transitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.isAodAvailable,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (isAsleep, lastStartedStep, isAodAvailable) ->
                    if (lastStartedStep.to == KeyguardState.LOCKSCREEN && isAsleep) {
                        val toState =
                            if (isAodAvailable) KeyguardState.AOD else KeyguardState.DOZING
                        val modeOnCanceled =
                            if (
                                toState == KeyguardState.AOD &&
                                    lastStartedStep.from == KeyguardState.AOD
                            ) {
                                TransitionModeOnCanceled.REVERSE
                            } else {
                                TransitionModeOnCanceled.LAST_VALUE
                            }
                        startTransitionTo(
                            toState = toState,
                            modeOnCanceled = modeOnCanceled,
                        )
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.DREAMING -> TO_DREAMING_DURATION
                    KeyguardState.OCCLUDED -> TO_OCCLUDED_DURATION
                    KeyguardState.AOD -> TO_AOD_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromLockscreenTransitionInteractor"
        private val DEFAULT_DURATION = 400.milliseconds
        val TO_DREAMING_DURATION = 933.milliseconds
        val TO_OCCLUDED_DURATION = 450.milliseconds
        val TO_AOD_DURATION = 500.milliseconds
        val TO_PRIMARY_BOUNCER_DURATION = DEFAULT_DURATION
        val TO_GONE_DURATION = DEFAULT_DURATION
    }
}
