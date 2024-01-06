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

package com.android.systemui.bouncer.domain.interactor

import android.hardware.biometrics.BiometricSourceType
import android.os.CountDownTimer
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Flags
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.BouncerMessageRepository
import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.bouncer.shared.model.Message
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.flags.SystemPropertiesHelper
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.res.R.string.bouncer_face_not_recognized
import com.android.systemui.res.R.string.keyguard_enter_password
import com.android.systemui.res.R.string.keyguard_enter_pattern
import com.android.systemui.res.R.string.keyguard_enter_pin
import com.android.systemui.res.R.string.kg_bio_too_many_attempts_password
import com.android.systemui.res.R.string.kg_bio_too_many_attempts_pattern
import com.android.systemui.res.R.string.kg_bio_too_many_attempts_pin
import com.android.systemui.res.R.string.kg_bio_try_again_or_password
import com.android.systemui.res.R.string.kg_bio_try_again_or_pattern
import com.android.systemui.res.R.string.kg_bio_try_again_or_pin
import com.android.systemui.res.R.string.kg_face_locked_out
import com.android.systemui.res.R.string.kg_fp_not_recognized
import com.android.systemui.res.R.string.kg_primary_auth_locked_out_password
import com.android.systemui.res.R.string.kg_primary_auth_locked_out_pattern
import com.android.systemui.res.R.string.kg_primary_auth_locked_out_pin
import com.android.systemui.res.R.string.kg_prompt_after_dpm_lock
import com.android.systemui.res.R.string.kg_prompt_after_update_password
import com.android.systemui.res.R.string.kg_prompt_after_update_pattern
import com.android.systemui.res.R.string.kg_prompt_after_update_pin
import com.android.systemui.res.R.string.kg_prompt_after_user_lockdown_password
import com.android.systemui.res.R.string.kg_prompt_after_user_lockdown_pattern
import com.android.systemui.res.R.string.kg_prompt_after_user_lockdown_pin
import com.android.systemui.res.R.string.kg_prompt_auth_timeout
import com.android.systemui.res.R.string.kg_prompt_password_auth_timeout
import com.android.systemui.res.R.string.kg_prompt_pattern_auth_timeout
import com.android.systemui.res.R.string.kg_prompt_pin_auth_timeout
import com.android.systemui.res.R.string.kg_prompt_reason_restart_password
import com.android.systemui.res.R.string.kg_prompt_reason_restart_pattern
import com.android.systemui.res.R.string.kg_prompt_reason_restart_pin
import com.android.systemui.res.R.string.kg_prompt_unattended_update
import com.android.systemui.res.R.string.kg_too_many_failed_attempts_countdown
import com.android.systemui.res.R.string.kg_trust_agent_disabled
import com.android.systemui.res.R.string.kg_unlock_with_password_or_fp
import com.android.systemui.res.R.string.kg_unlock_with_pattern_or_fp
import com.android.systemui.res.R.string.kg_unlock_with_pin_or_fp
import com.android.systemui.res.R.string.kg_wrong_input_try_fp_suggestion
import com.android.systemui.res.R.string.kg_wrong_password_try_again
import com.android.systemui.res.R.string.kg_wrong_pattern_try_again
import com.android.systemui.res.R.string.kg_wrong_pin_try_again
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.Quint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

private const val SYS_BOOT_REASON_PROP = "sys.boot.reason.last"
private const val REBOOT_MAINLINE_UPDATE = "reboot,mainline_update"
private const val TAG = "BouncerMessageInteractor"

