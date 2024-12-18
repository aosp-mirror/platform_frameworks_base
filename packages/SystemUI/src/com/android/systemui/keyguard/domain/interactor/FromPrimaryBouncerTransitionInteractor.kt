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
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.Flags.communalSceneKtfRefactor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.kotlin.sample
import com.android.wm.shell.shared.animation.Interpolators
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@SysUISingleton
class FromPrimaryBouncerTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val internalTransitionInteractor: InternalKeyguardTransitionInteractor,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    keyguardInteractor: KeyguardInteractor,
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val keyguardSecurityModel: KeyguardSecurityModel,
    private val selectedUserInteractor: SelectedUserInteractor,
    powerInteractor: PowerInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.PRIMARY_BOUNCER,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
        keyguardInteractor = keyguardInteractor,
    ) {

    override fun start() {
        listenForPrimaryBouncerToGone()
        listenForPrimaryBouncerToAsleep()
        listenForPrimaryBouncerNotShowing()
        listenForPrimaryBouncerToDreamingLockscreenHosted()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    val surfaceBehindVisibility: Flow<Boolean?> =
        transitionInteractor
            .transition(
                edge = Edge.INVALID,
                edgeWithoutSceneContainer =
                    Edge.create(from = KeyguardState.PRIMARY_BOUNCER, to = KeyguardState.GONE),
            )
            .map<TransitionStep, Boolean?> { it.value > TO_GONE_SURFACE_BEHIND_VISIBLE_THRESHOLD }
            .onStart {
                // Default to null ("don't care, use a reasonable default").
                emit(null)
            }
            .distinctUntilChanged()

    fun dismissPrimaryBouncer() {
        scope.launch {
            startTransitionTo(KeyguardState.GONE)
            closeHubImmediatelyIfNeeded()
        }
    }

    private fun listenForPrimaryBouncerNotShowing() {
        if (SceneContainerFlag.isEnabled) return
        if (KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                keyguardInteractor.primaryBouncerShowing
                    .sample(
                        powerInteractor.isAwake,
                        keyguardInteractor.isActiveDreamLockscreenHosted,
                        communalSceneInteractor.isIdleOnCommunal,
                    )
                    .filterRelevantKeyguardStateAnd { (isBouncerShowing, _, _, _) ->
                        // TODO(b/307976454) - See if we need to listen for SHOW_WHEN_LOCKED
                        // activities showing up over the bouncer. Camera launch can't show up over
                        // bouncer since the first power press hides bouncer. Do occluding
                        // activities auto hide bouncer? Not sure.
                        !isBouncerShowing
                    }
                    .collect { (_, isAwake, isActiveDreamLockscreenHosted, isIdleOnCommunal) ->
                        if (
                            !maybeStartTransitionToOccludedOrInsecureCamera { state, reason ->
                                startTransitionTo(state, ownerReason = reason)
                            } && isAwake && !isActiveDreamLockscreenHosted
                        ) {
                            val toState =
                                if (isIdleOnCommunal) {
                                    KeyguardState.GLANCEABLE_HUB
                                } else {
                                    KeyguardState.LOCKSCREEN
                                }
                            startTransitionTo(toState)
                        }
                    }
            }
        } else {
            scope.launch {
                keyguardInteractor.primaryBouncerShowing
                    .filterRelevantKeyguardStateAnd { isBouncerShowing -> !isBouncerShowing }
                    .sample(
                        powerInteractor.isAwake,
                        keyguardInteractor.isDreaming,
                        communalSceneInteractor.isIdleOnCommunal,
                    )
                    .collect { (_, isAwake, isDreaming, isIdleOnCommunal) ->
                        val isOccluded = keyguardInteractor.isKeyguardOccluded.value
                        val toState =
                            if (isAwake) {
                                if (isOccluded && !isDreaming) {
                                    KeyguardState.OCCLUDED
                                } else if (isIdleOnCommunal) {
                                    KeyguardState.GLANCEABLE_HUB
                                } else if (isDreaming) {
                                    KeyguardState.DREAMING
                                } else {
                                    KeyguardState.LOCKSCREEN
                                }
                            } else {
                                // This shouldn't necessarily happen, but there's a bug in the
                                // bouncer logic which is incorrectly showing/hiding rapidly
                                Log.i(
                                    TAG,
                                    "Going back to sleeping state to correct an attempt to " +
                                        "show bouncer",
                                )
                                keyguardInteractor.asleepKeyguardState.value
                            }
                        startTransitionTo(toState)
                    }
            }
        }
    }

    private fun closeHubImmediatelyIfNeeded() {
        // If the hub is showing, and we are not animating a widget launch nor transitioning to
        // edit mode, then close the hub immediately.
        if (
            communalSceneKtfRefactor() &&
                communalSceneInteractor.isIdleOnCommunal.value &&
                !communalSceneInteractor.isLaunchingWidget.value &&
                communalSceneInteractor.editModeState.value == null
        ) {
            communalSceneInteractor.snapToScene(
                newScene = CommunalScenes.Blank,
                loggingReason = "FromPrimaryBouncerTransitionInteractor",
            )
        }
    }

    private fun listenForPrimaryBouncerToAsleep() {
        if (SceneContainerFlag.isEnabled) return
        scope.launch { listenForSleepTransition() }
    }

    private fun listenForPrimaryBouncerToDreamingLockscreenHosted() {
        if (SceneContainerFlag.isEnabled) return
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(keyguardInteractor.isActiveDreamLockscreenHosted, ::Pair)
                .filterRelevantKeyguardStateAnd { (isBouncerShowing, isActiveDreamLockscreenHosted)
                    ->
                    !isBouncerShowing && isActiveDreamLockscreenHosted
                }
                .collect { startTransitionTo(KeyguardState.DREAMING_LOCKSCREEN_HOSTED) }
        }
    }

    private fun listenForPrimaryBouncerToGone() {
        if (SceneContainerFlag.isEnabled) return
        if (KeyguardWmStateRefactor.isEnabled) {
            // This is handled in KeyguardSecurityContainerController and
            // StatusBarKeyguardViewManager, which calls the transition interactor to kick off a
            // transition vs. listening to legacy state flags.
            return
        }

        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .filterRelevantKeyguardStateAnd { isKeyguardGoingAway -> isKeyguardGoingAway }
                .collect {
                    val securityMode =
                        keyguardSecurityModel.getSecurityMode(
                            selectedUserInteractor.getSelectedUserId()
                        )
                    // IME for password requires a slightly faster animation
                    val duration =
                        if (securityMode == KeyguardSecurityModel.SecurityMode.Password) {
                            TO_GONE_SHORT_DURATION
                        } else {
                            TO_GONE_DURATION
                        }

                    startTransitionTo(
                        toState = KeyguardState.GONE,
                        animator =
                            getDefaultAnimatorForTransitionsToState(KeyguardState.GONE).apply {
                                this.duration = duration.inWholeMilliseconds
                            },
                        modeOnCanceled = TransitionModeOnCanceled.RESET,
                    )
                    closeHubImmediatelyIfNeeded()
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.AOD -> TO_AOD_DURATION
                    KeyguardState.DOZING -> TO_DOZING_DURATION
                    KeyguardState.GONE -> TO_GONE_DURATION
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    KeyguardState.GLANCEABLE_HUB -> TO_GLANCEABLE_HUB_DURATION
                    KeyguardState.OCCLUDED -> TO_OCCLUDED_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        private const val TAG = "FromPrimaryBouncerTransitionInteractor"
        private val DEFAULT_DURATION = 300.milliseconds
        val TO_AOD_DURATION = DEFAULT_DURATION
        val TO_DOZING_DURATION = DEFAULT_DURATION
        val TO_GONE_DURATION = 500.milliseconds
        val TO_GONE_SHORT_DURATION = 200.milliseconds
        val TO_LOCKSCREEN_DURATION = 450.milliseconds
        val TO_OCCLUDED_DURATION = 550.milliseconds
        val TO_GLANCEABLE_HUB_DURATION = DEFAULT_DURATION
        val TO_GONE_SURFACE_BEHIND_VISIBLE_THRESHOLD = 0.1f
    }
}
