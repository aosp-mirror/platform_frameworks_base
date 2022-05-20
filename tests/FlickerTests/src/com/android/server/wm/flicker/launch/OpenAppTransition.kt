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
import android.platform.test.annotations.Presubmit
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.Test

/**
 * Base class for app launch tests
 */
abstract class OpenAppTransition(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected open val testApp: StandardAppHelper = SimpleAppHelper(instrumentation)

    /**
     * Defines the transition used to run the test
     */
    protected open val transition: FlickerBuilder.() -> Unit = {
        setup {
            test {
                device.wakeUpAndGoToHomeScreen()
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
    open fun navBarWindowIsVisible() {
        testSpec.navBarWindowIsVisible()
    }

    /**
     * Checks that the navigation bar layer is visible at the start and end of the trace
     */
    open fun navBarLayerIsVisible() {
        testSpec.navBarLayerIsVisible()
    }

    /**
     * Checks the position of the navigation bar at the start and end of the transition
     */
    @Presubmit
    @Test
    open fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    /**
     * Checks that the status bar window is visible during the whole transition
     */
    @Presubmit
    @Test
    open fun statusBarWindowIsVisible() {
        testSpec.statusBarWindowIsVisible()
    }

    /**
     * Checks that the status bar layer is visible at the start and end of the trace
     */
    @Presubmit
    @Test
    open fun statusBarLayerIsVisible() {
        testSpec.statusBarLayerIsVisible()
    }

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
     * Checks that the app layer doesn't exist or is invisible at the start of the transition, but
     * is created and/or becomes visible during the transition.
     */
    @Presubmit
    @Test
    open fun appLayerBecomesVisible() = appLayerBecomesVisible_coldStart()

    protected fun appLayerBecomesVisible_coldStart() {
        testSpec.assertLayers {
            this.notContains(testApp.component)
                    .then()
                    .isInvisible(testApp.component, isOptional = true)
                    .then()
                    .isVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isVisible(FlickerComponentName.SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isVisible(testApp.component)
        }
    }

    protected fun appLayerBecomesVisible_warmStart() {
        testSpec.assertLayers {
            this.isInvisible(testApp.component)
                    .then()
                    .isVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isVisible(FlickerComponentName.SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isVisible(testApp.component)
        }
    }

    /**
     * Checks that the app window doesn't exist at the start of the transition, that it is
     * created (invisible - optional) and becomes visible during the transition
     *
     * The `isAppWindowInvisible` step is optional because we log once per frame, upon logging,
     * the window may be visible or not depending on what was processed until that moment.
     */
    @Presubmit
    @Test
    open fun appWindowBecomesVisible() = appWindowBecomesVisible_coldStart()

    protected fun appWindowBecomesVisible_coldStart() {
        testSpec.assertWm {
            this.notContains(testApp.component)
                    .then()
                    .isAppWindowInvisible(testApp.component, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp.component)
        }
    }

    protected fun appWindowBecomesVisible_warmStart() {
        testSpec.assertWm {
            this.isAppWindowInvisible(testApp.component)
                    .then()
                    .isAppWindowVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowVisible(FlickerComponentName.SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isAppWindowVisible(testApp.component)
        }
    }

    /**
     * Checks that [testApp] window is not on top at the start of the transition, and then becomes
     * the top visible window until the end of the transition.
     */
    @Presubmit
    @Test
    open fun appWindowBecomesTopWindow() {
        testSpec.assertWm {
            this.isAppWindowNotOnTop(testApp.component)
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isAppWindowOnTop(testApp.component)
        }
    }
}
