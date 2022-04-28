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

package com.android.server.wm.flicker.ime

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import android.view.Display
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.ConditionList
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.common.WindowManagerConditionsFactory
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window opening transitions.
 * To run this test: `atest FlickerTests:ReOpenImeWindowTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
open class ReOpenImeWindowTest(private val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    private val waitConditionSetup = ConditionList(listOf(
        WindowManagerConditionsFactory.isAppTransitionIdle(Display.DEFAULT_DISPLAY),
        WindowManagerConditionsFactory.hasLayersAnimating().negate(),
        WindowManagerConditionsFactory.isHomeActivityVisible()
    ))

    @Before
    open fun before() {
        assumeFalse(isShellTransitionsEnabled)
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                test {
                    testApp.launchViaIntent(wmHelper)
                    testApp.openIME(device, wmHelper)
                }
                eachRun {
                    device.pressRecentApps()
                    wmHelper.waitFor(waitConditionSetup)
                    this.setRotation(testSpec.startRotation)
                }
            }
            transitions {
                device.reopenAppFromOverview(wmHelper)
                wmHelper.waitImeShown()
            }
            teardown {
                test {
                    testApp.exit()
                }
            }
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
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        val component = FlickerComponentName("", "RecentTaskScreenshotSurface")
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry(
                    ignoreWindows = listOf(FlickerComponentName.SPLASH_SCREEN,
                        FlickerComponentName.SNAPSHOT,
                        component)
            )
        }
    }

    @Presubmit
    @Test
    fun launcherWindowBecomesInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(LAUNCHER_COMPONENT)
                    .then()
                    .isAppWindowInvisible(LAUNCHER_COMPONENT)
        }
    }

    @Presubmit
    @Test
    fun imeWindowIsAlwaysVisible() = testSpec.imeWindowIsAlwaysVisible(!isShellTransitionsEnabled)

    @Presubmit
    @Test
    fun imeAppWindowVisibilityLegacy() {
        assumeFalse(isShellTransitionsEnabled)
        // the app starts visible in live tile, and stays visible for the duration of entering
        // and exiting overview. However, legacy transitions seem to have a bug which causes
        // everything to restart during the test, so expect the app to disappear and come back.
        // Since we log 1x per frame, sometimes the activity visibility and the app visibility
        // are updated together, sometimes not, thus ignore activity check at the start
        testSpec.assertWm {
            this.isAppWindowVisible(testApp.component)
                    .then()
                    .isAppWindowInvisible(testApp.component)
                    .then()
                    .isAppWindowVisible(testApp.component)
        }
    }

    @FlakyTest(bugId = 204570898)
    @Test
    fun imeAppWindowVisibility() {
        assumeTrue(isShellTransitionsEnabled)
        // the app starts visible in live tile, and stays visible for the duration of entering
        // and exiting overview. Since we log 1x per frame, sometimes the activity visibility
        // and the app visibility are updated together, sometimes not, thus ignore activity
        // check at the start
        testSpec.assertWm {
            this.isAppWindowVisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    // During testing the launcher is always in portrait mode
    fun entireScreenCovered() = testSpec.entireScreenCovered()

    @Presubmit
    @Test
    fun navBarLayerIsVisible() = testSpec.navBarLayerIsVisible()

    @Presubmit
    @Test
    fun statusBarLayerIsVisible() = testSpec.statusBarLayerIsVisible()

    @Presubmit
    @Test
    fun imeLayerIsBecomesVisibleLegacy() {
        assumeFalse(isShellTransitionsEnabled)
        testSpec.assertLayers {
            this.isVisible(FlickerComponentName.IME)
                    .then()
                    .isInvisible(FlickerComponentName.IME)
                    .then()
                    .isVisible(FlickerComponentName.IME)
        }
    }

    @FlakyTest(bugId = 204570898)
    @Test
    fun imeLayerIsBecomesVisible() {
        assumeTrue(isShellTransitionsEnabled)
        testSpec.assertLayers {
            this.isVisible(FlickerComponentName.IME)
        }
    }

    @Presubmit
    @Test
    fun appLayerReplacesLauncher() {
        testSpec.assertLayers {
            this.isVisible(LAUNCHER_COMPONENT)
                .then()
                .isVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(testApp.component)
        }
    }

    @Presubmit
    @Test
    fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    @FlakyTest(bugId = 206753786)
    @Test
    fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

    @Presubmit
    @Test
    fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        // depends on how much of the animation transactions are sent to SF at once
        // sometimes this layer appears for 2-3 frames, sometimes for only 1
        val recentTaskComponent = FlickerComponentName("", "RecentTaskScreenshotSurface")
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                    listOf(FlickerComponentName.SPLASH_SCREEN,
                        FlickerComponentName.SNAPSHOT, recentTaskComponent)
            )
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(
                    repetitions = 3,
                    supportedRotations = listOf(Surface.ROTATION_0)
                )
        }
    }
}
