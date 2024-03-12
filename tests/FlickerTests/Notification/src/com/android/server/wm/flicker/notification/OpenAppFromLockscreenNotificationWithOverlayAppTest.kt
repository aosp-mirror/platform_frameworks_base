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
import android.tools.device.helpers.wakeUpAndGoToHomeScreen
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.helpers.ShowWhenLockedAppHelper
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from a notification from the lock screen when there is an app overlaid
 * on the lock screen.
 *
 * This test assumes the device doesn't have AOD enabled
 *
 * To run this test: `atest FlickerTests:OpenAppFromLockNotificationWithLockOverlayApp`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Postsubmit
class OpenAppFromLockscreenNotificationWithOverlayAppTest(flicker: LegacyFlickerTest) :
    OpenAppFromLockscreenNotificationColdTest(flicker) {
    private val showWhenLockedApp = ShowWhenLockedAppHelper(instrumentation)

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            transitions {
                device.wakeUp()
                // Although we are technically still locked here, the overlay app means we should
                // open the
                // notification shade as if we were unlocked.
                openAppFromNotification()
                wmHelper.StateSyncBuilder().withFullScreenApp(testApp).waitForAndVerify()
            }

            setup {
                device.wakeUpAndGoToHomeScreen()
                launchAppAndPostNotification()
                clearOverview()
                // Launch an activity that is shown when the device is locked
                showWhenLockedApp.launchViaIntent(wmHelper)
                wmHelper.StateSyncBuilder().withFullScreenApp(showWhenLockedApp).waitForAndVerify()

                device.sleep()
                wmHelper.StateSyncBuilder().withoutTopVisibleAppWindows().waitForAndVerify()
            }

            teardown {
                testApp.exit(wmHelper)
                showWhenLockedApp.exit(wmHelper)
            }
        }

    @Test
    @FlakyTest(bugId = 227143265)
    fun showWhenLockedAppWindowBecomesVisible() {
        flicker.assertWm {
            this.hasNoVisibleAppWindow()
                .then()
                .isAppWindowOnTop(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isAppWindowOnTop(showWhenLockedApp)
        }
    }

    @Test
    @FlakyTest(bugId = 227143265)
    fun showWhenLockedAppLayerBecomesVisible() {
        flicker.assertLayers {
            this.isInvisible(showWhenLockedApp)
                .then()
                .isVisible(ComponentNameMatcher.SNAPSHOT, isOptional = true)
                .then()
                .isVisible(showWhenLockedApp)
        }
    }

    /** {@inheritDoc} */
    @Presubmit @Test override fun appLayerBecomesVisible() = super.appLayerBecomesVisible()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 227143265)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Test
    @Ignore("Not applicable to this CUJ. Display starts off and app is full screen at the end")
    override fun navBarLayerIsVisibleAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    @Presubmit @Test override fun entireScreenCovered() = super.entireScreenCovered()

    @FlakyTest(bugId = 278227468)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

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
