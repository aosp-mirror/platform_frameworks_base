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

package com.android.systemui.keyguard.data.repository

import android.hardware.biometrics.BiometricAuthenticator
import android.hardware.biometrics.BiometricAuthenticator.Modality
import android.hardware.biometrics.BiometricSourceType
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.biometrics.AuthController
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** Encapsulates state about device entry fingerprint auth mechanism. */
interface DeviceEntryFingerprintAuthRepository {
    /** Whether the device entry fingerprint auth is locked out. */
    val isLockedOut: Flow<Boolean>

    /**
     * Whether the fingerprint sensor is currently listening, this doesn't mean that the user is
     * actively authenticating.
     */
    val isRunning: Flow<Boolean>

    /**
     * Fingerprint sensor type present on the device, null if fingerprint sensor is not available.
     */
    val availableFpSensorType: Flow<BiometricType?>

    /** Provide the current status of fingerprint authentication. */
    val authenticationStatus: Flow<FingerprintAuthenticationStatus>

    /** Indicates whether to update the side fingerprint sensor indicator visibility. */
    val shouldUpdateIndicatorVisibility: Flow<Boolean>
}

/**
 * Implementation of [DeviceEntryFingerprintAuthRepository] that uses [KeyguardUpdateMonitor] as the
 * source of truth.
 *
 * Dependency on [KeyguardUpdateMonitor] will be removed once fingerprint auth state is moved out of
 * [KeyguardUpdateMonitor]
 */
