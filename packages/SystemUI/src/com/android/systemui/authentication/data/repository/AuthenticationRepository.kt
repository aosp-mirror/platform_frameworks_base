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

package com.android.systemui.authentication.data.repository

import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.user.data.repository.UserRepository
import dagger.Binds
import dagger.Module
import java.util.function.Function
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * Number of consecutively failed authentication attempts. This resets to `0` when
     * authentication succeeds.
     */
    val failedAuthenticationAttempts: StateFlow<Int>

    /**
     * Returns the currently-configured authentication method. This determines how the
     * authentication challenge is completed in order to unlock an otherwise locked device.
     */
    suspend fun getAuthenticationMethod(): AuthenticationMethodModel

    /** See [isBypassEnabled]. */
    fun setBypassEnabled(isBypassEnabled: Boolean)

    /** See [failedAuthenticationAttempts]. */
    fun setFailedAuthenticationAttempts(failedAuthenticationAttempts: Int)
}

class AuthenticationRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val getSecurityMode: Function<Int, KeyguardSecurityModel.SecurityMode>,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val userRepository: UserRepository,
    private val lockPatternUtils: LockPatternUtils,
    keyguardRepository: KeyguardRepository,
) : AuthenticationRepository {

    override val isUnlocked: StateFlow<Boolean> =
        keyguardRepository.isKeyguardUnlocked.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false,
        )

    private val _isBypassEnabled = MutableStateFlow(false)
    override val isBypassEnabled: StateFlow<Boolean> = _isBypassEnabled.asStateFlow()

    private val _failedAuthenticationAttempts = MutableStateFlow(0)
    override val failedAuthenticationAttempts: StateFlow<Int> =
        _failedAuthenticationAttempts.asStateFlow()

    override suspend fun getAuthenticationMethod(): AuthenticationMethodModel {
        return withContext(backgroundDispatcher) {
            val selectedUserId = userRepository.getSelectedUserInfo().id
            when (getSecurityMode.apply(selectedUserId)) {
                KeyguardSecurityModel.SecurityMode.PIN,
                KeyguardSecurityModel.SecurityMode.SimPin ->
                    AuthenticationMethodModel.Pin(
                        code = listOf(1, 2, 3, 4), // TODO(b/280883900): remove this
                        autoConfirm = lockPatternUtils.isAutoPinConfirmEnabled(selectedUserId),
                    )
                KeyguardSecurityModel.SecurityMode.Password,
                KeyguardSecurityModel.SecurityMode.SimPuk ->
                    AuthenticationMethodModel.Password(
                        password = "password", // TODO(b/280883900): remove this
                    )
                KeyguardSecurityModel.SecurityMode.Pattern ->
                    AuthenticationMethodModel.Pattern(
                        coordinates =
                            listOf(
                                AuthenticationMethodModel.Pattern.PatternCoordinate(2, 0),
                                AuthenticationMethodModel.Pattern.PatternCoordinate(2, 1),
                                AuthenticationMethodModel.Pattern.PatternCoordinate(2, 2),
                                AuthenticationMethodModel.Pattern.PatternCoordinate(1, 1),
                                AuthenticationMethodModel.Pattern.PatternCoordinate(0, 0),
                                AuthenticationMethodModel.Pattern.PatternCoordinate(0, 1),
                                AuthenticationMethodModel.Pattern.PatternCoordinate(0, 2),
                            ), // TODO(b/280883900): remove this
                    )
                KeyguardSecurityModel.SecurityMode.None -> AuthenticationMethodModel.None
                KeyguardSecurityModel.SecurityMode.Invalid -> error("Invalid security mode!")
                null -> error("Invalid security is null!")
            }
        }
    }

    override fun setBypassEnabled(isBypassEnabled: Boolean) {
        _isBypassEnabled.value = isBypassEnabled
    }

    override fun setFailedAuthenticationAttempts(failedAuthenticationAttempts: Int) {
        _failedAuthenticationAttempts.value = failedAuthenticationAttempts
    }
}

@Module
interface AuthenticationRepositoryModule {
    @Binds fun repository(impl: AuthenticationRepositoryImpl): AuthenticationRepository
}
