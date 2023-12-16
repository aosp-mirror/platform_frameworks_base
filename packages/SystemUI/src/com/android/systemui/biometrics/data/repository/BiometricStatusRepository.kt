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
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.AuthenticationReason.SettingsOperations
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

/** A repository for the state of biometric authentication. */
interface BiometricStatusRepository {
    /**
     * The logical reason for the current fingerprint auth operation if one is on-going, otherwise
     * [NotRunning].
     */
    val fingerprintAuthenticationReason: Flow<AuthenticationReason>
}

@SysUISingleton
class BiometricStatusRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val biometricManager: BiometricManager?
) : BiometricStatusRepository {

    override val fingerprintAuthenticationReason: Flow<AuthenticationReason> =
        conflatedCallbackFlow {
                val updateFingerprintAuthenticateReason = { reason: AuthenticationReason ->
                    trySendWithFailureLogging(
                        reason,
                        TAG,
                        "Error sending fingerprintAuthenticateReason reason"
                    )
                }

                val authenticationStateListener =
                    object : AuthenticationStateListener.Stub() {
                        override fun onAuthenticationStarted(requestReason: Int) {
                            val authenticationReason =
                                when (requestReason) {
                                    REASON_AUTH_BP ->
                                        AuthenticationReason.BiometricPromptAuthentication
                                    REASON_AUTH_KEYGUARD ->
                                        AuthenticationReason.DeviceEntryAuthentication
                                    REASON_AUTH_OTHER -> AuthenticationReason.OtherAuthentication
                                    REASON_AUTH_SETTINGS ->
                                        AuthenticationReason.SettingsAuthentication(
                                            SettingsOperations.OTHER
                                        )
                                    REASON_ENROLL_ENROLLING ->
                                        AuthenticationReason.SettingsAuthentication(
                                            SettingsOperations.ENROLL_ENROLLING
                                        )
                                    REASON_ENROLL_FIND_SENSOR ->
                                        AuthenticationReason.SettingsAuthentication(
                                            SettingsOperations.ENROLL_FIND_SENSOR
                                        )
                                    else -> AuthenticationReason.Unknown
                                }
                            updateFingerprintAuthenticateReason(authenticationReason)
                        }

                        override fun onAuthenticationStopped() {
                            updateFingerprintAuthenticateReason(AuthenticationReason.NotRunning)
                        }
                    }

                updateFingerprintAuthenticateReason(AuthenticationReason.NotRunning)
                biometricManager?.registerAuthenticationStateListener(authenticationStateListener)
                awaitClose {
                    biometricManager?.unregisterAuthenticationStateListener(
                        authenticationStateListener
                    )
                }
            }
            .shareIn(applicationScope, started = SharingStarted.Eagerly, replay = 1)

    companion object {
        private const val TAG = "BiometricStatusRepositoryImpl"
    }
}
