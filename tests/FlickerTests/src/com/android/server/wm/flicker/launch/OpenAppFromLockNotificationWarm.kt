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
import com.android.server.wm.flicker.navBarLayerPositionEnd
import com.android.server.wm.flicker.statusBarLayerPositionEnd
import com.android.server.wm.traces.common.FlickerComponentName
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test warm launching an app from a notification from the lock screen.
 *
 * This test assumes the device doesn't have AOD enabled
 *
 * To run this test: `atest FlickerTests:OpenAppFromLockNotificationWarm`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
@Postsubmit
open class OpenAppFromLockNotificationWarm(testSpec: FlickerTestParameter) :
    OpenAppFromNotificationWarm(testSpec) {

    override val openingNotificationsFromLockScreen = true

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            // Needs to run at start of transition,
            // so before the transition defined in super.transition
            transitions {
                device.wakeUp()
            }

            super.transition(this)

            // Needs to run at the end of the setup, so after the setup defined in super.transition
            setup {
                eachRun {
                    device.sleep()
                    wmHelper.waitFor("noAppWindowsOnTop") {
                        it.wmState.topVisibleAppWindow.isEmpty()
                    }
                }
            }
        }

    /**
     * Checks that we start of with no top windows and then [testApp] becomes the first and
     * only top window of the transition, with snapshot or splash screen windows optionally showing
     * first.
     */
    @Test
    @Postsubmit
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
     * Checks that the screen is locked.
     */
    @Test
    @Postsubmit
    fun screenLockedStart() {
        testSpec.assertLayersStart {
            isEmpty()
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 229735718)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 203538234)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

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
