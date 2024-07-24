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

package com.android.server.wm.flicker.activityembedding.layoutchange

import android.platform.test.annotations.Presubmit
import android.tools.datatypes.Rect
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.activityembedding.ActivityEmbeddingTestBase
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test changing split ratio at runtime on a horizona split.
 *
 * Setup: Launch A|B in horizontal split with B being the secondary activity, by default A and B
 * windows are equal in size. B is on the top and A is on the bottom. Transitions: Change the split
 * ratio to A:B=0.7:0.3, expect bounds change for both A and B.
 *
 * To run this test: `atest FlickerTestsOther:HorizontalSplitChangeRatioTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HorizontalSplitChangeRatioTest(flicker: LegacyFlickerTest) :
    ActivityEmbeddingTestBase(flicker) {
    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            testApp.launchViaIntent(wmHelper)
            testApp.launchSecondaryActivityHorizontally(wmHelper)
            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds ?: error("Display not found")
        }
        transitions { testApp.changeSecondaryActivityRatio(wmHelper) }
        teardown {
            tapl.goHome()
            testApp.exit(wmHelper)
        }
    }

    @FlakyTest(bugId = 293075402)
    @Test
    override fun backgroundLayerNeverVisible() = super.backgroundLayerNeverVisible()

    @FlakyTest(bugId = 293075402)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** Assert the Main activity window is always visible. */
    @Presubmit
    @Test
    fun mainActivityWindowIsAlwaysVisible() {
        flicker.assertWm { isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
    }

    /** Assert the Main activity window is always visible. */
    @Presubmit
    @Test
    fun mainActivityLayerIsAlwaysVisible() {
        flicker.assertLayers { isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
    }

    /** Assert the Secondary activity window is always visible. */
    @Presubmit
    @Test
    fun secondaryActivityWindowIsAlwaysVisible() {
        flicker.assertWm {
            isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    /** Assert the Secondary activity window is always visible. */
    @Presubmit
    @Test
    fun secondaryActivityLayerIsAlwaysVisible() {
        flicker.assertLayers { isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT) }
    }

    /** Assert the Main and Secondary activity change height during the transition. */
    @Presubmit
    @Test
    fun secondaryActivityAdjustsHeightRuntime() {
        flicker.assertLayersStart {
            val topLayerRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            val bottomLayerRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            // Compare dimensions of two splits, given we're using default split attributes,
            // both activities take up the same visible size on the display.
            check { "height" }
                .that(topLayerRegion.region.height)
                .isEqual(bottomLayerRegion.region.height)
            check { "width" }
                .that(topLayerRegion.region.width)
                .isEqual(bottomLayerRegion.region.width)
            topLayerRegion.notOverlaps(bottomLayerRegion.region)
            // Layers of two activities sum to be fullscreen size on display.
            topLayerRegion.plus(bottomLayerRegion.region).coversExactly(startDisplayBounds)
        }

        flicker.assertLayersEnd {
            val topLayerRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            val bottomLayerRegion =
                this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            // Compare dimensions of two splits, given we're using default split attributes,
            // both activities take up the same visible size on the display.
            check { "height" }
                .that(topLayerRegion.region.height)
                .isLower(bottomLayerRegion.region.height)
            check { "height" }
                .that(topLayerRegion.region.height / 0.3f - bottomLayerRegion.region.height / 0.7f)
                .isLower(0.1f)
            check { "width" }
                .that(topLayerRegion.region.width)
                .isEqual(bottomLayerRegion.region.width)
            topLayerRegion.notOverlaps(bottomLayerRegion.region)
            // Layers of two activities sum to be fullscreen size on display.
            topLayerRegion.plus(bottomLayerRegion.region).coversExactly(startDisplayBounds)
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
