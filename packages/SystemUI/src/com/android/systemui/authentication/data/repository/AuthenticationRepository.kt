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

import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
     * The currently-configured authentication method. This determines how the authentication
     * challenge is completed in order to unlock an otherwise locked device.
     */
    val authenticationMethod: StateFlow<AuthenticationMethodModel>

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

    /** See [isUnlocked]. */
    fun setUnlocked(isUnlocked: Boolean)

    /** See [authenticationMethod]. */
    fun setAuthenticationMethod(authenticationMethod: AuthenticationMethodModel)

    /** See [isBypassEnabled]. */
    fun setBypassEnabled(isBypassEnabled: Boolean)

    /** See [failedAuthenticationAttempts]. */
    fun setFailedAuthenticationAttempts(failedAuthenticationAttempts: Int)
}

class AuthenticationRepositoryImpl @Inject constructor() : AuthenticationRepository {
    // TODO(b/280883900): get data from real data sources in SysUI.

    private val _isUnlocked = MutableStateFlow(false)
    override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _authenticationMethod =
        MutableStateFlow<AuthenticationMethodModel>(AuthenticationMethodModel.Pin(1234))
    override val authenticationMethod: StateFlow<AuthenticationMethodModel> =
        _authenticationMethod.asStateFlow()

    private val _isBypassEnabled = MutableStateFlow(false)
    override val isBypassEnabled: StateFlow<Boolean> = _isBypassEnabled.asStateFlow()

    private val _failedAuthenticationAttempts = MutableStateFlow(0)
    override val failedAuthenticationAttempts: StateFlow<Int> =
        _failedAuthenticationAttempts.asStateFlow()

    override fun setUnlocked(isUnlocked: Boolean) {
        _isUnlocked.value = isUnlocked
    }

    override fun setBypassEnabled(isBypassEnabled: Boolean) {
        _isBypassEnabled.value = isBypassEnabled
    }

    override fun setAuthenticationMethod(authenticationMethod: AuthenticationMethodModel) {
        _authenticationMethod.value = authenticationMethod
    }

    override fun setFailedAuthenticationAttempts(failedAuthenticationAttempts: Int) {
        _failedAuthenticationAttempts.value = failedAuthenticationAttempts
    }
}

@Module
interface AuthenticationRepositoryModule {
    @Binds fun repository(impl: AuthenticationRepositoryImpl): AuthenticationRepository
}
