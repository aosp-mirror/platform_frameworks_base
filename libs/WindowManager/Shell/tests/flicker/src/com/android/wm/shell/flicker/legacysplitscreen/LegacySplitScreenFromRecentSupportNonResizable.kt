/*
 * Copyright (C) 2021 The Android Open Source Project
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
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group2
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.wm.shell.flicker.DOCKED_STACK_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.dockedStackDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.helpers.MultiWindowHelper.Companion.resetMultiWindowConfig
import com.android.wm.shell.flicker.helpers.MultiWindowHelper.Companion.setSupportsNonResizableMultiWindow
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launch non-resizable activity via recent overview in split screen mode. When the device
 * supports non-resizable in multi window, it should show the non-resizable app in split screen.
 * To run this test: `atest WMShellFlickerTests:LegacySplitScreenFromRecentSupportNonResizable`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group2
class LegacySplitScreenFromRecentSupportNonResizable(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {

    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = { configuration ->
            cleanSetup(this, configuration)
            setup {
                eachRun {
                    nonResizeableApp.launchViaIntent(wmHelper)
                    splitScreenApp.launchViaIntent(wmHelper)
                    device.launchSplitScreen(wmHelper)
                }
            }
            transitions {
                device.reopenAppFromOverview(wmHelper)
            }
        }

    override val ignoredWindows: List<FlickerComponentName>
        get() = listOf(DOCKED_STACK_DIVIDER_COMPONENT, LAUNCHER_COMPONENT, LETTERBOX_COMPONENT,
            TOAST_COMPONENT, splitScreenApp.component, nonResizeableApp.component,
            FlickerComponentName.SPLASH_SCREEN,
            FlickerComponentName.SNAPSHOT)

    @Before
    override fun setup() {
        super.setup()
        setSupportsNonResizableMultiWindow(instrumentation, 1)
    }

    @After
    override fun teardown() {
        super.teardown()
        resetMultiWindowConfig(instrumentation)
    }

    @Presubmit
    @Test
    fun nonResizableAppLayerBecomesVisible() {
        testSpec.assertLayers {
            this.isInvisible(nonResizeableApp.component)
                    .then()
                    .isVisible(nonResizeableApp.component)
        }
    }

    @Presubmit
    @Test
    fun nonResizableAppWindowBecomesVisible() {
        testSpec.assertWm {
            // when the app is launched, first the activity becomes visible, then the
            // SnapshotStartingWindow appears and then the app window becomes visible.
            // Because we log WM once per frame, sometimes the activity and the window
            // become visible in the same entry, sometimes not, thus it is not possible to
            // assert the visibility of the activity here
            this.isAppWindowInvisible(nonResizeableApp.component)
                    .then()
                    // during re-parenting, the window may disappear and reappear from the
                    // trace, this occurs because we log only 1x per frame
                    .notContains(nonResizeableApp.component, isOptional = true)
                    .then()
                    // if the window reappears after re-parenting it will most likely not
                    // be visible in the first log entry (because we log only 1x per frame)
                    .isAppWindowInvisible(nonResizeableApp.component, isOptional = true)
                    .then()
                    .isAppWindowVisible(nonResizeableApp.component)
        }
    }

    @Presubmit
    @Test
    fun dockedStackDividerIsVisibleAtEnd() = testSpec.dockedStackDividerIsVisibleAtEnd()

    @Presubmit
    @Test
    fun bothAppsWindowsAreVisibleAtEnd() {
        testSpec.assertWmEnd {
            isAppWindowVisible(splitScreenApp.component)
            isAppWindowVisible(nonResizeableApp.component)
        }
    }

    @Presubmit
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
            super.visibleLayersShownMoreThanOneConsecutiveEntry()

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
