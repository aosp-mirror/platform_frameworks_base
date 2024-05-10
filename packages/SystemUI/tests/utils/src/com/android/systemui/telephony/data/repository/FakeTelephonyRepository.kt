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

package com.android.systemui.telephony.data.repository

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class FakeTelephonyRepository @Inject constructor() : TelephonyRepository {

    private val _callState = MutableStateFlow(0)
    override val callState: Flow<Int> = _callState.asStateFlow()

    private val _isInCall = MutableStateFlow(false)
    override val isInCall: StateFlow<Boolean> = _isInCall.asStateFlow()

    override var hasTelephonyRadio: Boolean = true
        private set

    fun setCallState(value: Int) {
        _callState.value = value
    }

    fun setIsInCall(isInCall: Boolean) {
        _isInCall.value = isInCall
    }

    fun setHasTelephonyRadio(hasTelephonyRadio: Boolean) {
        this.hasTelephonyRadio = hasTelephonyRadio
    }
}

@Module
interface FakeTelephonyRepositoryModule {
    @Binds fun bindFake(fake: FakeTelephonyRepository): TelephonyRepository
}
