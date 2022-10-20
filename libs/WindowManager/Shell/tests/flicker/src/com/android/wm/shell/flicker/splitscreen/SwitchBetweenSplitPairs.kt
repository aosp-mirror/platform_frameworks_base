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
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appWindowBecomesInvisible
import com.android.wm.shell.flicker.appWindowBecomesVisible
import com.android.wm.shell.flicker.appWindowIsInvisibleAtEnd
import com.android.wm.shell.flicker.appWindowIsVisibleAtStart
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.layerBecomesInvisible
import com.android.wm.shell.flicker.layerBecomesVisible
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsSnapToDivider
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtStart
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtEnd
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switch between two split pairs.
 *
 * To run this test: `atest WMShellFlickerTests:SwitchBetweenSplitPairs`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SwitchBetweenSplitPairs(testSpec: FlickerTestParameter) : SplitScreenBase(testSpec) {
    private val thirdApp = SplitScreenUtils.getIme(instrumentation)
    private val fourthApp = SplitScreenUtils.getSendNotification(instrumentation)

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                SplitScreenUtils.enterSplit(wmHelper, tapl, device, primaryApp, secondaryApp)
                SplitScreenUtils.enterSplit(wmHelper, tapl, device, thirdApp, fourthApp)
                SplitScreenUtils.waitForSplitComplete(wmHelper, thirdApp, fourthApp)
            }
            transitions {
                tapl.launchedAppState.quickSwitchToPreviousApp()
                SplitScreenUtils.waitForSplitComplete(wmHelper, primaryApp, secondaryApp)
            }
            teardown {
                thirdApp.exit(wmHelper)
                fourthApp.exit(wmHelper)
            }
        }

    @Postsubmit
    @Test
    fun cujCompleted() {
        testSpec.appWindowIsVisibleAtStart(thirdApp)
        testSpec.appWindowIsVisibleAtStart(fourthApp)
        testSpec.splitScreenDividerIsVisibleAtStart()

        testSpec.appWindowIsVisibleAtEnd(primaryApp)
        testSpec.appWindowIsVisibleAtEnd(secondaryApp)
        testSpec.appWindowIsInvisibleAtEnd(thirdApp)
        testSpec.appWindowIsInvisibleAtEnd(fourthApp)
        testSpec.splitScreenDividerIsVisibleAtEnd()
    }

    @Postsubmit
    @Test
    fun splitScreenDividerInvisibleAtMiddle() =
        testSpec.assertLayers {
            this.isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
                .then()
                .isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
                .then()
                .isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
        }

    @FlakyTest(bugId = 247095572)
    @Test
    fun primaryAppLayerBecomesVisible() = testSpec.layerBecomesVisible(primaryApp)

    @FlakyTest(bugId = 247095572)
    @Test
    fun secondaryAppLayerBecomesVisible() = testSpec.layerBecomesVisible(secondaryApp)

    @FlakyTest(bugId = 247095572)
    @Test
    fun thirdAppLayerBecomesInvisible() = testSpec.layerBecomesInvisible(thirdApp)

    @FlakyTest(bugId = 247095572)
    @Test
    fun fourthAppLayerBecomesInvisible() = testSpec.layerBecomesInvisible(fourthApp)

    @Postsubmit
    @Test
    fun primaryAppBoundsIsVisibleAtEnd() =
        testSpec.splitAppLayerBoundsIsVisibleAtEnd(
            primaryApp,
            landscapePosLeft = tapl.isTablet,
            portraitPosTop = false
        )

    @Postsubmit
    @Test
    fun secondaryAppBoundsIsVisibleAtEnd() =
        testSpec.splitAppLayerBoundsIsVisibleAtEnd(
            secondaryApp,
            landscapePosLeft = !tapl.isTablet,
            portraitPosTop = true
        )

    @Postsubmit
    @Test
    fun thirdAppBoundsIsVisibleAtBegin() =
        testSpec.assertLayersStart {
            this.splitAppLayerBoundsSnapToDivider(
                thirdApp,
                landscapePosLeft = tapl.isTablet,
                portraitPosTop = false,
                testSpec.startRotation
            )
        }

    @Postsubmit
    @Test
    fun fourthAppBoundsIsVisibleAtBegin() =
        testSpec.assertLayersStart {
            this.splitAppLayerBoundsSnapToDivider(
                fourthApp,
                landscapePosLeft = !tapl.isTablet,
                portraitPosTop = true,
                testSpec.startRotation
            )
        }

    @Postsubmit
    @Test
    fun primaryAppWindowBecomesVisible() = testSpec.appWindowBecomesVisible(primaryApp)

    @Postsubmit
    @Test
    fun secondaryAppWindowBecomesVisible() = testSpec.appWindowBecomesVisible(secondaryApp)

    @Postsubmit
    @Test
    fun thirdAppWindowBecomesVisible() = testSpec.appWindowBecomesInvisible(thirdApp)

    @Postsubmit
    @Test
    fun fourthAppWindowBecomesVisible() = testSpec.appWindowBecomesInvisible(fourthApp)

    /** {@inheritDoc} */
    @FlakyTest(bugId = 251268711)
    @Test
    override fun entireScreenCovered() =
        super.entireScreenCovered()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() =
        super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 206753786)
    @Test
    override fun navBarLayerPositionAtStartAndEnd() =
        super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() =
        super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() =
        super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() =
        super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() =
        super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun taskBarWindowIsAlwaysVisible() =
        super.taskBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests()
        }
    }
}
