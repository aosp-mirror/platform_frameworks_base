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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.authentication.data.repository

import android.app.admin.DevicePolicyManager
import android.content.IntentFilter
import android.os.UserHandle
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.authentication.shared.model.AuthenticationLockoutModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationResultModel
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.time.SystemClock
import dagger.Binds
import dagger.Module
import java.util.function.Function
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Defines interface for classes that can access authentication-related application state. */
interface AuthenticationRepository {
    /**
     * Emits the result whenever a PIN/Pattern/Password security challenge is attempted by the user
     * in order to unlock the device.
     */
    val authenticationChallengeResult: SharedFlow<Boolean>

    /**
     * The exact length a PIN should be for us to enable PIN length hinting.
     *
     * A PIN that's shorter or longer than this is not eligible for the UI to render hints showing
     * how many digits the current PIN is, even if [isAutoConfirmFeatureEnabled] is enabled.
     *
     * Note that PIN length hinting is only available if the PIN auto confirmation feature is
     * available.
     */
    val hintedPinLength: Int

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean>

    /**
     * The current authentication lockout (aka "throttling") state, set when the user has to wait
     * before being able to try another authentication attempt. `null` indicates throttling isn't
     * active.
     */
    val lockout: MutableStateFlow<AuthenticationLockoutModel?>

    /** Whether throttling has occurred at least once since the last successful authentication. */
    val hasLockoutOccurred: MutableStateFlow<Boolean>

    /**
     * Whether the auto confirm feature is enabled for the currently-selected user.
     *
     * Note that the length of the PIN is also important to take into consideration, please see
     * [hintedPinLength].
     */
    val isAutoConfirmFeatureEnabled: StateFlow<Boolean>

    /**
     * The currently-configured authentication method. This determines how the authentication
     * challenge needs to be completed in order to unlock an otherwise locked device.
     *
     * Note: there may be other ways to unlock the device that "bypass" the need for this
     * authentication challenge (notably, biometrics like fingerprint or face unlock).
     *
     * Note: by design, this is a [Flow] and not a [StateFlow]; a consumer who wishes to get a
     * snapshot of the current authentication method without establishing a collector of the flow
     * can do so by invoking [getAuthenticationMethod].
     */
    val authenticationMethod: Flow<AuthenticationMethodModel>

    /** The minimal length of a pattern. */
    val minPatternLength: Int

    /** The minimal length of a password. */
    val minPasswordLength: Int

    /** Whether the "enhanced PIN privacy" setting is enabled for the current user. */
    val isPinEnhancedPrivacyEnabled: StateFlow<Boolean>

    /**
     * Returns the currently-configured authentication method. This determines how the
     * authentication challenge needs to be completed in order to unlock an otherwise locked device.
     *
     * Note: there may be other ways to unlock the device that "bypass" the need for this
     * authentication challenge (notably, biometrics like fingerprint or face unlock).
     *
     * Note: by design, this is offered as a convenience method alongside [authenticationMethod].
     * The flow should be used for code that wishes to stay up-to-date its logic as the
     * authentication changes over time and this method should be used for simple code that only
     * needs to check the current value.
     */
    suspend fun getAuthenticationMethod(): AuthenticationMethodModel

    /** Returns the length of the PIN or `0` if the current auth method is not PIN. */
    suspend fun getPinLength(): Int

    /** Reports an authentication attempt. */
    suspend fun reportAuthenticationAttempt(isSuccessful: Boolean)

    /** Reports that the user has entered a temporary device lockout (throttling). */
    suspend fun reportLockoutStarted(durationMs: Int)

    /** Returns the current number of failed authentication attempts. */
    suspend fun getFailedAuthenticationAttemptCount(): Int

    /**
     * Returns the timestamp for when the current lockout will end, allowing the user to attempt
     * authentication again.
     *
     * Note that this is in milliseconds and it matches [SystemClock.elapsedRealtime].
     */
    suspend fun getLockoutEndTimestamp(): Long

