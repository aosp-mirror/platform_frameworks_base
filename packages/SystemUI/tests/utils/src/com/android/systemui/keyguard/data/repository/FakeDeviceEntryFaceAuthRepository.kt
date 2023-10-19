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

import com.android.keyguard.FaceAuthUiEvent
import com.android.systemui.keyguard.shared.model.FaceAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FaceDetectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeDeviceEntryFaceAuthRepository : DeviceEntryFaceAuthRepository {

    private var _wasDisabled: Boolean = false

    val wasDisabled: Boolean
        get() = _wasDisabled

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
    private val _runningAuthRequest = MutableStateFlow<Pair<FaceAuthUiEvent, Boolean>?>(null)
    val runningAuthRequest: StateFlow<Pair<FaceAuthUiEvent, Boolean>?> =
        _runningAuthRequest.asStateFlow()

    private val _isAuthRunning = MutableStateFlow(false)
    override val isAuthRunning: StateFlow<Boolean> = _isAuthRunning

    override val isBypassEnabled = MutableStateFlow(false)
    override fun lockoutFaceAuth() {
        _wasDisabled = true
    }

    private val faceAuthPaused = MutableStateFlow(false)
    override fun pauseFaceAuth() {
        faceAuthPaused.value = true
    }

    override fun resumeFaceAuth() {
        faceAuthPaused.value = false
    }

    fun isFaceAuthPaused(): Boolean {
        return faceAuthPaused.value
    }

    override suspend fun authenticate(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean) {
        _runningAuthRequest.value = uiEvent to fallbackToDetection
        _isAuthRunning.value = true
    }

    fun setLockedOut(value: Boolean) {
        _isLockedOut.value = value
    }

    override fun cancel() {
        _isAuthRunning.value = false
        _runningAuthRequest.value = null
    }
}
