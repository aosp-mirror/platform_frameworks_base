/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.app.StatusBarManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.camera.CameraGestureHelper
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CameraQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var cameraGestureHelper: CameraGestureHelper
    @Mock private lateinit var context: Context
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager

    private lateinit var underTest: CameraQuickAffordanceConfig
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        setLaunchable()

        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        underTest =
            CameraQuickAffordanceConfig(
                context,
                packageManager,
                { cameraGestureHelper },
                userTracker,
                devicePolicyManager,
                testDispatcher,
            )
    }

    @Test
    fun `affordance triggered -- camera launch called`() {
        // When
        val result = underTest.onTriggered(null)

        // Then
        verify(cameraGestureHelper)
            .launchCamera(StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE)
        assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
    }

    @Test
    fun `getPickerScreenState - default when launchable`() =
        testScope.runTest {
            setLaunchable(true)

            Truth.assertThat(underTest.getPickerScreenState())
                .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Default::class.java)
        }

    @Test
    fun `getPickerScreenState - unavailable when camera app not installed`() =
        testScope.runTest {
            setLaunchable(isCameraAppInstalled = false)

            Truth.assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
        }

    @Test
    fun `getPickerScreenState - unavailable when camera disabled by admin`() =
        testScope.runTest {
            setLaunchable(isCameraDisabledByDeviceAdmin = true)

            Truth.assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
        }

    @Test
    fun `getPickerScreenState - unavailable when secure camera disabled by admin`() =
        testScope.runTest {
            setLaunchable(isSecureCameraDisabledByDeviceAdmin = true)

            Truth.assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
        }

    private fun setLaunchable(
        isCameraAppInstalled: Boolean = true,
        isCameraDisabledByDeviceAdmin: Boolean = false,
        isSecureCameraDisabledByDeviceAdmin: Boolean = false,
    ) {
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
            .thenReturn(isCameraAppInstalled)
        whenever(devicePolicyManager.getCameraDisabled(null, userTracker.userId))
            .thenReturn(isCameraDisabledByDeviceAdmin)
        whenever(devicePolicyManager.getKeyguardDisabledFeatures(null, userTracker.userId))
            .thenReturn(
                if (isSecureCameraDisabledByDeviceAdmin) {
                    DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
                } else {
                    0
                }
            )
    }
}
