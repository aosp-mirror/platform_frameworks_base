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

import android.platform.test.annotations.RequiresDevice
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switching back to previous app from last opened app
 *
 * To run this test: `atest FlickerTests:QuickSwitchBetweenTwoAppsForwardTestShellTransit`
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
class QuickSwitchBetweenTwoAppsForwardTestShellTransit(private val testSpec: FlickerTestParameter)
    : QuickSwitchBetweenTwoAppsForwardTest(testSpec) {

    @Before
    override fun setup() {
        // This test class should be removed after b/213867585 is fixed.
        Assume.assumeTrue(isShellTransitionsEnabled)
    }

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun startsWithApp1WindowsCoverFullScreen() =
            super.startsWithApp1WindowsCoverFullScreen()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun startsWithApp1LayersCoverFullScreen() = super.startsWithApp1LayersCoverFullScreen()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun startsWithApp1WindowBeingOnTop() = super.startsWithApp1WindowBeingOnTop()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun endsWithApp2WindowsCoveringFullScreen() =
            super.endsWithApp2WindowsCoveringFullScreen()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun endsWithApp2LayersCoveringFullScreen() =
            super.endsWithApp2LayersCoveringFullScreen()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun endsWithApp2BeingOnTop() = super.endsWithApp2BeingOnTop()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun app2WindowBecomesAndStaysVisible() = super.app2WindowBecomesAndStaysVisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun app2LayerBecomesAndStaysVisible() = super.app2LayerBecomesAndStaysVisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun app1WindowBecomesAndStaysInvisible() = super.app1WindowBecomesAndStaysInvisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun app1LayerBecomesAndStaysInvisible() = super.app1LayerBecomesAndStaysInvisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun app2WindowIsVisibleOnceApp1WindowIsInvisible() =
            super.app2WindowIsVisibleOnceApp1WindowIsInvisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun app2LayerIsVisibleOnceApp1LayerIsInvisible() =
            super.app2LayerIsVisibleOnceApp1LayerIsInvisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun navBarLayerAlwaysIsVisible() = super.navBarLayerAlwaysIsVisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun navbarIsAlwaysInRightPosition() = super.navbarIsAlwaysInRightPosition()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc}  */
    @FlakyTest(bugId = 214452854)
    @Test
    override fun statusBarLayerIsAlwaysVisible() = super.statusBarLayerIsAlwaysVisible()
}