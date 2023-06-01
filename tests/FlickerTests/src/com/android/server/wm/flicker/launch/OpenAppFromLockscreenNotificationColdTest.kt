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

import android.platform.test.annotations.Postsubmit
import android.platform.test.rule.SettingOverrideRule
import android.provider.Settings
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from a notification from the lock screen.
 *
 * This test assumes the device doesn't have AOD enabled
 *
 * To run this test: `atest FlickerTests:OpenAppFromLockNotificationCold`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Postsubmit
open class OpenAppFromLockscreenNotificationColdTest(flicker: FlickerTest) :
    OpenAppFromNotificationColdTest(flicker) {

    override val openingNotificationsFromLockScreen = true

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            // Needs to run at start of transition,
            // so before the transition defined in super.transition
            transitions { device.wakeUp() }

            super.transition(this)

            // Needs to run at the end of the setup, so after the setup defined in super.transition
            setup {
                device.sleep()
                wmHelper.StateSyncBuilder().withoutTopVisibleAppWindows().waitForAndVerify()
            }
        }

    /** {@inheritDoc} */
    @Test @Ignore("Display is off at the start") override fun navBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Display is off at the start")
    override fun statusBarLayerPositionAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Display is off at the start")
    override fun taskBarLayerIsVisibleAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test @Ignore("Display is off at the start") override fun taskBarWindowIsAlwaysVisible() {}

    /** {@inheritDoc} */
    @Test
    @Ignore("Display is off at the start")
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts locked and app is full screen at the end")
    override fun navBarWindowIsVisibleAtStartAndEnd() = super.navBarWindowIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts locked and app is full screen at the end")
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts locked and app is full screen at the end")
    override fun navBarWindowIsAlwaysVisible() {}

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests()
        }

        /**
         * Ensures that posted notifications will be visible on the lockscreen and not suppressed
         * due to being marked as seen.
         */
        @ClassRule
        @JvmField
        val disableUnseenNotifFilterRule =
            SettingOverrideRule(
                Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
                /* value = */ "0",
            )
    }
}
