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

package com.android.server.wm.flicker.launch

import android.app.Instrumentation
import android.app.WallpaperManager
import android.platform.test.annotations.Postsubmit
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.NewTasksAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.flicker.testapp.ActivityOptions.LAUNCH_NEW_TASK_ACTIVITY_COMPONENT_NAME
import com.android.server.wm.flicker.testapp.ActivityOptions.SIMPLE_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.common.FlickerComponentName.Companion.SPLASH_SCREEN
import com.android.server.wm.traces.common.FlickerComponentName.Companion.WALLPAPER_BBQ_WRAPPER
import com.android.server.wm.traces.parser.toFlickerComponent
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test the back and forward transition between 2 activities.
 *
 * To run this test: `atest FlickerTests:ActivitiesTransitionTest`
 *
 * Actions:
 *     Launch the NewTaskLauncherApp [mTestApp]
 *     Open a new task (SimpleActivity) from the NewTaskLauncherApp [mTestApp]
 *     Go back to the NewTaskLauncherApp [mTestApp]
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
class TaskTransitionTest(val testSpec: FlickerTestParameter) {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val mTestApp: NewTasksAppHelper = NewTasksAppHelper(instrumentation)
    private val mWallpaper by lazy {
        getWallpaperPackage(InstrumentationRegistry.getInstrumentation())
            ?: error("Unable to obtain wallpaper")
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                eachRun {
                    mTestApp.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(mTestApp.component)
                }
            }
            teardown {
                test {
                    mTestApp.exit()
                }
            }
            transitions {
                mTestApp.openNewTask(device, wmHelper)
                device.pressBack()
                wmHelper.waitForAppTransitionIdle()
                wmHelper.waitForFullScreenApp(mTestApp.component)
            }
        }
    }

    /**
     * Checks that the wallpaper window is never visible when performing task transitions.
     * A solid color background should be shown instead.
     */
    @Postsubmit
    @Test
    fun wallpaperWindowIsNeverVisible() {
        testSpec.assertWm {
            this.isNonAppWindowInvisible(mWallpaper)
        }
    }

    /**
     * Checks that the wallpaper layer is never visible when performing task transitions.
     * A solid color background should be shown instead.
     */
    @Postsubmit
    @Test
    fun wallpaperLayerIsNeverVisible() {
        testSpec.assertLayers {
            this.isInvisible(mWallpaper)
            this.isInvisible(WALLPAPER_BBQ_WRAPPER)
        }
    }

    /**
     * Check that the launcher window is never visible when performing task transitions.
     * A solid color background should be shown above it.
     */
    @Postsubmit
    @Test
    fun launcherWindowIsNeverVisible() {
        testSpec.assertWm {
            this.isAppWindowInvisible(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that the launcher layer is never visible when performing task transitions.
     * A solid color background should be shown above it.
     */
    @Postsubmit
    @Test
    fun launcherLayerIsNeverVisible() {
        testSpec.assertLayers {
            this.isInvisible(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that a color background is visible while the task transition is occurring.
     */
    @Postsubmit
    @Test
    fun colorLayerIsVisibleDuringTransition() {
        val bgColorLayer = FlickerComponentName("", "colorBackgroundLayer")
        val displayBounds = WindowUtils.getDisplayBounds(testSpec.startRotation)

        testSpec.assertLayers {
            this.invoke("LAUNCH_NEW_TASK_ACTIVITY coversExactly displayBounds") {
                    it.visibleRegion(LAUNCH_NEW_TASK_ACTIVITY).coversExactly(displayBounds)
                }
                .isInvisible(bgColorLayer)
                .then()
                // Transitioning
                .isVisible(bgColorLayer)
                .then()
                // Fully transitioned to simple SIMPLE_ACTIVITY
                .invoke("SIMPLE_ACTIVITY coversExactly displayBounds") {
                    it.visibleRegion(SIMPLE_ACTIVITY).coversExactly(displayBounds)
                }
                .isInvisible(bgColorLayer)
                .then()
                // Transitioning back
                .isVisible(bgColorLayer)
                .then()
                // Fully transitioned back to LAUNCH_NEW_TASK_ACTIVITY
                .invoke("LAUNCH_NEW_TASK_ACTIVITY coversExactly displayBounds") {
                    it.visibleRegion(LAUNCH_NEW_TASK_ACTIVITY).coversExactly(displayBounds)
                }
                .isInvisible(bgColorLayer)
        }
    }

    /**
     * Checks that we start with the LaunchNewTask activity on top and then open up
     * the SimpleActivity and then go back to the LaunchNewTask activity.
     */
    @Postsubmit
    @Test
    fun newTaskOpensOnTopAndThenCloses() {
        testSpec.assertWm {
            this.isAppWindowOnTop(LAUNCH_NEW_TASK_ACTIVITY)
                    .then()
                    .isAppWindowOnTop(SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isAppWindowOnTop(SIMPLE_ACTIVITY)
                    .then()
                    .isAppWindowOnTop(SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isAppWindowOnTop(LAUNCH_NEW_TASK_ACTIVITY)
        }
    }

    /**
     * Checks that all parts of the screen are covered at the start and end of the transition
     */
    @Postsubmit
    @Test
    fun entireScreenCovered() = testSpec.entireScreenCovered()

    /**
     * Checks that the navbar window is visible throughout the transition
     */
    @Postsubmit
    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    /**
     * Checks that the navbar layer is visible throughout the transition
     */
    @Postsubmit
    @Test
    fun navBarLayerIsVisible() = testSpec.navBarLayerIsVisible()

    /**
     * Checks that the status bar window is visible throughout the transition
     */
    @Postsubmit
    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    /**
     * Checks that the status bar layer is visible throughout the transition
     */
    @Postsubmit
    @Test
    fun statusBarLayerIsVisible() = testSpec.statusBarLayerIsVisible()

    companion object {
        private val LAUNCH_NEW_TASK_ACTIVITY =
                LAUNCH_NEW_TASK_ACTIVITY_COMPONENT_NAME.toFlickerComponent()
        private val SIMPLE_ACTIVITY = SIMPLE_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME.toFlickerComponent()

        private fun getWallpaperPackage(instrumentation: Instrumentation): FlickerComponentName? {
            val wallpaperManager = WallpaperManager.getInstance(instrumentation.targetContext)

            return wallpaperManager.wallpaperInfo?.component?.toFlickerComponent()
        }

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(repetitions = 3)
        }
    }
}
