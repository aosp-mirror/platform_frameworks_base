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

import com.android.keyguard.TrustGrantFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.TrustModel
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class FakeTrustRepository @Inject constructor() : TrustRepository {
    private val _isTrustUsuallyManaged = MutableStateFlow(false)
    override val isCurrentUserTrustUsuallyManaged: StateFlow<Boolean>
        get() = _isTrustUsuallyManaged
    private val _isCurrentUserTrusted = MutableStateFlow(false)
    override val isCurrentUserTrusted: Flow<Boolean>
        get() = _isCurrentUserTrusted

    private val _isCurrentUserActiveUnlockAvailable = MutableStateFlow(false)
    override val isCurrentUserActiveUnlockRunning: StateFlow<Boolean> =
        _isCurrentUserActiveUnlockAvailable.asStateFlow()

    private val _isCurrentUserTrustManaged = MutableStateFlow(false)
    override val isCurrentUserTrustManaged: StateFlow<Boolean>
        get() = _isCurrentUserTrustManaged

    private val _requestDismissKeyguard = MutableStateFlow(TrustModel(false, 0, TrustGrantFlags(0)))
    override val trustAgentRequestingToDismissKeyguard: Flow<TrustModel> = _requestDismissKeyguard

    fun setCurrentUserTrusted(trust: Boolean) {
        _isCurrentUserTrusted.value = trust
    }

    fun setCurrentUserTrustManaged(value: Boolean) {
        _isCurrentUserTrustManaged.value = value
    }

    fun setCurrentUserActiveUnlockAvailable(available: Boolean) {
        _isCurrentUserActiveUnlockAvailable.value = available
    }

    fun setRequestDismissKeyguard(trustModel: TrustModel) {
        _requestDismissKeyguard.value = trustModel
    }

    fun setTrustUsuallyManaged(value: Boolean) {
        _isTrustUsuallyManaged.value = value
    }
}

@Module
interface FakeTrustRepositoryModule {
    @Binds fun bindFake(fake: FakeTrustRepository): TrustRepository
}
