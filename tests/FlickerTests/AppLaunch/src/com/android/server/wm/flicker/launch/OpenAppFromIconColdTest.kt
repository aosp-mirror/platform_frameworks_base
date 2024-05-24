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
 */

package com.android.server.wm.flicker.launch

import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.launch.common.OpenAppFromIconTransition
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from launcher
 *
 * To run this test: `atest FlickerTests:OpenAppColdFromIcon`
 *
 * Actions:
 * ```
 *     Make sure no apps are running on the device
 *     Launch an app [testApp] by clicking it's icon on all apps and wait animation to complete
 * ```
 *
 * Notes:
 * ```
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [OpenAppTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 * ```
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class OpenAppFromIconColdTest(flicker: LegacyFlickerTest) :
    OpenAppFromIconTransition(flicker) {

    @FlakyTest(bugId = 240916028)
    @Test
    override fun focusChanges() {
        super.focusChanges()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() {
        super.appWindowReplacesLauncherAsTopWindow()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun appWindowAsTopWindowAtEnd() {
        super.appWindowAsTopWindowAtEnd()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun appWindowBecomesTopWindow() {
        super.appWindowBecomesTopWindow()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun appWindowBecomesVisible() {
        super.appWindowBecomesVisible()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun appWindowIsTopWindowAtEnd() {
        super.appWindowIsTopWindowAtEnd()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun appLayerBecomesVisible() {
        super.appLayerBecomesVisible()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun appLayerReplacesLauncher() {
        super.appLayerReplacesLauncher()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun cujCompleted() {
        super.cujCompleted()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun entireScreenCovered() {
        super.entireScreenCovered()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() {
        super.navBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun navBarLayerPositionAtStartAndEnd() {
        super.navBarLayerPositionAtStartAndEnd()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun navBarWindowIsAlwaysVisible() {
        super.navBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun navBarWindowIsVisibleAtStartAndEnd() {
        super.navBarWindowIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() {
        super.statusBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() {
        super.statusBarLayerPositionAtStartAndEnd()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun statusBarWindowIsAlwaysVisible() {
        super.statusBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() {
        super.taskBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun taskBarWindowIsAlwaysVisible() {
        super.taskBarWindowIsAlwaysVisible()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        super.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    @FlakyTest(bugId = 240916028)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
