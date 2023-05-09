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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeTrustRepository : TrustRepository {
    private val _isCurrentUserTrusted = MutableStateFlow(false)
    override val isCurrentUserTrusted: Flow<Boolean>
        get() = _isCurrentUserTrusted

    private val _isCurrentUserActiveUnlockAvailable = MutableStateFlow(false)
    override val isCurrentUserActiveUnlockAvailable: StateFlow<Boolean> =
        _isCurrentUserActiveUnlockAvailable.asStateFlow()

    private val _isCurrentUserTrustManaged = MutableStateFlow(false)
    override val isCurrentUserTrustManaged: StateFlow<Boolean>
        get() = _isCurrentUserTrustManaged

    fun setCurrentUserTrusted(trust: Boolean) {
        _isCurrentUserTrusted.value = trust
    }

    fun setCurrentUserTrustManaged(value: Boolean) {
        _isCurrentUserTrustManaged.value = value
    }

    fun setCurrentUserActiveUnlockAvailable(available: Boolean) {
        _isCurrentUserActiveUnlockAvailable.value = available
    }
}
