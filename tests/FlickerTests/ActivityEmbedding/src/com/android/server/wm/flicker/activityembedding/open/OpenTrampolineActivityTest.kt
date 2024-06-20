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

import android.graphics.Rect
import android.platform.test.annotations.Presubmit
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.flicker.subject.region.RegionSubject
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
 * Test launching a trampoline activity and resulting in a split state.
 *
 * Setup: Launch Activity A in fullscreen.
 *
 * Transitions: From A launch a trampoline Activity T, T launches secondary Activity B and finishes
 * itself, end up in split A|B.
 *
 * To run this test: `atest FlickerTestsOther:OpenTrampolineActivityTest`
 */
@FlakyTest(bugId = 341209752)
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenTrampolineActivityTest(flicker: LegacyFlickerTest) : ActivityEmbeddingTestBase(flicker) {
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            testApp.launchViaIntent(wmHelper)
            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds
                    ?: error("Can't get display bounds")
        }
        transitions { testApp.launchTrampolineActivity(wmHelper) }
        teardown {
            tapl.goHome()
            testApp.exit(wmHelper)
        }
    }

    /** Trampoline activity should finish itself before the end of this test. */
    @Presubmit
    @Test
    fun trampolineActivityFinishes() {
        flicker.assertWmEnd {
            notContains(ActivityEmbeddingAppHelper.TRAMPOLINE_ACTIVITY_COMPONENT)
        }
    }

    @Presubmit
    @Test
    fun trampolineLayerNeverVisible() {
        flicker.assertLayers {
            isInvisible(ActivityEmbeddingAppHelper.TRAMPOLINE_ACTIVITY_COMPONENT)
        }
    }

    /** Main activity is always visible throughout this test. */
    @Presubmit
    @Test
    fun mainActivityWindowAlwaysVisible() {
        flicker.assertWm { isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
    }

    // TODO(b/289140963): After this is fixed, assert the main Activity window is visible
    //  throughout the test instead.
    /** Main activity layer is visible before and after the transition. */
    @Presubmit
    @Test
    fun mainActivityLayerAlwaysVisible() {
        flicker.assertLayersStart { isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
        flicker.assertLayersEnd { isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
    }

    /** Secondary activity is launched from the trampoline activity. */
    @Presubmit
    @Test
    fun secondaryActivityWindowLaunchedFromTrampoline() {
        flicker.assertWm {
            notContains(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .then()
                .isAppWindowInvisible(
                    ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT,
                    isOptional = true
                )
                .then()
                .isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    /** Secondary activity is launched from the trampoline activity. */
    @Presubmit
    @Test
    fun secondaryActivityLayerLaunchedFromTrampoline() {
        flicker.assertLayers {
            isInvisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .then()
                .isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    /** Main activity should go from fullscreen to being a split with secondary activity. */
    @Presubmit
    @Test
    fun mainActivityWindowGoesFromFullscreenToSplit() {
        flicker.assertWm {
            this.invoke("mainActivityStartsInFullscreen") {
                    it.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                        .coversExactly(startDisplayBounds)
                }
                // Begin of transition.
                .then()
                .isAppWindowInvisible(
                    ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT,
                    isOptional = true
                )
                .then()
                .invoke("mainAndSecondaryInSplit") {
                    val mainActivityRegion =
                        RegionSubject(
                            it.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                                .region,
                            it.timestamp
                        )
                    val secondaryActivityRegion =
                        RegionSubject(
                            it.visibleRegion(
                                    ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT
                                )
                                .region,
                            it.timestamp
                        )
                    check { "height" }
                        .that(mainActivityRegion.region.bounds.height())
                        .isEqual(secondaryActivityRegion.region.bounds.height())
                    check { "width" }
                        .that(mainActivityRegion.region.bounds.width())
                        .isEqual(secondaryActivityRegion.region.bounds.width())
                    mainActivityRegion
                        .plus(secondaryActivityRegion.region)
                        .coversExactly(startDisplayBounds)
                }
        }
    }

    /** Main activity should go from fullscreen to being a split with secondary activity. */
    @Test
    fun mainActivityLayerGoesFromFullscreenToSplit() {
        flicker.assertLayers {
            this.invoke("mainActivityStartsInFullscreen") {
                    it.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                        .coversExactly(startDisplayBounds)
                }
                .then()
                .isInvisible(
                    ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT,
                    isOptional = true
                )
                .then()
                .isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
        flicker.assertLayersEnd {
            val leftLayerRegion = visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            val rightLayerRegion =
                visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            // Compare dimensions of two splits, given we're using default split attributes,
            // both activities take up the same visible size on the display.
            check { "height" }
                .that(leftLayerRegion.region.bounds.height())
                .isEqual(rightLayerRegion.region.bounds.height())
            check { "width" }
                .that(leftLayerRegion.region.bounds.width())
                .isEqual(rightLayerRegion.region.bounds.width())
            leftLayerRegion.notOverlaps(rightLayerRegion.region)
            // Layers of two activities sum to be fullscreen size on display.
            leftLayerRegion.plus(rightLayerRegion.region).coversExactly(startDisplayBounds)
        }
    }

    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        super.visibleLayersShownMoreThanOneConsecutiveEntry()
    }

    companion object {
        /** {@inheritDoc} */
        private var startDisplayBounds = Rect()

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
