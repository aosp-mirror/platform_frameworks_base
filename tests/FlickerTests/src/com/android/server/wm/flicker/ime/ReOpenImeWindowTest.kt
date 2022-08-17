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

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ImeAppAutoFocusHelper
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.Assume
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
open class ReOpenImeWindowTest(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    private val testApp = ImeAppAutoFocusHelper(instrumentation, testSpec.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            test {
                testApp.launchViaIntent(wmHelper)
                testApp.openIME(wmHelper)
            }
            eachRun {
                this.setRotation(testSpec.startRotation)
                device.pressRecentApps()
                wmHelper.StateSyncBuilder()
                    .withRecentsActivityVisible()
                    .waitForAndVerify()
            }
        }
        transitions {
            device.reopenAppFromOverview(wmHelper)
            wmHelper.StateSyncBuilder()
                .withFullScreenApp(testApp)
                .withImeShown()
                .waitForAndVerify()
        }
        teardown {
            test {
                testApp.exit(wmHelper)
            }
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        // depends on how much of the animation transactions are sent to SF at once
        // sometimes this layer appears for 2-3 frames, sometimes for only 1
        val recentTaskComponent = ComponentNameMatcher("", "RecentTaskScreenshotSurface")
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                listOf(ComponentNameMatcher.SPLASH_SCREEN,
                    ComponentNameMatcher.SNAPSHOT, recentTaskComponent)
            )
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        val component = ComponentNameMatcher("", "RecentTaskScreenshotSurface")
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry(
                    ignoreWindows = listOf(ComponentNameMatcher.SPLASH_SCREEN,
                        ComponentNameMatcher.SNAPSHOT,
                        component)
            )
        }
    }

    @Presubmit
    @Test
    fun launcherWindowBecomesInvisible() {
        testSpec.assertWm {
            this.isAppWindowVisible(ComponentNameMatcher.LAUNCHER)
                    .then()
                    .isAppWindowInvisible(ComponentNameMatcher.LAUNCHER)
        }
    }

    @Presubmit
    @Test
    fun imeWindowIsAlwaysVisible() = testSpec.imeWindowIsAlwaysVisible(!isShellTransitionsEnabled)

    @Presubmit
    @Test
    fun imeAppWindowVisibilityLegacy() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        // the app starts visible in live tile, and stays visible for the duration of entering
        // and exiting overview. However, legacy transitions seem to have a bug which causes
        // everything to restart during the test, so expect the app to disappear and come back.
        // Since we log 1x per frame, sometimes the activity visibility and the app visibility
        // are updated together, sometimes not, thus ignore activity check at the start
        testSpec.assertWm {
            this.isAppWindowVisible(testApp)
                    .then()
                    .isAppWindowInvisible(testApp)
                    .then()
                    .isAppWindowVisible(testApp)
        }
    }

    @Presubmit
    @Test
    fun imeAppWindowIsAlwaysVisibleShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        // the app starts visible in live tile, and stays visible for the duration of entering
        // and exiting overview. Since we log 1x per frame, sometimes the activity visibility
        // and the app visibility are updated together, sometimes not, thus ignore activity
        // check at the start
        testSpec.assertWm {
            this.isAppWindowVisible(testApp)
        }
    }

    @Presubmit
    @Test
    fun imeLayerIsBecomesVisibleLegacy() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        testSpec.assertLayers {
            this.isVisible(ComponentNameMatcher.IME)
                    .then()
                    .isInvisible(ComponentNameMatcher.IME)
                    .then()
                    .isVisible(ComponentNameMatcher.IME)
        }
    }

    @Presubmit
    @Test
    fun imeLayerBecomesVisibleShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        testSpec.assertLayers {
            this.isVisible(ComponentNameMatcher.IME)
        }
    }

    @Presubmit
    @Test
    fun appLayerReplacesLauncher() {
        testSpec.assertLayers {
            this.isVisible(ComponentNameMatcher.LAUNCHER)
                .then()
                .isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(testApp)
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