@SysUISingleton
class DeviceEntryFingerprintAuthRepositoryImpl
@Inject
constructor(
    val authController: AuthController,
    val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    @Application scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : DeviceEntryFingerprintAuthRepository {

    override val availableFpSensorType: Flow<BiometricType?>
        get() {
            return if (authController.areAllFingerprintAuthenticatorsRegistered()) {
                flowOf(getFpSensorType())
            } else {
                conflatedCallbackFlow {
                    val callback =
                        object : AuthController.Callback {
                            override fun onAllAuthenticatorsRegistered(@Modality modality: Int) {
                                if (modality == BiometricAuthenticator.TYPE_FINGERPRINT)
                                    trySendWithFailureLogging(
                                        getFpSensorType(),
                                        TAG,
                                        "onAllAuthenticatorsRegistered, emitting fpSensorType"
                                    )
                            }
                        }
                    authController.addCallback(callback)
                    trySendWithFailureLogging(
                        getFpSensorType(),
                        TAG,
                        "initial value for fpSensorType"
                    )
                    awaitClose { authController.removeCallback(callback) }
                }
            }
        }

    private fun getFpSensorType(): BiometricType? {
        return if (authController.isUdfpsSupported) BiometricType.UNDER_DISPLAY_FINGERPRINT
        else if (authController.isSfpsSupported) BiometricType.SIDE_FINGERPRINT
        else if (authController.isRearFpsSupported) BiometricType.REAR_FINGERPRINT else null
    }

    override val isLockedOut: Flow<Boolean> =
        conflatedCallbackFlow {
                val sendLockoutUpdate =
                    fun() {
                        trySendWithFailureLogging(
                            keyguardUpdateMonitor.isFingerprintLockedOut,
                            TAG,
                            "onLockedOutStateChanged"
                        )
                    }
                val callback =
                    object : KeyguardUpdateMonitorCallback() {
                        override fun onLockedOutStateChanged(
                            biometricSourceType: BiometricSourceType?
                        ) {
                            if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                                sendLockoutUpdate()
                            }
                        }
                    }
                keyguardUpdateMonitor.registerCallback(callback)
                sendLockoutUpdate()
                awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
            }
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = false)

    override val isRunning: Flow<Boolean>
        get() =
            conflatedCallbackFlow {
                    val callback =
                        object : KeyguardUpdateMonitorCallback() {
                            override fun onBiometricRunningStateChanged(
                                running: Boolean,
                                biometricSourceType: BiometricSourceType?
                            ) {
                                if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                                    trySendWithFailureLogging(
                                        running,
                                        TAG,
                                        "Fingerprint running state changed"
                                    )
                                }
                            }
                        }
                    keyguardUpdateMonitor.registerCallback(callback)
                    trySendWithFailureLogging(
                        keyguardUpdateMonitor.isFingerprintDetectionRunning,
                        TAG,
                        "Initial fingerprint running state"
                    )
                    awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
                }
                .flowOn(
                    mainDispatcher
                ) // keyguardUpdateMonitor requires registration on main thread.

    override val authenticationStatus: Flow<FingerprintAuthenticationStatus>
        get() = conflatedCallbackFlow {
            val callback =
                object : KeyguardUpdateMonitorCallback() {
                    override fun onBiometricAuthenticated(
                        userId: Int,
                        biometricSourceType: BiometricSourceType,
                        isStrongBiometric: Boolean,
                    ) {
                        sendUpdateIfFingerprint(
                            biometricSourceType,
                            SuccessFingerprintAuthenticationStatus(
                                userId,
                                isStrongBiometric,
                            ),
                        )
                    }

                    override fun onBiometricError(
                        msgId: Int,
                        errString: String?,
                        biometricSourceType: BiometricSourceType,
                    ) {
                        sendUpdateIfFingerprint(
                            biometricSourceType,
                            ErrorFingerprintAuthenticationStatus(
                                msgId,
                                errString,
                            ),
                        )
                    }

                    override fun onBiometricHelp(
                        msgId: Int,
                        helpString: String?,
                        biometricSourceType: BiometricSourceType,
                    ) {
                        sendUpdateIfFingerprint(
                            biometricSourceType,
                            HelpFingerprintAuthenticationStatus(
                                msgId,
                                helpString,
                            ),
                        )
                    }

                    override fun onBiometricAuthFailed(
                        biometricSourceType: BiometricSourceType,
                    ) {
                        sendUpdateIfFingerprint(
                            biometricSourceType,
                            FailFingerprintAuthenticationStatus,
                        )
                    }

                    override fun onBiometricAcquired(
                        biometricSourceType: BiometricSourceType,
                        acquireInfo: Int,
                    ) {
                        sendUpdateIfFingerprint(
                            biometricSourceType,
                            AcquiredFingerprintAuthenticationStatus(
                                acquireInfo,
                            ),
                        )
                    }

                    private fun sendUpdateIfFingerprint(
                        biometricSourceType: BiometricSourceType,
                        authenticationStatus: FingerprintAuthenticationStatus
                    ) {
                        if (biometricSourceType != BiometricSourceType.FINGERPRINT) {
                            return
                        }

                        trySendWithFailureLogging(
                            authenticationStatus,
                            TAG,
                            "new fingerprint authentication status"
                        )
                    }
                }
            keyguardUpdateMonitor.registerCallback(callback)
            awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
        }

    override val shouldUpdateIndicatorVisibility: Flow<Boolean> =
        conflatedCallbackFlow {
                val sendShouldUpdateIndicatorVisibility =
                    { shouldUpdateIndicatorVisibility: Boolean ->
                        trySendWithFailureLogging(
                            shouldUpdateIndicatorVisibility,
                            TAG,
                            "Error sending shouldUpdateIndicatorVisibility " +
                                "$shouldUpdateIndicatorVisibility"
                        )
                    }

                val callback =
                    object : KeyguardUpdateMonitorCallback() {
                        override fun onBiometricRunningStateChanged(
                            running: Boolean,
                            biometricSourceType: BiometricSourceType?
                        ) {
                            sendShouldUpdateIndicatorVisibility(true)
                        }
                        override fun onStrongAuthStateChanged(userId: Int) {
                            sendShouldUpdateIndicatorVisibility(true)
                        }
                    }
                sendShouldUpdateIndicatorVisibility(false)
                keyguardUpdateMonitor.registerCallback(callback)
                awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
            }
            .flowOn(mainDispatcher)
            .shareIn(scope, started = SharingStarted.WhileSubscribed(), replay = 1)

    companion object {
        const val TAG = "DeviceEntryFingerprintAuthRepositoryImpl"
    }
}
