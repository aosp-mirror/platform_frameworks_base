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

import android.hardware.biometrics.BiometricFaceConstants
import android.hardware.biometrics.BiometricSourceType
import android.os.CountDownTimer
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Flags
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.BouncerMessageRepository
import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.bouncer.shared.model.BouncerMessageStrings
import com.android.systemui.bouncer.shared.model.Message
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryBiometricsAllowedInteractor
import com.android.systemui.flags.SystemPropertiesHelper
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.Septuple
import com.android.systemui.util.kotlin.combine
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Handles business logic for the primary bouncer message area. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class BouncerMessageInteractor
@Inject
constructor(
    private val repository: BouncerMessageRepository,
    private val userRepository: UserRepository,
    private val countDownTimerUtil: CountDownTimerUtil,
    updateMonitor: KeyguardUpdateMonitor,
    trustRepository: TrustRepository,
    biometricSettingsRepository: BiometricSettingsRepository,
    private val systemPropertiesHelper: SystemPropertiesHelper,
    primaryBouncerInteractor: PrimaryBouncerInteractor,
    @Application private val applicationScope: CoroutineScope,
    private val facePropertyRepository: FacePropertyRepository,
    private val securityModel: KeyguardSecurityModel,
    deviceEntryBiometricsAllowedInteractor: DeviceEntryBiometricsAllowedInteractor,
) {

    private val isFingerprintAuthCurrentlyAllowedOnBouncer =
        deviceEntryBiometricsAllowedInteractor.isFingerprintCurrentlyAllowedOnBouncer.stateIn(
            applicationScope,
            SharingStarted.Eagerly,
            false
        )

    private val currentSecurityMode
        get() = securityModel.getSecurityMode(currentUserId)

    private val currentUserId
        get() = userRepository.getSelectedUserInfo().id

    private val kumCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricAuthFailed(biometricSourceType: BiometricSourceType?) {
                // Only show the biometric failure messages if the biometric is NOT locked out.
                // If the biometric is locked out, rely on the lock out message to show
                // the lockout message & don't override it with the failure message.
                if (
                    (biometricSourceType == BiometricSourceType.FACE &&
                        deviceEntryBiometricsAllowedInteractor.isFaceLockedOut.value) ||
                        (biometricSourceType == BiometricSourceType.FINGERPRINT &&
                            deviceEntryBiometricsAllowedInteractor.isFingerprintLockedOut.value)
                ) {
                    return
                }
                repository.setMessage(
                    when (biometricSourceType) {
                        BiometricSourceType.FINGERPRINT ->
                            BouncerMessageStrings.incorrectFingerprintInput(
                                    currentSecurityMode.toAuthModel()
                                )
                                .toMessage()
                        BiometricSourceType.FACE ->
                            BouncerMessageStrings.incorrectFaceInput(
                                    currentSecurityMode.toAuthModel(),
                                    isFingerprintAuthCurrentlyAllowedOnBouncer.value
                                )
                                .toMessage()
                        else ->
                            BouncerMessageStrings.defaultMessage(
                                    currentSecurityMode.toAuthModel(),
                                    isFingerprintAuthCurrentlyAllowedOnBouncer.value
                                )
                                .toMessage()
                    },
                    biometricSourceType,
                )
            }

            override fun onBiometricAcquired(
                biometricSourceType: BiometricSourceType?,
                acquireInfo: Int
            ) {
                if (
                    repository.getMessageSource() == BiometricSourceType.FACE &&
                        acquireInfo == BiometricFaceConstants.FACE_ACQUIRED_START
                ) {
                    repository.setMessage(defaultMessage)
                }
            }

            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType?,
                isStrongBiometric: Boolean
            ) {
                repository.setMessage(defaultMessage, biometricSourceType)
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
                primaryBouncerInteractor.lastShownSecurityMode, // required to update defaultMessage
                biometricSettingsRepository.authenticationFlags,
                trustRepository.isCurrentUserTrustManaged,
                isAnyBiometricsEnabledAndEnrolled,
                deviceEntryBiometricsAllowedInteractor.isFingerprintLockedOut,
                deviceEntryBiometricsAllowedInteractor.isFaceLockedOut,
                isFingerprintAuthCurrentlyAllowedOnBouncer,
                ::Septuple
            )
            .map { (_, flags, _, biometricsEnrolledAndEnabled, fpLockedOut, faceLockedOut, _) ->
                val isTrustUsuallyManaged = trustRepository.isCurrentUserTrustUsuallyManaged.value
                val trustOrBiometricsAvailable =
                    (isTrustUsuallyManaged || biometricsEnrolledAndEnabled)
                return@map if (
                    trustOrBiometricsAvailable && flags.isPrimaryAuthRequiredAfterReboot
                ) {
                    if (wasRebootedForMainlineUpdate) {
                        BouncerMessageStrings.authRequiredForMainlineUpdate(
                                currentSecurityMode.toAuthModel()
                            )
                            .toMessage()
                    } else {
                        BouncerMessageStrings.authRequiredAfterReboot(
                                currentSecurityMode.toAuthModel()
                            )
                            .toMessage()
                    }
                } else if (trustOrBiometricsAvailable && flags.isPrimaryAuthRequiredAfterTimeout) {
                    BouncerMessageStrings.authRequiredAfterPrimaryAuthTimeout(
                            currentSecurityMode.toAuthModel()
                        )
                        .toMessage()
                } else if (flags.isPrimaryAuthRequiredAfterDpmLockdown) {
                    BouncerMessageStrings.authRequiredAfterAdminLockdown(
                            currentSecurityMode.toAuthModel()
                        )
                        .toMessage()
                } else if (
                    trustOrBiometricsAvailable && flags.isPrimaryAuthRequiredForUnattendedUpdate
                ) {
                    BouncerMessageStrings.authRequiredForUnattendedUpdate(
                            currentSecurityMode.toAuthModel()
                        )
                        .toMessage()
                } else if (
                    biometricSettingsRepository.isFingerprintEnrolledAndEnabled.value && fpLockedOut
                ) {
                    BouncerMessageStrings.class3AuthLockedOut(currentSecurityMode.toAuthModel())
                        .toMessage()
                } else if (
                    biometricSettingsRepository.isFaceAuthEnrolledAndEnabled.value && faceLockedOut
                ) {
                    if (isFaceAuthClass3) {
                        BouncerMessageStrings.class3AuthLockedOut(currentSecurityMode.toAuthModel())
                            .toMessage()
                    } else {
                        BouncerMessageStrings.faceLockedOut(
                                currentSecurityMode.toAuthModel(),
                                isFingerprintAuthCurrentlyAllowedOnBouncer.value
                            )
                            .toMessage()
                    }
                } else if (flags.isSomeAuthRequiredAfterAdaptiveAuthRequest) {
                    BouncerMessageStrings.authRequiredAfterAdaptiveAuthRequest(
                            currentSecurityMode.toAuthModel(),
                            isFingerprintAuthCurrentlyAllowedOnBouncer.value
                        )
                        .toMessage()
                } else if (
                    trustOrBiometricsAvailable &&
                        flags.strongerAuthRequiredAfterNonStrongBiometricsTimeout
                ) {
                    BouncerMessageStrings.nonStrongAuthTimeout(
                            currentSecurityMode.toAuthModel(),
                            isFingerprintAuthCurrentlyAllowedOnBouncer.value
                        )
                        .toMessage()
                } else if (isTrustUsuallyManaged && flags.someAuthRequiredAfterUserRequest) {
                    BouncerMessageStrings.trustAgentDisabled(
                            currentSecurityMode.toAuthModel(),
                            isFingerprintAuthCurrentlyAllowedOnBouncer.value
                        )
                        .toMessage()
                } else if (isTrustUsuallyManaged && flags.someAuthRequiredAfterTrustAgentExpired) {
                    BouncerMessageStrings.trustAgentDisabled(
                            currentSecurityMode.toAuthModel(),
                            isFingerprintAuthCurrentlyAllowedOnBouncer.value
                        )
                        .toMessage()
                } else if (trustOrBiometricsAvailable && flags.isInUserLockdown) {
                    BouncerMessageStrings.authRequiredAfterUserLockdown(
                            currentSecurityMode.toAuthModel()
                        )
                        .toMessage()
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
                    val message =
                        BouncerMessageStrings.primaryAuthLockedOut(
                                currentSecurityMode.toAuthModel()
                            )
                            .toMessage()
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
            BouncerMessageStrings.incorrectSecurityInput(
                    currentSecurityMode.toAuthModel(),
                    isFingerprintAuthCurrentlyAllowedOnBouncer.value
                )
                .toMessage()
        )
    }

    fun setFingerprintAcquisitionMessage(value: String?) {
        if (!Flags.revampedBouncerMessages()) return
        repository.setMessage(
            defaultMessage(
                currentSecurityMode,
                value,
                isFingerprintAuthCurrentlyAllowedOnBouncer.value
            ),
            BiometricSourceType.FINGERPRINT,
        )
    }

    fun setFaceAcquisitionMessage(value: String?) {
        if (!Flags.revampedBouncerMessages()) return
        repository.setMessage(
            defaultMessage(
                currentSecurityMode,
                value,
                isFingerprintAuthCurrentlyAllowedOnBouncer.value
            ),
            BiometricSourceType.FACE,
        )
    }

    fun setCustomMessage(value: String?) {
        if (!Flags.revampedBouncerMessages()) return

        repository.setMessage(
            defaultMessage(
                currentSecurityMode,
                value,
                isFingerprintAuthCurrentlyAllowedOnBouncer.value
            )
        )
    }

    private val defaultMessage: BouncerMessageModel
        get() =
            BouncerMessageStrings.defaultMessage(
                    currentSecurityMode.toAuthModel(),
                    isFingerprintAuthCurrentlyAllowedOnBouncer.value
                )
                .toMessage()

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

