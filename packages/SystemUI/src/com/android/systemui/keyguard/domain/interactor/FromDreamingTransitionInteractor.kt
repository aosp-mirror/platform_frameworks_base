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
import android.annotation.SuppressLint
import android.app.DreamManager
import com.android.app.animation.Interpolators
import com.android.app.tracing.coroutines.launch
import com.android.systemui.Flags.communalSceneKtfRefactor
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class FromDreamingTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val internalTransitionInteractor: InternalKeyguardTransitionInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    keyguardInteractor: KeyguardInteractor,
    private val glanceableHubTransitions: GlanceableHubTransitions,
    private val communalInteractor: CommunalInteractor,
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    powerInteractor: PowerInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    private val dreamManager: DreamManager,
    private val deviceEntryInteractor: DeviceEntryInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.DREAMING,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
        keyguardInteractor = keyguardInteractor,
    ) {

    @SuppressLint("MissingPermission")
    override fun start() {
        listenForDreamingToAlternateBouncer()
        listenForDreamingToOccluded()
        listenForDreamingToGoneWhenDismissable()
        listenForDreamingToGoneFromBiometricUnlock()
        listenForDreamingToLockscreenOrGone()
        listenForDreamingToAodOrDozing()
        listenForTransitionToCamera(scope, keyguardInteractor)
        if (!communalSceneKtfRefactor()) {
            listenForDreamingToGlanceableHub()
        } else {
            listenForDreamingToGlanceableHubFromPowerButton()
        }
        listenForDreamingToPrimaryBouncer()
    }

    private fun listenForDreamingToAlternateBouncer() {
        scope.launch("$TAG#listenForDreamingToAlternateBouncer") {
            keyguardInteractor.alternateBouncerShowing
                .filterRelevantKeyguardStateAnd { isAlternateBouncerShowing ->
                    isAlternateBouncerShowing
                }
                .collect { startTransitionTo(KeyguardState.ALTERNATE_BOUNCER) }
        }
    }

    private fun listenForDreamingToGlanceableHub() {
        if (!communalSettingsInteractor.isCommunalFlagEnabled()) return
        if (SceneContainerFlag.isEnabled) return
        scope.launch("$TAG#listenForDreamingToGlanceableHub", mainDispatcher) {
            glanceableHubTransitions.listenForGlanceableHubTransition(
                transitionOwnerName = TAG,
                fromState = KeyguardState.DREAMING,
                toState = KeyguardState.GLANCEABLE_HUB,
            )
        }
    }

    /**
     * Normally when pressing power button from the dream, the devices goes from DREAMING to DOZING,
     * then [FromDozingTransitionInteractor] handles the transition to GLANCEABLE_HUB. However if
     * the power button is pressed quickly, we may need to go directly from DREAMING to
     * GLANCEABLE_HUB as the transition to DOZING has not occurred yet.
     */
    @SuppressLint("MissingPermission")
    private fun listenForDreamingToGlanceableHubFromPowerButton() {
        if (!communalSettingsInteractor.isCommunalFlagEnabled()) return
        if (SceneContainerFlag.isEnabled) return
        scope.launch {
            powerInteractor.isAwake
                .debounce(50L)
                .filterRelevantKeyguardStateAnd { isAwake -> isAwake }
                .sample(communalInteractor.isCommunalAvailable)
                .collect { isCommunalAvailable ->
                    if (isCommunalAvailable && dreamManager.canStartDreaming(false)) {
                        // This case handles tapping the power button to transition through
                        // dream -> off -> hub.
                        communalSceneInteractor.snapToScene(
                            newScene = CommunalScenes.Communal,
                            loggingReason = "from dreaming to hub",
                        )
                    }
                }
        }
    }

    private fun listenForDreamingToPrimaryBouncer() {
        // TODO(b/336576536): Check if adaptation for scene framework is needed
        if (SceneContainerFlag.isEnabled) return
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isBouncerShowing, lastStartedTransitionStep) = pair
                    if (
                        isBouncerShowing && lastStartedTransitionStep.to == KeyguardState.DREAMING
                    ) {
                        startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                    }
                }
        }
    }

    fun startToLockscreenOrGlanceableHubTransition(openHub: Boolean) {
        scope.launch {
            if (
                transitionInteractor.startedKeyguardTransitionStep.value.to ==
                    KeyguardState.DREAMING
            ) {
                if (powerInteractor.detailedWakefulness.value.isAwake()) {
                    if (openHub) {
                        communalSceneInteractor.changeScene(
                            newScene = CommunalScenes.Communal,
                            loggingReason = "FromDreamingTransitionInteractor",
                        )
                    } else {
                        startTransitionTo(
                            KeyguardState.LOCKSCREEN,
                            ownerReason = "Dream has ended and device is awake",
                        )
                    }
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun listenForDreamingToOccluded() {
        if (!KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                combine(
                        keyguardInteractor.isKeyguardOccluded,
                        keyguardInteractor.isDreaming,
                        ::Pair,
                    )
                    // Debounce signals since there is a race condition between the occluded and
                    // dreaming signals when starting or stopping dreaming. We therefore add a small
                    // delay to give enough time for occluded to flip to false when the dream
                    // ends, to avoid transitioning to OCCLUDED erroneously when exiting the dream.
                    .debounce(100.milliseconds)
                    .filterRelevantKeyguardStateAnd { (isOccluded, isDreaming) ->
                        isOccluded && !isDreaming
                    }
                    .collect {
                        startTransitionTo(
                            toState = KeyguardState.OCCLUDED,
                            ownerReason = "Occluded but no longer dreaming",
                        )
                    }
            }
        }
    }

    private fun listenForDreamingToLockscreenOrGone() {
        if (!KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            keyguardInteractor.isAbleToDream
                .filter { !it }
                .sample(deviceEntryInteractor.isUnlocked, ::Pair)
                .collect { (_, dismissable) ->
                    // TODO(b/349837588): Add check for -> OCCLUDED.
                    if (dismissable) {
                        startTransitionTo(
                            KeyguardState.GONE,
                            ownerReason = "No longer dreaming; dismissable",
                        )
                    } else {
                        startTransitionTo(
                            KeyguardState.LOCKSCREEN,
                            ownerReason = "No longer dreaming",
                        )
                    }
                }
        }
    }

    private fun listenForDreamingToGoneWhenDismissable() {
        if (SceneContainerFlag.isEnabled) {
            return
        }

        if (KeyguardWmStateRefactor.isEnabled) {
            return
        }

        scope.launch {
            keyguardInteractor.isAbleToDream
                .filterRelevantKeyguardStateAnd { isDreaming -> !isDreaming }
                .collect {
                    if (
                        keyguardInteractor.isKeyguardDismissible.value &&
                            !keyguardInteractor.isKeyguardShowing.value
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForDreamingToGoneFromBiometricUnlock() {
        // TODO(b/353542570): Adaptation for scene framework is needed
        if (SceneContainerFlag.isEnabled) return
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .filterRelevantKeyguardStateAnd { biometricUnlockState ->
                    biometricUnlockState.mode == BiometricUnlockMode.WAKE_AND_UNLOCK_FROM_DREAM
                }
                .collect { startTransitionTo(KeyguardState.GONE) }
        }
    }

    private fun listenForDreamingToAodOrDozing() {
        scope.launch {
            keyguardInteractor.dozeTransitionModel.filterRelevantKeyguardState().collect {
                dozeTransitionModel ->
                if (dozeTransitionModel.to == DozeStateModel.DOZE) {
                    startTransitionTo(KeyguardState.DOZING)
                } else if (dozeTransitionModel.to == DozeStateModel.DOZE_AOD) {
                    startTransitionTo(KeyguardState.AOD)
                }
            }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    KeyguardState.GLANCEABLE_HUB -> TO_GLANCEABLE_HUB_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromDreamingTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_GLANCEABLE_HUB_DURATION = 1.seconds
        val TO_LOCKSCREEN_DURATION = 1167.milliseconds
        val TO_AOD_DURATION = 300.milliseconds
        val TO_GONE_DURATION = DEFAULT_DURATION
    }
}
