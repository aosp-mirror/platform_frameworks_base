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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.Postsubmit
import android.tools.common.Rotation
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.graphics.Rect
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import android.tools.device.flicker.rules.RemoveAllTasksButHomeRule
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test the snapping of a PIP window via dragging, releasing, and checking its final location.
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipDragThenSnapTest(flicker: FlickerTest) : PipTransition(flicker){
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            val stringExtras: Map<String, String> =
                mapOf(ActivityOptions.Pip.EXTRA_ENTER_PIP to "true")

            // cache the starting bounds here
            val startBounds = Rect()

            setup {
                // Launch the PIP activity and wait for it to enter PiP mode
                setRotation(Rotation.ROTATION_0)
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
                pipApp.launchViaIntentAndWaitForPip(wmHelper, stringExtras = stringExtras)

                val initRegion = pipApp.dragPipWindowAwayFromEdge(wmHelper, 50)
                startBounds
                    .set(initRegion.left, initRegion.top, initRegion.right, initRegion.bottom)
            }
            transitions {
                // continue the transition until the PIP snaps
                pipApp.waitForPipToSnapTo(wmHelper, startBounds)
            }
        }

    /** Checks that the visible region area of [pipApp] always moves right during the animation. */
    @Postsubmit
    @Test
    fun pipLayerMovesRight() {
        flicker.assertLayers {
            val pipLayerList = layers { pipApp.layerMatchesAnyOf(it) && it.isVisible }
            pipLayerList.zipWithNext { previous, current ->
                current.visibleRegion.isToTheRight(previous.visibleRegion.region)
            }
        }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedRotations = listOf(Rotation.ROTATION_0)
            )
        }
    }
}