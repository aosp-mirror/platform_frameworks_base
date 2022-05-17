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

package com.android.wm.shell.flicker.apppairs

import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.wm.shell.flicker.appPairsDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.appPairsPrimaryBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.appPairsSecondaryBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.helpers.AppPairsHelper.Companion.waitAppsShown
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open apps to app pairs and rotate.
 * To run this test: `atest WMShellFlickerTests:RotateTwoLaunchedAppsInAppPairsMode`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
class RotateTwoLaunchedAppsInAppPairsMode(
    testSpec: FlickerTestParameter
) : RotateTwoLaunchedAppsTransition(testSpec) {
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            transitions {
                executeShellCommand(composePairsCommand(
                    primaryTaskId, secondaryTaskId, true /* pair */))
                waitAppsShown(primaryApp, secondaryApp)
                setRotation(testSpec.endRotation)
            }
        }

    @Ignore
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    @Ignore
    @Test
    override fun statusBarLayerIsVisible() = super.statusBarLayerIsVisible()

    @Ignore
    @Test
    fun bothAppWindowsVisible() {
        testSpec.assertWmEnd {
            isAppWindowVisible(primaryApp.component)
            isAppWindowVisible(secondaryApp.component)
        }
    }

    @Ignore
    @Test
    fun appPairsDividerIsVisibleAtEnd() = testSpec.appPairsDividerIsVisibleAtEnd()

    @Ignore
    @Test
    fun appPairsPrimaryBoundsIsVisibleAtEnd() =
        testSpec.appPairsPrimaryBoundsIsVisibleAtEnd(testSpec.endRotation,
            primaryApp.component)

    @Ignore
    @Test
    fun appPairsSecondaryBoundsIsVisibleAtEnd() =
        testSpec.appPairsSecondaryBoundsIsVisibleAtEnd(testSpec.endRotation,
            secondaryApp.component)

    @Ignore
    @Test
    override fun statusBarLayerRotatesScales() = super.statusBarLayerRotatesScales()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_90, Surface.ROTATION_270)
            )
        }
    }
}