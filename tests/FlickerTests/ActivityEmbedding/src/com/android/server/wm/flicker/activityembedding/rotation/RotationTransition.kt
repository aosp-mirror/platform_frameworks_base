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

package com.android.server.wm.flicker.activityembedding.rotation

import android.graphics.Rect
import android.platform.test.annotations.Presubmit
import android.tools.Position
import android.tools.flicker.legacy.FlickerBuilder
import android.tools.flicker.legacy.LegacyFlickerTest
import android.tools.traces.Condition
import android.tools.traces.DeviceStateDump
import android.tools.traces.component.ComponentNameMatcher
import com.android.server.wm.flicker.activityembedding.ActivityEmbeddingTestBase
import com.android.server.wm.flicker.helpers.setRotation
import org.junit.Test

/** Base class for app rotation tests */
abstract class RotationTransition(flicker: LegacyFlickerTest) : ActivityEmbeddingTestBase(flicker) {
    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup { this.setRotation(flicker.scenario.startRotation) }
        teardown { testApp.exit(wmHelper) }
        transitions {
            this.setRotation(flicker.scenario.endRotation)
            if (!flicker.scenario.isTablet) {
                wmHelper.StateSyncBuilder()
                    .add(navBarInPosition(flicker.scenario.isGesturalNavigation))
                    .waitForAndVerify()
            }
        }
    }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() {
        flicker.assertLayers {
            this.visibleLayersShownMoreThanOneConsecutiveEntry(
                ignoreLayers =
                    listOf(
                        ComponentNameMatcher.SPLASH_SCREEN,
                        ComponentNameMatcher.SNAPSHOT,
                        ComponentNameMatcher("", "SecondaryHomeHandle")
                    )
            )
        }
    }

    /** Checks that [testApp] layer covers the entire screen at the start of the transition */
    @Presubmit
    @Test
    open fun appLayerRotates_StartingPos() {
        flicker.assertLayersStart {
            this.entry.displays.map { display ->
                this.visibleRegion(testApp).coversExactly(display.layerStackSpace)
            }
        }
    }

    /** Checks that [testApp] layer covers the entire screen at the end of the transition */
    @Presubmit
    @Test
    open fun appLayerRotates_EndingPos() {
        flicker.assertLayersEnd {
            this.entry.displays.map { display ->
                this.visibleRegion(testApp).coversExactly(display.layerStackSpace)
            }
        }
    }

    override fun cujCompleted() {
        super.cujCompleted()
        appLayerRotates_StartingPos()
        appLayerRotates_EndingPos()
    }

    private fun navBarInPosition(isGesturalNavigation: Boolean): Condition<DeviceStateDump> {
        return Condition("navBarPosition") { dump ->
            val display =
                dump.layerState.displays.filterNot { it.isOff }.minByOrNull { it.id }
                    ?: error("There is no display!")
            val displayArea = display.layerStackSpace
            val navBarPosition = display.navBarPosition(isGesturalNavigation)
            val navBarRegion = dump.layerState
                .getLayerWithBuffer(ComponentNameMatcher.NAV_BAR)
                ?.visibleRegion?.bounds ?: Rect()

            when (navBarPosition) {
                Position.TOP ->
                    navBarRegion.top == displayArea.top &&
                        navBarRegion.left == displayArea.left &&
                        navBarRegion.right == displayArea.right
                Position.BOTTOM ->
                    navBarRegion.bottom == displayArea.bottom &&
                        navBarRegion.left == displayArea.left &&
                        navBarRegion.right == displayArea.right
                Position.LEFT ->
                    navBarRegion.left == displayArea.left &&
                        navBarRegion.top == displayArea.top &&
                        navBarRegion.bottom == displayArea.bottom
                Position.RIGHT ->
                    navBarRegion.right == displayArea.right &&
                        navBarRegion.top == displayArea.top &&
                        navBarRegion.bottom == displayArea.bottom
                else -> error("Unknown position $navBarPosition")
            }
        }
    }
}
