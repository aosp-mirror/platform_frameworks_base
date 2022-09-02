/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker.legacysplitscreen

import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.wm.shell.flicker.dockedStackDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.dockedStackPrimaryBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.dockedStackSecondaryBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open app to split screen.
 * To run this test: `atest WMShellFlickerTests:RotateTwoLaunchedAppAndEnterSplitScreen`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class RotateTwoLaunchedAppAndEnterSplitScreen(
    testSpec: FlickerTestParameter
) : LegacySplitScreenRotateTransition(testSpec) {
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            transitions {
                this.setRotation(testSpec.startRotation)
                device.launchSplitScreen(wmHelper)
                device.reopenAppFromOverview(wmHelper)
            }
        }

    @Presubmit
    @Test
    fun dockedStackDividerIsVisibleAtEnd() = testSpec.dockedStackDividerIsVisibleAtEnd()

    @Presubmit
    @Test
    fun dockedStackPrimaryBoundsIsVisibleAtEnd() =
        testSpec.dockedStackPrimaryBoundsIsVisibleAtEnd(testSpec.startRotation,
            splitScreenApp.component)

    @Presubmit
    @Test
    fun dockedStackSecondaryBoundsIsVisibleAtEnd() =
        testSpec.dockedStackSecondaryBoundsIsVisibleAtEnd(testSpec.startRotation,
            secondaryApp.component)

    @Presubmit
    @Test
    fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    @FlakyTest(bugId = 206753786)
    @Test
    fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

    @Presubmit
    @Test
    fun appWindowBecomesVisible() {
        testSpec.assertWm {
            // when the app is launched, first the activity becomes visible, then the
            // SnapshotStartingWindow appears and then the app window becomes visible.
            // Because we log WM once per frame, sometimes the activity and the window
            // become visible in the same entry, sometimes not, thus it is not possible to
            // assert the visibility of the activity here
            this.isAppWindowInvisible(secondaryApp.component)
                    .then()
                    // during re-parenting, the window may disappear and reappear from the
                    // trace, this occurs because we log only 1x per frame
                    .notContains(secondaryApp.component, isOptional = true)
                    .then()
                    // if the window reappears after re-parenting it will most likely not
                    // be visible in the first log entry (because we log only 1x per frame)
                    .isAppWindowInvisible(secondaryApp.component, isOptional = true)
                    .then()
                    .isAppWindowVisible(secondaryApp.component)
        }
    }

    @Presubmit
    @Test
    fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
            super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0)) // b/178685668
        }
    }
}
