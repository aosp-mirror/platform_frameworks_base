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
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.activityembedding.ActivityEmbeddingTestBase
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an activity with AlwaysExpand rule.
 *
 * Setup: Launch A|B in split with B being the secondary activity. Transitions: A start C with
 * alwaysExpand=true, expect C to launch in fullscreen and cover split A|B.
 *
 * To run this test: `atest FlickerTestsOther:MainActivityStartsSecondaryWithAlwaysExpandTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainActivityStartsSecondaryWithAlwaysExpandTest(flicker: LegacyFlickerTest) :
    ActivityEmbeddingTestBase(flicker) {

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup {
            tapl.setExpectedRotationCheckEnabled(false)
            // Launch a split
            testApp.launchViaIntent(wmHelper)
            testApp.launchSecondaryActivity(wmHelper)
            startDisplayBounds =
                wmHelper.currentState.layerState.physicalDisplayBounds ?: error("Display not found")
        }
        transitions {
            // Launch C with alwaysExpand
            testApp.launchAlwaysExpandActivity(wmHelper)
        }
        teardown {
            tapl.goHome()
            testApp.exit(wmHelper)
        }
    }

    @Ignore("Not applicable to this CUJ.") override fun navBarWindowIsVisibleAtStartAndEnd() {}

    @FlakyTest(bugId = 291575593) override fun entireScreenCovered() {}

    @Ignore("Not applicable to this CUJ.") override fun statusBarWindowIsAlwaysVisible() {}

    @Ignore("Not applicable to this CUJ.") override fun statusBarLayerPositionAtStartAndEnd() {}

    /** Transition begins with a split. */
    @FlakyTest(bugId = 286952194)
    @Test
    fun startsWithSplit() {
        flicker.assertWmStart {
            this.isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
        }
        flicker.assertWmStart {
            this.isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    /** Main activity should become invisible after being covered by always expand activity. */
    @FlakyTest(bugId = 286952194)
    @Test
    fun mainActivityLayerBecomesInvisible() {
        flicker.assertLayers {
            isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
                .then()
                .isInvisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
        }
    }

    /** Secondary activity should become invisible after being covered by always expand activity. */
    @FlakyTest(bugId = 286952194)
    @Test
    fun secondaryActivityLayerBecomesInvisible() {
        flicker.assertLayers {
            isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
                .then()
                .isInvisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
        }
    }

    /** At the end of transition always expand activity is in fullscreen. */
    @FlakyTest(bugId = 286952194)
    @Test
    fun endsWithAlwaysExpandActivityCoveringFullScreen() {
        flicker.assertWmEnd {
            this.visibleRegion(ActivityEmbeddingAppHelper.ALWAYS_EXPAND_ACTIVITY_COMPONENT)
                .coversExactly(startDisplayBounds)
        }
    }

    /** Always expand activity is on top of the split. */
    @FlakyTest(bugId = 286952194)
    @Presubmit
    @Test
    fun endsWithAlwaysExpandActivityOnTop() {
        flicker.assertWmEnd {
            this.isAppWindowOnTop(ActivityEmbeddingAppHelper.ALWAYS_EXPAND_ACTIVITY_COMPONENT)
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
