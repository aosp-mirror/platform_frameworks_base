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

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import javax.inject.Inject

@SysUISingleton
class KeyguardDismissTransitionInteractor
@Inject
constructor(
    private val repository: KeyguardTransitionRepository,
    private val fromLockscreenTransitionInteractor: FromLockscreenTransitionInteractor,
    private val fromPrimaryBouncerTransitionInteractor: FromPrimaryBouncerTransitionInteractor,
    private val fromAodTransitionInteractor: FromAodTransitionInteractor,
    private val fromAlternateBouncerTransitionInteractor: FromAlternateBouncerTransitionInteractor,
    private val fromDozingTransitionInteractor: FromDozingTransitionInteractor,
    private val fromOccludedTransitionInteractor: FromOccludedTransitionInteractor,
) {

    /**
     * Called to start a transition that will ultimately dismiss the keyguard from the current
     * state.
     *
     * This is called exclusively by sources that can authoritatively say we should be unlocked,
     * including KeyguardSecurityContainerController and WindowManager.
     */
    fun startDismissKeyguardTransition(reason: String = "") {
        if (SceneContainerFlag.isEnabled) return
        Log.d(TAG, "#startDismissKeyguardTransition(reason=$reason)")
        when (val startedState = repository.currentTransitionInfoInternal.value.to) {
            LOCKSCREEN -> fromLockscreenTransitionInteractor.dismissKeyguard()
            PRIMARY_BOUNCER -> fromPrimaryBouncerTransitionInteractor.dismissPrimaryBouncer()
            ALTERNATE_BOUNCER -> fromAlternateBouncerTransitionInteractor.dismissAlternateBouncer()
            AOD -> fromAodTransitionInteractor.dismissAod()
            DOZING -> fromDozingTransitionInteractor.dismissFromDozing()
            KeyguardState.OCCLUDED -> fromOccludedTransitionInteractor.dismissFromOccluded()
            KeyguardState.GONE ->
                Log.i(
                    TAG,
                    "Already transitioning to GONE; ignoring startDismissKeyguardTransition."
                )
            else -> Log.e(TAG, "We don't know how to dismiss keyguard from state $startedState.")
        }
    }

    companion object {
        private val TAG = KeyguardDismissTransitionInteractor::class.simpleName
    }
}
