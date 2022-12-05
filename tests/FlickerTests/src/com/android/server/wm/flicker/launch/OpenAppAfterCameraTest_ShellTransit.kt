/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an app after cold opening camera (with shell transitions)
 *
 * To run this test: `atest FlickerTests:OpenAppAfterCameraTest_ShellTransit`
 *
 * Notes: Some default assertions are inherited [OpenAppTransition]
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenAppAfterCameraTest_ShellTransit(flicker: FlickerTest) : OpenAppAfterCameraTest(flicker) {
    @Before
    override fun before() {
        Assume.assumeTrue(isShellTransitionsEnabled)
    }

    @FlakyTest
    @Test
    override fun appLayerReplacesLauncher() {
        super.appLayerReplacesLauncher()
    }

    @FlakyTest
    @Test
    override fun appLayerBecomesVisible() {
        super.appLayerBecomesVisible()
    }

    @FlakyTest
    @Test
    override fun appWindowBecomesTopWindow() {
        super.appWindowBecomesTopWindow()
    }

    @FlakyTest
    @Test
    override fun appWindowBecomesVisible() {
        super.appWindowBecomesVisible()
    }

    @FlakyTest
    @Test
    override fun appWindowIsTopWindowAtEnd() {
        super.appWindowIsTopWindowAtEnd()
    }

    @FlakyTest
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() {
        super.appWindowReplacesLauncherAsTopWindow()
    }

    @FlakyTest
    @Test
    override fun entireScreenCovered() {
        super.entireScreenCovered()
    }

    @FlakyTest
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() {
        super.navBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest
    @Test
    override fun navBarLayerPositionAtStartAndEnd() {
        super.navBarLayerPositionAtStartAndEnd()
    }

    @FlakyTest
    @Test
    override fun navBarWindowIsAlwaysVisible() {
        super.navBarWindowIsAlwaysVisible()
    }

    @FlakyTest
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() {
        super.statusBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() {
        super.statusBarLayerPositionAtStartAndEnd()
    }

    @FlakyTest
    @Test
    override fun statusBarWindowIsAlwaysVisible() {
        super.statusBarWindowIsAlwaysVisible()
    }

    @FlakyTest
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() {
        super.taskBarLayerIsVisibleAtStartAndEnd()
    }

    @FlakyTest
    @Test
    override fun taskBarWindowIsAlwaysVisible() {
        super.taskBarWindowIsAlwaysVisible()
    }

    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        super.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    @FlakyTest
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()
    }

    @FlakyTest
    @Test
    override fun focusChanges() {
        super.focusChanges()
    }

    @FlakyTest
    @Test
    override fun appWindowAsTopWindowAtEnd() {
        super.appWindowAsTopWindowAtEnd()
    }
}
