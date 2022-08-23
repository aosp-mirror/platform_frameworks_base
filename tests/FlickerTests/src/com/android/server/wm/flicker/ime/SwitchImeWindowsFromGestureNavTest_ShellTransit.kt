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
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group4
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
 * Test IME windows switching with 2-Buttons or gestural navigation.
 * To run this test: `atest FlickerTests:SwitchImeWindowsFromGestureNavTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
class SwitchImeWindowsFromGestureNavTest_ShellTransit(
    testSpec: FlickerTestParameter
) : SwitchImeWindowsFromGestureNavTest(testSpec) {
    @Before
    override fun before() {
        Assume.assumeTrue(isShellTransitionsEnabled)
    }

    @FlakyTest(bugId = 228012334)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    @FlakyTest(bugId = 228012334)
    @Test
    override fun imeLayerIsVisibleWhenSwitchingToImeApp() =
        super.imeLayerIsVisibleWhenSwitchingToImeApp()

    @FlakyTest(bugId = 228012334)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @FlakyTest(bugId = 228012334)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

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
