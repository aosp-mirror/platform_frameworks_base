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

package com.android.systemui.biometrics.data.repository

import android.hardware.biometrics.AuthenticationStateListener
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_OTHER
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR
import android.hardware.biometrics.BiometricSourceType
import android.hardware.biometrics.events.AuthenticationAcquiredInfo
import android.hardware.biometrics.events.AuthenticationErrorInfo
import android.hardware.biometrics.events.AuthenticationFailedInfo
import android.hardware.biometrics.events.AuthenticationHelpInfo
import android.hardware.biometrics.events.AuthenticationStartedInfo
import android.hardware.biometrics.events.AuthenticationStoppedInfo
import android.hardware.biometrics.events.AuthenticationSucceededInfo
import android.hardware.face.FaceManager
import android.hardware.fingerprint.FingerprintManager
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.AuthenticationReason.SettingsOperations
import com.android.systemui.biometrics.shared.model.AuthenticationState
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FingerprintAuthenticationStatus
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** A repository for the state of biometric authentication. */
interface BiometricStatusRepository {
    /**
     * The logical reason for the current fingerprint auth operation if one is on-going, otherwise
     * [NotRunning].
     */
    val fingerprintAuthenticationReason: Flow<AuthenticationReason>

    /** The current status of an acquired fingerprint. */
    val fingerprintAcquiredStatus: Flow<FingerprintAuthenticationStatus>
}

@SysUISingleton
class BiometricStatusRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val biometricManager: BiometricManager?,
) : BiometricStatusRepository {

    /**
     * TODO(b/322555228): Replace usages of onAuthenticationError, onAuthenticationHelp,
     *   onAuthenticationSucceeded, onAuthenticationFailed, onAuthenticationAcquired in
     *   [FingerprintManager.AuthenticationCallback] and [FaceManager.AuthenticationCallback],
     *   onDetectionError in [FingerprintManager.FingerprintDetectionCallback] and
     *   [FaceManager.FaceDetectionCallback], and onEnrollmentError, onEnrollmentHelp, and
     *   onAcquired in [FingerprintManager.EnrollmentCallback] and [FaceManager.EnrollmentCallback]
     */
    private val authenticationState: Flow<AuthenticationState> =
        conflatedCallbackFlow {
                val updateAuthenticationState = { state: AuthenticationState ->
                    trySendWithFailureLogging(state, TAG, "Error sending AuthenticationState state")
                }

                val authenticationStateListener =
                    object : AuthenticationStateListener.Stub() {
                        override fun onAuthenticationAcquired(
                            authInfo: AuthenticationAcquiredInfo
                        ) {
                            updateAuthenticationState(
                                AuthenticationState.Acquired(
                                    authInfo.biometricSourceType,
                                    authInfo.requestReason.toAuthenticationReason(),
                                    authInfo.acquiredInfo
                                )
                            )
                        }

                        override fun onAuthenticationError(authInfo: AuthenticationErrorInfo) {
                            updateAuthenticationState(
                                AuthenticationState.Error(
                                    authInfo.biometricSourceType,
                                    authInfo.errString,
                                    authInfo.errCode,
                                    authInfo.requestReason.toAuthenticationReason()
                                )
                            )
                        }

                        override fun onAuthenticationFailed(authInfo: AuthenticationFailedInfo) {
                            updateAuthenticationState(
                                AuthenticationState.Failed(
                                    authInfo.biometricSourceType,
                                    authInfo.requestReason.toAuthenticationReason(),
                                    authInfo.userId
                                )
                            )
                        }

                        override fun onAuthenticationHelp(authInfo: AuthenticationHelpInfo) {
                            updateAuthenticationState(
                                AuthenticationState.Help(
                                    authInfo.biometricSourceType,
                                    authInfo.helpString,
                                    authInfo.helpCode,
                                    authInfo.requestReason.toAuthenticationReason()
                                )
                            )
                        }

                        override fun onAuthenticationStarted(authInfo: AuthenticationStartedInfo) {
                            updateAuthenticationState(
                                AuthenticationState.Started(
                                    authInfo.biometricSourceType,
                                    authInfo.requestReason.toAuthenticationReason()
                                )
                            )
                        }

                        override fun onAuthenticationStopped(authInfo: AuthenticationStoppedInfo) {
                            updateAuthenticationState(
                                AuthenticationState.Stopped(
                                    authInfo.biometricSourceType,
                                    AuthenticationReason.NotRunning
                                )
                            )
                        }

                        override fun onAuthenticationSucceeded(
                            authInfo: AuthenticationSucceededInfo
                        ) {
                            updateAuthenticationState(
                                AuthenticationState.Succeeded(
                                    authInfo.biometricSourceType,
                                    authInfo.isIsStrongBiometric,
                                    authInfo.requestReason.toAuthenticationReason(),
                                    authInfo.userId
                                )
                            )
                        }
                    }

                updateAuthenticationState(AuthenticationState.Idle(AuthenticationReason.NotRunning))
                biometricManager?.registerAuthenticationStateListener(authenticationStateListener)
                awaitClose {
                    biometricManager?.unregisterAuthenticationStateListener(
                        authenticationStateListener
                    )
                }
            }
            .distinctUntilChanged()
            .shareIn(applicationScope, started = SharingStarted.Eagerly, replay = 1)

    override val fingerprintAuthenticationReason: Flow<AuthenticationReason> =
        authenticationState
            .filter {
                it is AuthenticationState.Idle ||
                    (it is AuthenticationState.Started &&
                        it.biometricSourceType == BiometricSourceType.FINGERPRINT) ||
                    (it is AuthenticationState.Stopped &&
                        it.biometricSourceType == BiometricSourceType.FINGERPRINT)
            }
            .map { it.requestReason }

    override val fingerprintAcquiredStatus: Flow<FingerprintAuthenticationStatus> =
        authenticationState
            .filterIsInstance<AuthenticationState.Acquired>()
            .filter { it.biometricSourceType == BiometricSourceType.FINGERPRINT }
            .map { AcquiredFingerprintAuthenticationStatus(it.requestReason, it.acquiredInfo) }

    companion object {
        private const val TAG = "BiometricStatusRepositoryImpl"
    }
}

private fun Int.toAuthenticationReason(): AuthenticationReason =
    when (this) {
        REASON_AUTH_BP -> AuthenticationReason.BiometricPromptAuthentication
        REASON_AUTH_KEYGUARD -> AuthenticationReason.DeviceEntryAuthentication
        REASON_AUTH_OTHER -> AuthenticationReason.OtherAuthentication
        REASON_AUTH_SETTINGS ->
            AuthenticationReason.SettingsAuthentication(SettingsOperations.OTHER)
        REASON_ENROLL_ENROLLING ->
            AuthenticationReason.SettingsAuthentication(SettingsOperations.ENROLL_ENROLLING)
        REASON_ENROLL_FIND_SENSOR ->
            AuthenticationReason.SettingsAuthentication(SettingsOperations.ENROLL_FIND_SENSOR)
        else -> AuthenticationReason.Unknown
    }
