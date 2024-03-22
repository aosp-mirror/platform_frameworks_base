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

package com.android.systemui.deviceentry.domain.interactor

import android.content.Context
import android.content.Intent
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.shared.model.BiometricMessage
import com.android.systemui.deviceentry.shared.model.FingerprintLockoutMessage
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Business logic for handling authentication events when an app is occluding the lockscreen. */
@ExperimentalCoroutinesApi
@SysUISingleton
class OccludingAppDeviceEntryInteractor
@Inject
constructor(
    biometricMessageInteractor: BiometricMessageInteractor,
    fingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    keyguardInteractor: KeyguardInteractor,
    primaryBouncerInteractor: PrimaryBouncerInteractor,
    alternateBouncerInteractor: AlternateBouncerInteractor,
    @Application scope: CoroutineScope,
    private val context: Context,
    activityStarter: ActivityStarter,
    powerInteractor: PowerInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {
    private val keyguardOccludedByApp: Flow<Boolean> =
        if (KeyguardWmStateRefactor.isEnabled) {
            keyguardTransitionInteractor.currentKeyguardState.map { it == KeyguardState.OCCLUDED }
        } else {
            combine(
                    keyguardInteractor.isKeyguardOccluded,
                    keyguardInteractor.isKeyguardShowing,
                    primaryBouncerInteractor.isShowing,
                    alternateBouncerInteractor.isVisible,
                    keyguardInteractor.isDozing,
                ) { occluded, showing, primaryBouncerShowing, alternateBouncerVisible, dozing ->
                    occluded &&
                        showing &&
                        !primaryBouncerShowing &&
                        !alternateBouncerVisible &&
                        !dozing
                }
                .distinctUntilChanged()
        }

    private val fingerprintUnlockSuccessEvents: Flow<Unit> =
        fingerprintAuthRepository.authenticationStatus
            .ifKeyguardOccludedByApp()
            .filter { it is SuccessFingerprintAuthenticationStatus }
            .map {} // maps FingerprintAuthenticationStatus => Unit
    private val fingerprintLockoutEvents: Flow<Unit> =
        fingerprintAuthRepository.authenticationStatus
            .ifKeyguardOccludedByApp()
            .filter { it is ErrorFingerprintAuthenticationStatus && it.isLockoutError() }
            .map {} // maps FingerprintAuthenticationStatus => Unit
    val message: Flow<BiometricMessage?> =
        biometricMessageInteractor.fingerprintMessage
            .filterNot { fingerprintMessage ->
                // On lockout, the device will show the bouncer. Let's not show the message
                // before the transition or else it'll look flickery.
                fingerprintMessage is FingerprintLockoutMessage
            }
            .ifKeyguardOccludedByApp(/* elseFlow */ flowOf(null))

    init {
        scope.launch {
            // On fingerprint success when the screen is on and not dreaming, go to the home screen
            fingerprintUnlockSuccessEvents
                .sample(
                    combine(powerInteractor.isInteractive, keyguardInteractor.isDreaming, ::Pair),
                )
                .collect { (interactive, dreaming) ->
                    if (interactive && !dreaming) {
                        goToHomeScreen()
                    }
                    // don't go to the home screen if the authentication is from
                    // AOD/dozing/off/dreaming
                }
        }

        scope.launch {
            // On device fingerprint lockout, request the bouncer with a runnable to
            // go to the home screen. Without this, the bouncer won't proceed to the home
            // screen.
            fingerprintLockoutEvents.collect {
                activityStarter.dismissKeyguardThenExecute(
                    object : ActivityStarter.OnDismissAction {
                        override fun onDismiss(): Boolean {
                            goToHomeScreen()
                            return false
                        }

                        override fun willRunAnimationOnKeyguard(): Boolean {
                            return false
                        }
                    },
                    /* cancel= */ null,
                    /* afterKeyguardGone */ false
                )
            }
        }
    }

    /** Launches an Activity which forces the current app to background by going home. */
    private fun goToHomeScreen() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    private fun <T> Flow<T>.ifKeyguardOccludedByApp(elseFlow: Flow<T> = emptyFlow()): Flow<T> {
        return keyguardOccludedByApp.flatMapLatest { keyguardOccludedByApp ->
            if (keyguardOccludedByApp) {
                this
            } else {
                elseFlow
            }
        }
    }
}
