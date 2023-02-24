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
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
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
 * To run this test: `atest FlickerTests:QuickSwitchBetweenTwoAppsBackTest`
 *
 * Actions:
 * ```
 *     Launch an app [testApp1]
 *     Launch another app [testApp2]
 *     Swipe right from the bottom of the screen to quick switch back to the first app [testApp1]
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class QuickSwitchBetweenTwoAppsBackTest_ShellTransit(flicker: FlickerTest) :
    QuickSwitchBetweenTwoAppsBackTest(flicker) {
    @Before
    override fun before() {
        Assume.assumeTrue(isShellTransitionsEnabled)
    }

    /** {@inheritDoc} */
    @Ignore("Nav bar window becomes invisible during quick switch")
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 250520840)
    @Test
    override fun startsWithApp2LayersCoverFullScreen() = super.startsWithApp2LayersCoverFullScreen()

    @FlakyTest(bugId = 246284708)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @FlakyTest(bugId = 250518877)
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()
}
