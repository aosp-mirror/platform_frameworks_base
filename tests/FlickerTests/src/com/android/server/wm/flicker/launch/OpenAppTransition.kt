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

import android.platform.test.annotations.Presubmit
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.traces.common.ComponentNameMatcher
import org.junit.Test

/**
 * Base class for app launch tests
 */
abstract class OpenAppTransition(testSpec: FlickerTestParameter) : BaseTest(testSpec) {
    protected open val testApp: StandardAppHelper = SimpleAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            test {
                tapl.setExpectedRotation(testSpec.startRotation)
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
     * Checks that the [testApp] layer doesn't exist or is invisible at the start of the
     * transition, but is created and/or becomes visible during the transition.
     */
    @Presubmit
    @Test
    open fun appLayerBecomesVisible() {
        appLayerBecomesVisible_coldStart()
    }

    protected fun appLayerBecomesVisible_coldStart() {
        testSpec.assertLayers {
            this.notContains(testApp)
                .then()
                .isInvisible(testApp, isOptional = true)
                .then()
                .isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(ComponentNameMatcher.SPLASH_SCREEN, isOptional = true)
                .then()
                .isVisible(testApp)
        }
    }

    protected fun appLayerBecomesVisible_warmStart() {
        testSpec.assertLayers {
            this.isInvisible(testApp)
                .then()
                .isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(ComponentNameMatcher.SPLASH_SCREEN, isOptional = true)
                .then()
                .isVisible(testApp)
        }
    }

    /**
     * Checks that the [testApp] window doesn't exist at the start of the transition, that it is
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
            this.notContains(testApp)
                .then()
                .isAppWindowInvisible(testApp, isOptional = true)
                .then()
                .isAppWindowVisible(testApp)
        }
    }

    protected fun appWindowBecomesVisible_warmStart() {
        testSpec.assertWm {
            this.isAppWindowInvisible(testApp)
                .then()
                .isAppWindowVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isAppWindowVisible(ComponentNameMatcher.SPLASH_SCREEN, isOptional = true)
                .then()
                .isAppWindowVisible(testApp)
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
            this.isAppWindowNotOnTop(testApp)
                .then()
                .isAppWindowOnTop(
                    testApp
                        .or(ComponentNameMatcher.SNAPSHOT)
                        .or(ComponentNameMatcher.SPLASH_SCREEN)
                )
        }
    }

    /**
     * Checks that [testApp] window is not on top at the start of the transition, and then becomes
     * the top visible window until the end of the transition.
     */
    @Presubmit
    @Test
    open fun appWindowIsTopWindowAtEnd() {
        testSpec.assertWmEnd {
            this.isAppWindowOnTop(testApp)
        }
    }
}
