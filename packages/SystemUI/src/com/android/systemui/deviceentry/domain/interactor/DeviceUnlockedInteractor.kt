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

import android.provider.Settings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.CoreStartable
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepository
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason
import com.android.systemui.deviceentry.shared.model.DeviceUnlockSource
import com.android.systemui.deviceentry.shared.model.DeviceUnlockStatus
import com.android.systemui.flags.SystemPropertiesHelper
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.TrustInteractor
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.settings.repository.UserAwareSecureSettingsRepository
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class DeviceUnlockedInteractor
@Inject
constructor(
    private val authenticationInteractor: AuthenticationInteractor,
    private val repository: DeviceEntryRepository,
    trustInteractor: TrustInteractor,
    faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    fingerprintAuthInteractor: DeviceEntryFingerprintAuthInteractor,
    private val powerInteractor: PowerInteractor,
    private val biometricSettingsInteractor: DeviceEntryBiometricSettingsInteractor,
    private val systemPropertiesHelper: SystemPropertiesHelper,
    private val userAwareSecureSettingsRepository: UserAwareSecureSettingsRepository,
    private val keyguardInteractor: KeyguardInteractor,
) : ExclusiveActivatable() {

    private val deviceUnlockSource =
        merge(
            fingerprintAuthInteractor.fingerprintSuccess.map { DeviceUnlockSource.Fingerprint },
            faceAuthInteractor.isAuthenticated
                .filter { it }
                .map {
                    if (repository.isBypassEnabled.value) {
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
        repository.deviceUnlockStatus.asStateFlow()

    private val lockNowRequests = Channel<Unit>()

    override suspend fun onActivated(): Nothing {
        authenticationInteractor.authenticationMethod.collectLatest { authMethod ->
            if (!authMethod.isSecure) {
                // Device remains unlocked as long as the authentication method is not secure.
                Log.d(TAG, "remaining unlocked because auth method not secure")
                repository.deviceUnlockStatus.value = DeviceUnlockStatus(true, null)
            } else if (authMethod == AuthenticationMethodModel.Sim) {
                // Device remains locked while SIM is locked.
                Log.d(TAG, "remaining locked because SIM locked")
                repository.deviceUnlockStatus.value = DeviceUnlockStatus(false, null)
            } else {
                handleLockAndUnlockEvents()
            }
        }

        awaitCancellation()
    }

    /** Locks the device instantly. */
    fun lockNow() {
        lockNowRequests.trySend(Unit)
    }

    private suspend fun handleLockAndUnlockEvents() {
        try {
            Log.d(TAG, "started watching for lock and unlock events")
            coroutineScope {
                launch { handleUnlockEvents() }
                launch { handleLockEvents() }
            }
        } finally {
            Log.d(TAG, "stopped watching for lock and unlock events")
        }
    }

    private suspend fun handleUnlockEvents() {
        // Unlock the device when a new unlock source is detected.
        deviceUnlockSource.collect {
            Log.d(TAG, "unlocking due to \"$it\"")
            repository.deviceUnlockStatus.value = DeviceUnlockStatus(true, it)
        }
    }

    private suspend fun handleLockEvents() {
        merge(
                // Device wakefulness events.
                powerInteractor.detailedWakefulness
                    .map { Pair(it.isAsleep(), it.lastSleepReason) }
                    .distinctUntilChangedBy { it.first }
                    .map { (isAsleep, lastSleepReason) ->
                        if (isAsleep) {
                            if (
                                (lastSleepReason == WakeSleepReason.POWER_BUTTON) &&
                                    authenticationInteractor.getPowerButtonInstantlyLocks()
                            ) {
                                LockImmediately("locked instantly from power button")
                            } else if (lastSleepReason == WakeSleepReason.SLEEP_BUTTON) {
                                LockImmediately("locked instantly from sleep button")
                            } else {
                                LockWithDelay("entering sleep")
                            }
                        } else {
                            CancelDelayedLock("waking up")
                        }
                    },
                // Device enters lockdown.
                isInLockdown
                    .distinctUntilChanged()
                    .filter { it }
                    .map { LockImmediately("lockdown") },
                // Started dreaming
                powerInteractor.isInteractive.flatMapLatestConflated { isInteractive ->
                    // Only respond to dream state changes while the device is interactive.
                    if (isInteractive) {
                        keyguardInteractor.isDreamingAny.distinctUntilChanged().map { isDreaming ->
                            if (isDreaming) {
                                LockWithDelay("started dreaming")
                            } else {
                                CancelDelayedLock("stopped dreaming")
                            }
                        }
                    } else {
                        emptyFlow()
                    }
                },
                lockNowRequests.receiveAsFlow().map { LockImmediately("lockNow") },
            )
            .collectLatest(::onLockEvent)
    }

    private suspend fun onLockEvent(event: LockEvent) {
        val debugReason = event.debugReason
        when (event) {
            is LockImmediately -> {
                Log.d(TAG, "locking without delay due to \"$debugReason\"")
                repository.deviceUnlockStatus.value = DeviceUnlockStatus(false, null)
            }

            is LockWithDelay -> {
                val lockDelay = lockDelay()
                Log.d(TAG, "locking in ${lockDelay}ms due to \"$debugReason\"")
                try {
                    delay(lockDelay)
                    Log.d(
                        TAG,
                        "locking after having waited for ${lockDelay}ms due to \"$debugReason\"",
                    )
                    repository.deviceUnlockStatus.value = DeviceUnlockStatus(false, null)
                } catch (_: CancellationException) {
                    Log.d(
                        TAG,
                        "delayed locking canceled, original delay was ${lockDelay}ms and reason was \"$debugReason\"",
                    )
                }
            }

            is CancelDelayedLock -> {
                // Do nothing, the mere receipt of this inside of a "latest" block means that any
                // previous coroutine is automatically canceled.
            }
        }
    }

    /**
     * Returns the amount of time to wait before locking down the device after the device has been
     * put to sleep by the user, in milliseconds.
     */
    private suspend fun lockDelay(): Long {
        val lockAfterScreenTimeoutSetting =
            userAwareSecureSettingsRepository
                .getInt(
                    Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                    KeyguardViewMediator.KEYGUARD_LOCK_AFTER_DELAY_DEFAULT,
                )
                .toLong()
        Log.d(TAG, "Lock after screen timeout setting set to ${lockAfterScreenTimeoutSetting}ms")

        val maxTimeToLockDevicePolicy = authenticationInteractor.getMaximumTimeToLock()
        Log.d(TAG, "Device policy max set to ${maxTimeToLockDevicePolicy}ms")

        if (maxTimeToLockDevicePolicy <= 0) {
            // No device policy enforced maximum.
            Log.d(TAG, "No device policy max, delay is ${lockAfterScreenTimeoutSetting}ms")
            return lockAfterScreenTimeoutSetting
        }

        val screenOffTimeoutSetting =
            userAwareSecureSettingsRepository
                .getInt(
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    KeyguardViewMediator.KEYGUARD_DISPLAY_TIMEOUT_DELAY_DEFAULT,
                )
                .coerceAtLeast(0)
                .toLong()
        Log.d(TAG, "Screen off timeout setting set to ${screenOffTimeoutSetting}ms")

        return (maxTimeToLockDevicePolicy - screenOffTimeoutSetting)
            .coerceIn(minimumValue = 0, maximumValue = lockAfterScreenTimeoutSetting)
            .also { Log.d(TAG, "Device policy max enforced, delay is ${it}ms") }
    }

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

    /** [CoreStartable] that activates the [DeviceUnlockedInteractor]. */
    class Activator
    @Inject
    constructor(
        @Application private val applicationScope: CoroutineScope,
        private val interactor: DeviceUnlockedInteractor,
    ) : CoreStartable {
        override fun start() {
            if (!SceneContainerFlag.isEnabled)
                return

            applicationScope.launch { interactor.activate() }
        }
    }

    private sealed interface LockEvent {
        val debugReason: String
    }

    private data class LockImmediately(override val debugReason: String) : LockEvent

    private data class LockWithDelay(override val debugReason: String) : LockEvent

    private data class CancelDelayedLock(override val debugReason: String) : LockEvent

    companion object {
        private val TAG = "DeviceUnlockedInteractor"
        @VisibleForTesting const val SYS_BOOT_REASON_PROP = "sys.boot.reason.last"
        @VisibleForTesting const val REBOOT_MAINLINE_UPDATE = "reboot,mainline_update"
    }
}
