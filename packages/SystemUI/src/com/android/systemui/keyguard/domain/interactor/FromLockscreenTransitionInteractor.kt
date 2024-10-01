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
import com.android.app.tracing.coroutines.launch
import com.android.systemui.Flags.communalSceneKtfRefactor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState.KEYGUARD
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason.FOLD
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@SysUISingleton
class FromLockscreenTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val internalTransitionInteractor: InternalKeyguardTransitionInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    keyguardInteractor: KeyguardInteractor,
    private val shadeRepository: ShadeRepository,
    powerInteractor: PowerInteractor,
    private val glanceableHubTransitions: GlanceableHubTransitions,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val swipeToDismissInteractor: SwipeToDismissInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.LOCKSCREEN,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
        keyguardInteractor = keyguardInteractor,
    ) {

    override fun start() {
        listenForLockscreenToGone()
        listenForLockscreenToGoneDragging()
        listenForLockscreenToOccludedOrDreaming()
        listenForLockscreenToAodOrDozing()
        listenForLockscreenToPrimaryBouncer()
        listenForLockscreenToDreaming()
        listenForLockscreenToPrimaryBouncerDragging()
        listenForLockscreenToAlternateBouncer()
        listenForLockscreenTransitionToCamera()
        if (!communalSceneKtfRefactor()) {
            listenForLockscreenToGlanceableHub()
        }
    }

    /**
     * Whether we want the surface behind the keyguard visible for the transition from LOCKSCREEN,
     * or null if we don't care and should just use a reasonable default.
     *
     * [KeyguardSurfaceBehindInteractor] will switch to this flow whenever a transition from
     * LOCKSCREEN is running.
     */
    val surfaceBehindVisibility: Flow<Boolean?> =
        transitionInteractor
            .transition(
                edge = Edge.create(from = KeyguardState.LOCKSCREEN, to = Scenes.Gone),
                edgeWithoutSceneContainer =
                    Edge.create(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GONE),
            )
            .map<TransitionStep, Boolean?> {
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
        if (KeyguardWmStateRefactor.isEnabled) {
            return
        }

        val invalidFromStates = setOf(KeyguardState.AOD, KeyguardState.DOZING)
        scope.launch("$TAG#listenForLockscreenToDreaming") {
            keyguardInteractor.isAbleToDream
                .filterRelevantKeyguardState()
                .sampleCombine(
                    internalTransitionInteractor.currentTransitionInfoInternal,
                    transitionInteractor.isFinishedIn(KeyguardState.LOCKSCREEN),
                    keyguardInteractor.isActiveDreamLockscreenHosted,
                )
                .collect {
                    (isAbleToDream, transitionInfo, isOnLockscreen, isActiveDreamLockscreenHosted)
                    ->
                    val isTransitionInterruptible =
                        transitionInfo.to == KeyguardState.LOCKSCREEN &&
                            !invalidFromStates.contains(transitionInfo.from)
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
        if (SceneContainerFlag.isEnabled) return
        scope.launch("$TAG#listenForLockscreenToPrimaryBouncer") {
            keyguardInteractor.primaryBouncerShowing
                .filterRelevantKeyguardStateAnd { isBouncerShowing -> isBouncerShowing }
                .collect {
                    startTransitionTo(
                        KeyguardState.PRIMARY_BOUNCER,
                        ownerReason = "#listenForLockscreenToPrimaryBouncer",
                    )
                }
        }
    }

    private fun listenForLockscreenToAlternateBouncer() {
        scope.launch("$TAG#listenForLockscreenToAlternateBouncer") {
            keyguardInteractor.alternateBouncerShowing
                .filterRelevantKeyguardStateAnd { isAlternateBouncerShowing ->
                    isAlternateBouncerShowing
                }
                .collect { pair -> startTransitionTo(KeyguardState.ALTERNATE_BOUNCER) }
        }
    }

    /* Starts transitions when manually dragging up the bouncer from the lockscreen. */
    private fun listenForLockscreenToPrimaryBouncerDragging() {
        if (SceneContainerFlag.isEnabled) return
        var transitionId: UUID? = null
        scope.launch("$TAG#listenForLockscreenToPrimaryBouncerDragging") {
            shadeRepository.legacyShadeExpansion
                .sampleCombine(
                    transitionInteractor.startedKeyguardTransitionStep,
                    internalTransitionInteractor.currentTransitionInfoInternal,
                    keyguardInteractor.statusBarState,
                    keyguardInteractor.isKeyguardDismissible,
                )
                .collect {
                    (
                        shadeExpansion,
                        startedStep,
                        currentTransitionInfo,
                        statusBarState,
                        isKeyguardUnlocked) ->
                    val id = transitionId
                    if (id != null) {
                        if (startedStep.to == KeyguardState.PRIMARY_BOUNCER) {
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

                            // startTransition below will issue the CANCELED directly
                            if (nextState != TransitionState.CANCELED) {
                                transitionRepository.updateTransition(
                                    id,
                                    // This maps the shadeExpansion to a much faster curve, to match
                                    // the existing logic
                                    1f -
                                        MathUtils.constrainedMap(0f, 1f, 0.95f, 1f, shadeExpansion),
                                    nextState,
                                )
                            }

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
                                        modeOnCanceled = TransitionModeOnCanceled.REVERSE,
                                        animator =
                                            getDefaultAnimatorForTransitionsToState(
                                                    KeyguardState.LOCKSCREEN
                                                )
                                                .apply { duration = 100L },
                                    )
                                )
                            }
                        }
                    } else {
                        // TODO (b/251849525): Remove statusbarstate check when that state is
                        // integrated into KeyguardTransitionRepository
                        if (
                            // Use currentTransitionInfo to decide whether to start the transition.
                            currentTransitionInfo.to == KeyguardState.LOCKSCREEN &&
                                shadeExpansion > 0f &&
                                shadeExpansion < 1f &&
                                shadeRepository.legacyShadeTracking.value &&
                                !isKeyguardUnlocked &&
                                statusBarState == KEYGUARD
                        ) {
                            transitionId =
                                startTransitionTo(
                                    toState = KeyguardState.PRIMARY_BOUNCER,
                                    animator = null, // transition will be manually controlled,
                                    ownerReason = "#listenForLockscreenToPrimaryBouncerDragging",
                                )
                        }
                    }
                }
        }
    }

    fun dismissKeyguard() {
        scope.launch("$TAG#dismissKeyguard") {
            startTransitionTo(KeyguardState.GONE, ownerReason = "#dismissKeyguard()")
        }
    }

    private fun listenForLockscreenToGone() {
        if (SceneContainerFlag.isEnabled) return
        if (KeyguardWmStateRefactor.isEnabled) return
        scope.launch("$TAG#listenForLockscreenToGone") {
            keyguardInteractor.isKeyguardGoingAway
                .filterRelevantKeyguardStateAnd { isKeyguardGoingAway -> isKeyguardGoingAway }
                .collect {
                    startTransitionTo(
                        KeyguardState.GONE,
                        modeOnCanceled = TransitionModeOnCanceled.RESET,
                    )
                }
        }
    }

    private fun listenForLockscreenToGoneDragging() {
        if (SceneContainerFlag.isEnabled) return
        if (KeyguardWmStateRefactor.isEnabled) {
            // When the refactor is enabled, we no longer use isKeyguardGoingAway.
            scope.launch("$TAG#listenForLockscreenToGoneDragging") {
                swipeToDismissInteractor.dismissFling
                    .filterNotNull()
                    .filterRelevantKeyguardState()
                    .collect { _ ->
                        startTransitionTo(KeyguardState.GONE, ownerReason = "dismissFling != null")
                    }
            }
        }
    }

    private fun listenForLockscreenToOccludedOrDreaming() {
        if (KeyguardWmStateRefactor.isEnabled) {
            scope.launch("$TAG#listenForLockscreenToOccludedOrDreaming") {
                keyguardOcclusionInteractor.showWhenLockedActivityInfo
                    .filterRelevantKeyguardStateAnd { it.isOnTop }
                    .collect { taskInfo ->
                        startTransitionTo(
                            if (taskInfo.isDream()) {
                                KeyguardState.DREAMING
                            } else {
                                KeyguardState.OCCLUDED
                            }
                        )
                    }
            }
        } else {
            scope.launch("$TAG#listenForLockscreenToOccludedOrDreaming") {
                keyguardInteractor.isKeyguardOccluded
                    .filterRelevantKeyguardStateAnd { isOccluded -> isOccluded }
                    .collect { startTransitionTo(KeyguardState.OCCLUDED) }
            }
        }
    }

    private fun listenForLockscreenToAodOrDozing() {
        scope.launch("$TAG#listenForLockscreenToAodOrDozing") {
            listenForSleepTransition(
                modeOnCanceledFromStartedStep = { startedStep ->
                    if (
                        keyguardInteractor.asleepKeyguardState.value == KeyguardState.AOD &&
                            startedStep.from == KeyguardState.AOD
                    ) {
                        TransitionModeOnCanceled.REVERSE
                    } else {
                        TransitionModeOnCanceled.LAST_VALUE
                    }
                }
            )
        }
    }

    /**
     * Listens for transition from glanceable hub back to lock screen and directly drives the
     * keyguard transition.
     */
    private fun listenForLockscreenToGlanceableHub() {
        if (SceneContainerFlag.isEnabled) return
        if (!communalSettingsInteractor.isCommunalFlagEnabled()) {
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
                    KeyguardState.AOD ->
                        if (powerInteractor.detailedWakefulness.value.lastSleepReason == FOLD) {
                            TO_AOD_FOLD_DURATION
                        } else {
                            TO_AOD_DURATION
                        }
                    KeyguardState.DOZING -> TO_DOZING_DURATION
                    KeyguardState.DREAMING_LOCKSCREEN_HOSTED -> TO_DREAMING_HOSTED_DURATION
                    KeyguardState.GLANCEABLE_HUB -> TO_GLANCEABLE_HUB_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        private const val TAG = "FromLockscreenTransitionInteractor"
        private val DEFAULT_DURATION = 400.milliseconds
        val TO_DOZING_DURATION = 500.milliseconds
        val TO_DREAMING_DURATION = 933.milliseconds
        val TO_DREAMING_HOSTED_DURATION = 933.milliseconds
        val TO_OCCLUDED_DURATION = 550.milliseconds
        val TO_AOD_DURATION = 500.milliseconds
        val TO_AOD_FOLD_DURATION = 1100.milliseconds
        val TO_PRIMARY_BOUNCER_DURATION = DEFAULT_DURATION
        val TO_GONE_DURATION = 633.milliseconds
        val TO_GLANCEABLE_HUB_DURATION = 1.seconds
    }
}
