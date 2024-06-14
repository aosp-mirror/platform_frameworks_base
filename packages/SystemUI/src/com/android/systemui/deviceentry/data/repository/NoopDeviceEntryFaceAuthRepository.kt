/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.deviceentry.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceDetectionStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Implementation of the repository that noops all face auth operations.
 *
 * This is required for SystemUI variants that do not support face authentication but still inject
 * other SysUI components that depend on [DeviceEntryFaceAuthRepository].
 */
@SysUISingleton
class NoopDeviceEntryFaceAuthRepository @Inject constructor() : DeviceEntryFaceAuthRepository {
    override val isAuthenticated: StateFlow<Boolean> = MutableStateFlow(false)

    override val canRunFaceAuth: StateFlow<Boolean> = MutableStateFlow(false)

    override val authenticationStatus: Flow<FaceAuthenticationStatus>
        get() = emptyFlow()

    override val detectionStatus: Flow<FaceDetectionStatus>
        get() = emptyFlow()

    override val isLockedOut: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override val isAuthRunning: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override val isBypassEnabled: Flow<Boolean>
        get() = emptyFlow()

    override fun setLockedOut(isLockedOut: Boolean) = Unit

    /**
     * Trigger face authentication.
     *
     * [uiEvent] provided should be logged whenever face authentication runs. Invocation should be
     * ignored if face authentication is already running. Results should be propagated through
     * [authenticationStatus]
     *
     * Run only face detection when [fallbackToDetection] is true and [canRunFaceAuth] is false.
     */
    override fun requestAuthenticate(uiEvent: FaceAuthUiEvent, fallbackToDetection: Boolean) = Unit

    /** Stop currently running face authentication or detection. */
    override fun cancel() {}
}
