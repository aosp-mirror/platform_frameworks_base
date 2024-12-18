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

import androidx.annotation.VisibleForTesting
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepository
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason
import com.android.systemui.deviceentry.shared.model.DeviceUnlockSource
import com.android.systemui.deviceentry.shared.model.DeviceUnlockStatus
import com.android.systemui.flags.SystemPropertiesHelper
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.TrustInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class DeviceUnlockedInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    authenticationInteractor: AuthenticationInteractor,
    deviceEntryRepository: DeviceEntryRepository,
    trustInteractor: TrustInteractor,
    faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    fingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    private val powerInteractor: PowerInteractor,
    private val biometricSettingsInteractor: DeviceEntryBiometricSettingsInteractor,
    private val systemPropertiesHelper: SystemPropertiesHelper,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {

    private val deviceUnlockSource =
        merge(
            fingerprintAuthInteractor.fingerprintSuccess.map { DeviceUnlockSource.Fingerprint },
            faceAuthInteractor.isAuthenticated
                .filter { it }
                .map {
                    if (deviceEntryRepository.isBypassEnabled.value) {
                        DeviceUnlockSource.FaceWithBypass
                    } else {
                        DeviceUnlockSource.FaceWithoutBypass
                    }
                },
            trustInteractor.isTrusted.filter { it }.map { DeviceUnlockSource.TrustAgent },
            authenticationInteractor.onAuthenticationResult
                .filter { it }
                .map { DeviceUnlockSource.BouncerInput },
        )

    private val faceEnrolledAndEnabled = biometricSettingsInteractor.isFaceAuthEnrolledAndEnabled
    private val fingerprintEnrolledAndEnabled =
        biometricSettingsInteractor.isFingerprintAuthEnrolledAndEnabled
    private val trustAgentEnabled = trustInteractor.isEnrolledAndEnabled

    private val faceOrFingerprintOrTrustEnabled: Flow<Triple<Boolean, Boolean, Boolean>> =
        combine(faceEnrolledAndEnabled, fingerprintEnrolledAndEnabled, trustAgentEnabled, ::Triple)

    /**
     * Reason why device entry is restricted to certain authentication methods for the current user.
     *
     * Emits null when there are no device entry restrictions active.
     */
    val deviceEntryRestrictionReason: Flow<DeviceEntryRestrictionReason?> =
        faceOrFingerprintOrTrustEnabled.flatMapLatest {
            (faceEnabled, fingerprintEnabled, trustEnabled) ->
            if (faceEnabled || fingerprintEnabled || trustEnabled) {
                combine(
                    biometricSettingsInteractor.authenticationFlags,
                    faceAuthInteractor.isLockedOut,
                    fingerprintAuthInteractor.isLockedOut,
                    trustInteractor.isTrustAgentCurrentlyAllowed,
                ) { authFlags, isFaceLockedOut, isFingerprintLockedOut, trustManaged ->
                    when {
                        authFlags.isPrimaryAuthRequiredAfterReboot &&
                            wasRebootedForMainlineUpdate() ->
                            DeviceEntryRestrictionReason.DeviceNotUnlockedSinceMainlineUpdate
                        authFlags.isPrimaryAuthRequiredAfterReboot ->
                            DeviceEntryRestrictionReason.DeviceNotUnlockedSinceReboot
                        authFlags.isPrimaryAuthRequiredAfterDpmLockdown ->
                            DeviceEntryRestrictionReason.PolicyLockdown
                        authFlags.isInUserLockdown -> DeviceEntryRestrictionReason.UserLockdown
                        authFlags.isPrimaryAuthRequiredForUnattendedUpdate ->
                            DeviceEntryRestrictionReason.UnattendedUpdate
                        authFlags.isPrimaryAuthRequiredAfterTimeout ->
                            DeviceEntryRestrictionReason.SecurityTimeout
                        authFlags.isPrimaryAuthRequiredAfterLockout ->
                            DeviceEntryRestrictionReason.BouncerLockedOut
                        isFingerprintLockedOut ->
                            DeviceEntryRestrictionReason.StrongBiometricsLockedOut
                        isFaceLockedOut && faceAuthInteractor.isFaceAuthStrong() ->
                            DeviceEntryRestrictionReason.StrongBiometricsLockedOut
                        isFaceLockedOut -> DeviceEntryRestrictionReason.NonStrongFaceLockedOut
                        authFlags.isSomeAuthRequiredAfterAdaptiveAuthRequest ->
                            DeviceEntryRestrictionReason.AdaptiveAuthRequest
                        (trustEnabled && !trustManaged) &&
                            (authFlags.someAuthRequiredAfterTrustAgentExpired ||
                                authFlags.someAuthRequiredAfterUserRequest) ->
                            DeviceEntryRestrictionReason.TrustAgentDisabled
                        authFlags.strongerAuthRequiredAfterNonStrongBiometricsTimeout ->
                            DeviceEntryRestrictionReason.NonStrongBiometricsSecurityTimeout
                        else -> null
                    }
                }
            } else {
                biometricSettingsInteractor.authenticationFlags.map { authFlags ->
                    when {
                        authFlags.isInUserLockdown -> DeviceEntryRestrictionReason.UserLockdown
                        authFlags.isPrimaryAuthRequiredAfterDpmLockdown ->
                            DeviceEntryRestrictionReason.PolicyLockdown
                        else -> null
                    }
                }
            }
        }

    /** Whether the device is in lockdown mode, where bouncer input is required to unlock. */
    val isInLockdown: Flow<Boolean> = deviceEntryRestrictionReason.map { it.isInLockdown() }

    /**
     * Whether the device is unlocked or not, along with the information about the authentication
     * method that was used to unlock the device.
     *
     * A device that is not yet unlocked requires unlocking by completing an authentication
     * challenge according to the current authentication method, unless in cases when the current
     * authentication method is not "secure" (for example, None and Swipe); in such cases, the value
     * of this flow will always be an instance of [DeviceUnlockStatus] with
     * [DeviceUnlockStatus.deviceUnlockSource] as null and [DeviceUnlockStatus.isUnlocked] set to
     * true, even if the lockscreen is showing and still needs to be dismissed by the user to
     * proceed.
     */
    val deviceUnlockStatus: StateFlow<DeviceUnlockStatus> =
        authenticationInteractor.authenticationMethod
            .flatMapLatest { authMethod ->
                if (!authMethod.isSecure) {
                    flowOf(DeviceUnlockStatus(true, null))
                } else if (authMethod == AuthenticationMethodModel.Sim) {
                    // Device is locked if SIM is locked.
                    flowOf(DeviceUnlockStatus(false, null))
                } else {
                    combine(
                            powerInteractor.isAsleep,
                            isInLockdown,
                            keyguardTransitionInteractor
                                .transitionValue(KeyguardState.AOD)
                                .map { it == 1f }
                                .distinctUntilChanged(),
                            ::Triple,
                        )
                        .flatMapLatestConflated { (isAsleep, isInLockdown, isAod) ->
                            val isForceLocked =
                                when {
                                    isAsleep && !isAod -> true
                                    isInLockdown -> true
                                    else -> false
                                }
                            if (isForceLocked) {
                                flowOf(DeviceUnlockStatus(false, null))
                            } else {
                                deviceUnlockSource.map { DeviceUnlockStatus(true, it) }
                            }
                        }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = DeviceUnlockStatus(false, null),
            )

    private fun DeviceEntryRestrictionReason?.isInLockdown(): Boolean {
        return when (this) {
            DeviceEntryRestrictionReason.UserLockdown -> true
            DeviceEntryRestrictionReason.PolicyLockdown -> true

            // Add individual enum value instead of using "else" so new reasons are guaranteed
            // to be added here at compile-time.
            null -> false
            DeviceEntryRestrictionReason.DeviceNotUnlockedSinceReboot -> false
            DeviceEntryRestrictionReason.BouncerLockedOut -> false
            DeviceEntryRestrictionReason.AdaptiveAuthRequest -> false
            DeviceEntryRestrictionReason.NonStrongBiometricsSecurityTimeout -> false
            DeviceEntryRestrictionReason.TrustAgentDisabled -> false
            DeviceEntryRestrictionReason.StrongBiometricsLockedOut -> false
            DeviceEntryRestrictionReason.SecurityTimeout -> false
            DeviceEntryRestrictionReason.DeviceNotUnlockedSinceMainlineUpdate -> false
            DeviceEntryRestrictionReason.UnattendedUpdate -> false
            DeviceEntryRestrictionReason.NonStrongFaceLockedOut -> false
        }
    }

    private fun wasRebootedForMainlineUpdate(): Boolean {
        return systemPropertiesHelper.get(SYS_BOOT_REASON_PROP) == REBOOT_MAINLINE_UPDATE
    }

    companion object {
        @VisibleForTesting const val SYS_BOOT_REASON_PROP = "sys.boot.reason.last"
        @VisibleForTesting const val REBOOT_MAINLINE_UPDATE = "reboot,mainline_update"
    }
}