    /**
     * Sets the lockout timeout duration (time during which the user should not be allowed to
     * attempt authentication).
     */
    suspend fun setLockoutDuration(durationMs: Int)

    /**
     * Checks the given [LockscreenCredential] to see if it's correct, returning an
     * [AuthenticationResultModel] representing what happened.
     */
    suspend fun checkCredential(credential: LockscreenCredential): AuthenticationResultModel
}

@SysUISingleton
class AuthenticationRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val getSecurityMode: Function<Int, KeyguardSecurityModel.SecurityMode>,
    private val userRepository: UserRepository,
    private val lockPatternUtils: LockPatternUtils,
    broadcastDispatcher: BroadcastDispatcher,
    mobileConnectionsRepository: MobileConnectionsRepository,
) : AuthenticationRepository {

    override val authenticationChallengeResult = MutableSharedFlow<Boolean>()

    override val hintedPinLength: Int = 6

    override val isPatternVisible: StateFlow<Boolean> =
        refreshingFlow(
            initialValue = true,
            getFreshValue = lockPatternUtils::isVisiblePatternEnabled,
        )

    override val lockout: MutableStateFlow<AuthenticationLockoutModel?> = MutableStateFlow(null)

    override val hasLockoutOccurred: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val isAutoConfirmFeatureEnabled: StateFlow<Boolean> =
        refreshingFlow(
            initialValue = false,
            getFreshValue = lockPatternUtils::isAutoPinConfirmEnabled,
        )

    override val authenticationMethod: Flow<AuthenticationMethodModel> =
        combine(userRepository.selectedUserInfo, mobileConnectionsRepository.isAnySimSecure) {
                selectedUserInfo,
                _ ->
                selectedUserInfo.id
            }
            .flatMapLatest { selectedUserId ->
                broadcastDispatcher
                    .broadcastFlow(
                        filter =
                            IntentFilter(
                                DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                            ),
                        user = UserHandle.of(selectedUserId),
                    )
                    .onStart { emit(Unit) }
                    .map { selectedUserId }
            }
            .map { selectedUserId ->
                withContext(backgroundDispatcher) {
                    blockingAuthenticationMethodInternal(selectedUserId)
                }
            }
            .distinctUntilChanged()

    override val minPatternLength: Int = LockPatternUtils.MIN_LOCK_PATTERN_SIZE

    override val minPasswordLength: Int = LockPatternUtils.MIN_LOCK_PASSWORD_SIZE

    override val isPinEnhancedPrivacyEnabled: StateFlow<Boolean> =
        refreshingFlow(
            initialValue = true,
            getFreshValue = { userId -> lockPatternUtils.isPinEnhancedPrivacyEnabled(userId) },
        )

    override suspend fun getAuthenticationMethod(): AuthenticationMethodModel {
        return withContext(backgroundDispatcher) {
            blockingAuthenticationMethodInternal(selectedUserId)
        }
    }

    override suspend fun getPinLength(): Int {
        return withContext(backgroundDispatcher) { lockPatternUtils.getPinLength(selectedUserId) }
    }

    override suspend fun reportAuthenticationAttempt(isSuccessful: Boolean) {
        withContext(backgroundDispatcher) {
            if (isSuccessful) {
                lockPatternUtils.reportSuccessfulPasswordAttempt(selectedUserId)
            } else {
                lockPatternUtils.reportFailedPasswordAttempt(selectedUserId)
            }
            authenticationChallengeResult.emit(isSuccessful)
        }
    }

    override suspend fun reportLockoutStarted(durationMs: Int) {
        return withContext(backgroundDispatcher) {
            lockPatternUtils.reportPasswordLockout(durationMs, selectedUserId)
        }
    }

    override suspend fun getFailedAuthenticationAttemptCount(): Int {
        return withContext(backgroundDispatcher) {
            lockPatternUtils.getCurrentFailedPasswordAttempts(selectedUserId)
        }
    }

    override suspend fun getLockoutEndTimestamp(): Long {
        return withContext(backgroundDispatcher) {
            lockPatternUtils.getLockoutAttemptDeadline(selectedUserId)
        }
    }

    override suspend fun setLockoutDuration(durationMs: Int) {
        withContext(backgroundDispatcher) {
            lockPatternUtils.setLockoutAttemptDeadline(selectedUserId, durationMs)
        }
    }

    override suspend fun checkCredential(
        credential: LockscreenCredential
    ): AuthenticationResultModel {
        return withContext(backgroundDispatcher) {
            try {
                val matched = lockPatternUtils.checkCredential(credential, selectedUserId) {}
                AuthenticationResultModel(isSuccessful = matched, lockoutDurationMs = 0)
            } catch (ex: LockPatternUtils.RequestThrottledException) {
                AuthenticationResultModel(isSuccessful = false, lockoutDurationMs = ex.timeoutMs)
            }
        }
    }

    private val selectedUserId: Int
        get() = userRepository.getSelectedUserInfo().id

    /**
     * Returns a [StateFlow] that's automatically kept fresh. The passed-in [getFreshValue] is
     * invoked on a background thread every time the selected user is changed and every time a new
     * downstream subscriber is added to the flow.
     *
     * Initially, the flow will emit [initialValue] while it refreshes itself in the background by
     * invoking the [getFreshValue] function and emitting the fresh value when that's done.
     *
     * Every time the selected user is changed, the flow will re-invoke [getFreshValue] and emit the
     * new value.
     *
     * Every time a new downstream subscriber is added to the flow it first receives the latest
     * cached value that's either the [initialValue] or the latest previously fetched value. In
     * addition, adding a new downstream subscriber also triggers another [getFreshValue] call and a
     * subsequent emission of that newest value.
     */
    private fun <T> refreshingFlow(
        initialValue: T,
        getFreshValue: suspend (selectedUserId: Int) -> T,
    ): StateFlow<T> {
        val flow = MutableStateFlow(initialValue)
        applicationScope.launch {
            combine(
                    // Emits a value initially and every time the selected user is changed.
                    userRepository.selectedUserInfo.map { it.id }.distinctUntilChanged(),
                    // Emits a value only when the number of downstream subscribers of this flow
                    // increases.
                    flow.subscriptionCount.pairwise(initialValue = 0).filter { (previous, current)
                        ->
                        current > previous
                    },
                ) { selectedUserId, _ ->
                    selectedUserId
                }
                .collect { selectedUserId ->
                    flow.value = withContext(backgroundDispatcher) { getFreshValue(selectedUserId) }
                }
        }

        return flow.asStateFlow()
    }

    /**
     * Returns the authentication method for the given user ID.
     *
     * WARNING: this is actually a blocking IPC/"binder" call that's expensive to do on the main
     * thread. We keep it not marked as `suspend` because we want to be able to run this without a
     * `runBlocking` which has a ton of performance/blocking problems.
     */
    private fun blockingAuthenticationMethodInternal(
        userId: Int,
    ): AuthenticationMethodModel {
        return when (getSecurityMode.apply(userId)) {
            KeyguardSecurityModel.SecurityMode.PIN -> AuthenticationMethodModel.Pin
            KeyguardSecurityModel.SecurityMode.SimPin,
            KeyguardSecurityModel.SecurityMode.SimPuk -> AuthenticationMethodModel.Sim
            KeyguardSecurityModel.SecurityMode.Password -> AuthenticationMethodModel.Password
            KeyguardSecurityModel.SecurityMode.Pattern -> AuthenticationMethodModel.Pattern
            KeyguardSecurityModel.SecurityMode.None -> AuthenticationMethodModel.None
            KeyguardSecurityModel.SecurityMode.Invalid -> error("Invalid security mode!")
        }
    }
}

@Module
interface AuthenticationRepositoryModule {
    @Binds fun repository(impl: AuthenticationRepositoryImpl): AuthenticationRepository
}
