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

import android.content.Context
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.utils.leaks.FakeFlashlightController
import com.android.systemui.utils.leaks.LeakCheckedTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class FlashlightQuickAffordanceConfigTest : LeakCheckedTest() {

    @Mock private lateinit var context: Context
    private lateinit var flashlightController: FakeFlashlightController
    private lateinit var underTest: FlashlightQuickAffordanceConfig

    @Before
    fun setUp() {
        injectLeakCheckedDependency(FlashlightController::class.java)
        MockitoAnnotations.initMocks(this)

        flashlightController =
            SysuiLeakCheck().getLeakChecker(FlashlightController::class.java)
                as FakeFlashlightController
        underTest = FlashlightQuickAffordanceConfig(context, flashlightController)
    }

    @Test
    fun `flashlight is off -- triggered -- icon is on and active`() = runTest {
        // given
        flashlightController.isEnabled = false
        flashlightController.isAvailable = true
        val values = mutableListOf<KeyguardQuickAffordanceConfig.LockScreenState>()
        val job = launch(UnconfinedTestDispatcher()) { underTest.lockScreenState.toList(values) }

        // when
        underTest.onTriggered(null)
        val lastValue = values.last()

        // then
        assertTrue(lastValue is KeyguardQuickAffordanceConfig.LockScreenState.Visible)
        assertEquals(
            R.drawable.qs_flashlight_icon_on,
            ((lastValue as KeyguardQuickAffordanceConfig.LockScreenState.Visible).icon
                    as? Icon.Resource)
                ?.res
        )
        job.cancel()
    }

    @Test
    fun `flashlight is on -- triggered -- icon is off and inactive`() = runTest {
        // given
        flashlightController.isEnabled = true
        flashlightController.isAvailable = true
        val values = mutableListOf<KeyguardQuickAffordanceConfig.LockScreenState>()
        val job = launch(UnconfinedTestDispatcher()) { underTest.lockScreenState.toList(values) }

        // when
        underTest.onTriggered(null)
        val lastValue = values.last()

        // then
        assertTrue(lastValue is KeyguardQuickAffordanceConfig.LockScreenState.Visible)
        assertEquals(
            R.drawable.qs_flashlight_icon_off,
            ((lastValue as KeyguardQuickAffordanceConfig.LockScreenState.Visible).icon
                    as? Icon.Resource)
                ?.res
        )
        job.cancel()
    }

    @Test
    fun `flashlight is on -- receives error -- icon is off and inactive`() = runTest {
        // given
        flashlightController.isEnabled = true
        flashlightController.isAvailable = false
        val values = mutableListOf<KeyguardQuickAffordanceConfig.LockScreenState>()
        val job = launch(UnconfinedTestDispatcher()) { underTest.lockScreenState.toList(values) }

        // when
        flashlightController.onFlashlightError()
        val lastValue = values.last()

        // then
        assertTrue(lastValue is KeyguardQuickAffordanceConfig.LockScreenState.Visible)
        assertEquals(
            R.drawable.qs_flashlight_icon_off,
            ((lastValue as KeyguardQuickAffordanceConfig.LockScreenState.Visible).icon
                    as? Icon.Resource)
                ?.res
        )
        job.cancel()
    }

    @Test
    fun `flashlight availability now off -- hidden`() = runTest {
        // given
        flashlightController.isEnabled = true
        flashlightController.isAvailable = false
        val values = mutableListOf<KeyguardQuickAffordanceConfig.LockScreenState>()
        val job = launch(UnconfinedTestDispatcher()) { underTest.lockScreenState.toList(values) }

        // when
        flashlightController.onFlashlightAvailabilityChanged(false)
        val lastValue = values.last()

        // then
        assertTrue(lastValue is KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        job.cancel()
    }

    @Test
    fun `flashlight availability now on -- flashlight on -- inactive and icon off`() = runTest {
        // given
        flashlightController.isEnabled = true
        flashlightController.isAvailable = false
        val values = mutableListOf<KeyguardQuickAffordanceConfig.LockScreenState>()
        val job = launch(UnconfinedTestDispatcher()) { underTest.lockScreenState.toList(values) }

        // when
        flashlightController.onFlashlightAvailabilityChanged(true)
        val lastValue = values.last()

        // then
        assertTrue(lastValue is KeyguardQuickAffordanceConfig.LockScreenState.Visible)
        assertTrue(
            (lastValue as KeyguardQuickAffordanceConfig.LockScreenState.Visible).activationState
                is ActivationState.Active
        )
        assertEquals(R.drawable.qs_flashlight_icon_on, (lastValue.icon as? Icon.Resource)?.res)
        job.cancel()
    }

    @Test
    fun `flashlight availability now on -- flashlight off -- inactive and icon off`() = runTest {
        // given
        flashlightController.isEnabled = false
        flashlightController.isAvailable = false
        val values = mutableListOf<KeyguardQuickAffordanceConfig.LockScreenState>()
        val job = launch(UnconfinedTestDispatcher()) { underTest.lockScreenState.toList(values) }

        // when
        flashlightController.onFlashlightAvailabilityChanged(true)
        val lastValue = values.last()

        // then
        assertTrue(lastValue is KeyguardQuickAffordanceConfig.LockScreenState.Visible)
        assertTrue(
            (lastValue as KeyguardQuickAffordanceConfig.LockScreenState.Visible).activationState
                is ActivationState.Inactive
        )
        assertEquals(R.drawable.qs_flashlight_icon_off, (lastValue.icon as? Icon.Resource)?.res)
        job.cancel()
    }

    @Test
    fun `flashlight available -- picker state default`() = runTest {
        // given
        flashlightController.isAvailable = true

        // when
        val result = underTest.getPickerScreenState()

        // then
        assertTrue(result is KeyguardQuickAffordanceConfig.PickerScreenState.Default)
    }

    @Test
    fun `flashlight not available -- picker state unavailable`() = runTest {
        // given
        flashlightController.isAvailable = false

        // when
        val result = underTest.getPickerScreenState()

        // then
        assertTrue(result is KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
    }
}
