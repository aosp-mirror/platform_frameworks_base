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
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.NotificationAppHelper
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.taskBarLayerIsVisibleAtEnd
import com.android.server.wm.flicker.taskBarWindowIsVisibleAtEnd
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from a notification.
 *
 * This test assumes the device doesn't have AOD enabled
 *
 * To run this test: `atest FlickerTests:OpenAppFromNotificationWarm`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
@Postsubmit
open class OpenAppFromNotificationWarm(
    testSpec: FlickerTestParameter
) : OpenAppTransition(testSpec) {
    override val testApp: NotificationAppHelper = NotificationAppHelper(instrumentation)

    open val openingNotificationsFromLockScreen = false

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    this.setRotation(testSpec.startRotation)
                }
                eachRun {
                    testApp.launchViaIntent(wmHelper)
                    wmHelper.StateSyncBuilder()
                        .withFullScreenApp(testApp)
                        .waitForAndVerify()
                    testApp.postNotification(wmHelper)
                    tapl.goHome()
                    wmHelper.StateSyncBuilder()
                        .withHomeActivityVisible()
                        .waitForAndVerify()
                }
            }

            transitions {
                var startY = 10
                var endY = 3 * device.displayHeight / 4
                var steps = 25
                if (openingNotificationsFromLockScreen) {
                    val wm: WindowManager =
                        instrumentation.context.getSystemService(WindowManager::class.java)
                            ?: error("Unable to connect to WindowManager service")
                    val metricInsets = wm.currentWindowMetrics.windowInsets
                    val insets = metricInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars()
                            or WindowInsets.Type.displayCutout()
                    )

                    startY = insets.top + 100
                    endY = device.displayHeight / 2
                    steps = 4
                }

                // Swipe down to show the notification shade
                val x = device.displayWidth / 2
                device.swipe(x, startY, x, endY, steps)
                device.waitForIdle(2000)
                instrumentation.uiAutomation.syncInputTransactions()

                // Launch the activity by clicking the notification
                val notification = device.wait(
                    Until.findObject(
                        By.text("Flicker Test Notification")
                    ), 2000L
                )
                notification?.click() ?: error("Notification not found")
                instrumentation.uiAutomation.syncInputTransactions()

                // Wait for the app to launch
                wmHelper.StateSyncBuilder()
                    .withFullScreenApp(testApp)
                    .waitForAndVerify()
            }

            teardown {
                test {
                    testApp.exit(wmHelper)
                }
            }
        }

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() =
        super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appWindowBecomesVisible() = appWindowBecomesVisible_warmStart()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appLayerBecomesVisible() = appLayerBecomesVisible_warmStart()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appWindowIsTopWindowAtEnd() =
        super.appWindowIsTopWindowAtEnd()

    @Postsubmit
    @Test
    open fun notificationAppWindowVisibleAtEnd() {
        testSpec.assertWmEnd {
            this.isAppWindowVisible(testApp)
        }
    }

    @Postsubmit
    @Test
    open fun notificationAppWindowOnTopAtEnd() {
        testSpec.assertWmEnd {
            this.isAppWindowOnTop(testApp)
        }
    }

    @Postsubmit
    @Test
    open fun notificationAppLayerVisibleAtEnd() {
        testSpec.assertLayersEnd {
            this.isVisible(testApp)
        }
    }

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun appWindowBecomesTopWindow() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        super.appWindowBecomesTopWindow()
    }

    @FlakyTest(bugId = 229738092)
    @Test
    open fun appWindowBecomesTopWindow_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        super.appWindowBecomesTopWindow()
    }

    /**
     * Checks that the [ComponentMatcher.TASK_BAR] window is visible at the end of the transition
     *
     * Note: Large screen only
     */
    @Postsubmit
    @Test
    open fun taskBarWindowIsVisibleAtEnd() {
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.taskBarWindowIsVisibleAtEnd()
    }

    /**
     * Checks that the [ComponentMatcher.TASK_BAR] layer is visible at the end of the transition
     *
     * Note: Large screen only
     */
    @Postsubmit
    @Test
    open fun taskBarLayerIsVisibleAtEnd() {
        Assume.assumeFalse(testSpec.isTablet)
        testSpec.taskBarLayerIsVisibleAtEnd()
    }

    /** {@inheritDoc} */
    @Test
    @Ignore("Display is locked at the start")
    override fun taskBarWindowIsAlwaysVisible() =
        super.taskBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Test
    @Ignore("Display is locked at the start")
    override fun taskBarLayerIsVisibleAtStartAndEnd() =
        super.taskBarLayerIsVisibleAtStartAndEnd()

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
