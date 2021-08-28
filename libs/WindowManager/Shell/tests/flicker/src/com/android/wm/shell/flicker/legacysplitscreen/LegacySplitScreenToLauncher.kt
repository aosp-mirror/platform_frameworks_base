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

package com.android.wm.shell.flicker.legacysplitscreen

import android.content.ComponentName
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.exitSplitScreen
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.openQuickStepAndClearRecentAppsFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.dockedStackDividerBecomesInvisible
import com.android.wm.shell.flicker.helpers.SimpleAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest WMShellFlickerTests:LegacySplitScreenToLauncher`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class LegacySplitScreenToLauncher(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    private val testApp = SimpleAppHelper(instrumentation)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    device.openQuickStepAndClearRecentAppsFromOverview(wmHelper)
                }
                eachRun {
                    testApp.launchViaIntent(wmHelper)
                    this.setRotation(configuration.endRotation)
                    device.launchSplitScreen(wmHelper)
                    device.waitForIdle()
                }
            }
            teardown {
                eachRun {
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                device.exitSplitScreen()
            }
        }

    override val ignoredWindows: List<ComponentName>
        get() = listOf(LAUNCHER_COMPONENT, WindowManagerStateHelper.SPLASH_SCREEN_COMPONENT,
            WindowManagerStateHelper.SNAPSHOT_COMPONENT)

    @Presubmit
    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    @Presubmit
    @Test
    fun navBarLayerIsVisible() = testSpec.navBarLayerIsVisible()

    @Presubmit
    @Test
    fun entireScreenCovered() = testSpec.entireScreenCovered()

    @Presubmit
    @Test
    fun navBarLayerRotatesAndScales() =
        testSpec.navBarLayerRotatesAndScales(testSpec.config.endRotation)

    @Presubmit
    @Test
    fun statusBarLayerRotatesScales() =
        testSpec.statusBarLayerRotatesScales(testSpec.config.endRotation)

    @Presubmit
    @Test
    fun statusBarLayerIsVisible() = testSpec.statusBarLayerIsVisible()

    @Postsubmit
    @Test
    fun dockedStackDividerBecomesInvisible() = testSpec.dockedStackDividerBecomesInvisible()

    @Postsubmit
    @Test
    fun layerBecomesInvisible() {
        testSpec.assertLayers {
            this.isVisible(testApp.component)
                    .then()
                    .isInvisible(testApp.component)
        }
    }

    @Postsubmit
    @Test
    fun focusDoesNotChange() {
        testSpec.assertEventLog {
            this.focusDoesNotChange()
        }
    }

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
            // b/161435597 causes the test not to work on 90 degrees
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0))
        }
    }
}
