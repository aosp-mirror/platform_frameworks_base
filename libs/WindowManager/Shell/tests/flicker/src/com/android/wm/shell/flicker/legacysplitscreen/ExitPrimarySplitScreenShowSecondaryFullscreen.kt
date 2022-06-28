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

package com.android.wm.shell.flicker.legacysplitscreen

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.wm.shell.flicker.dockedStackDividerNotExistsAtEnd
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test dock activity to primary split screen, and open secondary to side, exit primary split
 * and test secondary activity become full screen.
 * To run this test: `atest WMShellFlickerTests:ExitPrimarySplitScreenShowSecondaryFullscreen`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class ExitPrimarySplitScreenShowSecondaryFullscreen(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            teardown {
                eachRun {
                    secondaryApp.exit(wmHelper)
                }
            }
            transitions {
                splitScreenApp.launchViaIntent(wmHelper)
                secondaryApp.launchViaIntent(wmHelper)
                device.launchSplitScreen(wmHelper)
                device.reopenAppFromOverview(wmHelper)
                // TODO(b/175687842) Can not find Split screen divider, use exit() instead
                splitScreenApp.exit(wmHelper)
            }
        }

    override val ignoredWindows: List<FlickerComponentName>
        get() = listOf(LAUNCHER_COMPONENT, FlickerComponentName.SPLASH_SCREEN,
            splitScreenApp.component, secondaryApp.component,
            FlickerComponentName.SNAPSHOT)

    @Presubmit
    @Test
    fun dockedStackDividerNotExistsAtEnd() = testSpec.dockedStackDividerNotExistsAtEnd()

    @FlakyTest
    @Test
    fun layerBecomesInvisible() {
        testSpec.assertLayers {
            this.isVisible(splitScreenApp.component)
                    .then()
                    .isInvisible(splitScreenApp.component)
        }
    }

    @FlakyTest
    @Test
    fun appWindowBecomesInVisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(splitScreenApp.component)
                    .then()
                    .isAppWindowInvisible(splitScreenApp.component)
        }
    }

    @Presubmit
    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0) // bugId = 179116910
            )
        }
    }
}
