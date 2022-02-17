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

package com.android.wm.shell.flicker.bubble

import android.app.INotificationManager
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.os.ServiceManager
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SYSTEMUI_PACKAGE
import com.android.wm.shell.flicker.helpers.LaunchBubbleHelper
import org.junit.runners.Parameterized

/**
 * Base configurations for Bubble flicker tests
 */
abstract class BaseBubbleScreen(protected val testSpec: FlickerTestParameter) {

    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context: Context = instrumentation.context
    protected val testApp = LaunchBubbleHelper(instrumentation)

    protected val notifyManager = INotificationManager.Stub.asInterface(
            ServiceManager.getService(Context.NOTIFICATION_SERVICE))

    protected val uid = context.packageManager.getApplicationInfo(
            testApp.component.packageName, 0).uid

    protected abstract val transition: FlickerBuilder.() -> Unit

    @JvmOverloads
    protected open fun buildTransition(
        extraSpec: FlickerBuilder.() -> Unit = {}
    ): FlickerBuilder.() -> Unit {
        return {
            setup {
                test {
                    notifyManager.setBubblesAllowed(testApp.component.packageName,
                            uid, NotificationManager.BUBBLE_PREFERENCE_ALL)
                    testApp.launchViaIntent(wmHelper)
                    waitAndGetAddBubbleBtn()
                    waitAndGetCancelAllBtn()
                }
            }

            teardown {
                test {
                    notifyManager.setBubblesAllowed(testApp.component.packageName,
                        uid, NotificationManager.BUBBLE_PREFERENCE_NONE)
                    testApp.exit()
                }
            }

            extraSpec(this)
        }
    }

    protected fun Flicker.waitAndGetAddBubbleBtn(): UiObject2? = device.wait(Until.findObject(
            By.text("Add Bubble")), FIND_OBJECT_TIMEOUT)
    protected fun Flicker.waitAndGetCancelAllBtn(): UiObject2? = device.wait(Until.findObject(
            By.text("Cancel All Bubble")), FIND_OBJECT_TIMEOUT)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition(this)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                            repetitions = 3)
        }

        const val FIND_OBJECT_TIMEOUT = 2000L
        const val SYSTEM_UI_PACKAGE = SYSTEMUI_PACKAGE
        const val BUBBLE_RES_NAME = "bubble_view"
    }
}
