/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard.data.repository

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import android.os.UserHandle
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.biometrics.AuthController
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Acts as source of truth for biometric features.
 *
 * Abstracts-away data sources and their schemas so the rest of the app doesn't need to worry about
 * upstream changes.
 */
interface BiometricRepository {
    /** Whether any fingerprints are enrolled for the current user. */
    val isFingerprintEnrolled: StateFlow<Boolean>

    /**
     * Whether the current user is allowed to use a strong biometric for device entry based on
     * Android Security policies. If false, the user may be able to use primary authentication for
     * device entry.
     */
    val isStrongBiometricAllowed: StateFlow<Boolean>

    /** Whether fingerprint feature is enabled for the current user by the DevicePolicy */
    val isFingerprintEnabledByDevicePolicy: StateFlow<Boolean>
}

@SysUISingleton
class BiometricRepositoryImpl
@Inject
constructor(
    context: Context,
    lockPatternUtils: LockPatternUtils,
    broadcastDispatcher: BroadcastDispatcher,
    authController: AuthController,
    userRepository: UserRepository,
    devicePolicyManager: DevicePolicyManager,
    @Application scope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
    @Main looper: Looper,
) : BiometricRepository {

    /** UserId of the current selected user. */
    private val selectedUserId: Flow<Int> =
        userRepository.selectedUserInfo.map { it.id }.distinctUntilChanged()

    override val isFingerprintEnrolled: StateFlow<Boolean> =
        selectedUserId
            .flatMapLatest { userId ->
                conflatedCallbackFlow {
                    val callback =
                        object : AuthController.Callback {
                            override fun onEnrollmentsChanged(
                                sensorBiometricType: BiometricType,
                                userId: Int,
                                hasEnrollments: Boolean
                            ) {
                                if (sensorBiometricType.isFingerprint) {
                                    trySendWithFailureLogging(
                                        hasEnrollments,
                                        TAG,
                                        "update fpEnrollment"
                                    )
                                }
                            }
                        }
                    authController.addCallback(callback)
                    awaitClose { authController.removeCallback(callback) }
                }
            }
            .stateIn(
                scope,
                started = SharingStarted.Eagerly,
                initialValue =
                    authController.isFingerprintEnrolled(userRepository.getSelectedUserInfo().id)
            )

    override val isStrongBiometricAllowed: StateFlow<Boolean> =
        selectedUserId
            .flatMapLatest { currUserId ->
                conflatedCallbackFlow {
                    val callback =
                        object : LockPatternUtils.StrongAuthTracker(context, looper) {
                            override fun onStrongAuthRequiredChanged(userId: Int) {
                                if (currUserId != userId) {
                                    return
                                }

                                trySendWithFailureLogging(
                                    isBiometricAllowedForUser(true, currUserId),
                                    TAG
                                )
                            }

                            override fun onIsNonStrongBiometricAllowedChanged(userId: Int) {
                                // no-op
                            }
                        }
                    lockPatternUtils.registerStrongAuthTracker(callback)
                    awaitClose { lockPatternUtils.unregisterStrongAuthTracker(callback) }
                }
            }
            .stateIn(
                scope,
                started = SharingStarted.Eagerly,
                initialValue =
                    lockPatternUtils.isBiometricAllowedForUser(
                        userRepository.getSelectedUserInfo().id
                    )
            )

    override val isFingerprintEnabledByDevicePolicy: StateFlow<Boolean> =
        selectedUserId
            .flatMapLatest { userId ->
                broadcastDispatcher
                    .broadcastFlow(
                        filter = IntentFilter(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                        user = UserHandle.ALL
                    )
                    .transformLatest {
                        emit(
                            (devicePolicyManager.getKeyguardDisabledFeatures(null, userId) and
                                DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT) == 0
                        )
                    }
                    .flowOn(backgroundDispatcher)
                    .distinctUntilChanged()
            }
            .stateIn(
                scope,
                started = SharingStarted.Eagerly,
                initialValue =
                    devicePolicyManager.getKeyguardDisabledFeatures(
                        null,
                        userRepository.getSelectedUserInfo().id
                    ) and DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT == 0
            )

    companion object {
        private const val TAG = "BiometricsRepositoryImpl"
    }
}
