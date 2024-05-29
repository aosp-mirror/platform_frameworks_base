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

import android.content.res.Resources
import com.android.systemui.biometrics.domain.interactor.FingerprintPropertyInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceFailureMessage
import com.android.systemui.deviceentry.shared.model.FaceLockoutMessage
import com.android.systemui.deviceentry.shared.model.FaceMessage
import com.android.systemui.deviceentry.shared.model.FaceTimeoutMessage
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FingerprintFailureMessage
import com.android.systemui.deviceentry.shared.model.FingerprintLockoutMessage
import com.android.systemui.deviceentry.shared.model.FingerprintMessage
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.keyguard.domain.interactor.DevicePostureInteractor
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * BiometricMessage business logic. Filters biometric error/fail/success events for authentication
 * events that should never surface a message to the user at the current device state.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class BiometricMessageInteractor
@Inject
constructor(
    @Main private val resources: Resources,
    fingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    fingerprintPropertyInteractor: FingerprintPropertyInteractor,
    faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val biometricSettingsInteractor: DeviceEntryBiometricSettingsInteractor,
    faceHelpMessageDeferralInteractor: FaceHelpMessageDeferralInteractor,
    devicePostureInteractor: DevicePostureInteractor,
) {
    private val faceHelp: Flow<HelpFaceAuthenticationStatus> =
        faceAuthInteractor.authenticationStatus.filterIsInstance<HelpFaceAuthenticationStatus>()
    private val faceError: Flow<ErrorFaceAuthenticationStatus> =
        faceAuthInteractor.authenticationStatus.filterIsInstance<ErrorFaceAuthenticationStatus>()
    private val faceFailure: Flow<FailedFaceAuthenticationStatus> =
        faceAuthInteractor.authenticationStatus.filterIsInstance<FailedFaceAuthenticationStatus>()

    /**
     * The acquisition message ids to show message when both fingerprint and face are enrolled and
     * enabled for device entry.
     */
    private val coExFaceAcquisitionMsgIdsToShowDefault: Set<Int> =
        resources.getIntArray(R.array.config_face_help_msgs_when_fingerprint_enrolled).toSet()

    /**
     * The acquisition message ids to show message when both fingerprint and face are enrolled and
     * enabled for device entry and the device is unfolded.
     */
    private val coExFaceAcquisitionMsgIdsToShowUnfolded: Set<Int> =
        resources
            .getIntArray(R.array.config_face_help_msgs_when_fingerprint_enrolled_unfolded)
            .toSet()

    private fun ErrorFingerprintAuthenticationStatus.shouldSuppressError(): Boolean {
        return isCancellationError() || isPowerPressedError()
    }

    private fun ErrorFaceAuthenticationStatus.shouldSuppressError(): Boolean {
        return isCancellationError() || isUnableToProcessError()
    }

    private val fingerprintErrorMessage: Flow<FingerprintMessage> =
        fingerprintAuthInteractor.fingerprintError
            .filterNot { it.shouldSuppressError() }
            .sample(biometricSettingsInteractor.fingerprintAuthCurrentlyAllowed, ::Pair)
            .filter { (errorStatus, fingerprintAuthAllowed) ->
                fingerprintAuthAllowed || errorStatus.isLockoutError()
            }
            .map { (errorStatus, _) ->
                when {
                    errorStatus.isLockoutError() -> FingerprintLockoutMessage(errorStatus.msg)
                    else -> FingerprintMessage(errorStatus.msg)
                }
            }

    private val fingerprintHelpMessage: Flow<FingerprintMessage> =
        fingerprintAuthInteractor.fingerprintHelp
            .sample(biometricSettingsInteractor.fingerprintAuthCurrentlyAllowed, ::Pair)
            .filter { (_, fingerprintAuthAllowed) -> fingerprintAuthAllowed }
            .map { (helpStatus, _) -> FingerprintMessage(helpStatus.msg) }

    private val fingerprintFailMessage: Flow<FingerprintMessage> =
        fingerprintPropertyInteractor.isUdfps.flatMapLatest { isUdfps ->
            fingerprintAuthInteractor.fingerprintFailure
                .sample(biometricSettingsInteractor.fingerprintAuthCurrentlyAllowed)
                .filter { fingerprintAuthAllowed -> fingerprintAuthAllowed }
                .map {
                    FingerprintFailureMessage(
                        if (isUdfps) {
                            resources.getString(
                                com.android.internal.R.string.fingerprint_udfps_error_not_match
                            )
                        } else {
                            resources.getString(
                                com.android.internal.R.string.fingerprint_error_not_match
                            )
                        }
                    )
                }
        }

    val coExFaceAcquisitionMsgIdsToShow: Flow<Set<Int>> =
        devicePostureInteractor.posture.map { devicePosture ->
            when (devicePosture) {
                DevicePosture.OPENED -> coExFaceAcquisitionMsgIdsToShowUnfolded
                DevicePosture.UNKNOWN, // Devices without posture support (non-foldable) use UNKNOWN
                DevicePosture.CLOSED,
                DevicePosture.HALF_OPENED,
                DevicePosture.FLIPPED -> coExFaceAcquisitionMsgIdsToShowDefault
            }
        }

    val fingerprintMessage: Flow<FingerprintMessage> =
        merge(
            fingerprintErrorMessage,
            fingerprintFailMessage,
            fingerprintHelpMessage,
        )

    private val filterConditionForFaceHelpMessages:
        Flow<(HelpFaceAuthenticationStatus) -> Boolean> =
        combine(
                biometricSettingsInteractor.fingerprintAndFaceEnrolledAndEnabled,
                biometricSettingsInteractor.faceAuthCurrentlyAllowed,
                ::Pair
            )
            .flatMapLatest { (faceAndFingerprintEnrolled, faceAuthCurrentlyAllowed) ->
                if (faceAndFingerprintEnrolled && faceAuthCurrentlyAllowed) {
                    // Show only some face help messages if fingerprint is also enrolled
                    coExFaceAcquisitionMsgIdsToShow.map { msgIdsToShow ->
                        { helpStatus: HelpFaceAuthenticationStatus ->
                            msgIdsToShow.contains(helpStatus.msgId)
                        }
                    }
                } else if (faceAuthCurrentlyAllowed) {
                    // Show all face help messages if only face is enrolled and currently allowed
                    flowOf { _: HelpFaceAuthenticationStatus -> true }
                } else {
                    flowOf { _: HelpFaceAuthenticationStatus -> false }
                }
            }

    private val faceHelpMessage: Flow<FaceMessage> =
        faceHelp
            .filterNot {
                // Message deferred to potentially show at face timeout error instead
                faceHelpMessageDeferralInteractor.shouldDefer(it.msgId)
            }
            .sample(filterConditionForFaceHelpMessages, ::Pair)
            .filter { (helpMessage, filterCondition) -> filterCondition(helpMessage) }
            .map { (status, _) -> FaceMessage(status.msg) }

    private val faceFailureMessage: Flow<FaceMessage> =
        faceFailure
            .sample(biometricSettingsInteractor.faceAuthCurrentlyAllowed)
            .filter { faceAuthCurrentlyAllowed -> faceAuthCurrentlyAllowed }
            .map { FaceFailureMessage(resources.getString(R.string.keyguard_face_failed)) }

    private val faceErrorMessage: Flow<FaceMessage> =
        faceError
            .filterNot { it.shouldSuppressError() }
            .sample(biometricSettingsInteractor.faceAuthCurrentlyAllowed, ::Pair)
            .filter { (errorStatus, faceAuthCurrentlyAllowed) ->
                faceAuthCurrentlyAllowed || errorStatus.isLockoutError()
            }
            .map { (status, _) ->
                when {
                    status.isTimeoutError() -> {
                        val deferredMessage = faceHelpMessageDeferralInteractor.getDeferredMessage()
                        if (deferredMessage != null) {
                            FaceMessage(deferredMessage.toString())
                        } else {
                            FaceTimeoutMessage(status.msg)
                        }
                    }
                    status.isLockoutError() -> FaceLockoutMessage(status.msg)
                    else -> FaceMessage(status.msg)
                }
            }

    val faceMessage: Flow<FaceMessage> =
        merge(
            faceHelpMessage,
            faceFailureMessage,
            faceErrorMessage,
        )
}
