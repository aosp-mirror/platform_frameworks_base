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
import android.platform.test.rule.SettingOverrideRule
import android.provider.Settings
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.helpers.wakeUpAndGoToHomeScreen
import android.tools.traces.component.ComponentNameMatcher
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
 * To run this test: `atest FlickerTestsNotification:OpenAppFromLockscreenNotificationColdTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Postsubmit
open class OpenAppFromLockscreenNotificationColdTest(flicker: LegacyFlickerTest) :
    OpenAppFromNotificationColdTest(flicker) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            transitions {
                device.wakeUp()
                openAppFromLockNotification()
            }

            // Needs to run at the end of the setup, so after the setup defined in super.transition
            setup {
                device.wakeUpAndGoToHomeScreen()
                launchAppAndPostNotification()
                clearOverview()
                device.sleep()
                wmHelper.StateSyncBuilder().withoutTopVisibleAppWindows().waitForAndVerify()
            }

            teardown { testApp.exit(wmHelper) }
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

    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() {
        flicker.assertWm {
            this.visibleWindowsShownMoreThanOneConsecutiveEntry(
                listOf(
                    ComponentNameMatcher.SPLASH_SCREEN,
                    ComponentNameMatcher.SNAPSHOT,
                    ComponentNameMatcher.SECONDARY_HOME_HANDLE,
                    Consts.IMAGE_WALLPAPER
                )
            )
        }
    }

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
