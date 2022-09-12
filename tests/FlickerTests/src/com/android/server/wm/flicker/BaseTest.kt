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

package com.android.server.wm.flicker

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.Assume
import org.junit.Test

/**
 * Base test class containing common assertions for [ComponentMatcher.NAV_BAR],
 * [ComponentMatcher.TASK_BAR], [ComponentMatcher.STATUS_BAR], and general assertions
 * (layers visible in consecutive states, entire screen covered, etc.)
 */
abstract class BaseTest @JvmOverloads constructor(
    protected val testSpec: FlickerTestParameter,
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation(),
    protected val tapl: LauncherInstrumentation = LauncherInstrumentation()
) {
    init {
        testSpec.setIsTablet(
            WindowManagerStateHelper(
                instrumentation,
                clearCacheAfterParsing = false
            ).currentState.wmState.isTablet
        )
        tapl.setExpectedRotationCheckEnabled(true)
    }

    /**
     * Specification of the test transition to execute
     */
    abstract val transition: FlickerBuilder.() -> Unit

    /**
     * Entry point for the test runner. It will use this method to initialize and cache
     * flicker executions
     */
    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                testSpec.setIsTablet(wmHelper.currentState.wmState.isTablet)
            }
            transition()
        }
    }

    /**
     * Checks that all parts of the screen are covered during the transition
     */
    @Presubmit
    @Test
    open fun entireScreenCovered() = testSpec.entireScreenCovered()

    /**
     * Checks that the [ComponentMatcher.NAV_BAR] layer is visible during the whole transition
     *
     * Note: Phones only
     */
    @Presubmit
    @Test
    open fun navBarLayerIsVisibleAtStartAndEnd() {
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.navBarLayerIsVisibleAtStartAndEnd()
    }

    /**
     * Checks the position of the [ComponentMatcher.NAV_BAR] at the start and end of the transition
     *
     * Note: Phones only
     */
    @Presubmit
    @Test
    open fun navBarLayerPositionAtStartAndEnd() {
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.navBarLayerPositionAtStartAndEnd()
    }

    /**
     * Checks that the [ComponentMatcher.NAV_BAR] window is visible during the whole transition
     *
     * Note: Phones only
     */
    @Presubmit
    @Test
    open fun navBarWindowIsAlwaysVisible() {
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.navBarWindowIsAlwaysVisible()
    }

    /**
     * Checks that the [ComponentMatcher.TASK_BAR] window is visible at the start and end of the
     * transition
     *
     * Note: Large screen only
     */
    @Presubmit
    @Test
    open fun taskBarLayerIsVisibleAtStartAndEnd() {
        Assume.assumeTrue(testSpec.isTablet)
        testSpec.taskBarLayerIsVisibleAtStartAndEnd()
    }

    /**
     * Checks that the [ComponentMatcher.TASK_BAR] window is visible during the whole transition
     *
     * Note: Large screen only
     */
    @Presubmit
    @Test
    open fun taskBarWindowIsAlwaysVisible() {
        Assume.assumeTrue(testSpec.isTablet)
        testSpec.taskBarWindowIsAlwaysVisible()
    }

    /**
     * Checks that the [ComponentMatcher.STATUS_BAR] layer is visible at the start and end
     * of the transition
     */
    @Presubmit
    @Test
    open fun statusBarLayerIsVisibleAtStartAndEnd() =
        testSpec.statusBarLayerIsVisibleAtStartAndEnd()

    /**
     * Checks the position of the [ComponentMatcher.STATUS_BAR] at the start and end of the
     * transition
     */
    @Presubmit
    @Test
    open fun statusBarLayerPositionAtStartAndEnd() = testSpec.statusBarLayerPositionAtStartAndEnd()

    /**
     * Checks that the [ComponentMatcher.STATUS_BAR] window is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    /**
     * Checks that all layers that are visible on the trace, are visible for at least 2
     * consecutive entries.
     */
    @Presubmit
    @Test
    open fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        testSpec.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry()
        }
    }

    /**
     * Checks that all windows that are visible on the trace, are visible for at least 2
     * consecutive entries.
     */
    @Presubmit
    @Test
    open fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        testSpec.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry()
        }
    }
}
