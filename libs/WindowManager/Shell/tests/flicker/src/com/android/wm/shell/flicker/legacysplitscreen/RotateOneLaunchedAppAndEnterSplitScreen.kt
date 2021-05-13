/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker.legacysplitscreen

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.appWindowBecomesVisible
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.endRotation
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.dockedStackDividerIsVisible
import com.android.wm.shell.flicker.dockedStackPrimaryBoundsIsVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test dock activity to primary split screen and rotate
 * To run this test: `atest WMShellFlickerTests:RotateOneLaunchedAppAndEnterSplitScreen`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RotateOneLaunchedAppAndEnterSplitScreen(
    testSpec: FlickerTestParameter
) : LegacySplitScreenRotateTransition(testSpec) {
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            super.transition(this, configuration)
            transitions {
                device.launchSplitScreen(wmHelper)
                this.setRotation(testSpec.config.startRotation)
            }
        }

    @Presubmit
    @Test
    fun dockedStackDividerIsVisible() = testSpec.dockedStackDividerIsVisible()

    @Presubmit
    @Test
    fun dockedStackPrimaryBoundsIsVisible() =
        testSpec.dockedStackPrimaryBoundsIsVisible(testSpec.config.startRotation,
            splitScreenApp.defaultWindowName)

    @FlakyTest(bugId = 169271943)
    @Test
    fun navBarLayerRotatesAndScales() =
        testSpec.navBarLayerRotatesAndScales(testSpec.config.startRotation,
            testSpec.config.endRotation)

    @FlakyTest(bugId = 169271943)
    @Test
    fun statusBarLayerRotatesScales() =
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation,
            testSpec.config.endRotation)

    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @FlakyTest
    @Test
    fun appWindowBecomesVisible() =
        testSpec.appWindowBecomesVisible(splitScreenApp.defaultWindowName)

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(repetitions = SplitScreenHelper.TEST_REPETITIONS,
                    supportedRotations = listOf(Surface.ROTATION_0)) // b/178685668
        }
    }
}
