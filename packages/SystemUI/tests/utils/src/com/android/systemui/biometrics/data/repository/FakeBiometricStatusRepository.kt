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
 *
 */

package com.android.systemui.biometrics.data.repository

import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.keyguard.shared.model.FingerprintAuthenticationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeBiometricStatusRepository : BiometricStatusRepository {
    private val _fingerprintAuthenticationReason =
        MutableStateFlow<AuthenticationReason>(AuthenticationReason.NotRunning)
    override val fingerprintAuthenticationReason: StateFlow<AuthenticationReason> =
        _fingerprintAuthenticationReason.asStateFlow()

    private val _fingerprintAcquiredStatus =
        MutableStateFlow<FingerprintAuthenticationStatus?>(null)
    override val fingerprintAcquiredStatus: Flow<FingerprintAuthenticationStatus> =
        _fingerprintAcquiredStatus.asStateFlow().filterNotNull()

    fun setFingerprintAuthenticationReason(reason: AuthenticationReason) {
        _fingerprintAuthenticationReason.value = reason
    }

    fun setFingerprintAcquiredStatus(status: FingerprintAuthenticationStatus) {
        _fingerprintAcquiredStatus.value = status
    }
}
