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
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.service.PlatformConsts
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
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
 * Test enter split screen by dragging app icon from notification. This test is only for large
 * screen devices.
 *
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenByDragFromNotification`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterSplitScreenByDragFromNotification(flicker: FlickerTest) : SplitScreenBase(flicker) {

    private val sendNotificationApp = SplitScreenUtils.getSendNotification(instrumentation)

    @Before
    fun before() {
        Assume.assumeTrue(tapl.isTablet)
    }

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                // Send a notification
                sendNotificationApp.launchViaIntent(wmHelper)
                sendNotificationApp.postNotification(wmHelper)
                tapl.goHome()
                primaryApp.launchViaIntent(wmHelper)
            }
            transitions {
                SplitScreenUtils.dragFromNotificationToSplit(instrumentation, device, wmHelper)
                SplitScreenUtils.waitForSplitComplete(wmHelper, primaryApp, sendNotificationApp)
            }
            teardown { sendNotificationApp.exit(wmHelper) }
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun cujCompleted() = flicker.splitScreenEntered(primaryApp, secondaryApp, fromOtherApp = false)

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
            this.isInvisible(sendNotificationApp)
                .then()
                .isVisible(sendNotificationApp)
                .then()
                .isInvisible(sendNotificationApp)
                .then()
                .isVisible(sendNotificationApp)
        }
    }

    // TODO(b/245472831): Align to legacy transition after shell transition ready.
    @Presubmit
    @Test
    fun secondaryAppLayerBecomesVisible_ShellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        flicker.layerBecomesVisible(sendNotificationApp)
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
        flicker.splitAppLayerBoundsBecomesVisibleByDrag(sendNotificationApp)

    @Presubmit
    @Test
    fun primaryAppWindowIsVisibleAtEnd() = flicker.appWindowIsVisibleAtEnd(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowIsVisibleAtEnd() = flicker.appWindowIsVisibleAtEnd(sendNotificationApp)

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
                // TODO(b/176061063):The 3 buttons of nav bar do not exist in the hierarchy.
                supportedNavigationModes = listOf(PlatformConsts.NavBar.MODE_GESTURAL)
            )
        }
    }
}
