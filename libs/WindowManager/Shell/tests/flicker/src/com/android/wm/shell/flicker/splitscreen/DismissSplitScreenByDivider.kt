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
import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.wm.shell.flicker.appWindowBecomesInvisible
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.layerBecomesInvisible
import com.android.wm.shell.flicker.layerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsBecomesInvisible
import com.android.wm.shell.flicker.splitScreenDismissed
import com.android.wm.shell.flicker.splitScreenDividerBecomesInvisible
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test dismiss split screen by dragging the divider bar.
 *
 * To run this test: `atest WMShellFlickerTests:DismissSplitScreenByDivider`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DismissSplitScreenByDivider(flicker: FlickerTest) : SplitScreenBase(flicker) {

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup { SplitScreenUtils.enterSplit(wmHelper, tapl, device, primaryApp, secondaryApp) }
            transitions {
                if (tapl.isTablet) {
                    SplitScreenUtils.dragDividerToDismissSplit(
                        device,
                        wmHelper,
                        dragToRight = false,
                        dragToBottom = true
                    )
                } else {
                    SplitScreenUtils.dragDividerToDismissSplit(
                        device,
                        wmHelper,
                        dragToRight = true,
                        dragToBottom = true
                    )
                }
                wmHelper.StateSyncBuilder().withFullScreenApp(secondaryApp).waitForAndVerify()
            }
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    fun cujCompleted() = flicker.splitScreenDismissed(primaryApp, secondaryApp, toHome = false)

    @Presubmit
    @Test
    fun splitScreenDividerBecomesInvisible() = flicker.splitScreenDividerBecomesInvisible()

    @Presubmit
    @Test
    fun primaryAppLayerBecomesInvisible() = flicker.layerBecomesInvisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppLayerIsVisibleAtEnd() = flicker.layerIsVisibleAtEnd(secondaryApp)

    @Presubmit
    @Test
    fun primaryAppBoundsBecomesInvisible() =
        flicker.splitAppLayerBoundsBecomesInvisible(
            primaryApp,
            landscapePosLeft = tapl.isTablet,
            portraitPosTop = false
        )

    @Presubmit
    @Test
    fun secondaryAppBoundsIsFullscreenAtEnd() {
        flicker.assertLayers {
            this.isVisible(secondaryApp)
                .then()
                .isInvisible(secondaryApp)
                .then()
                .invoke("secondaryAppBoundsIsFullscreenAtEnd") {
                    val displayBounds = WindowUtils.getDisplayBounds(flicker.scenario.endRotation)
                    it.visibleRegion(secondaryApp).coversExactly(displayBounds)
                }
        }
    }

    @Presubmit
    @Test
    fun primaryAppWindowBecomesInvisible() = flicker.appWindowBecomesInvisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowIsVisibleAtEnd() = flicker.appWindowIsVisibleAtEnd(secondaryApp)

    /** {@inheritDoc} */
    @Postsubmit @Test override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @Postsubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 206753786)
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
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
