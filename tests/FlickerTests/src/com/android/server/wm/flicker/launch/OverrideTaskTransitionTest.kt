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

import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.R
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.StandardAppHelper
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.WindowManagerConditionsFactory
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test the [android.app.ActivityOptions.makeCustomTaskAnimation].
 *
 * To run this test: `atest FlickerTests:OverrideTaskTransitionTest`
 *
 * Actions:
 * ```
 *     Launches SimpleActivity with alpha_2000ms animation
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OverrideTaskTransitionTest(val testSpec: FlickerTestParameter) {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp: StandardAppHelper = SimpleAppHelper(instrumentation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                device.wakeUpAndGoToHomeScreen()
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
                setRotation(testSpec.startRotation)
            }
            transitions {
                instrumentation.context.startActivity(
                    testApp.openAppIntent,
                    createCustomTaskAnimation()
                )
                wmHelper
                    .StateSyncBuilder()
                    .add(WindowManagerConditionsFactory.isWMStateComplete())
                    .withAppTransitionIdle()
                    .withWindowSurfaceAppeared(testApp)
                    .waitForAndVerify()
            }
            teardown { testApp.exit() }
        }
    }

    @Presubmit
    @Test
    fun testSimpleActivityIsShownDirectly() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        testSpec.assertLayers {
            isVisible(ComponentNameMatcher.LAUNCHER)
                .isInvisible(ComponentNameMatcher.SPLASH_SCREEN)
                .isInvisible(testApp)
                .then()
                // The custom animation should block the entire launcher from the very beginning
                .isInvisible(ComponentNameMatcher.LAUNCHER)
        }
    }

    private fun createCustomTaskAnimation(): Bundle {
        return android.app.ActivityOptions.makeCustomTaskAnimation(
                instrumentation.context,
                R.anim.show_2000ms,
                0,
                Handler.getMain(),
                null,
                null
            )
            .toBundle()
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests()
        }
    }
}