@SysUISingleton
class BouncerMessageInteractor
@Inject
constructor(
    private val repository: BouncerMessageRepository,
    private val userRepository: UserRepository,
    private val countDownTimerUtil: CountDownTimerUtil,
    private val updateMonitor: KeyguardUpdateMonitor,
    trustRepository: TrustRepository,
    biometricSettingsRepository: BiometricSettingsRepository,
    private val systemPropertiesHelper: SystemPropertiesHelper,
    primaryBouncerInteractor: PrimaryBouncerInteractor,
    @Application private val applicationScope: CoroutineScope,
    private val facePropertyRepository: FacePropertyRepository,
    deviceEntryFingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    faceAuthRepository: DeviceEntryFaceAuthRepository,
    private val securityModel: KeyguardSecurityModel,
) {

    private val isFingerprintAuthCurrentlyAllowed =
        deviceEntryFingerprintAuthRepository.isLockedOut
            .isFalse()
            .and(biometricSettingsRepository.isFingerprintAuthCurrentlyAllowed)
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    private val currentSecurityMode
        get() = securityModel.getSecurityMode(currentUserId)
    private val currentUserId
        get() = userRepository.getSelectedUserInfo().id

    private val kumCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricAuthFailed(biometricSourceType: BiometricSourceType?) {
                repository.setMessage(
                    when (biometricSourceType) {
                        BiometricSourceType.FINGERPRINT ->
                            incorrectFingerprintInput(currentSecurityMode)
                        BiometricSourceType.FACE ->
                            incorrectFaceInput(
                                currentSecurityMode,
                                isFingerprintAuthCurrentlyAllowed.value
                            )
                        else ->
                            defaultMessage(
                                currentSecurityMode,
                                isFingerprintAuthCurrentlyAllowed.value
                            )
                    }
                )
            }

            override fun onBiometricAcquired(
                biometricSourceType: BiometricSourceType?,
                acquireInfo: Int
            ) {
                super.onBiometricAcquired(biometricSourceType, acquireInfo)
            }

            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType?,
                isStrongBiometric: Boolean
            ) {
                repository.setMessage(defaultMessage)
            }
        }

    private val isAnyBiometricsEnabledAndEnrolled =
        biometricSettingsRepository.isFaceAuthEnrolledAndEnabled.or(
            biometricSettingsRepository.isFingerprintEnrolledAndEnabled
        )

    private val wasRebootedForMainlineUpdate
        get() = systemPropertiesHelper.get(SYS_BOOT_REASON_PROP) == REBOOT_MAINLINE_UPDATE

    private val isFaceAuthClass3
        get() = facePropertyRepository.sensorInfo.value?.strength == SensorStrength.STRONG

    private val initialBouncerMessage: Flow<BouncerMessageModel> =
        combine(
                biometricSettingsRepository.authenticationFlags,
                trustRepository.isCurrentUserTrustManaged,
                isAnyBiometricsEnabledAndEnrolled,
                deviceEntryFingerprintAuthRepository.isLockedOut,
                faceAuthRepository.isLockedOut,
                ::Quint
            )
            .map { (flags, _, biometricsEnrolledAndEnabled, fpLockedOut, faceLockedOut) ->
                val isTrustUsuallyManaged = trustRepository.isCurrentUserTrustUsuallyManaged.value
                val trustOrBiometricsAvailable =
                    (isTrustUsuallyManaged || biometricsEnrolledAndEnabled)
                return@map if (
                    trustOrBiometricsAvailable && flags.isPrimaryAuthRequiredAfterReboot
                ) {
                    if (wasRebootedForMainlineUpdate) {
                        authRequiredForMainlineUpdate(currentSecurityMode)
                    } else {
                        authRequiredAfterReboot(currentSecurityMode)
                    }
                } else if (trustOrBiometricsAvailable && flags.isPrimaryAuthRequiredAfterTimeout) {
                    authRequiredAfterPrimaryAuthTimeout(currentSecurityMode)
                } else if (flags.isPrimaryAuthRequiredAfterDpmLockdown) {
                    authRequiredAfterAdminLockdown(currentSecurityMode)
                } else if (
                    trustOrBiometricsAvailable && flags.primaryAuthRequiredForUnattendedUpdate
                ) {
                    authRequiredForUnattendedUpdate(currentSecurityMode)
                } else if (fpLockedOut) {
                    class3AuthLockedOut(currentSecurityMode)
                } else if (faceLockedOut) {
                    if (isFaceAuthClass3) {
                        class3AuthLockedOut(currentSecurityMode)
                    } else {
                        faceLockedOut(currentSecurityMode, isFingerprintAuthCurrentlyAllowed.value)
                    }
                } else if (
                    trustOrBiometricsAvailable &&
                        flags.strongerAuthRequiredAfterNonStrongBiometricsTimeout
                ) {
                    nonStrongAuthTimeout(
                        currentSecurityMode,
                        isFingerprintAuthCurrentlyAllowed.value
                    )
                } else if (isTrustUsuallyManaged && flags.someAuthRequiredAfterUserRequest) {
                    trustAgentDisabled(currentSecurityMode, isFingerprintAuthCurrentlyAllowed.value)
                } else if (isTrustUsuallyManaged && flags.someAuthRequiredAfterTrustAgentExpired) {
                    trustAgentDisabled(currentSecurityMode, isFingerprintAuthCurrentlyAllowed.value)
                } else if (trustOrBiometricsAvailable && flags.isInUserLockdown) {
                    authRequiredAfterUserLockdown(currentSecurityMode)
                } else {
                    defaultMessage
                }
            }

    fun onPrimaryAuthLockedOut(secondsBeforeLockoutReset: Long) {
        if (!Flags.revampedBouncerMessages()) return

        val callback =
            object : CountDownTimerCallback {
                override fun onFinish() {
                    repository.setMessage(defaultMessage)
                }

                override fun onTick(millisUntilFinished: Long) {
                    val secondsRemaining = (millisUntilFinished / 1000.0).roundToInt()
                    val message = primaryAuthLockedOut(currentSecurityMode)
                    message.message?.animate = false
                    message.message?.formatterArgs =
                        mutableMapOf<String, Any>(Pair("count", secondsRemaining))
                    repository.setMessage(message)
                }
            }
        countDownTimerUtil.startNewTimer(secondsBeforeLockoutReset * 1000, 1000, callback)
    }

    fun onPrimaryAuthIncorrectAttempt() {
        if (!Flags.revampedBouncerMessages()) return

        repository.setMessage(
            incorrectSecurityInput(currentSecurityMode, isFingerprintAuthCurrentlyAllowed.value)
        )
    }

    fun setFingerprintAcquisitionMessage(value: String?) {
        if (!Flags.revampedBouncerMessages()) return
        repository.setMessage(
            defaultMessage(currentSecurityMode, value, isFingerprintAuthCurrentlyAllowed.value)
        )
    }

    fun setFaceAcquisitionMessage(value: String?) {
        if (!Flags.revampedBouncerMessages()) return
        repository.setMessage(
            defaultMessage(currentSecurityMode, value, isFingerprintAuthCurrentlyAllowed.value)
        )
    }

    fun setCustomMessage(value: String?) {
        if (!Flags.revampedBouncerMessages()) return

        repository.setMessage(
            defaultMessage(currentSecurityMode, value, isFingerprintAuthCurrentlyAllowed.value)
        )
    }

    private val defaultMessage: BouncerMessageModel
        get() = defaultMessage(currentSecurityMode, isFingerprintAuthCurrentlyAllowed.value)

    fun onPrimaryBouncerUserInput() {
        if (!Flags.revampedBouncerMessages()) return
        repository.setMessage(defaultMessage)
    }

    val bouncerMessage = repository.bouncerMessage

    init {
        updateMonitor.registerCallback(kumCallback)

        combine(primaryBouncerInteractor.isShowing, initialBouncerMessage) { showing, bouncerMessage
                ->
                if (showing) {
                    bouncerMessage
                } else {
                    null
                }
            }
            .filterNotNull()
            .onEach { repository.setMessage(it) }
            .launchIn(applicationScope)
    }
}

