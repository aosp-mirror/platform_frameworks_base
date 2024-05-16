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
import android.util.Log
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    private val biometricManager: BiometricManager?
) : BiometricStatusRepository {

    private val authenticationState: Flow<AuthenticationState> =
        conflatedCallbackFlow {
                val updateAuthenticationState = { state: AuthenticationState ->
                    Log.d(TAG, "authenticationState updated: $state")
                    trySendWithFailureLogging(state, TAG, "Error sending AuthenticationState state")
                }

                val authenticationStateListener =
                    object : AuthenticationStateListener.Stub() {
                        override fun onAuthenticationStarted(requestReason: Int) {
                            val authenticationReason = requestReason.toAuthenticationReason()
                            updateAuthenticationState(
                                AuthenticationState.AuthenticationStarted(authenticationReason)
                            )
                        }

                        override fun onAuthenticationStopped() {
                            updateAuthenticationState(
                                AuthenticationState.AuthenticationStopped(
                                    AuthenticationReason.NotRunning
                                )
                            )
                        }

                        override fun onAuthenticationSucceeded(requestReason: Int, userId: Int) {}

                        override fun onAuthenticationFailed(requestReason: Int, userId: Int) {}

                        override fun onAuthenticationAcquired(
                            biometricSourceType: BiometricSourceType,
                            requestReason: Int,
                            acquiredInfo: Int
                        ) {
                            val authReason = requestReason.toAuthenticationReason()

                            updateAuthenticationState(
                                AuthenticationState.AuthenticationAcquired(
                                    biometricSourceType,
                                    authReason,
                                    acquiredInfo
                                )
                            )
                        }
                    }

                updateAuthenticationState(
                    AuthenticationState.AuthenticationStarted(AuthenticationReason.NotRunning)
                )
                biometricManager?.registerAuthenticationStateListener(authenticationStateListener)
                awaitClose {
                    biometricManager?.unregisterAuthenticationStateListener(
                        authenticationStateListener
                    )
                }
            }
            .shareIn(applicationScope, started = SharingStarted.Eagerly, replay = 1)

    override val fingerprintAuthenticationReason: Flow<AuthenticationReason> =
        authenticationState
            .map { it.requestReason }
            .onEach { Log.d(TAG, "fingerprintAuthenticationReason updated: $it") }

    override val fingerprintAcquiredStatus: Flow<FingerprintAuthenticationStatus> =
        authenticationState
            .filterIsInstance<AuthenticationState.AuthenticationAcquired>()
            .filter {
                it.biometricSourceType == BiometricSourceType.FINGERPRINT &&
                    // TODO(b/322555228) This check will be removed after consolidating device
                    //  entry auth messages (currently in DeviceEntryFingerprintAuthRepository)
                    //  with BP auth messages (here)
                    it.requestReason == AuthenticationReason.BiometricPromptAuthentication
            }
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
