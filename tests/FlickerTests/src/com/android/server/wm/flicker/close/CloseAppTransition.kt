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

package com.android.server.wm.flicker.close

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.flicker.replacesLayer
import org.junit.Test

/**
 * Base test class for transitions that close an app back to the launcher screen
 */
abstract class CloseAppTransition(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected open val testApp: StandardAppHelper = SimpleAppHelper(instrumentation)

    /**
     * Specification of the test transition to execute
     */
    protected open val transition: FlickerBuilder.() -> Unit = {
        setup {
            eachRun {
                testApp.launchViaIntent(wmHelper)
                this.setRotation(testSpec.startRotation)
            }
        }
        teardown {
            test {
                testApp.exit(wmHelper)
            }
        }
    }

    /**
     * Entry point for the test runner. It will use this method to initialize and cache
     * flicker executions
     */
    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition()
        }
    }

    /**
     * Checks that the navigation bar window is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun navBarWindowIsVisible() {
        testSpec.navBarWindowIsVisible()
    }

    /**
     * Checks that the status bar window is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun statusBarWindowIsVisible() {
        testSpec.statusBarWindowIsVisible()
    }

    /**
     * Checks that the navigation bar layer is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun navBarLayerIsVisible() {
        testSpec.navBarLayerIsVisible()
    }

    /**
     * Checks that the status bar layer is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun statusBarLayerIsVisible() {
        testSpec.statusBarLayerIsVisible()
    }

    /**
     * Checks the position of the navigation bar at the start and end of the transition
     */
    @Presubmit
    @Test
    open fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    /**
     * Checks the position of the status bar at the start and end of the transition
     */
    @Presubmit
    @Test
    open fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

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
     * Checks that all parts of the screen are covered during the transition
     */
    @Presubmit
    @Test
    open fun entireScreenCovered() = testSpec.entireScreenCovered()

    /**
     * Checks that [testApp] is the top visible app window at the start of the transition and
     * that it is replaced by [LAUNCHER_COMPONENT] during the transition
     */
    @Presubmit
    @Test
    open fun launcherReplacesAppWindowAsTopWindow() {
        testSpec.assertWm {
            this.isAppWindowOnTop(testApp.component)
                    .then()
                    .isAppWindowOnTop(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that [LAUNCHER_COMPONENT] is invisible at the start of the transition and that
     * it becomes visible during the transition
     */
    @Presubmit
    @Test
    open fun launcherWindowBecomesVisible() {
        testSpec.assertWm {
            this.isAppWindowNotOnTop(LAUNCHER_COMPONENT)
                    .then()
                    .isAppWindowOnTop(LAUNCHER_COMPONENT)
        }
    }

    /**
     * Checks that [LAUNCHER_COMPONENT] layer becomes visible when [testApp] becomes invisible
     */
    @Presubmit
    @Test
    open fun launcherLayerReplacesApp() {
        testSpec.replacesLayer(testApp.component, LAUNCHER_COMPONENT)
    }
}
