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

package com.android.systemui.keyguard.bouncer.data.repository

import android.hardware.biometrics.BiometricSourceType
import android.hardware.biometrics.BiometricSourceType.FACE
import android.hardware.biometrics.BiometricSourceType.FINGERPRINT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_DEVICE_ADMIN
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_FACE_LOCKED_OUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_FINGERPRINT_LOCKED_OUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_INCORRECT_FACE_INPUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_INCORRECT_FINGERPRINT_INPUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_NONE
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_PREPARE_FOR_UPDATE
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_RESTART
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TIMEOUT
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_TRUSTAGENT_EXPIRED
import com.android.keyguard.KeyguardSecurityView.PROMPT_REASON_USER_REQUEST
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.bouncer.data.factory.BouncerMessageFactory
import com.android.systemui.keyguard.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Provide different sources of messages that needs to be shown on the bouncer. */
interface BouncerMessageRepository {
    /**
     * Messages that are shown in response to the incorrect security attempts on the bouncer and
     * primary authentication method being locked out, along with countdown messages before primary
     * auth is active again.
     */
    val primaryAuthMessage: Flow<BouncerMessageModel?>

    /**
     * Help messages that are shown to the user on how to successfully perform authentication using
     * face.
     */
    val faceAcquisitionMessage: Flow<BouncerMessageModel?>

    /**
     * Help messages that are shown to the user on how to successfully perform authentication using
     * fingerprint.
     */
    val fingerprintAcquisitionMessage: Flow<BouncerMessageModel?>

    /** Custom message that is displayed when the bouncer is being shown to launch an app. */
    val customMessage: Flow<BouncerMessageModel?>

    /**
     * Messages that are shown in response to biometric authentication attempts through face or
     * fingerprint.
     */
    val biometricAuthMessage: Flow<BouncerMessageModel?>

    /** Messages that are shown when certain auth flags are set. */
    val authFlagsMessage: Flow<BouncerMessageModel?>

    /** Messages that are show after biometrics are locked out temporarily or permanently */
    val biometricLockedOutMessage: Flow<BouncerMessageModel?>

    /** Set the value for [primaryAuthMessage] */
    fun setPrimaryAuthMessage(value: BouncerMessageModel?)

    /** Set the value for [faceAcquisitionMessage] */
    fun setFaceAcquisitionMessage(value: BouncerMessageModel?)
    /** Set the value for [fingerprintAcquisitionMessage] */
    fun setFingerprintAcquisitionMessage(value: BouncerMessageModel?)

    /** Set the value for [customMessage] */
    fun setCustomMessage(value: BouncerMessageModel?)

    /**
     * Clear any previously set messages for [primaryAuthMessage], [faceAcquisitionMessage],
     * [fingerprintAcquisitionMessage] & [customMessage]
     */
    fun clearMessage()
}

