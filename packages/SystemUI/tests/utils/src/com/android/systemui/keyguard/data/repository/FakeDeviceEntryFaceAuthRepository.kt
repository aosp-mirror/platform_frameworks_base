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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceDetectionStatus
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

@SysUISingleton
class FakeDeviceEntryFaceAuthRepository @Inject constructor() : DeviceEntryFaceAuthRepository {
    override val isAuthenticated = MutableStateFlow(false)
    override val canRunFaceAuth = MutableStateFlow(false)
    private val _authenticationStatus = MutableStateFlow<FaceAuthenticationStatus?>(null)
    override val authenticationStatus: Flow<FaceAuthenticationStatus> =
        _authenticationStatus.filterNotNull()

    fun setAuthenticationStatus(status: FaceAuthenticationStatus) {
        _authenticationStatus.value = status
    }

    private val _detectionStatus = MutableStateFlow<FaceDetectionStatus?>(null)
    override val detectionStatus: Flow<FaceDetectionStatus>
        get() = _detectionStatus.filterNotNull()

    fun setDetectionStatus(status: FaceDetectionStatus) {
        _detectionStatus.value = status
    }

    private val _isLockedOut = MutableStateFlow(false)
    override val isLockedOut = _isLockedOut
    val runningAuthRequest: MutableStateFlow<Pair<FaceAuthUiEvent, Boolean>?> =
        MutableStateFlow(null)

    private val _isAuthRunning = MutableStateFlow(false)
    override val isAuthRunning: StateFlow<Boolean> = _isAuthRunning

    override val isBypassEnabled = MutableStateFlow(false)

    override fun setLockedOut(isLockedOut: Boolean) {
        _isLockedOut.value = isLockedOut
    }

    override fun requestAuthenticate(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean) {
        runningAuthRequest.value = uiEvent to fallbackToDetection
        _isAuthRunning.value = true
    }

    override fun cancel() {
        _isAuthRunning.value = false
        runningAuthRequest.value = null
    }
}

@Module
interface FakeDeviceEntryFaceAuthRepositoryModule {
    @Binds fun bindFake(fake: FakeDeviceEntryFaceAuthRepository): DeviceEntryFaceAuthRepository
}
