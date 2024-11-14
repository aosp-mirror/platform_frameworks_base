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

package com.android.systemui.statusbar.phone.data.repository

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.KeyguardBypassRepository
import com.android.systemui.keyguard.data.repository.configureKeyguardBypass
import com.android.systemui.keyguard.data.repository.keyguardBypassRepository
import com.android.systemui.keyguard.data.repository.verifyCallback
import com.android.systemui.keyguard.data.repository.verifyNoCallback
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_CLOSED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN
import com.android.systemui.statusbar.policy.devicePostureController
import com.android.systemui.testKosmos
import com.android.systemui.util.settings.data.repository.userAwareSecureSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class KeyguardBypassRepositoryTest : SysuiTestCase() {

    private lateinit var postureControllerCallback: DevicePostureController.Callback

    private val kosmos = testKosmos()
    private lateinit var underTest: KeyguardBypassRepository
    private val testScope = kosmos.testScope

    // overrideFaceBypassSetting overridden to true
    // isFaceEnrolledAndEnabled true
    // isPostureAllowedForFaceAuth true/false on posture changes
    @Test
    fun updatesBypassAvailableOnPostureChanges_bypassOverrideAlways() =
        testScope.runTest {
            // KeyguardBypassRepository#overrideFaceBypassSetting = true due to ALWAYS override
            // Initialize face auth posture to DEVICE_POSTURE_OPENED config
            initUnderTest(
                faceUnlockBypassOverrideConfig = FACE_UNLOCK_BYPASS_ALWAYS,
                faceAuthPostureConfig = DEVICE_POSTURE_CLOSED,
            )
            val isBypassAvailable by collectLastValue(underTest.isBypassAvailable)
            runCurrent()

            postureControllerCallback = kosmos.devicePostureController.verifyCallback()

            // Update face auth posture to match config
            postureControllerCallback.onPostureChanged(DEVICE_POSTURE_CLOSED)

            // Assert bypass available
            assertThat(isBypassAvailable).isTrue()

            // Set face auth posture to not match config
            postureControllerCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

            // Assert bypass not available
            assertThat(isBypassAvailable).isFalse()
        }

    // overrideFaceBypassSetting overridden to false
    // isFaceEnrolledAndEnabled true
    // isPostureAllowedForFaceAuth true/false on posture changes
    @Test
    fun updatesBypassEnabledOnPostureChanges_bypassOverrideNever() =
        testScope.runTest {
            // KeyguardBypassRepository#overrideFaceBypassSetting = false due to NEVER override
            // Initialize face auth posture to DEVICE_POSTURE_OPENED config
            initUnderTest(
                faceUnlockBypassOverrideConfig = FACE_UNLOCK_BYPASS_NEVER,
                faceAuthPostureConfig = DEVICE_POSTURE_CLOSED,
            )
            val bypassEnabled by collectLastValue(underTest.isBypassAvailable)
            runCurrent()
            postureControllerCallback = kosmos.devicePostureController.verifyCallback()

            // Update face auth posture to match config
            postureControllerCallback.onPostureChanged(DEVICE_POSTURE_CLOSED)

            // Assert bypass not enabled
            assertThat(bypassEnabled).isFalse()

            // Set face auth posture to not match config
            postureControllerCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

            // Assert bypass not enabled
            assertThat(bypassEnabled).isFalse()
        }

    // overrideFaceBypassSetting set true/false depending on Setting
    // isFaceEnrolledAndEnabled true
    // isPostureAllowedForFaceAuth true
    @Test
    fun updatesBypassEnabledOnSettingsChanges_bypassNoOverride_devicePostureMatchesConfig() =
        testScope.runTest {
            // No bypass override
            // Initialize face auth posture to DEVICE_POSTURE_OPENED config
            initUnderTest(
                faceUnlockBypassOverrideConfig = FACE_UNLOCK_BYPASS_NO_OVERRIDE,
                faceAuthPostureConfig = DEVICE_POSTURE_CLOSED,
            )

            val bypassEnabled by collectLastValue(underTest.isBypassAvailable)
            runCurrent()
            postureControllerCallback = kosmos.devicePostureController.verifyCallback()

            // Update face auth posture to match config
            postureControllerCallback.onPostureChanged(DEVICE_POSTURE_CLOSED)

            // FACE_UNLOCK_DISMISSES_KEYGUARD setting true
            kosmos.userAwareSecureSettingsRepository.setBoolean(
                Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD,
                true,
            )

            runCurrent()
            // Assert bypass enabled
            assertThat(bypassEnabled).isTrue()

            // FACE_UNLOCK_DISMISSES_KEYGUARD setting false
            kosmos.userAwareSecureSettingsRepository.setBoolean(
                Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD,
                false,
            )

            runCurrent()
            // Assert bypass not enabled
            assertThat(bypassEnabled).isFalse()
        }

    // overrideFaceBypassSetting overridden to true
    // isFaceEnrolledAndEnabled true
    // isPostureAllowedForFaceAuth always true given DEVICE_POSTURE_UNKNOWN config
    @Test
    fun bypassEnabledTrue_bypassAlways_unknownDevicePostureConfig() =
        testScope.runTest {
            // KeyguardBypassRepository#overrideFaceBypassSetting = true due to ALWAYS override
            // Set face auth posture config to unknown
            initUnderTest(
                faceUnlockBypassOverrideConfig = FACE_UNLOCK_BYPASS_ALWAYS,
                faceAuthPostureConfig = DEVICE_POSTURE_UNKNOWN,
            )
            val bypassEnabled by collectLastValue(underTest.isBypassAvailable)
            kosmos.devicePostureController.verifyNoCallback()

            // Assert bypass enabled
            assertThat(bypassEnabled).isTrue()
        }

    // overrideFaceBypassSetting overridden to false
    // isFaceEnrolledAndEnabled true
    // isPostureAllowedForFaceAuth always true given DEVICE_POSTURE_UNKNOWN config
    @Test
    fun bypassEnabledFalse_bypassNever_unknownDevicePostureConfig() =
        testScope.runTest {
            // KeyguardBypassRepository#overrideFaceBypassSetting = false due to NEVER override
            // Set face auth posture config to unknown
            initUnderTest(
                faceUnlockBypassOverrideConfig = FACE_UNLOCK_BYPASS_NEVER,
                faceAuthPostureConfig = DEVICE_POSTURE_UNKNOWN,
            )
            val bypassEnabled by collectLastValue(underTest.isBypassAvailable)
            kosmos.devicePostureController.verifyNoCallback()

            // Assert bypass enabled
            assertThat(bypassEnabled).isFalse()
        }

    private fun TestScope.initUnderTest(
        faceUnlockBypassOverrideConfig: Int,
        faceAuthPostureConfig: Int,
    ) {
        kosmos.configureKeyguardBypass(
            faceAuthEnrolledAndEnabled = true,
            faceUnlockBypassOverrideConfig = faceUnlockBypassOverrideConfig,
            faceAuthPostureConfig = faceAuthPostureConfig,
        )
        underTest = kosmos.keyguardBypassRepository
        runCurrent()
    }

    companion object {
        private const val FACE_UNLOCK_BYPASS_NO_OVERRIDE = 0
        private const val FACE_UNLOCK_BYPASS_ALWAYS = 1
        private const val FACE_UNLOCK_BYPASS_NEVER = 2
    }
}
