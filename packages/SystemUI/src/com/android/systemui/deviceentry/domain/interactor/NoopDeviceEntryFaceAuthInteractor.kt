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

package com.android.systemui.deviceentry.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceDetectionStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Implementation of the interactor that noops all face auth operations.
 *
 * This is required for SystemUI variants that do not support face authentication but still inject
 * other SysUI components that depend on [DeviceEntryFaceAuthInteractor]
 */
@SysUISingleton
class NoopDeviceEntryFaceAuthInteractor @Inject constructor() : DeviceEntryFaceAuthInteractor {
    override val authenticationStatus: Flow<FaceAuthenticationStatus>
        get() = emptyFlow()
    override val detectionStatus: Flow<FaceDetectionStatus>
        get() = emptyFlow()

    override fun canFaceAuthRun(): Boolean = false

    override fun isRunning(): Boolean = false

    override fun isLockedOut(): Boolean = false

    override fun isFaceAuthEnabledAndEnrolled(): Boolean = false

    override fun isFaceAuthStrong(): Boolean = false

    override fun isAuthenticated(): Boolean = false

    override fun registerListener(listener: FaceAuthenticationListener) {}

    override fun unregisterListener(listener: FaceAuthenticationListener) {}

    override fun onUdfpsSensorTouched() {}

    override fun onAssistantTriggeredOnLockScreen() {}

    override fun onDeviceLifted() {}

    override fun onQsExpansionStared() {}

    override fun onNotificationPanelClicked() {}

    override fun onSwipeUpOnBouncer() {}
    override fun onPrimaryBouncerUserInput() {}
    override fun onAccessibilityAction() {}
    override fun onWalletLaunched() = Unit
}
