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

package com.android.wm.shell.flicker.splitscreen

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.IwTest
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.appWindowIsVisibleAtStart
import com.android.wm.shell.flicker.appWindowKeepVisible
import com.android.wm.shell.flicker.layerKeepVisible
import com.android.wm.shell.flicker.splitAppLayerBoundsChanges
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtStart
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test resize split by dragging the divider bar.
 *
 * To run this test: `atest WMShellFlickerTests:DragDividerToResize`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DragDividerToResize(flicker: FlickerTest) : SplitScreenBase(flicker) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup { SplitScreenUtils.enterSplit(wmHelper, tapl, device, primaryApp, secondaryApp) }
            transitions { SplitScreenUtils.dragDividerToResizeAndWait(device, wmHelper) }
        }

    @Before
    fun before() {
        Assume.assumeTrue(tapl.isTablet || !flicker.scenario.isLandscapeOrSeascapeAtStart)
    }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun cujCompleted() {
        flicker.appWindowIsVisibleAtStart(primaryApp)
        flicker.appWindowIsVisibleAtStart(secondaryApp)
        flicker.splitScreenDividerIsVisibleAtStart()

        flicker.appWindowIsVisibleAtEnd(primaryApp)
        flicker.appWindowIsVisibleAtEnd(secondaryApp)
        flicker.splitScreenDividerIsVisibleAtEnd()

        // TODO(b/246490534): Add validation for resized app after withAppTransitionIdle is
        // robust enough to get the correct end state.
    }

    @Presubmit
    @Test
    fun splitScreenDividerKeepVisible() = flicker.layerKeepVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)

    @Presubmit
    @Test
    fun primaryAppLayerKeepVisible() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        flicker.layerKeepVisible(primaryApp)
    }

    @FlakyTest(bugId = 263213649)
    @Test
    fun primaryAppLayerKeepVisible_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        flicker.layerKeepVisible(primaryApp)
    }

    @Presubmit
    @Test
    fun secondaryAppLayerVisibilityChanges() {
        flicker.assertLayers {
            this.isVisible(secondaryApp)
                .then()
                .isInvisible(secondaryApp)
                .then()
                .isVisible(secondaryApp)
        }
    }

    @Presubmit @Test fun primaryAppWindowKeepVisible() = flicker.appWindowKeepVisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowKeepVisible() = flicker.appWindowKeepVisible(secondaryApp)

    @Presubmit
    @Test
    fun primaryAppBoundsChanges() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        flicker.splitAppLayerBoundsChanges(
            primaryApp,
            landscapePosLeft = true,
            portraitPosTop = false
        )
    }

    @FlakyTest(bugId = 263213649)
    @Test
    fun primaryAppBoundsChanges_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        flicker.splitAppLayerBoundsChanges(
            primaryApp,
            landscapePosLeft = true,
            portraitPosTop = false
        )
    }

    @Presubmit
    @Test
    fun secondaryAppBoundsChanges() =
        flicker.splitAppLayerBoundsChanges(
            secondaryApp,
            landscapePosLeft = false,
            portraitPosTop = true
        )

    /** {@inheritDoc} */
    @FlakyTest(bugId = 263213649)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
