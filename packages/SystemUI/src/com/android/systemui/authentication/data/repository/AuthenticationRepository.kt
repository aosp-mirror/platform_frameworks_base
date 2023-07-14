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

import com.android.internal.widget.LockPatternChecker
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationResultModel
import com.android.systemui.authentication.shared.model.AuthenticationThrottlingModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.time.SystemClock
import dagger.Binds
import dagger.Module
import java.util.function.Function
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Defines interface for classes that can access authentication-related application state. */
interface AuthenticationRepository {

    /**
     * Whether the device is unlocked.
     *
     * A device that is not yet unlocked requires unlocking by completing an authentication
     * challenge according to the current authentication method.
     *
     * Note that this state has no real bearing on whether the lockscreen is showing or dismissed.
     */
    val isUnlocked: StateFlow<Boolean>

    /**
     * Whether lock screen bypass is enabled. When enabled, the lock screen will be automatically
     * dismisses once the authentication challenge is completed. For example, completing a biometric
     * authentication challenge via face unlock or fingerprint sensor can automatically bypass the
     * lock screen.
     */
    val isBypassEnabled: StateFlow<Boolean>

    /**
     * Whether the auto confirm feature is enabled for the currently-selected user.
     *
     * Note that the length of the PIN is also important to take into consideration, please see
     * [hintedPinLength].
     */
    val isAutoConfirmEnabled: StateFlow<Boolean>

    /**
     * The exact length a PIN should be for us to enable PIN length hinting.
     *
     * A PIN that's shorter or longer than this is not eligible for the UI to render hints showing
     * how many digits the current PIN is, even if [isAutoConfirmEnabled] is enabled.
     *
     * Note that PIN length hinting is only available if the PIN auto confirmation feature is
     * available.
     */
    val hintedPinLength: Int

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean>

    /** The current throttling state, as cached via [setThrottling]. */
    val throttling: StateFlow<AuthenticationThrottlingModel>

    /**
     * Returns the currently-configured authentication method. This determines how the
     * authentication challenge is completed in order to unlock an otherwise locked device.
     */
    suspend fun getAuthenticationMethod(): AuthenticationMethodModel

    /** Returns the length of the PIN or `0` if the current auth method is not PIN. */
    suspend fun getPinLength(): Int

    /**
     * Returns whether the lockscreen is enabled.
     *
     * When the lockscreen is not enabled, it shouldn't show in cases when the authentication method
     * is considered not secure (for example, "swipe" is considered to be "none").
     */
    suspend fun isLockscreenEnabled(): Boolean

    /** See [isBypassEnabled]. */
    fun setBypassEnabled(isBypassEnabled: Boolean)

    /** Reports an authentication attempt. */
    suspend fun reportAuthenticationAttempt(isSuccessful: Boolean)

    /** Returns the current number of failed authentication attempts. */
    suspend fun getFailedAuthenticationAttemptCount(): Int

    /**
     * Returns the timestamp for when the current throttling will end, allowing the user to attempt
     * authentication again.
     *
     * Note that this is in milliseconds and it matches [SystemClock.elapsedRealtime].
     */
    suspend fun getThrottlingEndTimestamp(): Long

    /** Sets the cached throttling state, updating the [throttling] flow. */
    fun setThrottling(throttlingModel: AuthenticationThrottlingModel)

    /**
     * Sets the throttling timeout duration (time during which the user should not be allowed to
     * attempt authentication).
     */
    suspend fun setThrottleDuration(durationMs: Int)

    /**
     * Checks the given [LockscreenCredential] to see if it's correct, returning an
     * [AuthenticationResultModel] representing what happened.
     */
    suspend fun checkCredential(credential: LockscreenCredential): AuthenticationResultModel
}

class AuthenticationRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val getSecurityMode: Function<Int, KeyguardSecurityModel.SecurityMode>,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val userRepository: UserRepository,
    keyguardRepository: KeyguardRepository,
    private val lockPatternUtils: LockPatternUtils,
) : AuthenticationRepository {

    override val isUnlocked: StateFlow<Boolean> = keyguardRepository.isKeyguardUnlocked

    override suspend fun isLockscreenEnabled(): Boolean {
        return withContext(backgroundDispatcher) {
            val selectedUserId = userRepository.selectedUserId
            !lockPatternUtils.isLockPatternEnabled(selectedUserId)
        }
    }

    private val _isBypassEnabled = MutableStateFlow(false)
    override val isBypassEnabled: StateFlow<Boolean> = _isBypassEnabled.asStateFlow()

    override val isAutoConfirmEnabled: StateFlow<Boolean> =
        userRepository.selectedUserInfo
            .map { it.id }
            .flatMapLatest { userId ->
                flow { emit(lockPatternUtils.isAutoPinConfirmEnabled(userId)) }
                    .flowOn(backgroundDispatcher)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    override val hintedPinLength: Int = 6

    override val isPatternVisible: StateFlow<Boolean> =
        userRepository.selectedUserInfo
            .map { it.id }
            .flatMapLatest { userId ->
                flow { emit(lockPatternUtils.isVisiblePatternEnabled(userId)) }
                    .flowOn(backgroundDispatcher)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = true,
            )

    private val _throttling = MutableStateFlow(AuthenticationThrottlingModel())
    override val throttling: StateFlow<AuthenticationThrottlingModel> = _throttling.asStateFlow()

    private val UserRepository.selectedUserId: Int
        get() = getSelectedUserInfo().id

    override suspend fun getAuthenticationMethod(): AuthenticationMethodModel {
        return withContext(backgroundDispatcher) {
            val selectedUserId = userRepository.selectedUserId
            when (getSecurityMode.apply(selectedUserId)) {
                KeyguardSecurityModel.SecurityMode.PIN,
                KeyguardSecurityModel.SecurityMode.SimPin,
                KeyguardSecurityModel.SecurityMode.SimPuk -> AuthenticationMethodModel.Pin
                KeyguardSecurityModel.SecurityMode.Password -> AuthenticationMethodModel.Password
                KeyguardSecurityModel.SecurityMode.Pattern -> AuthenticationMethodModel.Pattern
                KeyguardSecurityModel.SecurityMode.None -> AuthenticationMethodModel.None
                KeyguardSecurityModel.SecurityMode.Invalid -> error("Invalid security mode!")
            }
        }
    }

    override suspend fun getPinLength(): Int {
        return withContext(backgroundDispatcher) {
            val selectedUserId = userRepository.selectedUserId
            lockPatternUtils.getPinLength(selectedUserId)
        }
    }

    override fun setBypassEnabled(isBypassEnabled: Boolean) {
        _isBypassEnabled.value = isBypassEnabled
    }

    override suspend fun reportAuthenticationAttempt(isSuccessful: Boolean) {
        val selectedUserId = userRepository.selectedUserId
        withContext(backgroundDispatcher) {
            if (isSuccessful) {
                lockPatternUtils.reportSuccessfulPasswordAttempt(selectedUserId)
            } else {
                lockPatternUtils.reportFailedPasswordAttempt(selectedUserId)
            }
        }
    }

    override suspend fun getFailedAuthenticationAttemptCount(): Int {
        return withContext(backgroundDispatcher) {
            val selectedUserId = userRepository.selectedUserId
            lockPatternUtils.getCurrentFailedPasswordAttempts(selectedUserId)
        }
    }

    override suspend fun getThrottlingEndTimestamp(): Long {
        return withContext(backgroundDispatcher) {
            val selectedUserId = userRepository.selectedUserId
            lockPatternUtils.getLockoutAttemptDeadline(selectedUserId)
        }
    }

    override fun setThrottling(throttlingModel: AuthenticationThrottlingModel) {
        _throttling.value = throttlingModel
    }

    override suspend fun setThrottleDuration(durationMs: Int) {
        withContext(backgroundDispatcher) {
            lockPatternUtils.setLockoutAttemptDeadline(
                userRepository.selectedUserId,
                durationMs,
            )
        }
    }

    override suspend fun checkCredential(
        credential: LockscreenCredential
    ): AuthenticationResultModel {
        return suspendCoroutine { continuation ->
            LockPatternChecker.checkCredential(
                lockPatternUtils,
                credential,
                userRepository.selectedUserId,
                object : LockPatternChecker.OnCheckCallback {
                    override fun onChecked(matched: Boolean, throttleTimeoutMs: Int) {
                        continuation.resume(
                            AuthenticationResultModel(
                                isSuccessful = matched,
                                throttleDurationMs = throttleTimeoutMs,
                            )
                        )
                    }

                    override fun onCancelled() {
                        continuation.resume(AuthenticationResultModel(isSuccessful = false))
                    }

                    override fun onEarlyMatched() = Unit
                }
            )
        }
    }
}

@Module
interface AuthenticationRepositoryModule {
    @Binds fun repository(impl: AuthenticationRepositoryImpl): AuthenticationRepository
}
