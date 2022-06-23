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

import androidx.test.filters.RequiresDevice
import com.android.launcher3.tapl.LauncherInstrumentation
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test entering pip from an app via [onUserLeaveHint] and by navigating to home.
 *
 * To run this test: `atest WMShellFlickerTests:EnterPipOnUserLeaveHintTest`
 *
 * Actions:
 *     Launch an app in full screen
 *     Select "Via code behind" radio button
 *     Press Home button to put [pipApp] in pip mode
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
@Group3
class EnterPipOnUserLeaveHintTest(testSpec: FlickerTestParameter) : EnterPipTest(testSpec) {
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
                    pipApp.enableEnterPipOnUserLeaveHint()
                }
            }
            teardown {
                eachRun {
                    pipApp.exit(wmHelper)
                }
            }
            transitions {
                taplInstrumentation.goHome()
            }
        }

    override fun pipAppLayerAlwaysVisible() {
        if (!testSpec.isGesturalNavigation) super.pipAppLayerAlwaysVisible() else {
            // pip layer in gesture nav will disappear during transition
            testSpec.assertLayers {
                this.isVisible(pipApp.component)
                    .then().isInvisible(pipApp.component)
                    .then().isVisible(pipApp.component)
            }
        }
    }

    override fun pipLayerReduces() {
        // in gestural nav the pip enters through alpha animation
        Assume.assumeFalse(testSpec.isGesturalNavigation)
        super.pipLayerReduces()
    }

    override fun focusChanges() {
        // in gestural nav the focus goes to different activity on swipe up
        Assume.assumeFalse(testSpec.isGesturalNavigation)
        super.focusChanges()
    }

    override fun pipLayerRemainInsideVisibleBounds() {
        if (!testSpec.isGesturalNavigation) super.pipLayerRemainInsideVisibleBounds() else {
            // pip layer in gesture nav will disappear during transition
            testSpec.assertLayersStart {
                this.visibleRegion(pipApp.component).coversAtMost(displayBounds)
            }
            testSpec.assertLayersEnd {
                this.visibleRegion(pipApp.component).coversAtMost(displayBounds)
            }
        }
    }
}
