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

package com.android.server.wm.flicker.launch

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.ShowWhenLockedAppHelper
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerPositionEnd
import com.android.server.wm.flicker.statusBarLayerPositionEnd
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from a notification from the lock screen when there is an app
 * overlaid on the lock screen.
 *
 * To run this test: `atest FlickerTests:OpenAppFromLockNotificationWithLockOverlayApp`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
@Postsubmit
class OpenAppFromLockNotificationWithLockOverlayApp(testSpec: FlickerTestParameter) :
    OpenAppFromLockNotificationCold(testSpec) {
    private val showWhenLockedApp: ShowWhenLockedAppHelper =
            ShowWhenLockedAppHelper(instrumentation)

    // Although we are technically still locked here, the overlay app means we should open the
    // notification shade as if we were unlocked.
    override val openingNotificationsFromLockScreen = false

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)

            setup {
                eachRun {
                    device.wakeUpAndGoToHomeScreen()

                    // Launch an activity that is shown when the device is locked
                    showWhenLockedApp.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(showWhenLockedApp.component)

                    device.sleep()
                    wmHelper.waitFor("noAppWindowsOnTop") {
                        it.wmState.topVisibleAppWindow.isEmpty()
                    }
                }
            }

            teardown {
                test {
                    showWhenLockedApp.exit(wmHelper)
                }
            }
        }

    @Test
    @Postsubmit
    fun showWhenLockedAppWindowBecomesVisible() {
        testSpec.assertWm {
            this.hasNoVisibleAppWindow()
                    .then()
                    .isAppWindowOnTop(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isAppWindowOnTop(showWhenLockedApp.component)
        }
    }

    @Test
    @Postsubmit
    fun showWhenLockedAppLayerBecomesVisible() {
        testSpec.assertLayers {
            this.isInvisible(showWhenLockedApp.component)
                    .then()
                    .isVisible(FlickerComponentName.SNAPSHOT, isOptional = true)
                    .then()
                    .isVisible(showWhenLockedApp.component)
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 229735718)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    /**
     * Checks the position of the navigation bar at the start and end of the transition
     *
     * Differently from the normal usage of this assertion, check only the final state of the
     * transition because the display is off at the start and the NavBar is never visible
     */
    @Postsubmit
    @Test
    override fun navBarLayerRotatesAndScales() = testSpec.navBarLayerPositionEnd()

    /**
     * Checks the position of the status bar at the start and end of the transition
     *
     * Differently from the normal usage of this assertion, check only the final state of the
     * transition because the display is off at the start and the NavBar is never visible
     */
    @Postsubmit
    @Test
    override fun statusBarLayerRotatesScales() = testSpec.statusBarLayerPositionEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarWindowIsVisible() = super.navBarWindowIsVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appLayerBecomesVisible() = super.appLayerBecomesVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarWindowIsVisible() = super.statusBarWindowIsVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appWindowBecomesTopWindow() = super.appWindowBecomesTopWindow()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appWindowBecomesVisible() = super.appWindowBecomesVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerIsVisible() = super.statusBarLayerIsVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigNonRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(repetitions = 3)
        }
    }
}
