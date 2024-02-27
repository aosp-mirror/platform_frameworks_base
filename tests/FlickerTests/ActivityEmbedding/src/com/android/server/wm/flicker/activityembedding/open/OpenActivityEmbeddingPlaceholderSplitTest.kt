/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.tools.flicker.junit.FlickerParametersRunnerFactory
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.flicker.legacy.LegacyFlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.activityembedding.ActivityEmbeddingTestBase
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test opening an activity that will launch another activity as ActivityEmbedding placeholder in
 * split.
 *
 * To run this test: `atest FlickerTestsOther:OpenActivityEmbeddingPlaceholderSplitTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OpenActivityEmbeddingPlaceholderSplitTest(flicker: LegacyFlickerTest) :
    ActivityEmbeddingTestBase(flicker) {

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            testApp.launchViaIntent(wmHelper)
        }
        transitions { testApp.launchPlaceholderSplit(wmHelper) }
        teardown {
            tapl.goHome()
            testApp.exit(wmHelper)
        }
    }

    /** Main activity should become invisible after launching the placeholder primary activity. */
    @Presubmit
    @Test
    fun mainActivityWindowBecomesInvisible() {
        flicker.assertWm {
            isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .then()
                .isAppWindowInvisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
        }
    }

    /** Main activity should become invisible after launching the placeholder primary activity. */
    @Presubmit
    @Test
    fun mainActivityLayerBecomesInvisible() {
        flicker.assertLayers {
            isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .then()
                .isInvisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
        }
    }

    /**
     * Placeholder primary and secondary should become visible after launch. The windows are not
     * necessarily to become visible at the same time.
     */
    @Presubmit
    @Test
    fun placeholderSplitWindowsBecomeVisible() {
        flicker.assertWm {
            notContains(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
                .then()
                .isAppWindowInvisible(
                    ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT,
                    isOptional = true
                )
                .then()
                .isAppWindowVisible(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
        }
        flicker.assertWm {
            notContains(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
                .then()
                .isAppWindowInvisible(
                    ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT,
                    isOptional = true
                )
                .then()
                .isAppWindowVisible(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
        }
    }

    /** Placeholder primary and secondary should become visible together after launch. */
    @Presubmit
    @Test
    fun placeholderSplitLayersBecomeVisible() {
        flicker.assertLayers {
            isInvisible(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
            isInvisible(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
                .then()
                .isVisible(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
                .isVisible(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
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
