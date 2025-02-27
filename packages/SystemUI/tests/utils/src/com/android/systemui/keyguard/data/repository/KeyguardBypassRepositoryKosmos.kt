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

package com.android.systemui.keyguard.data.repository

import android.content.testableContext
import com.android.systemui.dump.dumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_CLOSED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.data.repository.userAwareSecureSettingsRepository
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

val Kosmos.keyguardBypassRepository: KeyguardBypassRepository by Fixture {
    KeyguardBypassRepository(
        testableContext.resources,
        biometricSettingsRepository,
        devicePostureRepository,
        dumpManager,
        userAwareSecureSettingsRepository,
        testDispatcher,
    )
}

fun Kosmos.configureKeyguardBypass(
    isBypassAvailable: Boolean? = null,
    faceAuthEnrolledAndEnabled: Boolean = true,
    faceUnlockBypassOverrideConfig: Int = 0, /* FACE_UNLOCK_BYPASS_NO_OVERRIDE */
    faceAuthPostureConfig: Int = DEVICE_POSTURE_UNKNOWN,
) {
    when (isBypassAvailable) {
        null -> {
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(faceAuthEnrolledAndEnabled)
            testableContext.orCreateTestableResources.addOverride(
                R.integer.config_face_unlock_bypass_override,
                faceUnlockBypassOverrideConfig,
            )
            testableContext.orCreateTestableResources.addOverride(
                R.integer.config_face_auth_supported_posture,
                faceAuthPostureConfig,
            )
        }
        true -> {
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            testableContext.orCreateTestableResources.addOverride(
                R.integer.config_face_unlock_bypass_override,
                1, /* FACE_UNLOCK_BYPASS_ALWAYS */
            )
            testableContext.orCreateTestableResources.addOverride(
                R.integer.config_face_auth_supported_posture,
                DEVICE_POSTURE_UNKNOWN,
            )
        }
        false -> {
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            testableContext.orCreateTestableResources.addOverride(
                R.integer.config_face_unlock_bypass_override,
                2, /* FACE_UNLOCK_BYPASS_NEVER */
            )
            testableContext.orCreateTestableResources.addOverride(
                R.integer.config_face_auth_supported_posture,
                DEVICE_POSTURE_CLOSED,
            )
        }
    }
}

fun DevicePostureController.verifyCallback() =
    withArgCaptor<DevicePostureController.Callback> {
        verify(this@verifyCallback).addCallback(capture())
    }

fun DevicePostureController.verifyNoCallback() =
    verify(this@verifyNoCallback, never()).addCallback(any())
