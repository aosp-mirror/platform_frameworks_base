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
import android.platform.test.annotations.Presubmit
import android.tools.common.NavBar
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.wm.shell.flicker.ICommonAssertions
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.layerBecomesVisible
import com.android.wm.shell.flicker.layerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsBecomesVisibleByDrag
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerBecomesVisible
import com.android.wm.shell.flicker.splitscreen.benchmark.EnterSplitScreenByDragFromShortcutBenchmark
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test enter split screen by dragging a shortcut. This test is only for large screen devices.
 *
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenByDragFromShortcut`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterSplitScreenByDragFromShortcut(override val flicker: FlickerTest) :
    EnterSplitScreenByDragFromShortcutBenchmark(flicker), ICommonAssertions {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            defaultSetup(this)
            defaultTeardown(this)
            thisTransition(this)
        }

    @Presubmit
    @Test
    fun splitScreenDividerBecomesVisible() = flicker.splitScreenDividerBecomesVisible()

    @Presubmit @Test fun primaryAppLayerIsVisibleAtEnd() = flicker.layerIsVisibleAtEnd(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppLayerBecomesVisible() = flicker.layerBecomesVisible(secondaryApp)

    @Presubmit
    @Test
    fun primaryAppBoundsIsVisibleAtEnd() =
        flicker.splitAppLayerBoundsIsVisibleAtEnd(
            primaryApp,
            landscapePosLeft = false,
            portraitPosTop = false
        )

    @Presubmit
    @Test
    fun secondaryAppBoundsBecomesVisible() =
        flicker.splitAppLayerBoundsBecomesVisibleByDrag(secondaryApp)

    @Presubmit
    @Test
    fun primaryAppWindowIsVisibleAtEnd() = flicker.appWindowIsVisibleAtEnd(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowBecomesVisible() {
        flicker.assertWm {
            this.notContains(secondaryApp)
                .then()
                .isAppWindowInvisible(secondaryApp, isOptional = true)
                .then()
                .isAppWindowVisible(secondaryApp)
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 241523824)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                // TODO(b/176061063):The 3 buttons of nav bar do not exist in the hierarchy.
                supportedNavigationModes = listOf(NavBar.MODE_GESTURAL)
            )
        }
    }
}