interface CountDownTimerCallback {
    fun onFinish()
    fun onTick(millisUntilFinished: Long)
}

@SysUISingleton
open class CountDownTimerUtil @Inject constructor() {

    /**
     * Start a new count down timer that runs for [millisInFuture] with a tick every
     * [millisInterval]
     */
    fun startNewTimer(
        millisInFuture: Long,
        millisInterval: Long,
        callback: CountDownTimerCallback,
    ): CountDownTimer {
        return object : CountDownTimer(millisInFuture, millisInterval) {
                override fun onFinish() = callback.onFinish()

                override fun onTick(millisUntilFinished: Long) =
                    callback.onTick(millisUntilFinished)
            }
            .start()
    }
}

private fun Flow<Boolean>.or(anotherFlow: Flow<Boolean>) =
    this.combine(anotherFlow) { a, b -> a || b }

private fun Flow<Boolean>.and(anotherFlow: Flow<Boolean>) =
    this.combine(anotherFlow) { a, b -> a && b }

private fun Flow<Boolean>.isFalse() = this.map { !it }

private fun defaultMessage(
    securityMode: SecurityMode,
    secondaryMessage: String?,
    fpAuthIsAllowed: Boolean
): BouncerMessageModel {
    return BouncerMessageModel(
        message =
            Message(
                messageResId = defaultMessage(securityMode, fpAuthIsAllowed).message?.messageResId,
                animate = false
            ),
        secondaryMessage = Message(message = secondaryMessage, animate = false)
    )
}

