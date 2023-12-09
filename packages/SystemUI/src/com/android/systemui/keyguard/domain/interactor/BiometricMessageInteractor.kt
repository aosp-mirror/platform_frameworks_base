/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.res.Resources
import android.hardware.biometrics.BiometricSourceType
import android.hardware.biometrics.BiometricSourceType.FINGERPRINT
import android.hardware.fingerprint.FingerprintManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpFingerprintAuthenticationStatus
import com.android.systemui.keyguard.util.IndicationHelper
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * BiometricMessage business logic. Filters biometric error/acquired/fail/success events for
 * authentication events that should never surface a message to the user at the current device
 * state.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class BiometricMessageInteractor
@Inject
constructor(
    @Main private val resources: Resources,
    private val fingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    private val fingerprintPropertyRepository: FingerprintPropertyRepository,
    private val indicationHelper: IndicationHelper,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
) {
    val fingerprintErrorMessage: Flow<BiometricMessage> =
        fingerprintAuthRepository.authenticationStatus
            .filter {
                it is ErrorFingerprintAuthenticationStatus &&
                    !indicationHelper.shouldSuppressErrorMsg(FINGERPRINT, it.msgId)
            }
            .map {
                val errorStatus = it as ErrorFingerprintAuthenticationStatus
                BiometricMessage(
                    FINGERPRINT,
                    BiometricMessageType.ERROR,
                    errorStatus.msgId,
                    errorStatus.msg,
                )
            }

    val fingerprintHelpMessage: Flow<BiometricMessage> =
        fingerprintAuthRepository.authenticationStatus
            .filter { it is HelpFingerprintAuthenticationStatus }
            .filterNot { isPrimaryAuthRequired() }
            .map {
                val helpStatus = it as HelpFingerprintAuthenticationStatus
                BiometricMessage(
                    FINGERPRINT,
                    BiometricMessageType.HELP,
                    helpStatus.msgId,
                    helpStatus.msg,
                )
            }

    val fingerprintFailMessage: Flow<BiometricMessage> =
        isUdfps().flatMapLatest { isUdfps ->
            fingerprintAuthRepository.authenticationStatus
                .filter { it is FailFingerprintAuthenticationStatus }
                .filterNot { isPrimaryAuthRequired() }
                .map {
                    BiometricMessage(
                        FINGERPRINT,
                        BiometricMessageType.FAIL,
                        BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                        if (isUdfps) {
                            resources.getString(
                                com.android.internal.R.string.fingerprint_udfps_error_not_match
                            )
                        } else {
                            resources.getString(
                                com.android.internal.R.string.fingerprint_error_not_match
                            )
                        },
                    )
                }
        }

    private fun isUdfps() =
        fingerprintPropertyRepository.sensorType.map {
            it == FingerprintSensorType.UDFPS_OPTICAL ||
                it == FingerprintSensorType.UDFPS_ULTRASONIC
        }

    private fun isPrimaryAuthRequired(): Boolean {
        // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
        // as long as primary auth, i.e. PIN/pattern/password, is required), so it's ok to
        // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
        // check of whether non-strong biometric is allowed since strong biometrics can still be
        // used.
        return !keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(true /* isStrongBiometric */)
    }
}

data class BiometricMessage(
    val source: BiometricSourceType,
    val type: BiometricMessageType,
    val id: Int,
    val message: String?,
) {
    fun isFingerprintLockoutMessage(): Boolean {
        return source == FINGERPRINT &&
            type == BiometricMessageType.ERROR &&
            (id == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT ||
                id == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT)
    }
}

enum class BiometricMessageType {
    HELP,
    ERROR,
    FAIL,
}
