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

import android.graphics.Region
import android.util.Rational
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.uiautomator.By
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.ImeAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.resizeSplitScreen
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.wm.shell.flicker.DOCKED_STACK_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.helpers.SimpleAppHelper
import com.android.wm.shell.flicker.testapp.Components
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test split screen resizing window transitions.
 * To run this test: `atest WMShellFlickerTests:ResizeLegacySplitScreen`
 *
 * Currently it runs only in 0 degrees because of b/156100803
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 159096424)
@Group2
class ResizeLegacySplitScreen(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    private val testAppTop = SimpleAppHelper(instrumentation)
    private val testAppBottom = ImeAppHelper(instrumentation)

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()
                    this.setRotation(configuration.startRotation)
                    this.launcherStrategy.clearRecentAppsFromOverview()
                    testAppBottom.launchViaIntent(wmHelper)
                    device.pressHome()
                    testAppTop.launchViaIntent(wmHelper)
                    device.waitForIdle()
                    device.launchSplitScreen(wmHelper)
                    val snapshot =
                        device.findObject(By.res(device.launcherPackageName, "snapshot"))
                    snapshot.click()
                    testAppBottom.openIME(device)
                    device.pressBack()
                    device.resizeSplitScreen(startRatio)
                }
            }
            teardown {
                eachRun {
                    testAppTop.exit(wmHelper)
                    testAppBottom.exit(wmHelper)
                }
            }
            transitions {
                device.resizeSplitScreen(stopRatio)
            }
        }

    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    @FlakyTest(bugId = 156223549)
    @Test
    fun topAppWindowIsAlwaysVisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(Components.SimpleActivity.COMPONENT.toFlickerComponent())
        }
    }

    @FlakyTest(bugId = 156223549)
    @Test
    fun bottomAppWindowIsAlwaysVisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(Components.ImeActivity.COMPONENT.toFlickerComponent())
        }
    }

    @Test
    fun navBarLayerIsVisible() = testSpec.navBarLayerIsVisible()

    @Test
    fun statusBarLayerIsVisible() = testSpec.statusBarLayerIsVisible()

    @Test
    fun entireScreenCovered() = testSpec.entireScreenCovered()

    @Test
    fun navBarLayerRotatesAndScales() =
        testSpec.navBarLayerRotatesAndScales(testSpec.config.endRotation)

    @Test
    fun statusBarLayerRotatesScales() =
        testSpec.statusBarLayerRotatesScales(testSpec.config.endRotation)

    @Test
    fun topAppLayerIsAlwaysVisible() {
        testSpec.assertLayers {
            this.isVisible(Components.SimpleActivity.COMPONENT.toFlickerComponent())
        }
    }

    @Test
    fun bottomAppLayerIsAlwaysVisible() {
        testSpec.assertLayers {
            this.isVisible(Components.ImeActivity.COMPONENT.toFlickerComponent())
        }
    }

    @Test
    fun dividerLayerIsAlwaysVisible() {
        testSpec.assertLayers {
            this.isVisible(DOCKED_STACK_DIVIDER_COMPONENT)
        }
    }

    @FlakyTest
    @Test
    fun appsStartingBounds() {
        testSpec.assertLayersStart {
            val displayBounds = WindowUtils.displayBounds
            val dividerBounds =
                layer(DOCKED_STACK_DIVIDER_COMPONENT).visibleRegion.region.bounds

            val topAppBounds = Region(0, 0, dividerBounds.right,
                dividerBounds.top + WindowUtils.dockedStackDividerInset)
            val bottomAppBounds = Region(0,
                dividerBounds.bottom - WindowUtils.dockedStackDividerInset,
                displayBounds.right,
                displayBounds.bottom - WindowUtils.navigationBarHeight)
            visibleRegion(Components.SimpleActivity.COMPONENT.toFlickerComponent())
                .coversExactly(topAppBounds)
            visibleRegion(Components.ImeActivity.COMPONENT.toFlickerComponent())
                .coversExactly(bottomAppBounds)
        }
    }

    @FlakyTest
    @Test
    fun appsEndingBounds() {
        testSpec.assertLayersStart {
            val displayBounds = WindowUtils.displayBounds
            val dividerBounds =
                layer(DOCKED_STACK_DIVIDER_COMPONENT).visibleRegion.region.bounds

            val topAppBounds = Region(0, 0, dividerBounds.right,
                dividerBounds.top + WindowUtils.dockedStackDividerInset)
            val bottomAppBounds = Region(0,
                dividerBounds.bottom - WindowUtils.dockedStackDividerInset,
                displayBounds.right,
                displayBounds.bottom - WindowUtils.navigationBarHeight)

            visibleRegion(Components.SimpleActivity.COMPONENT.toFlickerComponent())
                .coversExactly(topAppBounds)
            visibleRegion(Components.ImeActivity.COMPONENT.toFlickerComponent())
                .coversExactly(bottomAppBounds)
        }
    }

    @Test
    fun focusDoesNotChange() {
        testSpec.assertEventLog {
            focusDoesNotChange()
        }
    }

    companion object {
        private val startRatio = Rational(1, 3)
        private val stopRatio = Rational(2, 3)

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0))
                .map {
                    val description = (startRatio.toString().replace("/", "-") + "_to_" +
                        stopRatio.toString().replace("/", "-"))
                    val newName = "${FlickerTestParameter.defaultName(it.config)}_$description"
                    FlickerTestParameter(it.config, name = newName)
                }
        }
    }
}
