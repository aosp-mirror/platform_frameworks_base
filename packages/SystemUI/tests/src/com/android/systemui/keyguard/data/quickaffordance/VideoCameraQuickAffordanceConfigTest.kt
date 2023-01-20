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

import androidx.test.filters.SmallTest
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.SysuiTestCase
import com.android.systemui.camera.CameraIntentsWrapper
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class VideoCameraQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var activityIntentHelper: ActivityIntentHelper

    private lateinit var underTest: VideoCameraQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            VideoCameraQuickAffordanceConfig(
                context = context,
                cameraIntents = CameraIntentsWrapper(context),
                activityIntentHelper = activityIntentHelper,
                userTracker = FakeUserTracker(),
            )
    }

    @Test
    fun `lockScreenState - visible when launchable`() = runTest {
        setLaunchable(true)

        val lockScreenState = collectLastValue(underTest.lockScreenState)

        assertThat(lockScreenState())
            .isInstanceOf(KeyguardQuickAffordanceConfig.LockScreenState.Visible::class.java)
    }

    @Test
    fun `lockScreenState - hidden when not launchable`() = runTest {
        setLaunchable(false)

        val lockScreenState = collectLastValue(underTest.lockScreenState)

        assertThat(lockScreenState())
            .isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
    }

    @Test
    fun `getPickerScreenState - default when launchable`() = runTest {
        setLaunchable(true)

        assertThat(underTest.getPickerScreenState())
            .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Default::class.java)
    }

    @Test
    fun `getPickerScreenState - unavailable when not launchable`() = runTest {
        setLaunchable(false)

        assertThat(underTest.getPickerScreenState())
            .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
    }

    private fun setLaunchable(isLaunchable: Boolean) {
        whenever(
                activityIntentHelper.getTargetActivityInfo(
                    any(),
                    anyInt(),
                    anyBoolean(),
                )
            )
            .thenReturn(
                if (isLaunchable) {
                    mock()
                } else {
                    null
                }
            )
    }
}
