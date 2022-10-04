/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.Presubmit
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.helpers.FixedOrientationAppHelper
import org.junit.Test

/** Base class for pip expand tests */
abstract class ExitPipToAppTransition(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    protected val testApp = FixedOrientationAppHelper(instrumentation)

    /**
     * Checks that the pip app window remains inside the display bounds throughout the whole
     * animation
     */
    @Presubmit
    @Test
    open fun pipAppWindowRemainInsideVisibleBounds() {
        testSpec.assertWmVisibleRegion(pipApp) { coversAtMost(displayBounds) }
    }

    /**
     * Checks that the pip app layer remains inside the display bounds throughout the whole
     * animation
     */
    @Presubmit
    @Test
    open fun pipAppLayerRemainInsideVisibleBounds() {
        testSpec.assertLayersVisibleRegion(pipApp) { coversAtMost(displayBounds) }
    }

    /**
     * Checks both app windows are visible at the start of the transition (with [pipApp] on top).
     * Then, during the transition, [testApp] becomes invisible and [pipApp] remains visible
     */
    @Presubmit
    @Test
    open fun showBothAppWindowsThenHidePip() {
        testSpec.assertWm {
            // when the activity is STOPPING, sometimes it becomes invisible in an entry before
            // the window, sometimes in the same entry. This occurs because we log 1x per frame
            // thus we ignore activity here
            isAppWindowVisible(testApp)
                .isAppWindowOnTop(pipApp)
                .then()
                .isAppWindowInvisible(testApp)
                .isAppWindowVisible(pipApp)
        }
    }

    /**
     * Checks both app layers are visible at the start of the transition. Then, during the
     * transition, [testApp] becomes invisible and [pipApp] remains visible
     */
    @Presubmit
    @Test
    open fun showBothAppLayersThenHidePip() {
        testSpec.assertLayers {
            isVisible(testApp).isVisible(pipApp).then().isInvisible(testApp).isVisible(pipApp)
        }
    }

    /**
     * Checks that the visible region of [testApp] plus the visible region of [pipApp] cover the
     * full display area at the start of the transition
     */
    @Presubmit
    @Test
    open fun testPlusPipAppsCoverFullScreenAtStart() {
        testSpec.assertLayersStart {
            val pipRegion = visibleRegion(pipApp).region
            visibleRegion(testApp).plus(pipRegion).coversExactly(displayBounds)
        }
    }

    /**
     * Checks that the visible region oft [pipApp] covers the full display area at the end of the
     * transition
     */
    @Presubmit
    @Test
    open fun pipAppCoversFullScreenAtEnd() {
        testSpec.assertLayersEnd { visibleRegion(pipApp).coversExactly(displayBounds) }
    }

    /** Checks that the visible region of [pipApp] always expands during the animation */
    @Presubmit
    @Test
    open fun pipLayerExpands() {
        testSpec.assertLayers {
            val pipLayerList = this.layers { pipApp.layerMatchesAnyOf(it) && it.isVisible }
            pipLayerList.zipWithNext { previous, current ->
                current.visibleRegion.coversAtLeast(previous.visibleRegion.region)
            }
        }
    }

    /** {@inheritDoc} */
    @Presubmit @Test override fun entireScreenCovered() = super.entireScreenCovered()
}
