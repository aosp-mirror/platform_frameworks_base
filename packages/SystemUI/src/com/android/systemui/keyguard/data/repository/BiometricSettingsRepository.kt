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
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.user.data.repository.UserRepository
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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

    /**
     * Whether the current user is allowed to use a convenience biometric for device entry based on
     * Android Security policies. If false, the user may be able to use strong biometric or primary
     * authentication for device entry.
     */
    val isNonStrongBiometricAllowed: StateFlow<Boolean>

    /** Whether fingerprint feature is enabled for the current user by the DevicePolicy */
    val isFingerprintEnabledByDevicePolicy: StateFlow<Boolean>

    /**
     * Whether face authentication is supported for the current device posture. Face auth can be
     * restricted to specific postures using [R.integer.config_face_auth_supported_posture]
     */
    val isFaceAuthSupportedInCurrentPosture: Flow<Boolean>

    /**
     * Whether the user manually locked down the device. This doesn't include device policy manager
     * lockdown.
     */
    val isCurrentUserInLockdown: Flow<Boolean>

    /** Authentication flags set for the current user. */
    val authenticationFlags: Flow<AuthenticationFlags>
}

private const val TAG = "BiometricsRepositoryImpl"

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class BiometricSettingsRepositoryImpl
@Inject
constructor(
    context: Context,
    lockPatternUtils: LockPatternUtils,
    broadcastDispatcher: BroadcastDispatcher,
    authController: AuthController,
    private val userRepository: UserRepository,
    devicePolicyManager: DevicePolicyManager,
    @Application scope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
    biometricManager: BiometricManager?,
    devicePostureRepository: DevicePostureRepository,
    dumpManager: DumpManager,
) : BiometricSettingsRepository, Dumpable {

    private val biometricsEnabledForUser = mutableMapOf<Int, Boolean>()

    override val isFaceAuthSupportedInCurrentPosture: Flow<Boolean>

    private val strongAuthTracker = StrongAuthTracker(userRepository, context)

    override val isCurrentUserInLockdown: Flow<Boolean> =
        strongAuthTracker.currentUserAuthFlags.map { it.isInUserLockdown }

    override val authenticationFlags: Flow<AuthenticationFlags> =
        strongAuthTracker.currentUserAuthFlags

    init {
        Log.d(TAG, "Registering StrongAuthTracker")
        lockPatternUtils.registerStrongAuthTracker(strongAuthTracker)
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
                            if (sensorBiometricType == BiometricType.FACE) {
                                trySendWithFailureLogging(
                                    authController.isFaceAuthEnrolled(selectedUserId),
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

    private val isFaceEnabledByBiometricsManagerForCurrentUser: Flow<Boolean> =
        userRepository.selectedUserInfo.flatMapLatest { userInfo ->
            isFaceEnabledByBiometricsManager.map { biometricsEnabledForUser[userInfo.id] ?: false }
        }

    override val isFaceAuthenticationEnabled: Flow<Boolean>
        get() =
            combine(isFaceEnabledByBiometricsManagerForCurrentUser, isFaceEnabledByDevicePolicy) {
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

    private val isFaceEnabledByBiometricsManager: Flow<Pair<Int, Boolean>> =
        conflatedCallbackFlow {
                val callback =
                    object : IBiometricEnabledOnKeyguardCallback.Stub() {
                        override fun onChanged(enabled: Boolean, userId: Int) {
                            trySendWithFailureLogging(
                                Pair(userId, enabled),
                                TAG,
                                "biometricsEnabled state changed"
                            )
                        }
                    }
                biometricManager?.registerEnabledOnKeyguardCallback(callback)
                awaitClose {}
            }
            .onEach { biometricsEnabledForUser[it.first] = it.second }
            // This is because the callback is binder-based and we want to avoid multiple callbacks
            // being registered.
            .stateIn(scope, SharingStarted.Eagerly, Pair(0, false))

    override val isStrongBiometricAllowed: StateFlow<Boolean> =
        strongAuthTracker.isStrongBiometricAllowed.stateIn(
            scope,
            SharingStarted.Eagerly,
            strongAuthTracker.isBiometricAllowedForUser(
                true,
                userRepository.getSelectedUserInfo().id
            )
        )

    override val isNonStrongBiometricAllowed: StateFlow<Boolean> =
        strongAuthTracker.isNonStrongBiometricAllowed.stateIn(
            scope,
            SharingStarted.Eagerly,
            strongAuthTracker.isBiometricAllowedForUser(
                false,
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
}

@OptIn(ExperimentalCoroutinesApi::class)
private class StrongAuthTracker(private val userRepository: UserRepository, context: Context?) :
    LockPatternUtils.StrongAuthTracker(context) {

    // Backing field for onStrongAuthRequiredChanged
    private val _authFlags =
        MutableStateFlow(AuthenticationFlags(currentUserId, getStrongAuthForUser(currentUserId)))

    // Backing field for onIsNonStrongBiometricAllowedChanged
    private val _nonStrongBiometricAllowed =
        MutableStateFlow(
            Pair(currentUserId, isNonStrongBiometricAllowedAfterIdleTimeout(currentUserId))
        )

    val currentUserAuthFlags: Flow<AuthenticationFlags> =
        userRepository.selectedUserInfo
            .map { it.id }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                _authFlags
                    .map { AuthenticationFlags(userId, getStrongAuthForUser(userId)) }
                    .onEach { Log.d(TAG, "currentUser authFlags changed, new value: $it") }
                    .onStart { emit(AuthenticationFlags(userId, getStrongAuthForUser(userId))) }
            }

    /** isStrongBiometricAllowed for the current user. */
    val isStrongBiometricAllowed: Flow<Boolean> =
        currentUserAuthFlags.map { isBiometricAllowedForUser(true, it.userId) }

    /** isNonStrongBiometricAllowed for the current user. */
    val isNonStrongBiometricAllowed: Flow<Boolean> =
        userRepository.selectedUserInfo
            .map { it.id }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                _nonStrongBiometricAllowed
                    .filter { it.first == userId }
                    .map { it.second }
                    .onEach { Log.d(TAG, "isNonStrongBiometricAllowed changed for current user") }
                    .onStart { emit(isNonStrongBiometricAllowedAfterIdleTimeout(userId)) }
            }

    private val currentUserId
        get() = userRepository.getSelectedUserInfo().id

    override fun onStrongAuthRequiredChanged(userId: Int) {
        val newFlags = getStrongAuthForUser(userId)
        _authFlags.value = AuthenticationFlags(userId, newFlags)
        Log.d(TAG, "onStrongAuthRequiredChanged for userId: $userId, flag value: $newFlags")
    }

    override fun onIsNonStrongBiometricAllowedChanged(userId: Int) {
        val allowed = isNonStrongBiometricAllowedAfterIdleTimeout(userId)
        _nonStrongBiometricAllowed.value = Pair(userId, allowed)
        Log.d(TAG, "onIsNonStrongBiometricAllowedChanged for userId: $userId, $allowed")
    }
}

private fun DevicePolicyManager.isFaceDisabled(userId: Int): Boolean =
    isNotActive(userId, DevicePolicyManager.KEYGUARD_DISABLE_FACE)

private fun DevicePolicyManager.isFingerprintDisabled(userId: Int): Boolean =
    isNotActive(userId, DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT)

private fun DevicePolicyManager.isNotActive(userId: Int, policy: Int): Boolean =
    (getKeyguardDisabledFeatures(null, userId) and policy) == 0
