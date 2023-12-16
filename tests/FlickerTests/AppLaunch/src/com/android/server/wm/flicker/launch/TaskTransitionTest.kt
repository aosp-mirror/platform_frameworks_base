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

import android.app.Instrumentation
import android.app.WallpaperManager
import android.content.res.Resources
import android.platform.test.annotations.Presubmit
import android.tools.common.datatypes.Region
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.flicker.subject.layers.LayersTraceSubject.Companion.VISIBLE_FOR_MORE_THAN_ONE_ENTRY_IGNORE_LAYERS
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.component.ComponentNameMatcher.Companion.SPLASH_SCREEN
import android.tools.common.traces.component.ComponentNameMatcher.Companion.WALLPAPER_BBQ_WRAPPER
import android.tools.common.traces.component.ComponentSplashScreenMatcher
import android.tools.common.traces.component.IComponentMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import android.tools.device.helpers.WindowUtils
import android.tools.device.traces.parsers.toFlickerComponent
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.helpers.NewTasksAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test the back and forward transition between 2 activities.
 *
 * To run this test: `atest FlickerTests:TaskTransitionTest`
 *
 * Actions:
 * ```
 *     Launch the NewTaskLauncherApp [mTestApp]
 *     Open a new task (SimpleActivity) from the NewTaskLauncherApp [mTestApp]
 *     Go back to the NewTaskLauncherApp [mTestApp]
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TaskTransitionTest(flicker: LegacyFlickerTest) : BaseTest(flicker) {
    private val launchNewTaskApp = NewTasksAppHelper(instrumentation)
    private val simpleApp = SimpleAppHelper(instrumentation)
    private val wallpaper by lazy { getWallpaperPackage(instrumentation) }

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup { launchNewTaskApp.launchViaIntent(wmHelper) }
        teardown { launchNewTaskApp.exit(wmHelper) }
        transitions {
            launchNewTaskApp.openNewTask(device, wmHelper)
            tapl.pressBack()
            wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
        }
    }

    /**
     * Checks that the [wallpaper] window is never visible when performing task transitions. A solid
     * color background should be shown instead.
     */
    @FlakyTest(bugId = 253617416)
    @Test
    fun wallpaperWindowIsNeverVisible() {
        flicker.assertWm { this.isNonAppWindowInvisible(wallpaper) }
    }

    /**
     * Checks that the [wallpaper] layer is never visible when performing task transitions. A solid
     * color background should be shown instead.
     */
    @Presubmit
    @Test
    fun wallpaperLayerIsNeverVisible() {
        flicker.assertLayers {
            this.isInvisible(wallpaper)
            this.isInvisible(WALLPAPER_BBQ_WRAPPER)
        }
    }

    /**
     * Check that the [ComponentNameMatcher.LAUNCHER] window is never visible when performing task
     * transitions. A solid color background should be shown above it.
     */
    @Presubmit
    @Test
    fun launcherWindowIsNeverVisible() {
        flicker.assertWm { this.isAppWindowInvisible(ComponentNameMatcher.LAUNCHER) }
    }

    /**
     * Checks that the [ComponentNameMatcher.LAUNCHER] layer is never visible when performing task
     * transitions. A solid color background should be shown above it.
     */
    @Presubmit
    @Test
    fun launcherLayerIsNeverVisible() {
        flicker.assertLayers { this.isInvisible(ComponentNameMatcher.LAUNCHER) }
    }

    /** Checks that a color background is visible while the task transition is occurring. */
    @FlakyTest(bugId = 265007895)
    @Test
    fun transitionHasColorBackground() {
        val backgroundColorLayer = ComponentNameMatcher("", "animation-background")
        val displayBounds = WindowUtils.getDisplayBounds(flicker.scenario.startRotation)
        flicker.assertLayers {
            visibleRegionCovers(launchNewTaskApp.componentMatcher, displayBounds)
                .isInvisible(backgroundColorLayer)
                .then()
                // Transitioning
                .isVisible(backgroundColorLayer)
                .then()
                // Fully transitioned to simple SIMPLE_ACTIVITY
                .visibleRegionCovers(
                    ComponentSplashScreenMatcher(simpleApp.componentMatcher),
                    displayBounds,
                    isOptional = true
                )
                .visibleRegionCovers(simpleApp.componentMatcher, displayBounds)
                .isInvisible(backgroundColorLayer)
                .hasNoColor(backgroundColorLayer)
                .then()
                // Transitioning back
                .isVisible(backgroundColorLayer)
                .hasColor(backgroundColorLayer)
                .then()
                // Fully transitioned back to LAUNCH_NEW_TASK_ACTIVITY
                .visibleRegionCovers(
                    ComponentSplashScreenMatcher(launchNewTaskApp.componentMatcher),
                    displayBounds,
                    isOptional = true
                )
                .visibleRegionCovers(launchNewTaskApp.componentMatcher, displayBounds)
                .isInvisible(backgroundColorLayer)
                .hasNoColor(backgroundColorLayer)
        }
    }

    /**
     * Checks that we start with the LaunchNewTask activity on top and then open up the
     * SimpleActivity and then go back to the LaunchNewTask activity.
     */
    @Presubmit
    @Test
    fun newTaskOpensOnTopAndThenCloses() {
        flicker.assertWm {
            this.isAppWindowOnTop(launchNewTaskApp.componentMatcher)
                .then()
                .isAppWindowOnTop(SPLASH_SCREEN, isOptional = true)
                .then()
                .isAppWindowOnTop(simpleApp.componentMatcher)
                .then()
                .isAppWindowOnTop(SPLASH_SCREEN, isOptional = true)
                .then()
                .isAppWindowOnTop(launchNewTaskApp.componentMatcher)
        }
    }

    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        flicker.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                VISIBLE_FOR_MORE_THAN_ONE_ENTRY_IGNORE_LAYERS + listOf(launchNewTaskApp)
            )
        }
    }

    companion object {
        private fun getWallpaperPackage(instrumentation: Instrumentation): IComponentMatcher {
            val wallpaperManager = WallpaperManager.getInstance(instrumentation.targetContext)

            return wallpaperManager.wallpaperInfo?.component?.toFlickerComponent()
                ?: getStaticWallpaperPackage(instrumentation)
        }

        private fun getStaticWallpaperPackage(instrumentation: Instrumentation): IComponentMatcher {
            val resourceId =
                Resources.getSystem()
                    .getIdentifier("image_wallpaper_component", "string", "android")
            // frameworks/base/core/res/res/values/config.xml returns package plus class name,
            // but wallpaper layer has only class name
            val rawComponentMatcher =
                ComponentNameMatcher.unflattenFromString(
                    instrumentation.targetContext.resources.getString(resourceId)
                )

            return ComponentNameMatcher(rawComponentMatcher.className)
        }

        private fun LayersTraceSubject.visibleRegionCovers(
            component: IComponentMatcher,
            expectedArea: Region,
            isOptional: Boolean = true
        ): LayersTraceSubject = invoke("$component coversExactly $expectedArea", isOptional) {
            it.visibleRegion(component).coversExactly(expectedArea)
        }

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
