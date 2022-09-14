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

import android.platform.test.annotations.IwTest
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.wm.shell.flicker.appWindowBecomesVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import com.android.wm.shell.flicker.layerBecomesVisible
import com.android.wm.shell.flicker.layerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsBecomesVisible
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerBecomesVisible
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
@Group1
class EnterSplitScreenFromOverview(testSpec: FlickerTestParameter) : SplitScreenBase(testSpec) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                tapl.workspace.switchToOverview().dismissAllTasks()
                primaryApp.launchViaIntent(wmHelper)
                secondaryApp.launchViaIntent(wmHelper)
                tapl.goHome()
                wmHelper.StateSyncBuilder()
                    .withAppTransitionIdle()
                    .withHomeActivityVisible()
                    .waitForAndVerify()
            }
            transitions {
                SplitScreenHelper.splitFromOverview(tapl)
                SplitScreenHelper.waitForSplitComplete(wmHelper, primaryApp, secondaryApp)
            }
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun splitScreenDividerBecomesVisible() = testSpec.splitScreenDividerBecomesVisible()

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun primaryAppLayerIsVisibleAtEnd() = testSpec.layerIsVisibleAtEnd(primaryApp)

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun secondaryAppLayerBecomesVisible() = testSpec.layerBecomesVisible(secondaryApp)

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun primaryAppBoundsIsVisibleAtEnd() = testSpec.splitAppLayerBoundsIsVisibleAtEnd(
        primaryApp, landscapePosLeft = tapl.isTablet, portraitPosTop = false)

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun secondaryAppBoundsBecomesVisible() = testSpec.splitAppLayerBoundsBecomesVisible(
        secondaryApp, landscapePosLeft = !tapl.isTablet, portraitPosTop = true)

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun primaryAppWindowBecomesVisible() = testSpec.appWindowBecomesVisible(primaryApp)

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun secondaryAppWindowBecomesVisible() = testSpec.appWindowBecomesVisible(secondaryApp)

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun entireScreenCovered() =
        super.entireScreenCovered()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() =
        super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() =
        super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() =
        super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() =
        super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() =
        super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() =
        super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarWindowIsAlwaysVisible() =
        super.taskBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Postsubmit
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
