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
import androidx.test.filters.FlakyTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.repetitions
import com.android.wm.shell.flicker.helpers.LaunchBubbleHelper
import org.junit.Test
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

    protected val packageManager = context.getPackageManager()
    protected val uid = packageManager.getApplicationInfo(
            testApp.component.packageName, 0).uid

    protected lateinit var addBubbleBtn: UiObject2

    protected abstract val transition: FlickerBuilder.(Map<String, Any?>) -> Unit

    @JvmOverloads
    protected open fun buildTransition(
        extraSpec: FlickerBuilder.(Map<String, Any?>) -> Unit = {}
    ): FlickerBuilder.(Map<String, Any?>) -> Unit {
        return { configuration ->

            setup {
                test {
                    notifyManager.setBubblesAllowed(testApp.component.packageName,
                            uid, NotificationManager.BUBBLE_PREFERENCE_ALL)
                    testApp.launchViaIntent(wmHelper)
                    addBubbleBtn = device.wait(Until.findObject(
                            By.text("Add Bubble")), FIND_OBJECT_TIMEOUT)
                }
            }

            teardown {
                notifyManager.setBubblesAllowed(testApp.component.packageName,
                        uid, NotificationManager.BUBBLE_PREFERENCE_NONE)
                testApp.exit()
            }

            extraSpec(this, configuration)
        }
    }

    @FlakyTest
    @Test
    fun testAppIsAlwaysVisible() {
        testSpec.assertLayers {
            this.isVisible(testApp.component)
        }
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            repeat { testSpec.config.repetitions }
            transition(this, testSpec.config)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                            repetitions = 5)
        }

        const val FIND_OBJECT_TIMEOUT = 2000L
    }
}