private fun defaultMessage(
    securityMode: SecurityMode,
    secondaryMessage: String?,
    fpAuthIsAllowed: Boolean
): BouncerMessageModel {
    return BouncerMessageModel(
        message =
            Message(
                messageResId =
                    BouncerMessageStrings.defaultMessage(
                            securityMode.toAuthModel(),
                            fpAuthIsAllowed
                        )
                        .toMessage()
                        .message
                        ?.messageResId,
                animate = false
            ),
        secondaryMessage = Message(message = secondaryMessage, animate = false)
    )
}

private fun Pair<Int, Int>.toMessage(): BouncerMessageModel {
    return BouncerMessageModel(
        message = Message(messageResId = this.first, animate = false),
        secondaryMessage = Message(messageResId = this.second, animate = false)
    )
}

private fun SecurityMode.toAuthModel(): AuthenticationMethodModel {
    return when (this) {
        SecurityMode.Invalid -> AuthenticationMethodModel.None
        SecurityMode.None -> AuthenticationMethodModel.None
        SecurityMode.Pattern -> AuthenticationMethodModel.Pattern
        SecurityMode.Password -> AuthenticationMethodModel.Password
        SecurityMode.PIN -> AuthenticationMethodModel.Pin
        SecurityMode.SimPin -> AuthenticationMethodModel.Sim
        SecurityMode.SimPuk -> AuthenticationMethodModel.Sim
    }
}
