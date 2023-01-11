/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.flicker.ime

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.service.PlatformConsts
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME windows switching with 2-Buttons or gestural navigation. To run this test: `atest
 * FlickerTests:SwitchImeWindowsFromGestureNavTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Presubmit
open class SwitchImeWindowsFromGestureNavTest(flicker: FlickerTest) : BaseTest(flicker) {
    private val testApp = SimpleAppHelper(instrumentation)
    private val imeTestApp = ImeAppAutoFocusHelper(instrumentation, flicker.scenario.startRotation)

    @Before
    open fun before() {
        Assume.assumeFalse(isShellTransitionsEnabled)
    }

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            tapl.setIgnoreTaskbarVisibility(true)
            this.setRotation(flicker.scenario.startRotation)
            testApp.launchViaIntent(wmHelper)
            wmHelper.StateSyncBuilder().withFullScreenApp(testApp).waitForAndVerify()

            imeTestApp.launchViaIntent(wmHelper)
            wmHelper.StateSyncBuilder().withFullScreenApp(imeTestApp).waitForAndVerify()

            imeTestApp.openIME(wmHelper)
        }
        teardown {
            tapl.goHome()
            wmHelper.StateSyncBuilder().withHomeActivityVisible().waitForAndVerify()
            testApp.exit(wmHelper)
            imeTestApp.exit(wmHelper)
        }
        transitions {
            // [Step1]: Swipe right from testApp task to imeTestApp
            createTag(TAG_IME_VISIBLE)
            // Expect taskBar invisible when switching to imeTestApp on the large screen device.
            tapl.launchedAppState.quickSwitchToPreviousApp()
            wmHelper.StateSyncBuilder().withFullScreenApp(testApp).waitForAndVerify()
            createTag(TAG_IME_INVISIBLE)
        }
        transitions {
            // [Step2]: Swipe left to back to testApp task
            // Expect taskBar visible when switching to testApp on the large screen device.
            tapl.launchedAppState.quickSwitchToPreviousAppSwipeLeft()
            wmHelper.StateSyncBuilder().withFullScreenApp(imeTestApp).waitForAndVerify()
        }
    }
    /** {@inheritDoc} */
    @FlakyTest(bugId = 265016201)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    @Presubmit
    @Test
    fun imeAppWindowVisibility() {
        flicker.assertWm {
            isAppWindowVisible(imeTestApp)
                .then()
                .isAppSnapshotStartingWindowVisibleFor(testApp, isOptional = true)
                .then()
                .isAppWindowVisible(testApp)
                .then()
                .isAppSnapshotStartingWindowVisibleFor(imeTestApp, isOptional = true)
                .then()
                .isAppWindowVisible(imeTestApp)
        }
    }

    @FlakyTest(bugId = 244414110)
    @Test
    open fun imeLayerIsVisibleWhenSwitchingToImeApp() {
        flicker.assertLayersStart { isVisible(ComponentNameMatcher.IME) }
        flicker.assertLayersTag(TAG_IME_VISIBLE) { isVisible(ComponentNameMatcher.IME) }
        flicker.assertLayersEnd { isVisible(ComponentNameMatcher.IME) }
    }

    @Presubmit
    @Test
    fun imeLayerIsInvisibleWhenSwitchingToTestApp() {
        flicker.assertLayersTag(TAG_IME_INVISIBLE) { isInvisible(ComponentNameMatcher.IME) }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedNavigationModes = listOf(PlatformConsts.NavBar.MODE_GESTURAL),
                supportedRotations = listOf(PlatformConsts.Rotation.ROTATION_0)
            )
        }

        private const val TAG_IME_VISIBLE = "imeVisible"
        private const val TAG_IME_INVISIBLE = "imeInVisible"
    }
}
