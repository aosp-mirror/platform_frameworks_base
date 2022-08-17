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

package com.android.server.wm.flicker.quickswitch

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.navBarWindowIsVisibleAtStartAndEnd
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switching back to previous app from last opened app
 *
 * To run this test: `atest FlickerTests:QuickSwitchBetweenTwoAppsForwardTest`
 *
 * Actions:
 *     Launch an app [testApp1]
 *     Launch another app [testApp2]
 *     Swipe right from the bottom of the screen to quick switch back to the first app [testApp1]
 *     Swipe left from the bottom of the screen to quick switch forward to the second app [testApp2]
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
open class QuickSwitchBetweenTwoAppsForwardTest_ShellTransit(
    testSpec: FlickerTestParameter
) : QuickSwitchBetweenTwoAppsForwardTest(testSpec) {
    @Before
    override fun before() {
        Assume.assumeTrue(isShellTransitionsEnabled)
    }

    @FlakyTest(bugId = 228009808)
    @Test
    override fun app2LayerIsVisibleOnceApp1LayerIsInvisible() =
        super.app2LayerIsVisibleOnceApp1LayerIsInvisible()

    @FlakyTest(bugId = 228009808)
    @Test
    override fun app2WindowBecomesAndStaysVisible() = super.app2WindowBecomesAndStaysVisible()

    @FlakyTest(bugId = 228009808)
    @Test
    override fun endsWithApp2BeingOnTop() = super.endsWithApp2BeingOnTop()

    /** {@inheritDoc} */
    @Ignore("Nav bar window becomes invisible during quick switch")
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    /**
     * Checks that [ComponentMatcher.NAV_BAR] window is visible and above the app windows at the start
     * and end of the WM trace
     */
    @Presubmit
    @Test
    fun navBarWindowIsVisibleAtStartAndEnd() {
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.navBarWindowIsVisibleAtStartAndEnd()
    }
}
