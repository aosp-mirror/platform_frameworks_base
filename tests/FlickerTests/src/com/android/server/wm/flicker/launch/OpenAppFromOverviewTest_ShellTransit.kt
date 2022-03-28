/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm.flicker.launch

import androidx.test.filters.FlakyTest
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an app from the recents app view (the overview)
 *
 * To run this test: `atest FlickerTests:OpenAppFromOverviewTest`
 *
 * Actions:
 *     Launch [testApp]
 *     Press recents
 *     Relaunch an app [testApp] by selecting it in the overview screen, and wait animation to
 *     complete (only this action is traced)
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [OpenAppTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class OpenAppFromOverviewTest_ShellTransit(testSpec: FlickerTestParameter)
    : OpenAppFromOverviewTest(testSpec) {
    @Before
    override fun before() {
        assumeTrue(isShellTransitionsEnabled)
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 216266712)
    @Test
    override fun appWindowBecomesTopWindow() = super.appWindowBecomesTopWindow()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 216266712)
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() =
            super.appWindowReplacesLauncherAsTopWindow()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 218470989)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()
}
