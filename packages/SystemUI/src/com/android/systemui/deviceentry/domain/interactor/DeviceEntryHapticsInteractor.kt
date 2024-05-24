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

import com.android.keyguard.logging.BiometricUnlockLogger
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyevent.domain.interactor.KeyEventInteractor
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Business logic for device entry haptic events. Determines whether the haptic should play. In
 * particular, there are extra guards for whether device entry error and successes haptics should
 * play when the physical fingerprint sensor is located on the power button.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class DeviceEntryHapticsInteractor
@Inject
constructor(
    deviceEntrySourceInteractor: DeviceEntrySourceInteractor,
    deviceEntryFingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    deviceEntryBiometricAuthInteractor: DeviceEntryBiometricAuthInteractor,
    fingerprintPropertyRepository: FingerprintPropertyRepository,
    biometricSettingsRepository: BiometricSettingsRepository,
    keyEventInteractor: KeyEventInteractor,
    powerInteractor: PowerInteractor,
    private val systemClock: SystemClock,
    private val logger: BiometricUnlockLogger,
) {
    private val powerButtonSideFpsEnrolled =
        combineTransform(
                fingerprintPropertyRepository.sensorType,
                biometricSettingsRepository.isFingerprintEnrolledAndEnabled,
            ) { sensorType, enrolledAndEnabled ->
                if (sensorType == FingerprintSensorType.POWER_BUTTON) {
                    emit(enrolledAndEnabled)
                } else {
                    emit(false)
                }
            }
            .distinctUntilChanged()
    private val powerButtonDown: Flow<Boolean> = keyEventInteractor.isPowerButtonDown
    private val lastPowerButtonWakeup: Flow<Long> =
        powerInteractor.detailedWakefulness
            .filter { it.isAwakeFrom(WakeSleepReason.POWER_BUTTON) }
            .map { systemClock.uptimeMillis() }
            .onStart {
                // If the power button hasn't been pressed, we still want this to evaluate to true:
                // `uptimeMillis() - lastPowerButtonWakeup > recentPowerButtonPressThresholdMs`
                emit(recentPowerButtonPressThresholdMs * -1L - 1L)
            }

    val playSuccessHaptic: Flow<Unit> =
        deviceEntrySourceInteractor.deviceEntryFromBiometricSource
            .sample(
                combine(
                    powerButtonSideFpsEnrolled,
                    powerButtonDown,
                    lastPowerButtonWakeup,
                    ::Triple
                )
            )
            .filter { (sideFpsEnrolled, powerButtonDown, lastPowerButtonWakeup) ->
                val sideFpsAllowsHaptic =
                    !powerButtonDown &&
                        systemClock.uptimeMillis() - lastPowerButtonWakeup >
                            recentPowerButtonPressThresholdMs
                val allowHaptic = !sideFpsEnrolled || sideFpsAllowsHaptic
                if (!allowHaptic) {
                    logger.d("Skip success haptic. Recent power button press or button is down.")
                }
                allowHaptic
            }
            .map {} // map to Unit

    private val playErrorHapticForBiometricFailure: Flow<Unit> =
        merge(
                deviceEntryFingerprintAuthInteractor.fingerprintFailure,
                deviceEntryBiometricAuthInteractor.faceOnlyFaceFailure,
            )
            .map {} // map to Unit
    val playErrorHaptic: Flow<Unit> =
        playErrorHapticForBiometricFailure
            .sample(combine(powerButtonSideFpsEnrolled, powerButtonDown, ::Pair))
            .filter { (sideFpsEnrolled, powerButtonDown) ->
                val allowHaptic = !sideFpsEnrolled || !powerButtonDown
                if (!allowHaptic) {
                    logger.d("Skip error haptic. Power button is down.")
                }
                allowHaptic
            }
            .map {} // map to Unit

    private val recentPowerButtonPressThresholdMs = 400L
}
