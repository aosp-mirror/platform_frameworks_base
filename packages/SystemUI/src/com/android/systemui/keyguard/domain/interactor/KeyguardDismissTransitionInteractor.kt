/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.util.Log
import com.android.systemui.Flags.transitionRaceCondition
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class KeyguardDismissTransitionInteractor
@Inject
constructor(
    @Background private val scope: CoroutineScope,
    private val repository: KeyguardTransitionRepository,
    private val fromLockscreenTransitionInteractor: FromLockscreenTransitionInteractor,
    private val fromPrimaryBouncerTransitionInteractor: FromPrimaryBouncerTransitionInteractor,
    private val fromAodTransitionInteractor: FromAodTransitionInteractor,
    private val fromAlternateBouncerTransitionInteractor: FromAlternateBouncerTransitionInteractor,
    private val fromDozingTransitionInteractor: FromDozingTransitionInteractor,
    private val fromOccludedTransitionInteractor: FromOccludedTransitionInteractor,
) {

    /**
     * Launches a coroutine to start a transition that will ultimately dismiss the keyguard from the
     * current state.
     *
     * This is called exclusively by sources that can authoritatively say we should be unlocked,
     * including KeyguardSecurityContainerController and WindowManager.
     *
     * This is one of the few transitions that is started outside of the From*TransitionInteractor
     * classes. This is because this is an external call that must be respected, so it doesn't
     * matter what state we're in/coming from - we must transition from that state to GONE.
     *
     * Invokes [onAlreadyGone] if the transition was not started because we're already GONE by the
     * time the coroutine runs.
     */
    @JvmOverloads
    fun startDismissKeyguardTransition(reason: String = "", onAlreadyGone: (() -> Unit)? = null) {
        if (SceneContainerFlag.isEnabled) return
        Log.d(TAG, "#startDismissKeyguardTransition(reason=$reason)")

        scope.launch {
            val startedState =
                if (transitionRaceCondition()) {
                    repository.currentTransitionInfo.to
                } else {
                    repository.currentTransitionInfoInternal.value.to
                }

            val animator: ValueAnimator? =
                when (startedState) {
                    LOCKSCREEN -> fromLockscreenTransitionInteractor
                    PRIMARY_BOUNCER -> fromPrimaryBouncerTransitionInteractor
                    ALTERNATE_BOUNCER -> fromAlternateBouncerTransitionInteractor
                    AOD -> fromAodTransitionInteractor
                    DOZING -> fromDozingTransitionInteractor
                    OCCLUDED -> fromOccludedTransitionInteractor
                    else -> null
                }?.getDefaultAnimatorForTransitionsToState(KeyguardState.GONE)

            if (startedState != KeyguardState.GONE && animator != null) {
                repository.startTransition(
                    TransitionInfo(
                        "KeyguardDismissTransitionInteractor" +
                            if (reason.isNotBlank()) "($reason)" else "",
                        startedState,
                        KeyguardState.GONE,
                        animator,
                        TransitionModeOnCanceled.LAST_VALUE,
                    )
                )
            } else {
                Log.i(
                    TAG,
                    "Can't transition to GONE from $startedState; " +
                        "ignoring startDismissKeyguardTransition.",
                )
                onAlreadyGone?.invoke()
            }
        }
    }

    companion object {
        private val TAG = KeyguardDismissTransitionInteractor::class.simpleName
    }
}
