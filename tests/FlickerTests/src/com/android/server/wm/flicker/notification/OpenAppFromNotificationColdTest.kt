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

package com.android.server.wm.flicker.notification

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import com.android.server.wm.flicker.statusBarLayerPositionAtEnd
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from a notification.
 *
 * To run this test: `atest FlickerTests:OpenAppFromNotificationCold`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Postsubmit
open class OpenAppFromNotificationColdTest(flicker: LegacyFlickerTest) :
    OpenAppFromNotificationWarmTest(flicker) {
    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)

            setup {
                // Close the app that posted the notification to trigger a cold start next time
                // it is open - can't just kill it because that would remove the notification.
                tapl.setExpectedRotationCheckEnabled(false)
                tapl.goHome()
                tapl.workspace.switchToOverview()
                tapl.overview.dismissAllTasks()
            }
        }

    @Presubmit @Test override fun appWindowBecomesVisible() = appWindowBecomesVisible_coldStart()

    @Presubmit @Test override fun appLayerBecomesVisible() = appLayerBecomesVisible_coldStart()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts locked and app is full screen at the end")
    override fun navBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun statusBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /**
     * Checks the position of the [ComponentNameMatcher.STATUS_BAR] at the start and end of the
     * transition
     */
    @Presubmit @Test open fun statusBarLayerPositionAtEnd() = flicker.statusBarLayerPositionAtEnd()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts locked and app is full screen at the end")
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts locked and app is full screen at the end")
    override fun navBarWindowIsAlwaysVisible() {}

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