@SysUISingleton
class BouncerMessageRepositoryImpl
@Inject
constructor(
    trustRepository: TrustRepository,
    biometricSettingsRepository: BiometricSettingsRepository,
    updateMonitor: KeyguardUpdateMonitor,
    private val bouncerMessageFactory: BouncerMessageFactory,
    private val userRepository: UserRepository,
    fingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
) : BouncerMessageRepository {

    private val isFaceEnrolledAndEnabled =
        and(
            biometricSettingsRepository.isFaceAuthenticationEnabled,
            biometricSettingsRepository.isFaceEnrolled
        )

    private val isFingerprintEnrolledAndEnabled =
        and(
            biometricSettingsRepository.isFingerprintEnabledByDevicePolicy,
            biometricSettingsRepository.isFingerprintEnrolled
        )

    private val isAnyBiometricsEnabledAndEnrolled =
        or(isFaceEnrolledAndEnabled, isFingerprintEnrolledAndEnabled)

    private val authFlagsBasedPromptReason: Flow<Int> =
        combine(
                biometricSettingsRepository.authenticationFlags,
                trustRepository.isCurrentUserTrustManaged,
                isAnyBiometricsEnabledAndEnrolled,
                ::Triple
            )
            .map { (flags, isTrustManaged, biometricsEnrolledAndEnabled) ->
                val trustOrBiometricsAvailable = (isTrustManaged || biometricsEnrolledAndEnabled)
                return@map if (
                    trustOrBiometricsAvailable && flags.isPrimaryAuthRequiredAfterReboot
                ) {
                    PROMPT_REASON_RESTART
                } else if (trustOrBiometricsAvailable && flags.isPrimaryAuthRequiredAfterTimeout) {
                    PROMPT_REASON_TIMEOUT
                } else if (flags.isPrimaryAuthRequiredAfterDpmLockdown) {
                    PROMPT_REASON_DEVICE_ADMIN
                } else if (isTrustManaged && flags.someAuthRequiredAfterUserRequest) {
                    PROMPT_REASON_TRUSTAGENT_EXPIRED
                } else if (isTrustManaged && flags.someAuthRequiredAfterTrustAgentExpired) {
                    PROMPT_REASON_TRUSTAGENT_EXPIRED
                } else if (trustOrBiometricsAvailable && flags.isInUserLockdown) {
                    PROMPT_REASON_USER_REQUEST
                } else if (
                    trustOrBiometricsAvailable && flags.primaryAuthRequiredForUnattendedUpdate
                ) {
                    PROMPT_REASON_PREPARE_FOR_UPDATE
                } else if (
                    trustOrBiometricsAvailable &&
                        flags.strongerAuthRequiredAfterNonStrongBiometricsTimeout
                ) {
                    PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT
                } else {
                    PROMPT_REASON_NONE
                }
            }

    private val biometricAuthReason: Flow<Int> =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardUpdateMonitorCallback() {
                        override fun onBiometricAuthFailed(
                            biometricSourceType: BiometricSourceType?
                        ) {
                            val promptReason =
                                if (biometricSourceType == FINGERPRINT)
                                    PROMPT_REASON_INCORRECT_FINGERPRINT_INPUT
                                else if (
                                    biometricSourceType == FACE && !updateMonitor.isFaceLockedOut
                                ) {
                                    PROMPT_REASON_INCORRECT_FACE_INPUT
                                } else PROMPT_REASON_NONE
                            trySendWithFailureLogging(promptReason, TAG, "onBiometricAuthFailed")
                        }

                        override fun onBiometricsCleared() {
                            trySendWithFailureLogging(
                                PROMPT_REASON_NONE,
                                TAG,
                                "onBiometricsCleared"
                            )
                        }

                        override fun onBiometricAcquired(
                            biometricSourceType: BiometricSourceType?,
                            acquireInfo: Int
                        ) {
                            trySendWithFailureLogging(
                                PROMPT_REASON_NONE,
                                TAG,
                                "clearBiometricPrompt for new auth session."
                            )
                        }

                        override fun onBiometricAuthenticated(
                            userId: Int,
                            biometricSourceType: BiometricSourceType?,
                            isStrongBiometric: Boolean
                        ) {
                            trySendWithFailureLogging(
                                PROMPT_REASON_NONE,
                                TAG,
                                "onBiometricAuthenticated"
                            )
                        }
                    }
                updateMonitor.registerCallback(callback)
                awaitClose { updateMonitor.removeCallback(callback) }
            }
            .distinctUntilChanged()

    private val _primaryAuthMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val primaryAuthMessage: Flow<BouncerMessageModel?> = _primaryAuthMessage

    private val _faceAcquisitionMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val faceAcquisitionMessage: Flow<BouncerMessageModel?> = _faceAcquisitionMessage

    private val _fingerprintAcquisitionMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val fingerprintAcquisitionMessage: Flow<BouncerMessageModel?> =
        _fingerprintAcquisitionMessage

    private val _customMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val customMessage: Flow<BouncerMessageModel?> = _customMessage

    override val biometricAuthMessage: Flow<BouncerMessageModel?> =
        biometricAuthReason
            .map {
                if (it == PROMPT_REASON_NONE) null
                else
                    bouncerMessageFactory.createFromPromptReason(
                        it,
                        userRepository.getSelectedUserInfo().id
                    )
            }
            .onStart { emit(null) }
            .distinctUntilChanged()

    override val authFlagsMessage: Flow<BouncerMessageModel?> =
        authFlagsBasedPromptReason
            .map {
                if (it == PROMPT_REASON_NONE) null
                else
                    bouncerMessageFactory.createFromPromptReason(
                        it,
                        userRepository.getSelectedUserInfo().id
                    )
            }
            .onStart { emit(null) }
            .distinctUntilChanged()

    // TODO (b/262838215): Replace with DeviceEntryFaceAuthRepository when the new face auth system
    // has been launched.
    private val faceLockedOut: Flow<Boolean> = conflatedCallbackFlow {
        val callback =
            object : KeyguardUpdateMonitorCallback() {
                override fun onLockedOutStateChanged(biometricSourceType: BiometricSourceType?) {
                    if (biometricSourceType == FACE) {
                        trySendWithFailureLogging(
                            updateMonitor.isFaceLockedOut,
                            TAG,
                            "face lock out state changed."
                        )
                    }
                }
            }
        updateMonitor.registerCallback(callback)
        trySendWithFailureLogging(updateMonitor.isFaceLockedOut, TAG, "face lockout initial value")
        awaitClose { updateMonitor.removeCallback(callback) }
    }

    override val biometricLockedOutMessage: Flow<BouncerMessageModel?> =
        combine(fingerprintAuthRepository.isLockedOut, faceLockedOut) { fp, face ->
            return@combine if (fp) {
                bouncerMessageFactory.createFromPromptReason(
                    PROMPT_REASON_FINGERPRINT_LOCKED_OUT,
                    userRepository.getSelectedUserInfo().id
                )
            } else if (face) {
                bouncerMessageFactory.createFromPromptReason(
                    PROMPT_REASON_FACE_LOCKED_OUT,
                    userRepository.getSelectedUserInfo().id
                )
            } else null
        }

    override fun setPrimaryAuthMessage(value: BouncerMessageModel?) {
        _primaryAuthMessage.value = value
    }

    override fun setFaceAcquisitionMessage(value: BouncerMessageModel?) {
        _faceAcquisitionMessage.value = value
    }

    override fun setFingerprintAcquisitionMessage(value: BouncerMessageModel?) {
        _fingerprintAcquisitionMessage.value = value
    }

    override fun setCustomMessage(value: BouncerMessageModel?) {
        _customMessage.value = value
    }

    override fun clearMessage() {
        _fingerprintAcquisitionMessage.value = null
        _faceAcquisitionMessage.value = null
        _primaryAuthMessage.value = null
        _customMessage.value = null
    }

    companion object {
        const val TAG = "BouncerDetailedMessageRepository"
    }
}

private fun and(flow: Flow<Boolean>, anotherFlow: Flow<Boolean>) =
    flow.combine(anotherFlow) { a, b -> a && b }

private fun or(flow: Flow<Boolean>, anotherFlow: Flow<Boolean>) =
    flow.combine(anotherFlow) { a, b -> a || b }
