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

import android.os.SystemClock
import android.platform.test.annotations.Postsubmit
import android.tools.device.apphelpers.CameraAppHelper
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.flicker.rules.RemoveAllTasksButHomeRule
import android.tools.flicker.subject.layers.LayersTraceSubject
import android.tools.traces.component.ComponentNameMatcher
import android.view.KeyEvent
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.launch.common.OpenAppFromLauncherTransition
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching camera from launcher by double pressing power button
 *
 * To run this test: `atest FlickerTests:OpenCameraOnDoubleClickPowerButton`
 *
 * Actions:
 * ```
 *     Make sure no apps are running on the device
 *     Launch an app [testApp] and wait animation to complete
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
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenCameraFromHomeOnDoubleClickPowerButtonTest(flicker: LegacyFlickerTest) :
    OpenAppFromLauncherTransition(flicker) {
    private val cameraApp = CameraAppHelper(instrumentation)
    override val testApp: StandardAppHelper
        get() = cameraApp

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
                this.setRotation(flicker.scenario.startRotation)
            }
            transitions {
                device.pressKeyCode(KeyEvent.KEYCODE_POWER)
                SystemClock.sleep(100)
                device.pressKeyCode(KeyEvent.KEYCODE_POWER)
                wmHelper.StateSyncBuilder().withWindowSurfaceAppeared(cameraApp).waitForAndVerify()
            }
            teardown { RemoveAllTasksButHomeRule.removeAllTasksButHome() }
        }

    @Postsubmit @Test override fun appLayerBecomesVisible() = super.appLayerBecomesVisible()

    @Postsubmit @Test override fun appWindowAsTopWindowAtEnd() = super.appWindowAsTopWindowAtEnd()

    @Postsubmit @Test override fun appWindowBecomesTopWindow() = super.appWindowBecomesTopWindow()

    @Postsubmit @Test override fun appWindowBecomesVisible() = super.appWindowBecomesVisible()

    @Postsubmit @Test override fun appLayerReplacesLauncher() = super.appLayerReplacesLauncher()

    @Postsubmit @Test override fun appWindowIsTopWindowAtEnd() = super.appWindowIsTopWindowAtEnd()

    @Postsubmit
    @Test
    override fun appWindowReplacesLauncherAsTopWindow() =
        super.appWindowReplacesLauncherAsTopWindow()

    @Postsubmit @Test override fun focusChanges() = super.focusChanges()

    @Postsubmit @Test override fun entireScreenCovered() = super.entireScreenCovered()

    @Ignore("Not applicable to this CUJ. App is full screen at the end")
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    @Postsubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    @Postsubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    @Ignore("Status bar visibility depends on whether the permission dialog is displayed or not")
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    @Ignore("Status bar visibility depends on whether the permission dialog is displayed or not")
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() = super.statusBarLayerPositionAtStartAndEnd()

    @Ignore("Status bar visibility depends on whether the permission dialog is displayed or not")
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    @Ignore("Not applicable to this CUJ. App is full screen at the end")
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    @Ignore("Not applicable to this CUJ. App is full screen at the end")
    @Test
    override fun taskBarWindowIsAlwaysVisible() = super.taskBarWindowIsAlwaysVisible()

    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        flicker.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                LayersTraceSubject.VISIBLE_FOR_MORE_THAN_ONE_ENTRY_IGNORE_LAYERS +
                    listOf(CAMERA_BACKGROUND)
            )
        }
    }

    @Postsubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    @Ignore("Not applicable to this CUJ. App is full screen at the end")
    @Test
    override fun navBarWindowIsVisibleAtStartAndEnd() {
        super.navBarWindowIsVisibleAtStartAndEnd()
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

        private val CAMERA_BACKGROUND =
            ComponentNameMatcher(
                "Background for SurfaceView" +
                    "[com.google.android.GoogleCamera/" +
                    "com.google.android.apps.camera.legacy.app.activity.main.CameraActivity]"
            )
    }
}
