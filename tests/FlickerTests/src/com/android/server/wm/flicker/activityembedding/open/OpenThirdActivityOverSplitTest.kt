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

package com.android.server.wm.flicker.activityembedding.open

import android.platform.test.annotations.Presubmit
import android.tools.common.datatypes.Rect
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.activityembedding.ActivityEmbeddingTestBase
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching a secondary activity over an existing split. By default the new secondary activity
 * will stack over the previous secondary activity.
 *
 * Setup: From Activity A launch a split A|B.
 *
 * Transitions: Let B start C, expect C to cover B and end up in split A|C.
 *
 * To run this test: `atest FlickerTestsOther:OpenThirdActivityOverSplitTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenThirdActivityOverSplitTest(flicker: LegacyFlickerTest) :
    ActivityEmbeddingTestBase(flicker) {
    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            // Launch a split.
            testApp.launchViaIntent(wmHelper)
            testApp.launchSecondaryActivity(wmHelper)

            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds
                    ?: error("Can't get display bounds")
        }
        transitions { testApp.launchThirdActivity(wmHelper) }
        teardown {
            tapl.goHome()
            testApp.exit(wmHelper)
        }
    }

    /** Main activity remains visible throughout the transition. */
    @Presubmit
    @Test
    fun mainActivityWindowAlwaysVisible() {
        flicker.assertWm { isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
    }

    /** Main activity remains visible throughout the transition and takes up half of the screen. */
    @Presubmit
    @Test
    fun mainActivityLayersAlwaysVisible() {
        flicker.assertLayers { isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }

        flicker.assertLayersStart {
            val display =
                this.entry.displays.firstOrNull { it.isOn && !it.isVirtual }
                    ?: error("No non-virtual and on display found")
            val mainActivityRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            val secondaryActivityRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT).region
            mainActivityRegion.plus(secondaryActivityRegion).coversExactly(display.layerStackSpace)
        }

        flicker.assertLayersEnd {
            val display =
                this.entry.displays.firstOrNull { it.isOn && !it.isVirtual }
                    ?: error("No non-virtual and on display found")
            val mainActivityRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            val secondaryActivityRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            secondaryActivityRegion.isEmpty()
            val thirdActivityRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.THIRD_ACTIVITY_COMPONENT)
            mainActivityRegion
                .plus(thirdActivityRegion.region)
                .coversExactly(display.layerStackSpace)
        }
    }

    /** Third activity launches during the transition and covers up secondary activity. */
    @Presubmit
    @Test
    fun thirdActivityWindowLaunchesIntoSplit() {
        flicker.assertWm {
            isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .isAppWindowInvisible(ActivityEmbeddingAppHelper.THIRD_ACTIVITY_COMPONENT)
                .then()
                .isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .isAppWindowVisible(ActivityEmbeddingAppHelper.THIRD_ACTIVITY_COMPONENT)
                .then()
                .isAppWindowVisible(ActivityEmbeddingAppHelper.THIRD_ACTIVITY_COMPONENT)
                .isAppWindowInvisible(
                    ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT
                ) // expectation
        }
    }

    /** Third activity launches during the transition and covers up secondary activity. */
    @Presubmit
    @Test
    fun thirdActivityLayerLaunchesIntoSplit() {
        flicker.assertLayers {
            isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .isInvisible(ActivityEmbeddingAppHelper.THIRD_ACTIVITY_COMPONENT)
                .then()
                .isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .isVisible(ActivityEmbeddingAppHelper.THIRD_ACTIVITY_COMPONENT)
                .then()
                .isVisible(ActivityEmbeddingAppHelper.THIRD_ACTIVITY_COMPONENT)
                .isInvisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    companion object {
        /** {@inheritDoc} */
        private var startDisplayBounds = Rect.EMPTY
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
