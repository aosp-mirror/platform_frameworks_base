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

package com.android.server.wm.flicker.activityembedding.pip

import android.platform.test.annotations.Presubmit
import android.tools.common.datatypes.Rect
import android.tools.common.flicker.subject.layers.LayersTraceSubject
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.component.ComponentNameMatcher.Companion.TRANSITION_SNAPSHOT
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
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
 * Test launching a secondary Activity into Picture-In-Picture mode.
 *
 * Setup: Start from a split A|B. Transition: B enters PIP, observe the window first goes fullscreen
 * then shrink to the bottom right corner on screen.
 *
 * To run this test: `atest FlickerTestsOther:SecondaryActivityEnterPipTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SecondaryActivityEnterPipTest(flicker: LegacyFlickerTest) :
    ActivityEmbeddingTestBase(flicker) {
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            testApp.launchViaIntent(wmHelper)
            testApp.launchSecondaryActivity(wmHelper)
            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds
                    ?: error("Can't get display bounds")
        }
        transitions { testApp.secondaryActivityEnterPip(wmHelper) }
        teardown {
            tapl.goHome()
            testApp.exit(wmHelper)
        }
    }

    /** We expect the background layer to be visible during this transition. */
    @Presubmit @Test override fun backgroundLayerNeverVisible() {}

    /** Main and secondary activity start from a split each taking half of the screen. */
    @Presubmit
    @Test
    fun layersStartFromEqualSplit() {
        flicker.assertLayersStart {
            val leftLayerRegion = visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            val rightLayerRegion =
                visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            // Compare dimensions of two splits, given we're using default split attributes,
            // both activities take up the same visible size on the display.
            check { "height" }
                .that(leftLayerRegion.region.height)
                .isEqual(rightLayerRegion.region.height)
            check { "width" }
                .that(leftLayerRegion.region.width)
                .isEqual(rightLayerRegion.region.width)
            leftLayerRegion.notOverlaps(rightLayerRegion.region)
            leftLayerRegion.plus(rightLayerRegion.region).coversExactly(startDisplayBounds)
        }
        flicker.assertLayersEnd {
            visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .coversExactly(startDisplayBounds)
        }
    }

    /** Main Activity is visible throughout the transition and becomes fullscreen. */
    @Presubmit
    @Test
    fun mainActivityWindowBecomesFullScreen() {
        flicker.assertWm { isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
        flicker.assertWmEnd {
            visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .coversExactly(startDisplayBounds)
        }
    }

    /** Main Activity is visible throughout the transition and becomes fullscreen. */
    @Presubmit
    @Test
    fun mainActivityLayerBecomesFullScreen() {
        flicker.assertLayers {
            isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .then()
                .isVisible(TRANSITION_SNAPSHOT)
                .isInvisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .then()
                .isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT, isOptional = true)
        }
        flicker.assertLayersEnd {
            visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .coversExactly(startDisplayBounds)
        }
    }

    /**
     * Secondary Activity is visible throughout the transition and shrinks to the bottom right
     * corner.
     */
    @Presubmit
    @Test
    fun secondaryWindowShrinks() {
        flicker.assertWm {
            isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
        flicker.assertWmEnd {
            val pipWindowRegion =
                visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            check { "height" }
                .that(pipWindowRegion.region.height)
                .isLower(startDisplayBounds.height / 2)
            check { "width" }.that(pipWindowRegion.region.width).isLower(startDisplayBounds.width)
        }
    }

    /** During the transition Secondary Activity shrinks to the bottom right corner. */
    @FlakyTest(bugId = 315605409)
    @Test
    fun secondaryLayerShrinks() {
        flicker.assertLayers {
            val pipLayerList = layers {
                ComponentNameMatcher.PIP_CONTENT_OVERLAY.layerMatchesAnyOf(it) && it.isVisible
            }
            pipLayerList.zipWithNext { previous, current ->
                if (startDisplayBounds.width > startDisplayBounds.height) {
                    // Only verify when the display is landscape, because otherwise the final pip
                    // window can be to the left of the original secondary activity.
                    current.screenBounds.isToTheRightBottom(previous.screenBounds.region, 3)
                }
                current.screenBounds.overlaps(previous.screenBounds.region)
                current.screenBounds.notBiggerThan(previous.screenBounds.region)
            }
        }
        flicker.assertLayersEnd {
            val pipRegion = visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            check { "height" }.that(pipRegion.region.height).isLower(startDisplayBounds.height / 2)
            check { "width" }.that(pipRegion.region.width).isLower(startDisplayBounds.width)
        }
    }

    /** The secondary layer should never jump to the left. */
    @Presubmit
    @Test
    fun secondaryLayerNotJumpToLeft() {
        flicker.assertLayers {
            invoke("secondaryLayerNotJumpToLeft") {
                val secondaryVisibleRegion =
                    it.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                if (secondaryVisibleRegion.region.isNotEmpty) {
                    check { "left" }
                        .that(secondaryVisibleRegion.region.bounds.left)
                        .isGreater(0)
                }
            }
        }
    }

    /**
     * The pip overlay layer should cover exactly the secondary activity layer when both are
     * visible.
     */
    @Presubmit
    @Test
    fun pipContentOverlayLayerCoversExactlySecondaryLayer() {
        flicker.assertLayers {
            isInvisible(ComponentNameMatcher.PIP_CONTENT_OVERLAY)
                .then()
                .isVisible(ComponentNameMatcher.PIP_CONTENT_OVERLAY)
                .isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .invoke("pipContentOverlayLayerCoversExactlySecondaryLayer") {
                    val overlayVisibleRegion =
                        it.visibleRegion(ComponentNameMatcher.PIP_CONTENT_OVERLAY)
                    val secondaryVisibleRegion =
                        it.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                    overlayVisibleRegion.coversExactly(secondaryVisibleRegion.region)
                }
                .then()
                .isInvisible(ComponentNameMatcher.PIP_CONTENT_OVERLAY)
                .isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        // Expected for the main activity to become invisible for 1-2 frames because the snapshot
        // covers it.
        flicker.assertLayers {
            visibleLayersShownMoreThanOneConsecutiveEntry(
                LayersTraceSubject.VISIBLE_FOR_MORE_THAN_ONE_ENTRY_IGNORE_LAYERS +
                        listOf(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            )
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
