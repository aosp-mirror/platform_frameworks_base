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
import android.tools.common.traces.ConditionsFactory
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerBuilderProvider
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import android.tools.device.flicker.rules.RemoveAllTasksButHomeRule
import android.tools.device.helpers.wakeUpAndGoToHomeScreen
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.R
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.setRotation
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
 *     Launches SimpleActivity with a special animation.
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OverrideTaskTransitionTest(val flicker: LegacyFlickerTest) {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testApp = SimpleAppHelper(instrumentation)

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            setup {
                device.wakeUpAndGoToHomeScreen()
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
                setRotation(flicker.scenario.startRotation)
            }
            transitions {
                instrumentation.context.startActivity(
                    testApp.openAppIntent,
                    createCustomTaskAnimation()
                )
                wmHelper
                    .StateSyncBuilder()
                    .add(ConditionsFactory.isWMStateComplete())
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
        flicker.assertLayers {
            // Before the app launches, only the launcher is visible.
            isVisible(ComponentNameMatcher.LAUNCHER)
                .isInvisible(testApp)
                .then()
                // Animation starts, but the app may not be drawn yet which means the Splash
                // may be visible.
                .isSplashScreenVisibleFor(testApp, isOptional = true)
                .then()
                // App shows up with the custom animation starting at alpha=1.
                .isVisible(testApp)
                .then()
                // App custom animation continues to alpha=0 (invisible).
                .isInvisible(testApp)
                .then()
                // App custom animation ends with it being visible.
                .isVisible(testApp)
        }
    }

    private fun createCustomTaskAnimation(): Bundle {
        return android.app.ActivityOptions.makeCustomTaskAnimation(
                instrumentation.context,
                R.anim.show_hide_show_3000ms,
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
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
