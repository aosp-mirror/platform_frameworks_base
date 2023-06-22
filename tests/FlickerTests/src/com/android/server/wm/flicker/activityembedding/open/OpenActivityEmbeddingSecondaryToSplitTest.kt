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

package com.android.server.wm.flicker.activityembedding

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
 * Test opening a secondary activity that will split with the main activity.
 *
 * To run this test: `atest FlickerTests:OpenActivityEmbeddingSecondaryToSplitTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenActivityEmbeddingSecondaryToSplitTest(flicker: LegacyFlickerTest) :
    ActivityEmbeddingTestBase(flicker) {

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            testApp.launchViaIntent(wmHelper)
        }
        transitions { testApp.launchSecondaryActivity(wmHelper) }
        teardown {
            tapl.goHome()
            testApp.exit(wmHelper)
        }
    }

    /** Main activity should remain visible when enter split from fullscreen. */
    @Presubmit
    @Test
    fun mainActivityWindowIsAlwaysVisible() {
        flicker.assertWm { isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT) }
    }

    /**
     * Main activity surface is animated from fullscreen to ActivityEmbedding split. During the
     * transition, there is a period of time that it is covered by a snapshot of itself.
     */
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

    /** Secondary activity should become visible after launching into split. */
    @Presubmit
    @Test
    fun secondaryActivityWindowBecomesVisible() {
        flicker.assertWm {
            notContains(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .then()
                .isAppWindowInvisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .then()
                .isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    /** Secondary activity should become visible after launching into split. */
    @Presubmit
    @Test
    fun secondaryActivityLayerBecomesVisible() {
        flicker.assertLayers {
            isInvisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .then()
                .isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
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
        fun getParams() = LegacyFlickerTestFactory.nonRotationTests()
    }
}
