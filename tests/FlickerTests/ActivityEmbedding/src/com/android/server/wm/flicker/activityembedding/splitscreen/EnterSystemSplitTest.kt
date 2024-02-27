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

package com.android.server.wm.flicker.activityembedding.splitscreen

import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.tools.datatypes.Rect
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.traces.parsers.toFlickerComponent
import com.android.server.wm.flicker.activityembedding.ActivityEmbeddingTestBase
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.wm.shell.flicker.utils.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.utils.SplitScreenUtils
import com.android.wm.shell.flicker.utils.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.utils.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.utils.splitScreenDividerBecomesVisible
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test entering System SplitScreen with Activity Embedding Split and another app.
 *
 * Setup: Launch A|B in split and secondaryApp, return to home. Transitions: Let AE Split A|B enter
 * splitscreen with secondaryApp. Resulting in A|B|secondaryApp.
 *
 * To run this test: `atest FlickerTestsOther:EnterSystemSplitTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterSystemSplitTest(flicker: LegacyFlickerTest) : ActivityEmbeddingTestBase(flicker) {

    private val secondaryApp = SplitScreenUtils.getPrimary(instrumentation)
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            testApp.launchViaIntent(wmHelper)
            testApp.launchSecondaryActivity(wmHelper)
            secondaryApp.launchViaIntent(wmHelper)
            tapl.goHome()
            wmHelper
                .StateSyncBuilder()
                .withAppTransitionIdle()
                .withHomeActivityVisible()
                .waitForAndVerify()
            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds ?: error("Display not found")
        }
        transitions {
            SplitScreenUtils.enterSplit(
                wmHelper,
                tapl,
                device,
                testApp,
                secondaryApp,
                flicker.scenario.startRotation
            )
            SplitScreenUtils.waitForSplitComplete(wmHelper, testApp, secondaryApp)
        }
    }

    @Presubmit
    @Test
    fun splitScreenDividerBecomesVisible() = flicker.splitScreenDividerBecomesVisible()

    @Presubmit
    @Test
    fun activityEmbeddingSplitLayerBecomesVisible() {
        flicker.splitAppLayerBoundsIsVisibleAtEnd(
            testApp,
            landscapePosLeft = tapl.isTablet,
            portraitPosTop = false
        )
    }

    @Presubmit
    @Test
    fun activityEmbeddingSplitWindowBecomesVisible() = flicker.appWindowIsVisibleAtEnd(testApp)

    @Presubmit
    @Test
    fun secondaryLayerBecomesVisible() {
        flicker.splitAppLayerBoundsIsVisibleAtEnd(
            secondaryApp,
            landscapePosLeft = !tapl.isTablet,
            portraitPosTop = true
        )
    }

    @Presubmit
    @Test
    fun secondaryAppWindowBecomesVisible() = flicker.appWindowIsVisibleAtEnd(secondaryApp)

    /**
     * After the transition there should be both ActivityEmbedding activities,
     * SplitScreenPrimaryActivity and the system split divider on screen. Verify the layers are in
     * expected sizes.
     */
    @Presubmit
    @Test
    fun activityEmbeddingSplitSurfaceAreEven() {
        flicker.assertLayersEnd {
            val leftAELayerRegion =
                visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            val rightAELayerRegion =
                visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            val secondaryAppLayerRegion =
                visibleRegion(ActivityOptions.SplitScreen.Primary.COMPONENT.toFlickerComponent())
            val systemDivider = visibleRegion(SPLIT_SCREEN_DIVIDER_COMPONENT)
            leftAELayerRegion
                .plus(rightAELayerRegion.region)
                .plus(secondaryAppLayerRegion.region)
                .plus(systemDivider.region)
                .coversExactly(startDisplayBounds)
            check { "ActivityEmbeddingSplitHeight" }
                .that(leftAELayerRegion.region.height)
                .isEqual(rightAELayerRegion.region.height)
            check { "SystemSplitHeight" }
                .that(rightAELayerRegion.region.height)
                .isEqual(secondaryAppLayerRegion.region.height)
            // TODO(b/292283182): Remove this special case handling.
            check { "ActivityEmbeddingSplitWidth" }
                .that(Math.abs(leftAELayerRegion.region.width - rightAELayerRegion.region.width))
                .isLower(2)
            check { "SystemSplitWidth" }
                .that(
                    Math.abs(
                        secondaryAppLayerRegion.region.width - 2 * rightAELayerRegion.region.width
                    )
                )
                .isLower(2)
        }
    }

    /** Verify the windows are in expected sizes. */
    @Presubmit
    @Test
    fun activityEmbeddingSplitWindowsAreEven() {
        flicker.assertWmEnd {
            val leftAEWindowRegion =
                visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
            val rightAEWindowRegion =
                visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
            // There's no window for the divider bar.
            val secondaryAppLayerRegion =
                visibleRegion(ActivityOptions.SplitScreen.Primary.COMPONENT.toFlickerComponent())
            check { "ActivityEmbeddingSplitHeight" }
                .that(leftAEWindowRegion.region.height)
                .isEqual(rightAEWindowRegion.region.height)
            check { "SystemSplitHeight" }
                .that(rightAEWindowRegion.region.height)
                .isEqual(secondaryAppLayerRegion.region.height)
            check { "ActivityEmbeddingSplitWidth" }
                .that(Math.abs(leftAEWindowRegion.region.width - rightAEWindowRegion.region.width))
                .isLower(2)
            check { "SystemSplitWidth" }
                .that(
                    Math.abs(
                        secondaryAppLayerRegion.region.width - 2 * rightAEWindowRegion.region.width
                    )
                )
                .isLower(2)
        }
    }

    @Ignore("Not applicable to this CUJ.")
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {}

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
