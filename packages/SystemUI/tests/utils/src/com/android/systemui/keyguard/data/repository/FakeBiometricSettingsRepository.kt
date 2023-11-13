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
 * limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.repository

import com.android.internal.widget.LockPatternUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

@SysUISingleton
class FakeBiometricSettingsRepository @Inject constructor() : BiometricSettingsRepository {
    private val _isFingerprintEnrolledAndEnabled = MutableStateFlow(false)
    override val isFingerprintEnrolledAndEnabled: StateFlow<Boolean>
        get() = _isFingerprintEnrolledAndEnabled

    private val _isFingerprintAuthCurrentlyAllowed = MutableStateFlow(false)
    override val isFingerprintAuthCurrentlyAllowed: StateFlow<Boolean>
        get() = _isFingerprintAuthCurrentlyAllowed

    private val _isFaceAuthEnrolledAndEnabled = MutableStateFlow(false)
    override val isFaceAuthEnrolledAndEnabled: StateFlow<Boolean>
        get() = _isFaceAuthEnrolledAndEnabled

    private val _isFaceAuthCurrentlyAllowed = MutableStateFlow(false)
    override val isFaceAuthCurrentlyAllowed: Flow<Boolean>
        get() = _isFaceAuthCurrentlyAllowed

    private val _isFaceAuthSupportedInCurrentPosture = MutableStateFlow(false)
    override val isFaceAuthSupportedInCurrentPosture: Flow<Boolean>
        get() = _isFaceAuthSupportedInCurrentPosture

    override val isCurrentUserInLockdown: Flow<Boolean>
        get() = _authFlags.map { it.isInUserLockdown }

    private val _authFlags = MutableStateFlow(AuthenticationFlags(0, 0))
    override val authenticationFlags: Flow<AuthenticationFlags>
        get() = _authFlags

    fun setAuthenticationFlags(value: AuthenticationFlags) {
        _authFlags.value = value
    }

    fun setIsFingerprintAuthEnrolledAndEnabled(value: Boolean) {
        _isFingerprintEnrolledAndEnabled.value = value
        _isFingerprintAuthCurrentlyAllowed.value = _isFingerprintAuthCurrentlyAllowed.value && value
    }

    fun setIsFingerprintAuthCurrentlyAllowed(value: Boolean) {
        _isFingerprintAuthCurrentlyAllowed.value = value
    }

    fun setIsFaceAuthEnrolledAndEnabled(value: Boolean) {
        _isFaceAuthEnrolledAndEnabled.value = value
        _isFaceAuthCurrentlyAllowed.value = _isFaceAuthCurrentlyAllowed.value && value
    }

    fun setIsFaceAuthCurrentlyAllowed(value: Boolean) {
        _isFaceAuthCurrentlyAllowed.value = value
    }

    fun setIsFaceAuthSupportedInCurrentPosture(value: Boolean) {
        _isFaceAuthSupportedInCurrentPosture.value = value
    }

    fun setIsUserInLockdown(value: Boolean) {
        if (value) {
            setAuthenticationFlags(
                AuthenticationFlags(
                    _authFlags.value.userId,
                    _authFlags.value.flag or
                        LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN
                )
            )
        } else {
            setAuthenticationFlags(
                AuthenticationFlags(
                    _authFlags.value.userId,
                    LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED
                )
            )
        }
    }
}

@Module
interface FakeBiometricSettingsRepositoryModule {
    @Binds fun bindFake(fake: FakeBiometricSettingsRepository): BiometricSettingsRepository
}
