/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import com.android.app.animation.Interpolators
import com.android.app.tracing.coroutines.launch
import com.android.systemui.Flags.communalSceneKtfRefactor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalTransitionKeys
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.noneOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.android.systemui.util.kotlin.Utils.Companion.sampleFilter
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
@SysUISingleton
class FromGlanceableHubTransitionInteractor
@Inject
constructor(
    @Background private val scope: CoroutineScope,
    @Main mainDispatcher: CoroutineDispatcher,
    @Background bgDispatcher: CoroutineDispatcher,
    private val glanceableHubTransitions: GlanceableHubTransitions,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    keyguardInteractor: KeyguardInteractor,
    private val communalSceneInteractor: CommunalSceneInteractor,
    override val transitionRepository: KeyguardTransitionRepository,
    override val internalTransitionInteractor: InternalKeyguardTransitionInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    powerInteractor: PowerInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.GLANCEABLE_HUB,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
        keyguardInteractor = keyguardInteractor,
    ) {

    override fun start() {
        if (SceneContainerFlag.isEnabled) return
        if (!communalSettingsInteractor.isCommunalFlagEnabled()) {
            return
        }
        if (!communalSceneKtfRefactor()) {
            listenForHubToLockscreenOrDreaming()
        }
        listenForHubToDozing()
        listenForHubToPrimaryBouncer()
        listenForHubToAlternateBouncer()
        listenForHubToOccluded()
        listenForHubToGone()
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    KeyguardState.OCCLUDED -> TO_OCCLUDED_DURATION
                    KeyguardState.ALTERNATE_BOUNCER -> TO_BOUNCER_DURATION
                    KeyguardState.PRIMARY_BOUNCER -> TO_BOUNCER_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    /**
     * Listens for the glanceable hub transition to lock screen and directly drives the keyguard
     * transition.
     */
    private fun listenForHubToLockscreenOrDreaming() {
        scope.launch("$TAG#listenForGlanceableHubToLockscreenOrDream") {
            keyguardInteractor.isDreaming.collectLatest { dreaming ->
                withContext(mainDispatcher) {
                    val toState =
                        if (dreaming) {
                            KeyguardState.DREAMING
                        } else {
                            KeyguardState.LOCKSCREEN
                        }
                    glanceableHubTransitions.listenForGlanceableHubTransition(
                        transitionOwnerName = TAG,
                        fromState = KeyguardState.GLANCEABLE_HUB,
                        toState = toState,
                    )
                }
            }
        }
    }

    private fun listenForHubToPrimaryBouncer() {
        scope.launch("$TAG#listenForHubToPrimaryBouncer") {
            keyguardInteractor.primaryBouncerShowing
                .filterRelevantKeyguardStateAnd { primaryBouncerShowing -> primaryBouncerShowing }
                .collect {
                    // Bouncer shows on top of the hub, so do not change scenes here.
                    startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                }
        }
    }

    private fun listenForHubToAlternateBouncer() {
        scope.launch("$TAG#listenForHubToAlternateBouncer") {
            keyguardInteractor.alternateBouncerShowing
                .filterRelevantKeyguardStateAnd { alternateBouncerShowing ->
                    alternateBouncerShowing
                }
                .collect { pair ->
                    // Bouncer shows on top of the hub, so do not change scenes here.
                    startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
                }
        }
    }

    private fun listenForHubToDozing() {
        scope.launch {
            powerInteractor.isAsleep
                .filterRelevantKeyguardStateAnd { isAsleep -> isAsleep }
                .collect {
                    if (communalSceneKtfRefactor()) {
                        communalSceneInteractor.snapToScene(
                            newScene = CommunalScenes.Blank,
                            loggingReason = "hub to dozing",
                            keyguardState = KeyguardState.DOZING,
                        )
                    } else {
                        startTransitionTo(
                            toState = KeyguardState.DOZING,
                            modeOnCanceled = TransitionModeOnCanceled.LAST_VALUE,
                        )
                    }
                }
        }
    }

    private fun listenForHubToOccluded() {
        if (KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop
                    .filterRelevantKeyguardStateAnd { onTop -> onTop }
                    .collect {
                        maybeStartTransitionToOccludedOrInsecureCamera { state, reason ->
                            if (communalSceneKtfRefactor()) {
                                communalSceneInteractor.changeScene(
                                    newScene = CommunalScenes.Blank,
                                    loggingReason = "hub to occluded (KeyguardWmStateRefactor)",
                                    transitionKey = CommunalTransitionKeys.SimpleFade,
                                    keyguardState = state,
                                )
                                null
                            } else {
                                startTransitionTo(state, ownerReason = reason)
                            }
                        }
                    }
            }
        } else if (communalSceneKtfRefactor()) {
            scope.launch {
                combine(
                        keyguardInteractor.isKeyguardOccluded,
                        keyguardInteractor.isDreaming,
                        ::Pair,
                    )
                    // Debounce signals since there is a race condition between the occluded and
                    // dreaming signals when starting or stopping dreaming. We therefore add a small
                    // delay to give enough time for occluded to flip to false when the dream
                    // ends, to avoid transitioning to OCCLUDED erroneously when exiting the dream
                    // or when the dream starts underneath the hub.
                    .debounce(200.milliseconds)
                    .sampleFilter(
                        // When launching activities from widgets on the hub, we have a
                        // custom occlusion animation.
                        communalSceneInteractor.isLaunchingWidget
                    ) { launchingWidget ->
                        !launchingWidget
                    }
                    .filterRelevantKeyguardStateAnd { (isOccluded, isDreaming) ->
                        isOccluded && !isDreaming
                    }
                    .collect { _ ->
                        communalSceneInteractor.changeScene(
                            newScene = CommunalScenes.Blank,
                            loggingReason = "hub to occluded",
                            transitionKey = CommunalTransitionKeys.SimpleFade,
                            keyguardState = KeyguardState.OCCLUDED,
                        )
                    }
            }
        } else {
            scope.launch {
                allOf(keyguardInteractor.isKeyguardOccluded, not(keyguardInteractor.isDreaming))
                    .filterRelevantKeyguardStateAnd { isOccludedAndNotDreaming ->
                        isOccludedAndNotDreaming
                    }
                    .collect { _ -> startTransitionTo(KeyguardState.OCCLUDED) }
            }
        }
    }

    private fun listenForHubToGone() {
        if (SceneContainerFlag.isEnabled) return
        if (communalSceneKtfRefactor()) {
            scope.launch {
                allOf(
                        keyguardInteractor.isKeyguardGoingAway,
                        // TODO(b/327225415): Handle edit mode opening here to avoid going to GONE
                        // state until after edit mode is ready to be shown.
                        noneOf(
                            // When launching activities from widgets on the hub, we wait to change
                            // scenes until the activity launch is complete.
                            communalSceneInteractor.isLaunchingWidget
                        ),
                    )
                    .filterRelevantKeyguardStateAnd { isKeyguardGoingAway -> isKeyguardGoingAway }
                    .sample(communalSceneInteractor.editModeState, ::Pair)
                    .collect { (_, editModeState) ->
                        if (
                            editModeState == EditModeState.STARTING ||
                                editModeState == EditModeState.SHOWING
                        ) {
                            // Don't change scenes here as that is handled by the edit activity.
                            startTransitionTo(KeyguardState.GONE)
                        } else {
                            communalSceneInteractor.changeScene(
                                newScene = CommunalScenes.Blank,
                                loggingReason = "hub to gone",
                                transitionKey = CommunalTransitionKeys.SimpleFade,
                                keyguardState = KeyguardState.GONE,
                            )
                        }
                    }
            }
        } else {
            scope.launch {
                keyguardInteractor.isKeyguardGoingAway
                    .filterRelevantKeyguardStateAnd { isKeyguardGoingAway -> isKeyguardGoingAway }
                    .collect { startTransitionTo(KeyguardState.GONE) }
            }
        }
    }

    companion object {
        const val TAG = "FromGlanceableHubTransitionInteractor"

        /**
         * DEFAULT_DURATION controls the timing for all animations other than those with overrides
         * in [getDefaultAnimatorForTransitionsToState].
         *
         * Set at 400ms for parity with [FromLockscreenTransitionInteractor]
         */
        val DEFAULT_DURATION = 400.milliseconds
        val TO_LOCKSCREEN_DURATION = 1.seconds
        val TO_BOUNCER_DURATION = 400.milliseconds
        val TO_OCCLUDED_DURATION = 450.milliseconds
    }
}
