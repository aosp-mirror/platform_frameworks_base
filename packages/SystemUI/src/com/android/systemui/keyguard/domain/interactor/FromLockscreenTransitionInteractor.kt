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
import android.util.MathUtils
import com.android.app.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState.KEYGUARD
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class FromLockscreenTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    private val flags: FeatureFlags,
    private val shadeRepository: ShadeRepository,
    private val powerInteractor: PowerInteractor,
    private val glanceableHubTransitions: GlanceableHubTransitions,
    private val swipeToDismissInteractor: SwipeToDismissInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.LOCKSCREEN,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
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
        listenForLockscreenToGlanceableHub()
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

                true // Make the surface visible during LS -> GONE transitions.
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
        scope.launch {
            keyguardInteractor.isAbleToDream
                .sample(
                    combine(
                        startedKeyguardTransitionStep,
                        finishedKeyguardState,
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
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
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
        scope.launch {
            keyguardInteractor.alternateBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
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
        scope.launch {
            shadeRepository.legacyShadeExpansion
                .sample(
                    combine(
                        startedKeyguardTransitionStep,
                        keyguardInteractor.statusBarState,
                        keyguardInteractor.isKeyguardDismissible,
                        ::Triple
                    ),
                    ::toQuad
                )
                .collect { (shadeExpansion, keyguardState, statusBarState, isKeyguardUnlocked) ->
                    withContext(mainDispatcher) {
                        val id = transitionId
                        if (id != null) {
                            if (keyguardState.to == KeyguardState.PRIMARY_BOUNCER) {
                                // An existing `id` means a transition is started, and calls to
                                // `updateTransition` will control it until FINISHED or CANCELED
                                var nextState =
                                    if (shadeExpansion == 0f) {
                                        TransitionState.FINISHED
                                    } else if (shadeExpansion == 1f) {
                                        TransitionState.CANCELED
                                    } else {
                                        TransitionState.RUNNING
                                    }
                                transitionRepository.updateTransition(
                                    id,
                                    // This maps the shadeExpansion to a much faster curve, to match
                                    // the existing logic
                                    1f -
                                        MathUtils.constrainedMap(0f, 1f, 0.95f, 1f, shadeExpansion),
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
                                    shadeRepository.legacyShadeTracking.value &&
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
    }

    fun dismissKeyguard() {
        scope.launch { startTransitionTo(KeyguardState.GONE) }
    }

    private fun listenForLockscreenToGone() {
        if (KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isKeyguardGoingAway, lastStartedStep) = pair
                    if (isKeyguardGoingAway && lastStartedStep.to == KeyguardState.LOCKSCREEN) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForLockscreenToGoneDragging() {
        if (KeyguardWmStateRefactor.isEnabled) {
            // When the refactor is enabled, we no longer use isKeyguardGoingAway.
            scope.launch {
                swipeToDismissInteractor.dismissFling.filterNotNull().collect { _ ->
                    startTransitionTo(KeyguardState.GONE)
                }
            }

            return
        }

        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    KeyguardWmStateRefactor.assertInLegacyMode()
                    val (isKeyguardGoingAway, lastStartedStep) = pair
                    if (isKeyguardGoingAway && lastStartedStep.to == KeyguardState.LOCKSCREEN) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForLockscreenToOccluded() {
        scope.launch {
            keyguardInteractor.isKeyguardOccluded.sample(startedKeyguardState, ::Pair).collect {
                (isOccluded, keyguardState) ->
                if (isOccluded && keyguardState == KeyguardState.LOCKSCREEN) {
                    startTransitionTo(KeyguardState.OCCLUDED)
                }
            }
        }
    }

    private fun listenForLockscreenToAodOrDozing() {
        scope.launch {
            powerInteractor.isAsleep
                .sample(
                    combine(
                        startedKeyguardTransitionStep,
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

    /**
     * Listens for transition from glanceable hub back to lock screen and directly drives the
     * keyguard transition.
     */
    private fun listenForLockscreenToGlanceableHub() {
        if (!com.android.systemui.Flags.communalHub()) {
            return
        }
        scope.launch(mainDispatcher) {
            glanceableHubTransitions.listenForGlanceableHubTransition(
                transitionOwnerName = TAG,
                fromState = KeyguardState.LOCKSCREEN,
                toState = KeyguardState.GLANCEABLE_HUB,
            )
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    // Adds 100ms to the overall delay to workaround legacy setOccluded calls
                    // being delayed in KeyguardViewMediator
                    KeyguardState.DREAMING -> TO_DREAMING_DURATION + 100.milliseconds
                    KeyguardState.OCCLUDED -> TO_OCCLUDED_DURATION
                    KeyguardState.AOD -> TO_AOD_DURATION
                    KeyguardState.DOZING -> TO_DOZING_DURATION
                    KeyguardState.DREAMING_LOCKSCREEN_HOSTED -> TO_DREAMING_HOSTED_DURATION
                    KeyguardState.GLANCEABLE_HUB -> TO_GLANCEABLE_HUB_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromLockscreenTransitionInteractor"
        private val DEFAULT_DURATION = 400.milliseconds
        val TO_DOZING_DURATION = 500.milliseconds
        val TO_DREAMING_DURATION = 933.milliseconds
        val TO_DREAMING_HOSTED_DURATION = 933.milliseconds
        val TO_OCCLUDED_DURATION = 450.milliseconds
        val TO_AOD_DURATION = 500.milliseconds
        val TO_PRIMARY_BOUNCER_DURATION = DEFAULT_DURATION
        val TO_GONE_DURATION = DEFAULT_DURATION
        val TO_GLANCEABLE_HUB_DURATION = 1.seconds
    }
}
