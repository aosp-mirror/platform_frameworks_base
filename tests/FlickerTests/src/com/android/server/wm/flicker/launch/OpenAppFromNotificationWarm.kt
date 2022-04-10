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
import android.platform.test.annotations.RequiresDevice
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.NotificationAppHelper
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test cold launching an app from a notification.
 *
 * To run this test: `atest FlickerTests:OpenAppFromNotificationWarm`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
@Postsubmit
open class OpenAppFromNotificationWarm(testSpec: FlickerTestParameter)
    : OpenAppTransition(testSpec) {
    protected val taplInstrumentation = LauncherInstrumentation()

    override val testApp: NotificationAppHelper = NotificationAppHelper(instrumentation)

    open val openingNotificationsFromLockScreen = false

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                    this.setRotation(testSpec.startRotation)
                }
                eachRun {
                    testApp.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(testApp.component)
                    testApp.postNotification(device, wmHelper)
                    device.pressHome()
                    wmHelper.waitForAppTransitionIdle()
                }
            }

            transitions {
                var startY = 10
                var endY = 3 * device.displayHeight / 4
                var steps = 25
                if (openingNotificationsFromLockScreen) {
                    val wm = instrumentation.context.getSystemService(WindowManager::class.java)
                    val metricInsets = wm.currentWindowMetrics.windowInsets
                    val insets = metricInsets.getInsetsIgnoringVisibility(
                            WindowInsets.Type.statusBars()
                                    or WindowInsets.Type.displayCutout())

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
                val notification = device.wait(Until.findObject(
                        By.text("Flicker Test Notification")), 2000L)
                notification?.click() ?: error("Notification not found")
                instrumentation.uiAutomation.syncInputTransactions()

                // Wait for the app to launch
                wmHelper.waitForFullScreenApp(testApp.component)
            }

            teardown {
                test {
                    testApp.exit(wmHelper)
                }
            }
        }

    @Test
    @Postsubmit
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    @Test
    @Postsubmit
    override fun statusBarLayerIsVisible() = super.statusBarLayerIsVisible()

    @Test
    @Postsubmit
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    @Test
    @Postsubmit
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

    @Test
    @Postsubmit
    override fun appWindowBecomesVisible() = appWindowBecomesVisible_warmStart()

    @Test
    @Postsubmit
    override fun appLayerBecomesVisible() = appLayerBecomesVisible_warmStart()

    @Test
    @Postsubmit
    fun notificationAppWindowVisibleAtEnd() {
        testSpec.assertWmEnd {
            this.isAppWindowVisible(testApp.component)
        }
    }

    @Test
    @Postsubmit
    fun notificationAppWindowOnTopAtEnd() {
        testSpec.assertWmEnd {
            this.isAppWindowOnTop(testApp.component)
        }
    }

    @Test
    @Postsubmit
    fun notificationAppLayerVisibleAtEnd() {
        testSpec.assertLayersEnd {
            this.isVisible(testApp.component)
        }
    }

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