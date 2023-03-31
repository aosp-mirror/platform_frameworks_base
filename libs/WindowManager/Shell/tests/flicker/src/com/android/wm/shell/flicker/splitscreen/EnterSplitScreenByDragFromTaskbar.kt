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
import android.tools.common.NavBar
import android.tools.device.flicker.isShellTransitionsEnabled
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appWindowBecomesVisible
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.layerBecomesVisible
import com.android.wm.shell.flicker.layerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsBecomesVisibleByDrag
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerBecomesVisible
import com.android.wm.shell.flicker.splitScreenEntered
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test enter split screen by dragging app icon from taskbar. This test is only for large screen
 * devices.
 *
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenByDragFromTaskbar`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterSplitScreenByDragFromTaskbar(flicker: FlickerTest) : SplitScreenBase(flicker) {

    @Before
    fun before() {
        Assume.assumeTrue(tapl.isTablet)
    }

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                tapl.goHome()
                SplitScreenUtils.createShortcutOnHotseatIfNotExist(tapl, secondaryApp.appName)
                primaryApp.launchViaIntent(wmHelper)
            }
            transitions {
                tapl.launchedAppState.taskbar
                    .getAppIcon(secondaryApp.appName)
                    .dragToSplitscreen(secondaryApp.`package`, primaryApp.`package`)
                SplitScreenUtils.waitForSplitComplete(wmHelper, primaryApp, secondaryApp)
            }
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun cujCompleted() =
        flicker.splitScreenEntered(
            primaryApp,
            secondaryApp,
            fromOtherApp = false,
            appExistAtStart = false
        )

    @Presubmit
    @Test
    fun splitScreenDividerBecomesVisible() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        flicker.splitScreenDividerBecomesVisible()
    }

    // TODO(b/245472831): Back to splitScreenDividerBecomesVisible after shell transition ready.
    @Presubmit
    @Test
    fun splitScreenDividerIsVisibleAtEnd_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        flicker.assertLayersEnd { this.isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT) }
    }

    @Presubmit @Test fun primaryAppLayerIsVisibleAtEnd() = flicker.layerIsVisibleAtEnd(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppLayerBecomesVisible() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        flicker.assertLayers {
            this.isInvisible(secondaryApp)
                .then()
                .isVisible(secondaryApp)
                .then()
                .isInvisible(secondaryApp)
                .then()
                .isVisible(secondaryApp)
        }
    }

    // TODO(b/245472831): Align to legacy transition after shell transition ready.
    @Presubmit
    @Test
    fun secondaryAppLayerBecomesVisible_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        flicker.layerBecomesVisible(secondaryApp)
    }

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
    fun secondaryAppWindowBecomesVisible() = flicker.appWindowBecomesVisible(secondaryApp)

    /** {@inheritDoc} */
    @Postsubmit @Test override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() = super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun taskBarWindowIsAlwaysVisible() = super.taskBarWindowIsAlwaysVisible()

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
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                supportedNavigationModes = listOf(NavBar.MODE_GESTURAL)
            )
        }
    }
}
