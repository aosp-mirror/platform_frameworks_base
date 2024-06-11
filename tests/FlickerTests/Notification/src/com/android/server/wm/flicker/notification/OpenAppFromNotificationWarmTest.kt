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
import android.platform.test.rule.DisableNotificationCooldownSettingRule
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.FlickerTestData
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.helpers.wakeUpAndGoToHomeScreen
import android.tools.traces.component.ComponentNameMatcher
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.NotificationAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerIsVisibleAtEnd
import com.android.server.wm.flicker.navBarLayerPositionAtEnd
import com.android.server.wm.flicker.navBarWindowIsVisibleAtEnd
import com.android.server.wm.flicker.taskBarLayerIsVisibleAtEnd
import com.android.server.wm.flicker.taskBarWindowIsVisibleAtEnd
import org.junit.Assume
import org.junit.ClassRule
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from a notification.
 *
 * To run this test: `atest FlickerTestsNotification:OpenAppFromNotificationWarmTest`
 */
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class OpenAppFromNotificationWarmTest(flicker: LegacyFlickerTest) :
    OpenAppTransition(flicker) {
    override val testApp: NotificationAppHelper = NotificationAppHelper(instrumentation)

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                device.wakeUpAndGoToHomeScreen()
                this.setRotation(flicker.scenario.startRotation)
                launchAppAndPostNotification()
                goHome()
            }

            transitions { openAppFromNotification() }

            teardown { testApp.exit(wmHelper) }
        }

    protected fun FlickerTestData.launchAppAndPostNotification() {
        testApp.launchViaIntent(wmHelper)
        wmHelper.StateSyncBuilder().withFullScreenApp(testApp).waitForAndVerify()
        testApp.postNotification(wmHelper)
    }

    protected fun FlickerTestData.goHome() {
        device.pressHome()
        wmHelper
            .StateSyncBuilder()
            .withHomeActivityVisible()
            .withWindowSurfaceDisappeared(ComponentNameMatcher.NOTIFICATION_SHADE)
            .waitForAndVerify()
    }
    protected fun FlickerTestData.openAppFromNotification() {
        doOpenAppAndWait(startY = 10, endY = 3 * device.displayHeight / 4, steps = 25)
    }

    protected fun FlickerTestData.openAppFromLockNotification() {
        val wm: WindowManager =
            instrumentation.context.getSystemService(WindowManager::class.java)
                ?: error("Unable to connect to WindowManager service")
        val metricInsets = wm.currentWindowMetrics.windowInsets
        val insets =
            metricInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )

        doOpenAppAndWait(startY = insets.top + 100, endY = device.displayHeight / 2, steps = 4)
    }

    protected fun FlickerTestData.doOpenAppAndWait(startY: Int, endY: Int, steps: Int) {
        // Swipe down to show the notification shade
        val x = device.displayWidth / 2
        device.swipe(x, startY, x, endY, steps)
        device.waitForIdle(2000)
        instrumentation.uiAutomation.syncInputTransactions()

        // Launch the activity by clicking the notification
        val notification =
            device.wait(Until.findObject(By.text("Flicker Test Notification")), 2000L)
        notification?.click() ?: error("Notification not found")
        instrumentation.uiAutomation.syncInputTransactions()

        // Wait for the app to launch
        wmHelper.StateSyncBuilder().withFullScreenApp(testApp).waitForAndVerify()
    }
    @Presubmit @Test override fun appWindowBecomesVisible() = appWindowBecomesVisible_warmStart()

    @Presubmit @Test override fun appLayerBecomesVisible() = appLayerBecomesVisible_warmStart()

    @Presubmit
    @Test
    open fun notificationAppWindowVisibleAtEnd() {
        flicker.assertWmEnd { this.isAppWindowVisible(testApp) }
    }

    @Presubmit
    @Test
    open fun notificationAppWindowOnTopAtEnd() {
        flicker.assertWmEnd { this.isAppWindowOnTop(testApp) }
    }

    @Presubmit
    @Test
    open fun notificationAppLayerVisibleAtEnd() {
        flicker.assertLayersEnd { this.isVisible(testApp) }
    }

    /**
     * Checks that the [ComponentNameMatcher.TASK_BAR] window is visible at the end of the
     * transition
     *
     * Note: Large screen only
     */
    @Presubmit
    @Test
    open fun taskBarWindowIsVisibleAtEnd() {
        Assume.assumeTrue(flicker.scenario.isTablet)
        flicker.taskBarWindowIsVisibleAtEnd()
    }

    /**
     * Checks that the [ComponentNameMatcher.TASK_BAR] layer is visible at the end of the transition
     *
     * Note: Large screen only
     */
    @Presubmit
    @Test
    open fun taskBarLayerIsVisibleAtEnd() {
        Assume.assumeTrue(flicker.scenario.isTablet)
        flicker.taskBarLayerIsVisibleAtEnd()
    }

    /** Checks the position of the [ComponentNameMatcher.NAV_BAR] at the end of the transition */
    @Presubmit
    @Test
    open fun navBarLayerPositionAtEnd() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.navBarLayerPositionAtEnd()
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    open fun navBarLayerIsVisibleAtEnd() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.navBarLayerIsVisibleAtEnd()
    }

    @Presubmit
    @Test
    open fun navBarWindowIsVisibleAtEnd() {
        Assume.assumeFalse(flicker.scenario.isTablet)
        flicker.navBarWindowIsVisibleAtEnd()
    }

    /** {@inheritDoc} */
    @Test
    @Ignore("Display is off at the start")
    override fun taskBarLayerIsVisibleAtStartAndEnd() {}

    /** {@inheritDoc} */
    @Test
    @Postsubmit
    override fun taskBarWindowIsAlwaysVisible() = super.taskBarWindowIsAlwaysVisible()

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

        /** Ensures that posted notifications will alert and HUN even just after boot. */
        @ClassRule
        @JvmField
        val disablenotificationCooldown = DisableNotificationCooldownSettingRule()
    }
}
