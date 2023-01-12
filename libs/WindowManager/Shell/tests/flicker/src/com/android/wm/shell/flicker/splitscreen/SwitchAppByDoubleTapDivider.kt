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
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.service.PlatformConsts
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.appWindowIsVisibleAtStart
import com.android.wm.shell.flicker.layerIsVisibleAtEnd
import com.android.wm.shell.flicker.layerKeepVisible
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtStart
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test double tap the divider bar to switch the two apps.
 *
 * To run this test: `atest WMShellFlickerTests:SwitchAppByDoubleTapDivider`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SwitchAppByDoubleTapDivider(flicker: FlickerTest) : SplitScreenBase(flicker) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup { SplitScreenUtils.enterSplit(wmHelper, tapl, device, primaryApp, secondaryApp) }
            transitions {
                SplitScreenUtils.doubleTapDividerToSwitch(device)
                wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()

                waitForLayersToSwitch(wmHelper)
                waitForWindowsToSwitch(wmHelper)
            }
        }

    private fun waitForWindowsToSwitch(wmHelper: WindowManagerStateHelper) {
        wmHelper
            .StateSyncBuilder()
            .add("appWindowsSwitched") {
                val primaryAppWindow =
                    it.wmState.visibleWindows.firstOrNull { window ->
                        primaryApp.windowMatchesAnyOf(window)
                    }
                        ?: return@add false
                val secondaryAppWindow =
                    it.wmState.visibleWindows.firstOrNull { window ->
                        secondaryApp.windowMatchesAnyOf(window)
                    }
                        ?: return@add false

                if (isLandscape(flicker.scenario.endRotation)) {
                    return@add if (flicker.scenario.isTablet) {
                        secondaryAppWindow.frame.right <= primaryAppWindow.frame.left
                    } else {
                        primaryAppWindow.frame.right <= secondaryAppWindow.frame.left
                    }
                } else {
                    return@add if (flicker.scenario.isTablet) {
                        primaryAppWindow.frame.bottom <= secondaryAppWindow.frame.top
                    } else {
                        primaryAppWindow.frame.bottom <= secondaryAppWindow.frame.top
                    }
                }
            }
            .waitForAndVerify()
    }

    private fun waitForLayersToSwitch(wmHelper: WindowManagerStateHelper) {
        wmHelper
            .StateSyncBuilder()
            .add("appLayersSwitched") {
                val primaryAppLayer =
                    it.layerState.visibleLayers.firstOrNull { window ->
                        primaryApp.layerMatchesAnyOf(window)
                    }
                        ?: return@add false
                val secondaryAppLayer =
                    it.layerState.visibleLayers.firstOrNull { window ->
                        secondaryApp.layerMatchesAnyOf(window)
                    }
                        ?: return@add false

                val primaryVisibleRegion = primaryAppLayer.visibleRegion?.bounds ?: return@add false
                val secondaryVisibleRegion =
                    secondaryAppLayer.visibleRegion?.bounds ?: return@add false

                if (isLandscape(flicker.scenario.endRotation)) {
                    return@add if (flicker.scenario.isTablet) {
                        secondaryVisibleRegion.right <= primaryVisibleRegion.left
                    } else {
                        primaryVisibleRegion.right <= secondaryVisibleRegion.left
                    }
                } else {
                    return@add if (flicker.scenario.isTablet) {
                        primaryVisibleRegion.bottom <= secondaryVisibleRegion.top
                    } else {
                        primaryVisibleRegion.bottom <= secondaryVisibleRegion.top
                    }
                }
            }
            .waitForAndVerify()
    }

    private fun isLandscape(rotation: PlatformConsts.Rotation): Boolean {
        val displayBounds = WindowUtils.getDisplayBounds(rotation)
        return displayBounds.width > displayBounds.height
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

        // TODO(b/246490534): Add validation for switched app after withAppTransitionIdle is
        // robust enough to get the correct end state.
    }

    @Presubmit
    @Test
    fun splitScreenDividerKeepVisible() = flicker.layerKeepVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)

    @Presubmit @Test fun primaryAppLayerIsVisibleAtEnd() = flicker.layerIsVisibleAtEnd(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppLayerIsVisibleAtEnd() = flicker.layerIsVisibleAtEnd(secondaryApp)

    @Presubmit
    @Test
    fun primaryAppBoundsIsVisibleAtEnd() =
        flicker.splitAppLayerBoundsIsVisibleAtEnd(
            primaryApp,
            landscapePosLeft = !tapl.isTablet,
            portraitPosTop = true
        )

    @Presubmit
    @Test
    fun secondaryAppBoundsIsVisibleAtEnd() =
        flicker.splitAppLayerBoundsIsVisibleAtEnd(
            secondaryApp,
            landscapePosLeft = tapl.isTablet,
            portraitPosTop = false
        )

    @Presubmit
    @Test
    fun primaryAppWindowIsVisibleAtEnd() = flicker.appWindowIsVisibleAtEnd(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowIsVisibleAtEnd() = flicker.appWindowIsVisibleAtEnd(secondaryApp)

    /** {@inheritDoc} */
    @Postsubmit @Test override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                // TODO(b/176061063):The 3 buttons of nav bar do not exist in the hierarchy.
                supportedNavigationModes = listOf(PlatformConsts.NavBar.MODE_GESTURAL)
            )
        }
    }
}
