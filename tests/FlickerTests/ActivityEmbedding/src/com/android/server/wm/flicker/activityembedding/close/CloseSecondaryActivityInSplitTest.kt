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

package com.android.server.wm.flicker.activityembedding.close

import android.graphics.Rect
import android.platform.test.annotations.Presubmit
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import android.tools.traces.component.ComponentNameMatcher
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.activityembedding.ActivityEmbeddingTestBase
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test closing a secondary activity in a split.
 *
 * Setup: Launch A|B in split with B being the secondary activity. Transitions: Finish B and expect
 * A to become fullscreen.
 *
 * To run this test: `atest FlickerTestsOther:CloseSecondaryActivityInSplitTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CloseSecondaryActivityInSplitTest(flicker: LegacyFlickerTest) :
    ActivityEmbeddingTestBase(flicker) {

    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            // Launches fullscreen A.
            testApp.launchViaIntent(wmHelper)
            // Launches a split A|B and waits for both activities to show.
            testApp.launchSecondaryActivity(wmHelper)
            // Get fullscreen bounds
            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds
                    ?: error("Can't get display bounds")
        }
        transitions {
            // Finish secondary activity B.
            testApp.finishSecondaryActivity(wmHelper)
            // Expect the main activity A to expand into fullscreen.
            wmHelper.StateSyncBuilder().withFullScreenApp(testApp).waitForAndVerify()
        }
        teardown {
            tapl.goHome()
            testApp.exit(wmHelper)
        }
    }

    /** Main activity is always visible and becomes fullscreen in the end. */
    @Presubmit
    @Test
    fun mainActivityWindowBecomesFullScreen() {
        flicker.assertWm { isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
        flicker.assertWmEnd {
            this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .coversExactly(startDisplayBounds)
        }
    }

    /** Main activity surface is animated from split to fullscreen. */
    @Presubmit
    @Test
    fun mainActivityLayerIsAlwaysVisible() {
        flicker.assertLayers {
            isVisible(
                ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT.or(
                    ComponentNameMatcher.TRANSITION_SNAPSHOT
                )
            )
        }
        flicker.assertLayersEnd {
            isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .isInvisible(ComponentNameMatcher.TRANSITION_SNAPSHOT)
        }
    }

    /** Secondary activity should destroy and become invisible. */
    @Presubmit
    @Test
    fun secondaryActivityWindowFinishes() {
        flicker.assertWm {
            contains(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .then()
                .notContains(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    @Presubmit
    @Test
    fun secondaryActivityLayerFinishes() {
        flicker.assertLayers {
            isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .then()
                .isInvisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
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
