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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test entering pip from an app via auto-enter property when navigating to home.
 *
 * To run this test: `atest WMShellFlickerTests:AutoEnterPipOnGoToHomeTest`
 *
 * Actions:
 *     Launch an app in full screen
 *     Select "Auto-enter PiP" radio button
 *     Press Home button or swipe up to go Home and put [pipApp] in pip mode
 *
 * Notes:
 *     1. All assertions are inherited from [EnterPipTest]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@FlakyTest(bugId = 238367575)
@Group3
class AutoEnterPipOnGoToHomeTest(testSpec: FlickerTestParameter) : EnterPipTest(testSpec) {
    protected val taplInstrumentation = LauncherInstrumentation()
    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setupAndTeardown(this)
            setup {
                eachRun {
                    pipApp.launchViaIntent(wmHelper)
                    pipApp.enableAutoEnterForPipActivity()
                }
            }
            teardown {
                eachRun {
                    // close gracefully so that onActivityUnpinned() can be called before force exit
                    pipApp.closePipWindow(wmHelper)
                    pipApp.exit(wmHelper)
                }
            }
            transitions {
                taplInstrumentation.goHome()
            }
        }

    override fun pipLayerReduces() {
        val layerName = pipApp.component.toLayerName()
        testSpec.assertLayers {
            val pipLayerList = this.layers { it.name.contains(layerName) && it.isVisible }
            pipLayerList.zipWithNext { previous, current ->
                current.visibleRegion.notBiggerThan(previous.visibleRegion.region)
            }
        }
    }

    /**
     * Checks that [pipApp] window is animated towards default position in right bottom corner
     */
    @Test
    fun pipLayerMovesTowardsRightBottomCorner() {
        // in gestural nav the swipe makes PiP first go upwards
        Assume.assumeFalse(testSpec.isGesturalNavigation)
        val layerName = pipApp.component.toLayerName()
        testSpec.assertLayers {
            val pipLayerList = this.layers { it.name.contains(layerName) && it.isVisible }
            // Pip animates towards the right bottom corner, but because it is being resized at the
            // same time, it is possible it shrinks first quickly below the default position and get
            // moved up after that in just few last frames
            pipLayerList.zipWithNext { previous, current ->
                current.visibleRegion.isToTheRightBottom(previous.visibleRegion.region, 3)
            }
        }
    }

    override fun focusChanges() {
        // in gestural nav the focus goes to different activity on swipe up
        Assume.assumeFalse(testSpec.isGesturalNavigation)
        super.focusChanges()
    }
}
