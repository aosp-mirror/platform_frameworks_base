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
import android.tools.Rotation
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.traces.component.ComponentNameMatcher
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.ImeShownOnAppStartHelper
import com.android.server.wm.flicker.helpers.setRotation
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test IME window opening transitions. To run this test: `atest FlickerTests:ReOpenImeWindowTest`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ShowImeOnAppStartWhenLaunchingAppFromOverviewTest(flicker: LegacyFlickerTest) :
    BaseTest(flicker) {
    private val testApp = ImeShownOnAppStartHelper(instrumentation, flicker.scenario.startRotation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.expectedRotationCheckEnabled = false
            tapl.workspace.switchToOverview().dismissAllTasks()
            testApp.launchViaIntent(wmHelper)
            testApp.openIME(wmHelper)
            this.setRotation(flicker.scenario.startRotation)
            device.pressRecentApps()
            wmHelper.StateSyncBuilder().withRecentsActivityVisible().waitForAndVerify()
        }
        transitions {
            tapl.overview.currentTask.open()
            wmHelper.StateSyncBuilder().withFullScreenApp(testApp).withImeShown().waitForAndVerify()
        }
        teardown { testApp.exit(wmHelper) }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        // depends on how much of the animation transactions are sent to SF at once
        // sometimes this layer appears for 2-3 frames, sometimes for only 1
        val recentTaskComponent = ComponentNameMatcher("", "RecentTaskScreenshotSurface")
        flicker.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                listOf(
                    ComponentNameMatcher.SPLASH_SCREEN,
                    ComponentNameMatcher.SNAPSHOT,
                    recentTaskComponent
                )
            )
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        val component = ComponentNameMatcher("", "RecentTaskScreenshotSurface")
        flicker.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry(
                ignoreWindows =
                    listOf(
                        ComponentNameMatcher.SPLASH_SCREEN,
                        ComponentNameMatcher.SNAPSHOT,
                        component
                    )
            )
        }
    }

    @Presubmit
    @Test
    fun launcherWindowBecomesInvisible() {
        flicker.assertWm {
            this.isAppWindowVisible(ComponentNameMatcher.LAUNCHER)
                .then()
                .isAppWindowInvisible(ComponentNameMatcher.LAUNCHER)
        }
    }

    @Presubmit @Test fun imeWindowIsAlwaysVisible() = flicker.imeWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun imeAppWindowIsAlwaysVisible() {
        // the app starts visible in live tile, and stays visible for the duration of entering
        // and exiting overview. Since we log 1x per frame, sometimes the activity visibility
        // and the app visibility are updated together, sometimes not, thus ignore activity
        // check at the start
        flicker.assertWm { this.isAppWindowVisible(testApp) }
    }

    @Presubmit
    @Test
    fun imeLayerBecomesVisible() {
        flicker.assertLayers { this.isVisible(ComponentNameMatcher.IME) }
    }

    @Presubmit
    @Test
    fun appLayerReplacesLauncher() {
        flicker.assertLayers {
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
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
    }
}
