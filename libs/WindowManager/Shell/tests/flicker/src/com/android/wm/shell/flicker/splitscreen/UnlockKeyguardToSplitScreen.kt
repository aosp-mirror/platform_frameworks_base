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

package com.android.wm.shell.flicker.splitscreen

import android.platform.test.annotations.Postsubmit
import android.tools.common.NavBar
import android.tools.common.flicker.subject.region.RegionSubject
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.wm.shell.flicker.ICommonAssertions
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.layerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitscreen.benchmark.UnlockKeyguardToSplitScreenBenchmark
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test unlocking insecure keyguard to back to split screen tasks and verify the transition behavior.
 *
 * To run this test: `atest WMShellFlickerTests:UnlockKeyguardToSplitScreen`
 */
@RequiresDevice
@Postsubmit
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UnlockKeyguardToSplitScreen(override val flicker: FlickerTest) :
        UnlockKeyguardToSplitScreenBenchmark(flicker), ICommonAssertions {
    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            defaultSetup(this)
            defaultTeardown(this)
            thisTransition(this)
        }

    @Test
    fun splitScreenDividerIsVisibleAtEnd() {
        flicker.assertLayersEnd { this.isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT) }
    }

    @Test fun primaryAppLayerIsVisibleAtEnd() = flicker.layerIsVisibleAtEnd(primaryApp)

    @Test
    fun primaryAppBoundsIsVisibleAtEnd() =
            flicker.splitAppLayerBoundsIsVisibleAtEnd(
                    primaryApp,
                    landscapePosLeft = false,
                    portraitPosTop = false
            )

    @Test
    fun secondaryAppBoundsIsVisibleAtEnd() =
            flicker.splitAppLayerBoundsIsVisibleAtEnd(
                    secondaryApp,
                    landscapePosLeft = true,
                    portraitPosTop = true
            )

    @Test
    fun primaryAppWindowIsVisibleAtEnd() = flicker.appWindowIsVisibleAtEnd(primaryApp)

    @Test
    fun secondaryAppWindowIsVisibleAtEnd() = flicker.appWindowIsVisibleAtEnd(secondaryApp)

    @Test
    fun notOverlapsForPrimaryAndSecondaryAppLayers() {
        flicker.assertLayers {
            this.invoke("notOverlapsForPrimaryAndSecondaryLayers") {
                val primaryAppRegions = it.subjects.filter { subject ->
                    subject.name.contains(primaryApp.toLayerName()) && subject.isVisible
                }.mapNotNull { primaryApp -> primaryApp.layer.visibleRegion }.toTypedArray()

                val primaryAppRegionArea = RegionSubject(primaryAppRegions, it.timestamp)
                it.visibleRegion(secondaryApp).notOverlaps(primaryAppRegionArea.region)
            }
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                    supportedNavigationModes = listOf(NavBar.MODE_GESTURAL)
            )
        }
    }
}