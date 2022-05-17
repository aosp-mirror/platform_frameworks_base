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
import androidx.test.filters.FlakyTest
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.navBarLayerPositionEnd
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.Test

/**
 * Base class for app launch tests from lock screen
 */
abstract class OpenAppFromLockTransition(testSpec: FlickerTestParameter)
    : OpenAppTransition(testSpec) {

    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                eachRun {
                    device.sleep()
                    wmHelper.waitFor("noAppWindowsOnTop") {
                        it.wmState.topVisibleAppWindow.isEmpty()
                    }
                }
            }
            teardown {
                eachRun {
                    testApp.exit(wmHelper)
                }
            }
            transitions {
                testApp.launchViaIntent(wmHelper)
                wmHelper.waitForFullScreenApp(testApp.component)
            }
        }

    /**
     * Check that we go from no focus to focus on the [testApp]
     */
    @Presubmit
    @Test
    open fun focusChanges() {
        testSpec.assertEventLog {
            this.focusChanges("", testApp.`package`)
        }
    }

    /**
     * Checks that we start of with no top windows and then [testApp] becomes the first and only top
     * window of the transition, with snapshot or splash screen windows optionally showing first.
     */
    @FlakyTest(bugId = 203538234)
    @Test
    open fun appWindowBecomesFirstAndOnlyTopWindow() {
        testSpec.assertWm {
            this.hasNoVisibleAppWindow()
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SPLASH_SCREEN, isOptional = true)
                    .then()
                    .isAppWindowOnTop(testApp.component)
        }
    }

    /**
     * Checks that the screen is locked at the start of the transition ([colorFadComponent])
     * layer is visible
     */
    @Presubmit
    @Test
    fun screenLockedStart() {
        testSpec.assertLayersStart {
            isEmpty()
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 203538234)
    @Test
    override fun appWindowBecomesVisible() = super.appWindowBecomesVisible()

    /**
     * Checks the position of the navigation bar at the start and end of the transition
     *
     * Differently from the normal usage of this assertion, check only the final state of the
     * transition because the display is off at the start and the NavBar is never visible
     */
    @Presubmit
    @Test
    override fun navBarLayerRotatesAndScales() = testSpec.navBarLayerPositionEnd()

    /**
     * Checks that the status bar layer is visible at the end of the trace
     *
     * It is not possible to check at the start because the screen is off
     */
    @Presubmit
    @Test
    override fun statusBarLayerIsVisible() {
        testSpec.assertLayersEnd {
            this.isVisible(FlickerComponentName.STATUS_BAR)
        }
    }
}
