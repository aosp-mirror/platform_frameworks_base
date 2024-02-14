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
import com.android.systemui.deviceentry.shared.model.FaceMessage
import com.android.systemui.deviceentry.shared.model.FaceTimeoutMessage
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FingerprintLockoutMessage
import com.android.systemui.deviceentry.shared.model.FingerprintMessage
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

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
    fingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    fingerprintPropertyInteractor: FingerprintPropertyInteractor,
    faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    biometricSettingsInteractor: DeviceEntryBiometricSettingsInteractor,
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
    private val coExFaceAcquisitionMsgIdsToShow: Set<Int> =
        resources.getIntArray(R.array.config_face_help_msgs_when_fingerprint_enrolled).toSet()

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
            .map { (helpStatus, _) ->
                FingerprintMessage(
                    helpStatus.msg,
                )
            }

    private val fingerprintFailMessage: Flow<FingerprintMessage> =
        fingerprintPropertyInteractor.isUdfps.flatMapLatest { isUdfps ->
            fingerprintAuthInteractor.fingerprintFailure
                .sample(biometricSettingsInteractor.fingerprintAuthCurrentlyAllowed)
                .filter { fingerprintAuthAllowed -> fingerprintAuthAllowed }
                .map {
                    FingerprintMessage(
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

    val fingerprintMessage: Flow<FingerprintMessage> =
        merge(
            fingerprintErrorMessage,
            fingerprintFailMessage,
            fingerprintHelpMessage,
        )

    private val faceHelpMessage: Flow<FaceMessage> =
        biometricSettingsInteractor.fingerprintAndFaceEnrolledAndEnabled
            .flatMapLatest { fingerprintAndFaceEnrolledAndEnabled ->
                if (fingerprintAndFaceEnrolledAndEnabled) {
                    faceHelp.filter { faceAuthHelpStatus ->
                        coExFaceAcquisitionMsgIdsToShow.contains(faceAuthHelpStatus.msgId)
                    }
                } else {
                    faceHelp
                }
            }
            .sample(biometricSettingsInteractor.faceAuthCurrentlyAllowed, ::Pair)
            .filter { (_, faceAuthCurrentlyAllowed) -> faceAuthCurrentlyAllowed }
            .map { (status, _) -> FaceMessage(status.msg) }

    private val faceFailureMessage: Flow<FaceMessage> =
        faceFailure
            .sample(biometricSettingsInteractor.faceAuthCurrentlyAllowed)
            .filter { faceAuthCurrentlyAllowed -> faceAuthCurrentlyAllowed }
            .map { FaceMessage(resources.getString(R.string.keyguard_face_failed)) }

    private val faceErrorMessage: Flow<FaceMessage> =
        faceError
            .filterNot { it.shouldSuppressError() }
            .sample(biometricSettingsInteractor.faceAuthCurrentlyAllowed, ::Pair)
            .filter { (errorStatus, faceAuthCurrentlyAllowed) ->
                faceAuthCurrentlyAllowed || errorStatus.isLockoutError()
            }
            .map { (status, _) ->
                when {
                    status.isTimeoutError() -> FaceTimeoutMessage(status.msg)
                    else -> FaceMessage(status.msg)
                }
            }

    // TODO(b/317215391): support showing face acquired messages on timeout + face lockout errors
    val faceMessage: Flow<FaceMessage> =
        merge(
            faceHelpMessage,
            faceFailureMessage,
            faceErrorMessage,
        )
}
