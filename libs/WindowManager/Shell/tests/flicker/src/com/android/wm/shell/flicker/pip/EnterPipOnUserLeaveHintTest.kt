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
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
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
@Group3
class EnterPipOnUserLeaveHintTest(testSpec: FlickerTestParameter) : EnterPipTest(testSpec) {
    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
                device.wakeUpAndGoToHomeScreen()
                device.wakeUpAndGoToHomeScreen()
                pipApp.launchViaIntent(wmHelper)
                pipApp.enableEnterPipOnUserLeaveHint()
            }
            teardown {
                setRotation(Surface.ROTATION_0)
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
                pipApp.exit(wmHelper)
            }
            transitions {
                tapl.goHome()
            }
        }

    @Presubmit
    @Test
    override fun pipAppLayerAlwaysVisible() {
        if (!testSpec.isGesturalNavigation) super.pipAppLayerAlwaysVisible() else {
            // pip layer in gesture nav will disappear during transition
            testSpec.assertLayers {
                this.isVisible(pipApp)
                    .then().isInvisible(pipApp)
                    .then().isVisible(pipApp)
            }
        }
    }

    @Presubmit
    @Test
    override fun pipLayerReduces() {
        // in gestural nav the pip enters through alpha animation
        Assume.assumeFalse(testSpec.isGesturalNavigation)
        super.pipLayerReduces()
    }

    @Presubmit
    @Test
    override fun focusChanges() {
        // in gestural nav the focus goes to different activity on swipe up
        Assume.assumeFalse(testSpec.isGesturalNavigation)
        super.focusChanges()
    }

    @Presubmit
    @Test
    override fun entireScreenCovered() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        super.entireScreenCovered()
    }

    @FlakyTest(bugId = 227313015)
    @Test
    fun entireScreenCovered_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        super.entireScreenCovered()
    }

    @Presubmit
    @Test
    override fun pipLayerRemainInsideVisibleBounds() {
        if (!testSpec.isGesturalNavigation) super.pipLayerRemainInsideVisibleBounds() else {
            // pip layer in gesture nav will disappear during transition
            testSpec.assertLayersStart {
                this.visibleRegion(pipApp).coversAtMost(displayBounds)
            }
            testSpec.assertLayersEnd {
                this.visibleRegion(pipApp).coversAtMost(displayBounds)
            }
        }
    }
}
