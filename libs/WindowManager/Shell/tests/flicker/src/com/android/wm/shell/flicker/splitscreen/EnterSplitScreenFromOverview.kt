/*
 * Copyright (C) 2022 The Android Source Project
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
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.wm.shell.flicker.appWindowBecomesVisible
import com.android.wm.shell.flicker.layerBecomesVisible
import com.android.wm.shell.flicker.layerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsBecomesVisible
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerBecomesVisible
import com.android.wm.shell.flicker.splitScreenEntered
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test enter split screen from Overview.
 *
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenFromOverview`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterSplitScreenFromOverview(flicker: FlickerTest) : SplitScreenBase(flicker) {
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                primaryApp.launchViaIntent(wmHelper)
                secondaryApp.launchViaIntent(wmHelper)
                tapl.goHome()
                wmHelper
                    .StateSyncBuilder()
                    .withAppTransitionIdle()
                    .withHomeActivityVisible()
                    .waitForAndVerify()
            }
            transitions {
                SplitScreenUtils.splitFromOverview(tapl, device)
                SplitScreenUtils.waitForSplitComplete(wmHelper, primaryApp, secondaryApp)
            }
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun cujCompleted() = flicker.splitScreenEntered(primaryApp, secondaryApp, fromOtherApp = true)

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
            landscapePosLeft = tapl.isTablet,
            portraitPosTop = false
        )

    @Presubmit
    @Test
    fun secondaryAppBoundsBecomesVisible() {
        flicker.splitAppLayerBoundsBecomesVisible(
            secondaryApp,
            landscapePosLeft = !tapl.isTablet,
            portraitPosTop = true
        )
    }

    @Presubmit
    @Test
    fun primaryAppWindowBecomesVisible() = flicker.appWindowBecomesVisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowBecomesVisible() = flicker.appWindowBecomesVisible(secondaryApp)

    /** {@inheritDoc} */
    @FlakyTest(bugId = 251269324)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 252736515)
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
