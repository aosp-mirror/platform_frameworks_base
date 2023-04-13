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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.app.admin.DevicePolicyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.SysuiTestCase
import com.android.systemui.camera.CameraIntentsWrapper
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class VideoCameraQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var activityIntentHelper: ActivityIntentHelper
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager

    private lateinit var underTest: VideoCameraQuickAffordanceConfig
    private lateinit var userTracker: UserTracker
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        userTracker = FakeUserTracker()
        underTest =
            VideoCameraQuickAffordanceConfig(
                context = context,
                cameraIntents = CameraIntentsWrapper(context),
                activityIntentHelper = activityIntentHelper,
                userTracker = userTracker,
                devicePolicyManager = devicePolicyManager,
                backgroundDispatcher = testDispatcher,
            )
    }

    @Test
    fun lockScreenState_visibleWhenLaunchable() =
        testScope.runTest {
            setLaunchable()

            val lockScreenState = collectLastValue(underTest.lockScreenState)

            assertThat(lockScreenState())
                .isInstanceOf(KeyguardQuickAffordanceConfig.LockScreenState.Visible::class.java)
        }

    @Test
    fun lockScreenState_hiddenWhenAppNotInstalledOnDevice() =
        testScope.runTest {
            setLaunchable(isVideoCameraAppInstalled = false)

            val lockScreenState = collectLastValue(underTest.lockScreenState)

            assertThat(lockScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun lockScreenState_hiddenWhenCameraDisabledByAdmin() =
        testScope.runTest {
            setLaunchable(isCameraDisabledByAdmin = true)

            val lockScreenState = collectLastValue(underTest.lockScreenState)

            assertThat(lockScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun getPickerScreenState_defaultWhenLaunchable() =
        testScope.runTest {
            setLaunchable()

            assertThat(underTest.getPickerScreenState())
                .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Default::class.java)
        }

    @Test
    fun getPickerScreenState_unavailableWhenAppNotInstalledOnDevice() =
        testScope.runTest {
            setLaunchable(isVideoCameraAppInstalled = false)

            assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
        }

    @Test
    fun getPickerScreenState_unavailableWhenCameraDisabledByAdmin() =
        testScope.runTest {
            setLaunchable(isCameraDisabledByAdmin = true)

            assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
        }

    private fun setLaunchable(
        isVideoCameraAppInstalled: Boolean = true,
        isCameraDisabledByAdmin: Boolean = false,
    ) {
        whenever(
                activityIntentHelper.getTargetActivityInfo(
                    any(),
                    anyInt(),
                    anyBoolean(),
                )
            )
            .thenReturn(
                if (isVideoCameraAppInstalled) {
                    mock()
                } else {
                    null
                }
            )
        whenever(devicePolicyManager.getCameraDisabled(null, userTracker.userId))
            .thenReturn(isCameraDisabledByAdmin)
    }
}
