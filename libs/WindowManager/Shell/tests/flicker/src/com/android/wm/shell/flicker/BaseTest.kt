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

package com.android.wm.shell.flicker

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.junit.FlickerBuilderProvider
import com.android.server.wm.flicker.navBarLayerIsVisibleAtStartAndEnd
import com.android.server.wm.flicker.navBarLayerPositionAtStartAndEnd
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerIsVisibleAtStartAndEnd
import com.android.server.wm.flicker.statusBarLayerPositionAtStartAndEnd
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.taskBarLayerIsVisibleAtStartAndEnd
import com.android.server.wm.flicker.taskBarWindowIsAlwaysVisible
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.Assume
import org.junit.Test

/**
 * Base test class containing common assertions for [ComponentNameMatcher.NAV_BAR],
 * [ComponentNameMatcher.TASK_BAR], [ComponentNameMatcher.STATUS_BAR], and general assertions
 * (layers visible in consecutive states, entire screen covered, etc.)
 */
abstract class BaseTest
@JvmOverloads
constructor(
    protected val flicker: FlickerTest,
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation(),
    protected val tapl: LauncherInstrumentation = LauncherInstrumentation()
) {
    /** Specification of the test transition to execute */
    abstract val transition: FlickerBuilder.() -> Unit

    /**
     * Entry point for the test runner. It will use this method to initialize and cache flicker
     * executions
     */
    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup { flicker.scenario.setIsTablet(tapl.isTablet) }
            transition()
        }
    }

    /** Checks that all parts of the screen are covered during the transition */
    @Presubmit @Test open fun entireScreenCovered() = flicker.entireScreenCovered()

    /**
     * Checks that the [ComponentNameMatcher.NAV_BAR] layer is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun navBarLayerIsVisibleAtStartAndEnd() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.navBarLayerIsVisibleAtStartAndEnd()
    }

    /**
     * Checks the position of the [ComponentNameMatcher.NAV_BAR] at the start and end of the
     * transition
     */
    @Presubmit
    @Test
    open fun navBarLayerPositionAtStartAndEnd() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.navBarLayerPositionAtStartAndEnd()
    }

    /**
     * Checks that the [ComponentNameMatcher.NAV_BAR] window is visible during the whole transition
     *
     * Note: Phones only
     */
    @Presubmit
    @Test
    open fun navBarWindowIsAlwaysVisible() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.navBarWindowIsAlwaysVisible()
    }

    /**
     * Checks that the [ComponentNameMatcher.TASK_BAR] layer is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun taskBarLayerIsVisibleAtStartAndEnd() {
        Assume.assumeTrue(flicker.scenario.isTablet)
        flicker.taskBarLayerIsVisibleAtStartAndEnd()
    }

    /**
     * Checks that the [ComponentNameMatcher.TASK_BAR] window is visible during the whole transition
     *
     * Note: Large screen only
     */
    @Presubmit
    @Test
    open fun taskBarWindowIsAlwaysVisible() {
        Assume.assumeTrue(flicker.scenario.isTablet)
        flicker.taskBarWindowIsAlwaysVisible()
    }

    /**
     * Checks that the [ComponentNameMatcher.STATUS_BAR] layer is visible during the whole
     * transition
     */
    @Presubmit
    @Test
    open fun statusBarLayerIsVisibleAtStartAndEnd() = flicker.statusBarLayerIsVisibleAtStartAndEnd()

    /**
     * Checks the position of the [ComponentNameMatcher.STATUS_BAR] at the start and end of the
     * transition
     */
    @Presubmit
    @Test
    open fun statusBarLayerPositionAtStartAndEnd() = flicker.statusBarLayerPositionAtStartAndEnd()

    /**
     * Checks that the [ComponentNameMatcher.STATUS_BAR] window is visible during the whole
     * transition
     */
    @Presubmit
    @Test
    open fun statusBarWindowIsAlwaysVisible() = flicker.statusBarWindowIsAlwaysVisible()

    /**
     * Checks that all layers that are visible on the trace, are visible for at least 2 consecutive
     * entries.
     */
    @Presubmit
    @Test
    open fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        flicker.assertLayers { this.visibleLayersShownMoreThanOneConsecutiveEntry() }
    }

    /**
     * Checks that all windows that are visible on the trace, are visible for at least 2 consecutive
     * entries.
     */
    @Presubmit
    @Test
    open fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        flicker.assertWm { this.visibleWindowsShownMoreThanOneConsecutiveEntry() }
    }
}
