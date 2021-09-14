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
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import org.junit.Test

/**
 * Base class for pip expand tests
 */
abstract class ExitPipToAppTransition(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    protected val testApp = FixedAppHelper(instrumentation)

    /**
     * Checks that the pip app window remains inside the display bounds throughout the whole
     * animation
     */
    @Presubmit
    @Test
    open fun pipAppWindowRemainInsideVisibleBounds() {
        testSpec.assertWm {
            coversAtMost(displayBounds, pipApp.component)
        }
    }

    /**
     * Checks that the pip app layer remains inside the display bounds throughout the whole
     * animation
     */
    @Presubmit
    @Test
    open fun pipAppLayerRemainInsideVisibleBounds() {
        testSpec.assertLayers {
            coversAtMost(displayBounds, pipApp.component)
        }
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
            isAppWindowVisible(testApp.component, ignoreActivity = true)
                    .isAppWindowOnTop(pipApp.component)
                    .then()
                    .isAppWindowInvisible(testApp.component)
                    .isAppWindowVisible(pipApp.component)
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
            isVisible(testApp.component)
                    .isVisible(pipApp.component)
                    .then()
                    .isInvisible(testApp.component)
                    .isVisible(pipApp.component)
        }
    }

    /**
     * Checks that the visible region of [testApp] plus the visible region of [pipApp]
     * cover the full display area at the start of the transition
     */
    @Presubmit
    @Test
    open fun testPlusPipAppsCoverFullScreenAtStart() {
        testSpec.assertLayersStart {
            val pipRegion = visibleRegion(pipApp.component).region
            visibleRegion(testApp.component)
                    .plus(pipRegion)
                    .coversExactly(displayBounds)
        }
    }

    /**
     * Checks that the visible region of [pipApp] covers the full display area at the end of
     * the transition
     */
    @Presubmit
    @Test
    open fun pipAppCoversFullScreenAtEnd() {
        testSpec.assertLayersEnd {
            visibleRegion(pipApp.component).coversExactly(displayBounds)
        }
    }

    /**
     * Checks that the visible region of [pipApp] always expands during the animation
     */
    @Presubmit
    @Test
    open fun pipLayerExpands() {
        val layerName = pipApp.component.toLayerName()
        testSpec.assertLayers {
            val pipLayerList = this.layers { it.name.contains(layerName) && it.isVisible }
            pipLayerList.zipWithNext { previous, current ->
                current.visibleRegion.coversAtLeast(previous.visibleRegion.region)
            }
        }
    }
}
