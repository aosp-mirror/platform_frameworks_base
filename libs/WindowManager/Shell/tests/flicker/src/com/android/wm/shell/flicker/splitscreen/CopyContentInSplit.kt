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
import android.platform.test.annotations.PlatinumTest
import android.platform.test.annotations.Presubmit
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.common.traces.component.EdgeExtensionComponentMatcher
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.wm.shell.flicker.ICommonAssertions
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.appWindowIsVisibleAtStart
import com.android.wm.shell.flicker.appWindowKeepVisible
import com.android.wm.shell.flicker.layerKeepVisible
import com.android.wm.shell.flicker.splitAppLayerBoundsKeepVisible
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtStart
import com.android.wm.shell.flicker.splitscreen.benchmark.CopyContentInSplitBenchmark
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test copy content from the left to the right side of the split-screen.
 *
 * To run this test: `atest WMShellFlickerTests:CopyContentInSplit`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CopyContentInSplit(override val flicker: FlickerTest) :
    CopyContentInSplitBenchmark(flicker), ICommonAssertions {
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            defaultSetup(this)
            defaultTeardown(this)
            thisTransition(this)
        }

    @PlatinumTest(focusArea = "sysui")
    @Presubmit
    @Test
    override fun cujCompleted() {
        flicker.appWindowIsVisibleAtStart(primaryApp)
        flicker.appWindowIsVisibleAtStart(textEditApp)
        flicker.splitScreenDividerIsVisibleAtStart()

        flicker.appWindowIsVisibleAtEnd(primaryApp)
        flicker.appWindowIsVisibleAtEnd(textEditApp)
        flicker.splitScreenDividerIsVisibleAtEnd()

        // The validation of copied text is already done in SplitScreenUtils.copyContentInSplit()
    }

    @Presubmit
    @Test
    fun splitScreenDividerKeepVisible() = flicker.layerKeepVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)

    @Presubmit @Test fun primaryAppLayerKeepVisible() = flicker.layerKeepVisible(primaryApp)

    @Presubmit @Test fun textEditAppLayerKeepVisible() = flicker.layerKeepVisible(textEditApp)

    @Presubmit
    @Test
    fun primaryAppBoundsKeepVisible() =
        flicker.splitAppLayerBoundsKeepVisible(
            primaryApp,
            landscapePosLeft = tapl.isTablet,
            portraitPosTop = false
        )

    @Presubmit
    @Test
    fun textEditAppBoundsKeepVisible() =
        flicker.splitAppLayerBoundsKeepVisible(
            textEditApp,
            landscapePosLeft = !tapl.isTablet,
            portraitPosTop = true
        )

    @Presubmit @Test fun primaryAppWindowKeepVisible() = flicker.appWindowKeepVisible(primaryApp)

    @Presubmit @Test fun textEditAppWindowKeepVisible() = flicker.appWindowKeepVisible(textEditApp)

    /** {@inheritDoc} */
    @Presubmit @Test override fun entireScreenCovered() = super.entireScreenCovered()

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
                        ComponentNameMatcher.IME_SNAPSHOT,
                        EdgeExtensionComponentMatcher(),
                        magnifierLayer,
                        popupWindowLayer
                    )
            )
        }
    }

    /** {@inheritDoc} */
    @FlakyTest(bugId = 264241018)
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
