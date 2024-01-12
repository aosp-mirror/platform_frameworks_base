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
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardSurfaceBehindModel
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.Utils.Companion.toQuint
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import com.android.wm.shell.animation.Interpolators
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@SysUISingleton
class FromPrimaryBouncerTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    private val flags: FeatureFlags,
    private val keyguardSecurityModel: KeyguardSecurityModel,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val powerInteractor: PowerInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.PRIMARY_BOUNCER,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
    ) {

    override fun start() {
        listenForPrimaryBouncerToGone()
        listenForPrimaryBouncerToAodOrDozing()
        listenForPrimaryBouncerToLockscreenOrOccluded()
        listenForPrimaryBouncerToDreamingLockscreenHosted()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    val surfaceBehindVisibility: Flow<Boolean?> =
        combine(
                transitionInteractor.startedKeyguardTransitionStep,
                transitionInteractor.transitionStepsFromState(KeyguardState.PRIMARY_BOUNCER)
            ) { startedStep, fromBouncerStep ->
                if (startedStep.to != KeyguardState.GONE) {
                    return@combine null
                }

                fromBouncerStep.value > 0.5f
            }
            .onStart {
                // Default to null ("don't care, use a reasonable default").
                emit(null)
            }
            .distinctUntilChanged()

    val surfaceBehindModel: Flow<KeyguardSurfaceBehindModel?> =
        combine(
                transitionInteractor.startedKeyguardTransitionStep,
                transitionInteractor.transitionStepsFromState(KeyguardState.PRIMARY_BOUNCER)
            ) { startedStep, fromBouncerStep ->
                if (startedStep.to != KeyguardState.GONE) {
                    // BOUNCER to anything but GONE does not require any special surface
                    // visibility handling.
                    return@combine null
                }

                if (fromBouncerStep.value > 0.5f) {
                    KeyguardSurfaceBehindModel(
                        animateFromAlpha = 0f,
                        alpha = 1f,
                        animateFromTranslationY = 500f,
                        translationY = 0f,
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

    fun dismissPrimaryBouncer() {
        scope.launch { startTransitionTo(KeyguardState.GONE) }
    }

    private fun listenForPrimaryBouncerToLockscreenOrOccluded() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(
                    combine(
                        powerInteractor.isAwake,
                        startedKeyguardTransitionStep,
                        keyguardInteractor.isKeyguardOccluded,
                        keyguardInteractor.isActiveDreamLockscreenHosted,
                        ::toQuad
                    ),
                    ::toQuint
                )
                .collect {
                    (
                        isBouncerShowing,
                        isAwake,
                        lastStartedTransitionStep,
                        occluded,
                        isActiveDreamLockscreenHosted) ->
                    if (
                        !isBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.PRIMARY_BOUNCER &&
                            isAwake &&
                            !isActiveDreamLockscreenHosted
                    ) {
                        startTransitionTo(
                            if (occluded) KeyguardState.OCCLUDED else KeyguardState.LOCKSCREEN
                        )
                    }
                }
        }
    }

    private fun listenForPrimaryBouncerToAodOrDozing() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(
                    combine(
                        powerInteractor.isAsleep,
                        startedKeyguardTransitionStep,
                        keyguardInteractor.isAodAvailable,
                        ::Triple
                    ),
                    ::toQuad
                )
                .collect { (isBouncerShowing, isAsleep, lastStartedTransitionStep, isAodAvailable)
                    ->
                    if (
                        !isBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.PRIMARY_BOUNCER &&
                            isAsleep
                    ) {
                        startTransitionTo(
                            if (isAodAvailable) KeyguardState.AOD else KeyguardState.DOZING
                        )
                    }
                }
        }
    }

    private fun listenForPrimaryBouncerToDreamingLockscreenHosted() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(
                    combine(
                        keyguardInteractor.isActiveDreamLockscreenHosted,
                        startedKeyguardTransitionStep,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect {
                    (isBouncerShowing, isActiveDreamLockscreenHosted, lastStartedTransitionStep) ->
                    if (
                        !isBouncerShowing &&
                            isActiveDreamLockscreenHosted &&
                            lastStartedTransitionStep.to == KeyguardState.PRIMARY_BOUNCER
                    ) {
                        startTransitionTo(KeyguardState.DREAMING_LOCKSCREEN_HOSTED)
                    }
                }
        }
    }

    private fun listenForPrimaryBouncerToGone() {
        if (flags.isEnabled(Flags.KEYGUARD_WM_STATE_REFACTOR)) {
            // This is handled in KeyguardSecurityContainerController and
            // StatusBarKeyguardViewManager, which calls the transition interactor to kick off a
            // transition vs. listening to legacy state flags.
            return
        }

        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (isKeyguardGoingAway, lastStartedTransitionStep) ->
                    if (
                        isKeyguardGoingAway &&
                            lastStartedTransitionStep.to == KeyguardState.PRIMARY_BOUNCER
                    ) {
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
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration = DEFAULT_DURATION.inWholeMilliseconds
        }
    }

    companion object {
        private val DEFAULT_DURATION = 300.milliseconds
        val TO_GONE_DURATION = 500.milliseconds
        val TO_GONE_SHORT_DURATION = 200.milliseconds
        val TO_AOD_DURATION = DEFAULT_DURATION
        val TO_LOCKSCREEN_DURATION = DEFAULT_DURATION
    }
}
