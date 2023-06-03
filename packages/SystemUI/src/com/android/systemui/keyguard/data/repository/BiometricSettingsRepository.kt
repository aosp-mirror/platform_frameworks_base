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
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.biometrics.AuthController
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.user.data.repository.UserRepository
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Acts as source of truth for biometric authentication related settings like enrollments, device
 * policy, etc.
 *
 * Abstracts-away data sources and their schemas so the rest of the app doesn't need to worry about
 * upstream changes.
 */
interface BiometricSettingsRepository {
    /** Whether any fingerprints are enrolled for the current user. */
    val isFingerprintEnrolled: StateFlow<Boolean>

    /** Whether face authentication is enrolled for the current user. */
    val isFaceEnrolled: Flow<Boolean>

    /**
     * Whether face authentication is enabled/disabled based on system settings like device policy,
     * biometrics setting.
     */
    val isFaceAuthenticationEnabled: Flow<Boolean>

    /**
     * Whether the current user is allowed to use a strong biometric for device entry based on
     * Android Security policies. If false, the user may be able to use primary authentication for
     * device entry.
     */
    val isStrongBiometricAllowed: StateFlow<Boolean>

    /** Whether fingerprint feature is enabled for the current user by the DevicePolicy */
    val isFingerprintEnabledByDevicePolicy: StateFlow<Boolean>

    /**
     * Whether face authentication is supported for the current device posture. Face auth can be
     * restricted to specific postures using [R.integer.config_face_auth_supported_posture]
     */
    val isFaceAuthSupportedInCurrentPosture: Flow<Boolean>
}

@SysUISingleton
class BiometricSettingsRepositoryImpl
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
    biometricManager: BiometricManager?,
    @Main looper: Looper,
    devicePostureRepository: DevicePostureRepository,
    dumpManager: DumpManager,
) : BiometricSettingsRepository, Dumpable {

    override val isFaceAuthSupportedInCurrentPosture: Flow<Boolean>

    init {
        dumpManager.registerDumpable(this)
        val configFaceAuthSupportedPosture =
            DevicePosture.toPosture(
                context.resources.getInteger(R.integer.config_face_auth_supported_posture)
            )
        isFaceAuthSupportedInCurrentPosture =
            if (configFaceAuthSupportedPosture == DevicePosture.UNKNOWN) {
                    flowOf(true)
                } else {
                    devicePostureRepository.currentDevicePosture.map {
                        it == configFaceAuthSupportedPosture
                    }
                }
                .onEach { Log.d(TAG, "isFaceAuthSupportedInCurrentPosture value changed to: $it") }
    }

    override fun dump(pw: PrintWriter, args: Array<String?>) {
        pw.println("isFingerprintEnrolled=${isFingerprintEnrolled.value}")
        pw.println("isStrongBiometricAllowed=${isStrongBiometricAllowed.value}")
        pw.println("isFingerprintEnabledByDevicePolicy=${isFingerprintEnabledByDevicePolicy.value}")
    }

    /** UserId of the current selected user. */
    private val selectedUserId: Flow<Int> =
        userRepository.selectedUserInfo.map { it.id }.distinctUntilChanged()

    private val devicePolicyChangedForAllUsers =
        broadcastDispatcher.broadcastFlow(
            filter = IntentFilter(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
            user = UserHandle.ALL
        )

    override val isFingerprintEnrolled: StateFlow<Boolean> =
        selectedUserId
            .flatMapLatest { currentUserId ->
                conflatedCallbackFlow {
                    val callback =
                        object : AuthController.Callback {
                            override fun onEnrollmentsChanged(
                                sensorBiometricType: BiometricType,
                                userId: Int,
                                hasEnrollments: Boolean
                            ) {
                                if (sensorBiometricType.isFingerprint && userId == currentUserId) {
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

    override val isFaceEnrolled: Flow<Boolean> =
        selectedUserId.flatMapLatest { selectedUserId: Int ->
            conflatedCallbackFlow {
                val callback =
                    object : AuthController.Callback {
                        override fun onEnrollmentsChanged(
                            sensorBiometricType: BiometricType,
                            userId: Int,
                            hasEnrollments: Boolean
                        ) {
                            // TODO(b/242022358), use authController.isFaceAuthEnrolled after
                            //  ag/20176811 is available.
                            if (
                                sensorBiometricType == BiometricType.FACE &&
                                    userId == selectedUserId
                            ) {
                                trySendWithFailureLogging(
                                    hasEnrollments,
                                    TAG,
                                    "Face enrollment changed"
                                )
                            }
                        }
                    }
                authController.addCallback(callback)
                trySendWithFailureLogging(
                    authController.isFaceAuthEnrolled(selectedUserId),
                    TAG,
                    "Initial value of face auth enrollment"
                )
                awaitClose { authController.removeCallback(callback) }
            }
        }

    override val isFaceAuthenticationEnabled: Flow<Boolean>
        get() =
            combine(isFaceEnabledByBiometricsManager, isFaceEnabledByDevicePolicy) {
                biometricsManagerSetting,
                devicePolicySetting ->
                biometricsManagerSetting && devicePolicySetting
            }

    private val isFaceEnabledByDevicePolicy: Flow<Boolean> =
        combine(selectedUserId, devicePolicyChangedForAllUsers) { userId, _ ->
                devicePolicyManager.isFaceDisabled(userId)
            }
            .onStart {
                emit(devicePolicyManager.isFaceDisabled(userRepository.getSelectedUserInfo().id))
            }
            .flowOn(backgroundDispatcher)
            .distinctUntilChanged()

    private val isFaceEnabledByBiometricsManager =
        conflatedCallbackFlow {
                val callback =
                    object : IBiometricEnabledOnKeyguardCallback.Stub() {
                        override fun onChanged(enabled: Boolean, userId: Int) {
                            trySendWithFailureLogging(
                                enabled,
                                TAG,
                                "biometricsEnabled state changed"
                            )
                        }
                    }
                biometricManager?.registerEnabledOnKeyguardCallback(callback)
                awaitClose {}
            }
            // This is because the callback is binder-based and we want to avoid multiple callbacks
            // being registered.
            .stateIn(scope, SharingStarted.Eagerly, false)

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
                devicePolicyChangedForAllUsers
                    .transformLatest { emit(devicePolicyManager.isFingerprintDisabled(userId)) }
                    .flowOn(backgroundDispatcher)
                    .distinctUntilChanged()
            }
            .stateIn(
                scope,
                started = SharingStarted.Eagerly,
                initialValue =
                    devicePolicyManager.isFingerprintDisabled(
                        userRepository.getSelectedUserInfo().id
                    )
            )

    companion object {
        private const val TAG = "BiometricsRepositoryImpl"
    }
}

private fun DevicePolicyManager.isFaceDisabled(userId: Int): Boolean =
    isNotActive(userId, DevicePolicyManager.KEYGUARD_DISABLE_FACE)

private fun DevicePolicyManager.isFingerprintDisabled(userId: Int): Boolean =
    isNotActive(userId, DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT)

private fun DevicePolicyManager.isNotActive(userId: Int, policy: Int): Boolean =
    (getKeyguardDisabledFeatures(null, userId) and policy) == 0