private fun defaultMessage(
    securityMode: SecurityMode,
    fpAuthIsAllowed: Boolean
): BouncerMessageModel {
    return if (fpAuthIsAllowed) {
        defaultMessageWithFingerprint(securityMode)
    } else
        when (securityMode) {
            SecurityMode.Pattern -> Pair(keyguard_enter_pattern, 0)
            SecurityMode.Password -> Pair(keyguard_enter_password, 0)
            SecurityMode.PIN -> Pair(keyguard_enter_pin, 0)
            else -> Pair(0, 0)
        }.toMessage()
}

private fun defaultMessageWithFingerprint(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, 0)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, 0)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, 0)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun incorrectSecurityInput(
    securityMode: SecurityMode,
    fpAuthIsAllowed: Boolean
): BouncerMessageModel {
    return if (fpAuthIsAllowed) {
        incorrectSecurityInputWithFingerprint(securityMode)
    } else
        when (securityMode) {
            SecurityMode.Pattern -> Pair(kg_wrong_pattern_try_again, 0)
            SecurityMode.Password -> Pair(kg_wrong_password_try_again, 0)
            SecurityMode.PIN -> Pair(kg_wrong_pin_try_again, 0)
            else -> Pair(0, 0)
        }.toMessage()
}

private fun incorrectSecurityInputWithFingerprint(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_wrong_pattern_try_again, kg_wrong_input_try_fp_suggestion)
        SecurityMode.Password -> Pair(kg_wrong_password_try_again, kg_wrong_input_try_fp_suggestion)
        SecurityMode.PIN -> Pair(kg_wrong_pin_try_again, kg_wrong_input_try_fp_suggestion)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun incorrectFingerprintInput(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_fp_not_recognized, kg_bio_try_again_or_pattern)
        SecurityMode.Password -> Pair(kg_fp_not_recognized, kg_bio_try_again_or_password)
        SecurityMode.PIN -> Pair(kg_fp_not_recognized, kg_bio_try_again_or_pin)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun incorrectFaceInput(
    securityMode: SecurityMode,
    fpAuthIsAllowed: Boolean
): BouncerMessageModel {
    return if (fpAuthIsAllowed) incorrectFaceInputWithFingerprintAllowed(securityMode)
    else
        when (securityMode) {
            SecurityMode.Pattern -> Pair(bouncer_face_not_recognized, kg_bio_try_again_or_pattern)
            SecurityMode.Password -> Pair(bouncer_face_not_recognized, kg_bio_try_again_or_password)
            SecurityMode.PIN -> Pair(bouncer_face_not_recognized, kg_bio_try_again_or_pin)
            else -> Pair(0, 0)
        }.toMessage()
}

