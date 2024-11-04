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

package com.android.systemui.deviceentry.domain.interactor

import androidx.annotation.VisibleForTesting
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.biometrics.AuthController
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardBypassInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_DISMISS_BOUNCER
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_NONE
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_ONLY_WAKE
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_SHOW_BOUNCER
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_UNLOCK_COLLAPSING
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
import com.android.systemui.statusbar.phone.BiometricUnlockController.WakeAndUnlockMode
import com.android.systemui.statusbar.phone.DozeScrimController
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.Utils.Companion.sampleFilter
import com.android.systemui.util.kotlin.combine
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Hosts application business logic related to the source of the user entering the device. Note: The
 * source of the user entering the device isn't equivalent to the reason the device is unlocked.
 *
 * For example, the user successfully enters the device when they dismiss the lockscreen via a
 * bypass biometric or, if the device is already unlocked, by triggering an affordance that
 * dismisses the lockscreen.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class DeviceEntrySourceInteractor
@Inject
constructor(
    authenticationInteractor: AuthenticationInteractor,
    authController: AuthController,
    alternateBouncerInteractor: AlternateBouncerInteractor,
    deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
    deviceEntryFingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    dozeScrimController: DozeScrimController,
    keyguardBypassInteractor: KeyguardBypassInteractor,
    keyguardUpdateMonitor: KeyguardUpdateMonitor,
    keyguardInteractor: KeyguardInteractor,
    sceneContainerOcclusionInteractor: SceneContainerOcclusionInteractor,
    sceneInteractor: SceneInteractor,
    dumpManager: DumpManager,
) : FlowDumperImpl(dumpManager) {
    private val isShowingBouncerScene: Flow<Boolean> =
        sceneInteractor.transitionState
            .map { transitionState ->
                transitionState.isIdle(Scenes.Bouncer) ||
                    transitionState.isTransitioning(null, Scenes.Bouncer)
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("isShowingBouncerScene")

    private val isUnlockedWithStrongFaceUnlock =
        deviceEntryFaceAuthInteractor.authenticationStatus
            .map { status ->
                (status as? SuccessFaceAuthenticationStatus)?.successResult?.isStrongBiometric
                    ?: false
            }
            .dumpWhileCollecting("unlockedWithStrongFaceUnlock")

    private val isUnlockedWithStrongFingerprintUnlock =
        deviceEntryFingerprintAuthInteractor.authenticationStatus
            .map { status ->
                (status as? SuccessFingerprintAuthenticationStatus)?.isStrongBiometric ?: false
            }
            .dumpWhileCollecting("unlockedWithStrongFingerprintUnlock")

    private val faceWakeAndUnlockMode: Flow<BiometricUnlockMode> =
        combine(
                alternateBouncerInteractor.isVisible,
                keyguardBypassInteractor.isBypassAvailable,
                isUnlockedWithStrongFaceUnlock,
                sceneContainerOcclusionInteractor.isOccludingActivityShown,
                sceneInteractor.currentScene,
                isShowingBouncerScene,
            ) {
                isAlternateBouncerVisible,
                isBypassAvailable,
                isFaceStrongBiometric,
                isOccluded,
                currentScene,
                isShowingBouncerScene ->
                val isUnlockingAllowed =
                    keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(isFaceStrongBiometric)
                val bypass = isBypassAvailable || authController.isUdfpsFingerDown()

                when {
                    !keyguardUpdateMonitor.isDeviceInteractive ->
                        when {
                            !isUnlockingAllowed -> if (bypass) MODE_SHOW_BOUNCER else MODE_NONE
                            bypass -> MODE_WAKE_AND_UNLOCK_PULSING
                            else -> MODE_ONLY_WAKE
                        }

                    isUnlockingAllowed && currentScene == Scenes.Dream ->
                        if (bypass) MODE_WAKE_AND_UNLOCK_FROM_DREAM else MODE_ONLY_WAKE

                    isUnlockingAllowed && isOccluded -> MODE_UNLOCK_COLLAPSING

                    (isShowingBouncerScene || isAlternateBouncerVisible) && isUnlockingAllowed ->
                        MODE_DISMISS_BOUNCER

                    isUnlockingAllowed && bypass -> MODE_UNLOCK_COLLAPSING

                    bypass -> MODE_SHOW_BOUNCER

                    else -> MODE_NONE
                }
            }
            .map { biometricModeIntToObject(it) }
            .dumpWhileCollecting("faceWakeAndUnlockMode")

    private val fingerprintWakeAndUnlockMode: Flow<BiometricUnlockMode> =
        combine(
                alternateBouncerInteractor.isVisible,
                authenticationInteractor.authenticationMethod,
                sceneInteractor.currentScene,
                isUnlockedWithStrongFingerprintUnlock,
                isShowingBouncerScene,
            ) {
                alternateBouncerVisible,
                authenticationMethod,
                currentScene,
                isFingerprintStrongBiometric,
                isShowingBouncerScene ->
                val unlockingAllowed =
                    keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                        isFingerprintStrongBiometric
                    )
                when {
                    !keyguardUpdateMonitor.isDeviceInteractive ->
                        when {
                            dozeScrimController.isPulsing && unlockingAllowed ->
                                MODE_WAKE_AND_UNLOCK_PULSING

                            unlockingAllowed || !authenticationMethod.isSecure ->
                                MODE_WAKE_AND_UNLOCK

                            else -> MODE_SHOW_BOUNCER
                        }

                    unlockingAllowed && currentScene == Scenes.Dream ->
                        MODE_WAKE_AND_UNLOCK_FROM_DREAM

                    isShowingBouncerScene && unlockingAllowed -> MODE_DISMISS_BOUNCER

                    unlockingAllowed -> MODE_UNLOCK_COLLAPSING

                    currentScene != Scenes.Bouncer && !alternateBouncerVisible -> MODE_SHOW_BOUNCER

                    else -> MODE_NONE
                }
            }
            .map { biometricModeIntToObject(it) }
            .dumpWhileCollecting("fingerprintWakeAndUnlockMode")

    @VisibleForTesting
    private val biometricUnlockStateOnKeyguardDismissed =
        merge(
                fingerprintWakeAndUnlockMode
                    .filter { BiometricUnlockMode.dismissesKeyguard(it) }
                    .map { mode ->
                        BiometricUnlockModel(mode, BiometricUnlockSource.FINGERPRINT_SENSOR)
                    },
                faceWakeAndUnlockMode
                    .filter { BiometricUnlockMode.dismissesKeyguard(it) }
                    .map { mode -> BiometricUnlockModel(mode, BiometricUnlockSource.FACE_SENSOR) },
            )
            .dumpWhileCollecting("biometricUnlockState")

    private val deviceEntryFingerprintAuthWakeAndUnlockEvents:
        Flow<SuccessFingerprintAuthenticationStatus> =
        deviceEntryFingerprintAuthInteractor.authenticationStatus
            .filterIsInstance<SuccessFingerprintAuthenticationStatus>()
            .dumpWhileCollecting("deviceEntryFingerprintAuthSuccessEvents")

    private val deviceEntryFaceAuthWakeAndUnlockEvents: Flow<SuccessFaceAuthenticationStatus> =
        deviceEntryFaceAuthInteractor.authenticationStatus
            .filterIsInstance<SuccessFaceAuthenticationStatus>()
            .sampleFilter(
                combine(
                    sceneContainerOcclusionInteractor.isOccludingActivityShown,
                    keyguardBypassInteractor.isBypassAvailable,
                    keyguardBypassInteractor.canBypass,
                    ::Triple,
                )
            ) { (isOccludingActivityShown, isBypassAvailable, canBypass) ->
                isOccludingActivityShown || !isBypassAvailable || canBypass
            }
            .dumpWhileCollecting("deviceEntryFaceAuthSuccessEvents")

    private val deviceEntryBiometricAuthSuccessEvents =
        merge(deviceEntryFingerprintAuthWakeAndUnlockEvents, deviceEntryFaceAuthWakeAndUnlockEvents)
            .dumpWhileCollecting("deviceEntryBiometricAuthSuccessEvents")

    val deviceEntryFromBiometricSource: Flow<BiometricUnlockSource> =
        if (SceneContainerFlag.isEnabled) {
                deviceEntryBiometricAuthSuccessEvents
                    .sample(biometricUnlockStateOnKeyguardDismissed)
                    .map { it.source }
                    .filterNotNull()
            } else {
                keyguardInteractor.biometricUnlockState
                    .filter { BiometricUnlockMode.dismissesKeyguard(it.mode) }
                    .map { it.source }
                    .filterNotNull()
            }
            .dumpWhileCollecting("deviceEntryFromBiometricSource")

    private val attemptEnterDeviceFromDeviceEntryIcon: MutableSharedFlow<Unit> = MutableSharedFlow()
    val deviceEntryFromDeviceEntryIcon: Flow<Unit> =
        attemptEnterDeviceFromDeviceEntryIcon
            .sample(keyguardInteractor.isKeyguardDismissible)
            .filter { it } // only send events if the keyguard is dismissible
            .map {} // map to Unit

    suspend fun attemptEnterDeviceFromDeviceEntryIcon() {
        attemptEnterDeviceFromDeviceEntryIcon.emit(Unit)
    }

    private fun biometricModeIntToObject(@WakeAndUnlockMode value: Int): BiometricUnlockMode {
        return when (value) {
            MODE_NONE -> BiometricUnlockMode.NONE
            MODE_WAKE_AND_UNLOCK -> BiometricUnlockMode.WAKE_AND_UNLOCK
            MODE_WAKE_AND_UNLOCK_PULSING -> BiometricUnlockMode.WAKE_AND_UNLOCK_PULSING
            MODE_SHOW_BOUNCER -> BiometricUnlockMode.SHOW_BOUNCER
            MODE_ONLY_WAKE -> BiometricUnlockMode.ONLY_WAKE
            MODE_UNLOCK_COLLAPSING -> BiometricUnlockMode.UNLOCK_COLLAPSING
            MODE_WAKE_AND_UNLOCK_FROM_DREAM -> BiometricUnlockMode.WAKE_AND_UNLOCK_FROM_DREAM
            MODE_DISMISS_BOUNCER -> BiometricUnlockMode.DISMISS_BOUNCER
            else -> throw IllegalArgumentException("Invalid BiometricUnlockModel value: $value")
        }
    }
}
