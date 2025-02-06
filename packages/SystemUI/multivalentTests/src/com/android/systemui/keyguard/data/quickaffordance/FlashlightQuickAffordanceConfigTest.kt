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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.utils.leaks.FakeFlashlightController
import com.android.systemui.utils.leaks.LeakCheckedTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
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
    fun flashlightIsOff_triggered_iconIsOnAndActive() = runTest {
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
    fun flashlightIsOn_triggered_iconIsOffAndInactive() = runTest {
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
    fun flashlightIsOn_receivesError_iconIsOffAndInactive() = runTest {
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
    fun flashlightAvailabilityNowOff_hidden() = runTest {
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
    fun flashlightAvailabilityNowOn_flashlightOn_inactiveAndIconOff() = runTest {
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
    fun flashlightAvailabilityNowOn_flashlightOff_inactiveAndIconOff() = runTest {
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
    fun flashlightAvailable_pickerStateDefault() = runTest {
        // given
        flashlightController.isAvailable = true

        // when
        val result = underTest.getPickerScreenState()

        // then
        assertTrue(result is KeyguardQuickAffordanceConfig.PickerScreenState.Default)
    }

    @Test
    fun flashlightNotAvailable_pickerStateUnavailable() = runTest {
        // given
        flashlightController.isAvailable = false

        // when
        val result = underTest.getPickerScreenState()

        // then
        assertTrue(result is KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
    }
}
