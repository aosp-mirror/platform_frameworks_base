/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.traces.RegionSubject
import com.android.server.wm.traces.parser.toLayerName
import com.android.server.wm.traces.parser.toWindowName
import com.android.wm.shell.flicker.helpers.FixedAppHelper
import org.junit.Test

/**
 * Base class for pip tests with Launcher shelf height change
 */
abstract class MovePipShelfHeightTransition(
    testSpec: FlickerTestParameter
) : PipTransition(testSpec) {
    protected val taplInstrumentation = LauncherInstrumentation()
    protected val testApp = FixedAppHelper(instrumentation)

    /**
     * Checks if the window movement direction is valid
     */
    protected abstract fun assertRegionMovement(previous: RegionSubject, current: RegionSubject)

    /**
     * Checks [pipApp] window remains visible throughout the animation
     */
    @Postsubmit
    @Test
    open fun pipWindowIsAlwaysVisible() {
        testSpec.assertWm {
            isAppWindowVisible(pipApp.component)
        }
    }

    /**
     * Checks [pipApp] layer remains visible throughout the animation
     */
    @Postsubmit
    @Test
    open fun pipLayerIsAlwaysVisible() {
        testSpec.assertLayers {
            isVisible(pipApp.component)
        }
    }

    /**
     * Checks that the pip app window remains inside the display bounds throughout the whole
     * animation
     */
    @Postsubmit
    @Test
    open fun pipWindowRemainInsideVisibleBounds() {
        testSpec.assertWm {
            coversAtMost(displayBounds, pipApp.component)
        }
    }

    /**
     * Checks that the pip app layer remains inside the display bounds throughout the whole
     * animation
     */
    @Postsubmit
    @Test
    open fun pipLayerRemainInsideVisibleBounds() {
        testSpec.assertLayers {
            coversAtMost(displayBounds, pipApp.component)
        }
    }

    /**
     * Checks that the visible region of [pipApp] always moves in the correct direction
     * during the animation.
     */
    @Presubmit
    @Test
    open fun pipWindowMoves() {
        val windowName = pipApp.component.toWindowName()
        testSpec.assertWm {
            val pipWindowList = this.windowStates { it.name.contains(windowName) && it.isVisible }
            pipWindowList.zipWithNext { previous, current ->
                assertRegionMovement(previous.frame, current.frame)
            }
        }
    }

    /**
     * Checks that the visible region of [pipApp] always moves up during the animation
     */
    @Presubmit
    @Test
    open fun pipLayerMoves() {
        val layerName = pipApp.component.toLayerName()
        testSpec.assertLayers {
            val pipLayerList = this.layers { it.name.contains(layerName) && it.isVisible }
            pipLayerList.zipWithNext { previous, current ->
                assertRegionMovement(previous.visibleRegion, current.visibleRegion)
            }
        }
    }
}