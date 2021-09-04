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

package com.android.server.wm.flicker.launch

import android.app.Instrumentation
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.TwoActivitiesAppHelper
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test the back and forward transition between 2 activities.
 * To run this test: `atest FlickerTests:ActivitiesTransitionTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class ActivitiesTransitionTest(val testSpec: FlickerTestParameter) {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp: TwoActivitiesAppHelper = TwoActivitiesAppHelper(instrumentation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            withTestName { testSpec.name }
            repeat { testSpec.config.repetitions }
            setup {
                eachRun {
                    testApp.launchViaIntent(wmHelper)
                    wmHelper.waitForFullScreenApp(testApp.component)
                }
            }
            teardown {
                test {
                    testApp.exit()
                }
            }
            transitions {
                testApp.openSecondActivity(device, wmHelper)
                device.pressBack()
                wmHelper.waitForAppTransitionIdle()
                wmHelper.waitForFullScreenApp(testApp.component)
            }
        }
    }

    @Presubmit
    @Test
    fun finishSubActivity() {
        testSpec.assertWm {
            this.isAppWindowOnTop(ActivityOptions.BUTTON_ACTIVITY_COMPONENT_NAME)
                    .then()
                    .isAppWindowOnTop(ActivityOptions.SIMPLE_ACTIVITY_AUTO_FOCUS_COMPONENT_NAME)
                    .then()
                    .isAppWindowOnTop(ActivityOptions.BUTTON_ACTIVITY_COMPONENT_NAME)
        }
    }

    @Presubmit
    @Test
    fun entireScreenCovered() = testSpec.entireScreenCovered()

    @Presubmit
    @Test
    fun launcherWindowNotVisible() {
        testSpec.assertWm {
            this.isAppWindowInvisible(LAUNCHER_COMPONENT, ignoreActivity = true)
        }
    }

    @Presubmit
    @Test
    fun launcherLayerNotVisible() {
        testSpec.assertLayers { this.isInvisible(LAUNCHER_COMPONENT) }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(repetitions = 5)
        }
    }
}
