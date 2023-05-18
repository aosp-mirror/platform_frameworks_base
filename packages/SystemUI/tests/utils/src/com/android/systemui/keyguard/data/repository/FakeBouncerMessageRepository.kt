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

import com.android.systemui.keyguard.bouncer.data.repository.BouncerMessageRepository
import com.android.systemui.keyguard.bouncer.shared.model.BouncerMessageModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeBouncerMessageRepository : BouncerMessageRepository {
    private val _primaryAuthMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val primaryAuthMessage: StateFlow<BouncerMessageModel?>
        get() = _primaryAuthMessage

    private val _faceAcquisitionMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val faceAcquisitionMessage: StateFlow<BouncerMessageModel?>
        get() = _faceAcquisitionMessage
    private val _fingerprintAcquisitionMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val fingerprintAcquisitionMessage: StateFlow<BouncerMessageModel?>
        get() = _fingerprintAcquisitionMessage
    private val _customMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val customMessage: StateFlow<BouncerMessageModel?>
        get() = _customMessage
    private val _biometricAuthMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val biometricAuthMessage: StateFlow<BouncerMessageModel?>
        get() = _biometricAuthMessage
    private val _authFlagsMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val authFlagsMessage: StateFlow<BouncerMessageModel?>
        get() = _authFlagsMessage

    private val _biometricLockedOutMessage = MutableStateFlow<BouncerMessageModel?>(null)
    override val biometricLockedOutMessage: Flow<BouncerMessageModel?>
        get() = _biometricLockedOutMessage

    override fun setPrimaryAuthMessage(value: BouncerMessageModel?) {
        _primaryAuthMessage.value = value
    }

    override fun setFaceAcquisitionMessage(value: BouncerMessageModel?) {
        _faceAcquisitionMessage.value = value
    }

    override fun setFingerprintAcquisitionMessage(value: BouncerMessageModel?) {
        _fingerprintAcquisitionMessage.value = value
    }

    override fun setCustomMessage(value: BouncerMessageModel?) {
        _customMessage.value = value
    }

    fun setBiometricAuthMessage(value: BouncerMessageModel?) {
        _biometricAuthMessage.value = value
    }

    fun setAuthFlagsMessage(value: BouncerMessageModel?) {
        _authFlagsMessage.value = value
    }

    fun setBiometricLockedOutMessage(value: BouncerMessageModel?) {
        _biometricLockedOutMessage.value = value
    }

    override fun clearMessage() {
        _primaryAuthMessage.value = null
        _faceAcquisitionMessage.value = null
        _fingerprintAcquisitionMessage.value = null
        _customMessage.value = null
    }
}
