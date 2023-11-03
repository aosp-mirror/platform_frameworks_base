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

package com.android.server.wm.flicker.activityembedding.rotation

import android.platform.test.annotations.Presubmit
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Tests rotating two activities in an Activity Embedding split.
 *
 * Setup: Launch A|B in split with B being the secondary activity. Transitions: Rotate display, and
 * expect A and B to split evenly in new rotation.
 *
 * To run this test: `atest FlickerTestsOther:RotateSplitNoChangeTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class RotateSplitNoChangeTest(flicker: LegacyFlickerTest) : RotationTransition(flicker) {

    override val testApp = ActivityEmbeddingAppHelper(instrumentation)
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                testApp.launchViaIntent(wmHelper)
                testApp.launchSecondaryActivity(wmHelper)
            }
        }

    /**
     * Checks that the [ComponentNameMatcher.ROTATION] layer appears during the transition, doesn't
     * flicker, and disappears before the transition is complete
     */
    @Presubmit
    @Test
    fun rotationLayerAppearsAndVanishes() {
        flicker.assertLayers {
            this.isVisible(testApp)
                .then()
                .isVisible(ComponentNameMatcher.ROTATION)
                .then()
                .isVisible(testApp)
                .isInvisible(ComponentNameMatcher.ROTATION)
        }
    }

    /**
     * Overrides inherited assertion because in AE Split, the main and secondary activity are
     * separate layers, each covering up exactly half of the display.
     */
    @Presubmit
    @Test
    override fun appLayerRotates_StartingPos() {
        flicker.assertLayersStart {
            this.entry.displays.map { display ->
                val leftLayerRegion =
                    this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                val rightLayerRegion =
                    this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                // Compare dimensions of two splits, given we're using default split attributes,
                // both activities take up the same visible size on the display.
                check { "height" }
                    .that(leftLayerRegion.region.height)
                    .isEqual(rightLayerRegion.region.height)
                check { "width" }
                    .that(leftLayerRegion.region.width)
                    .isEqual(rightLayerRegion.region.width)
                leftLayerRegion.notOverlaps(rightLayerRegion.region)
                // Layers of two activities sum to be fullscreen size on display.
                leftLayerRegion.plus(rightLayerRegion.region).coversExactly(display.layerStackSpace)
            }
        }
    }

    /** Verifies dimensions of both split activities hold their invariance after transition too. */
    @Presubmit
    @Test
    override fun appLayerRotates_EndingPos() {
        flicker.assertLayersEnd {
            this.entry.displays.map { display ->
                val leftLayerRegion =
                    this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                val rightLayerRegion =
                    this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                check { "height" }
                    .that(leftLayerRegion.region.height)
                    .isEqual(rightLayerRegion.region.height)
                check { "width" }
                    .that(leftLayerRegion.region.width)
                    .isEqual(rightLayerRegion.region.width)
                leftLayerRegion.notOverlaps(rightLayerRegion.region)
                leftLayerRegion.plus(rightLayerRegion.region).coversExactly(display.layerStackSpace)
            }
        }
    }

    /** Both activities in split should remain visible during rotation. */
    @Presubmit
    @Test
    fun bothActivitiesAreAlwaysVisible() {
        flicker.assertWm { isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
        flicker.assertWm {
            isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [LegacyFlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() = LegacyFlickerTestFactory.rotationTests()
    }
}