private fun incorrectFaceInputWithFingerprintAllowed(
    securityMode: SecurityMode
): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, bouncer_face_not_recognized)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, bouncer_face_not_recognized)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, bouncer_face_not_recognized)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun biometricLockout(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_bio_too_many_attempts_pattern)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_bio_too_many_attempts_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_bio_too_many_attempts_pin)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun authRequiredAfterReboot(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_reason_restart_pattern)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_reason_restart_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_reason_restart_pin)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun authRequiredAfterAdminLockdown(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_after_dpm_lock)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_after_dpm_lock)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_after_dpm_lock)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun authRequiredAfterUserLockdown(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_after_user_lockdown_pattern)
        SecurityMode.Password ->
            Pair(keyguard_enter_password, kg_prompt_after_user_lockdown_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_after_user_lockdown_pin)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun authRequiredForUnattendedUpdate(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_unattended_update)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_unattended_update)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_unattended_update)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun authRequiredForMainlineUpdate(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_after_update_pattern)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_after_update_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_after_update_pin)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun authRequiredAfterPrimaryAuthTimeout(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_pattern_auth_timeout)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_password_auth_timeout)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_pin_auth_timeout)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun nonStrongAuthTimeout(
    securityMode: SecurityMode,
    fpAuthIsAllowed: Boolean
): BouncerMessageModel {
    return if (fpAuthIsAllowed) {
        nonStrongAuthTimeoutWithFingerprintAllowed(securityMode)
    } else
        when (securityMode) {
            SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_prompt_auth_timeout)
            SecurityMode.Password -> Pair(keyguard_enter_password, kg_prompt_auth_timeout)
            SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_prompt_auth_timeout)
            else -> Pair(0, 0)
        }.toMessage()
}

fun nonStrongAuthTimeoutWithFingerprintAllowed(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, kg_prompt_auth_timeout)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, kg_prompt_auth_timeout)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, kg_prompt_auth_timeout)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun faceLockedOut(
    securityMode: SecurityMode,
    fpAuthIsAllowed: Boolean
): BouncerMessageModel {
    return if (fpAuthIsAllowed) faceLockedOutButFingerprintAvailable(securityMode)
    else
        when (securityMode) {
            SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_face_locked_out)
            SecurityMode.Password -> Pair(keyguard_enter_password, kg_face_locked_out)
            SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_face_locked_out)
            else -> Pair(0, 0)
        }.toMessage()
}

private fun faceLockedOutButFingerprintAvailable(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, kg_face_locked_out)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, kg_face_locked_out)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, kg_face_locked_out)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun class3AuthLockedOut(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_bio_too_many_attempts_pattern)
        SecurityMode.Password -> Pair(keyguard_enter_password, kg_bio_too_many_attempts_password)
        SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_bio_too_many_attempts_pin)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun trustAgentDisabled(
    securityMode: SecurityMode,
    fpAuthIsAllowed: Boolean
): BouncerMessageModel {
    return if (fpAuthIsAllowed) trustAgentDisabledWithFingerprintAllowed(securityMode)
    else
        when (securityMode) {
            SecurityMode.Pattern -> Pair(keyguard_enter_pattern, kg_trust_agent_disabled)
            SecurityMode.Password -> Pair(keyguard_enter_password, kg_trust_agent_disabled)
            SecurityMode.PIN -> Pair(keyguard_enter_pin, kg_trust_agent_disabled)
            else -> Pair(0, 0)
        }.toMessage()
}

private fun trustAgentDisabledWithFingerprintAllowed(
    securityMode: SecurityMode
): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern -> Pair(kg_unlock_with_pattern_or_fp, kg_trust_agent_disabled)
        SecurityMode.Password -> Pair(kg_unlock_with_password_or_fp, kg_trust_agent_disabled)
        SecurityMode.PIN -> Pair(kg_unlock_with_pin_or_fp, kg_trust_agent_disabled)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun primaryAuthLockedOut(securityMode: SecurityMode): BouncerMessageModel {
    return when (securityMode) {
        SecurityMode.Pattern ->
            Pair(kg_too_many_failed_attempts_countdown, kg_primary_auth_locked_out_pattern)
        SecurityMode.Password ->
            Pair(kg_too_many_failed_attempts_countdown, kg_primary_auth_locked_out_password)
        SecurityMode.PIN ->
            Pair(kg_too_many_failed_attempts_countdown, kg_primary_auth_locked_out_pin)
        else -> Pair(0, 0)
    }.toMessage()
}

private fun Pair<Int, Int>.toMessage(): BouncerMessageModel {
    return BouncerMessageModel(
        message = Message(messageResId = this.first, animate = false),
        secondaryMessage = Message(messageResId = this.second, animate = false)
    )
}
