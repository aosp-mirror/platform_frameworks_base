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

package com.android.wm.shell.flicker.pip

import android.app.Instrumentation
import android.os.SystemClock
import android.platform.test.annotations.Presubmit
import android.tools.common.NavBar
import android.tools.common.Rotation
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.LegacyFlickerTest
import android.tools.device.flicker.legacy.LegacyFlickerTestFactory
import android.tools.device.helpers.WindowUtils
import android.tools.device.traces.parsers.toFlickerComponent
import androidx.test.filters.RequiresDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.wm.shell.flicker.LAUNCHER_UI_PACKAGE_NAME
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test entering pip from an app via auto-enter property when navigating to home from split screen.
 *
 * To run this test: `atest WMShellFlickerTests:AutoEnterPipOnGoToHomeTest`
 *
 * Actions:
 * ```
 *     Launch an app in full screen
 *     Select "Auto-enter PiP" radio button
 *     Open all apps and drag another app icon to enter split screen
 *     Press Home button or swipe up to go Home and put [pipApp] in pip mode
 * ```
 *
 * Notes:
 * ```
 *     1. All assertions are inherited from [EnterPipTest]
 *     2. Part of the test setup occurs automatically via
 *        [android.tools.device.flicker.legacy.runner.TransitionRunner],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AutoEnterPipFromSplitScreenOnGoToHomeTest(flicker: LegacyFlickerTest) :
    AutoEnterPipOnGoToHomeTest(flicker) {
    private val portraitDisplayBounds = WindowUtils.getDisplayBounds(Rotation.ROTATION_0)
    /** Second app used to enter split screen mode */
    protected val secondAppForSplitScreen = getSplitScreenApp(instrumentation)
    fun getSplitScreenApp(instrumentation: Instrumentation): StandardAppHelper =
        SimpleAppHelper(
            instrumentation,
            ActivityOptions.SplitScreen.Primary.LABEL,
            ActivityOptions.SplitScreen.Primary.COMPONENT.toFlickerComponent()
        )

    /** Defines the transition used to run the test */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                secondAppForSplitScreen.launchViaIntent(wmHelper)
                pipApp.launchViaIntent(wmHelper)
                tapl.goHome()
                enterSplitScreen()
                // wait until split screen is established
                wmHelper
                    .StateSyncBuilder()
                    .withWindowSurfaceAppeared(pipApp)
                    .withWindowSurfaceAppeared(secondAppForSplitScreen)
                    .withSplitDividerVisible()
                    .waitForAndVerify()
                pipApp.enableAutoEnterForPipActivity()
            }
            teardown {
                // close gracefully so that onActivityUnpinned() can be called before force exit
                pipApp.closePipWindow(wmHelper)
                pipApp.exit(wmHelper)
                secondAppForSplitScreen.exit(wmHelper)
            }
            transitions { tapl.goHome() }
        }

    // TODO(b/285400227) merge the code in a common utility - this is copied from SplitScreenUtils
    private val TIMEOUT_MS = 3_000L
    private val overviewSnapshotSelector: BySelector
        get() = By.res(LAUNCHER_UI_PACKAGE_NAME, "snapshot")
    private fun enterSplitScreen() {
        // Note: The initial split position in landscape is different between tablet and phone.
        // In landscape, tablet will let the first app split to right side, and phone will
        // split to left side.
        if (tapl.isTablet) {
            // TAPL's currentTask on tablet is sometimes not what we expected if the overview
            // contains more than 3 task views. We need to use uiautomator directly to find the
            // second task to split.
            tapl.workspace.switchToOverview().overviewActions.clickSplit()
            val snapshots =
                tapl.device.wait(Until.findObjects(overviewSnapshotSelector), TIMEOUT_MS)
            if (snapshots == null || snapshots.size < 1) {
                error("Fail to find a overview snapshot to split.")
            }

            // Find the second task in the upper right corner in split select mode by sorting
            // 'left' in descending order and 'top' in ascending order.
            snapshots.sortWith { t1: UiObject2, t2: UiObject2 ->
                t2.getVisibleBounds().left - t1.getVisibleBounds().left
            }
            snapshots.sortWith { t1: UiObject2, t2: UiObject2 ->
                t1.getVisibleBounds().top - t2.getVisibleBounds().top
            }
            snapshots[0].click()
        } else {
            tapl.workspace
                .switchToOverview()
                .currentTask
                .tapMenu()
                .tapSplitMenuItem()
                .currentTask
                .open()
        }
        SystemClock.sleep(TIMEOUT_MS)
    }

    @Presubmit
    @Test
    override fun pipOverlayLayerAppearThenDisappear() {
        // when entering from split screen we use alpha animation, without overlay
    }

    @Presubmit
    @Test
    override fun pipLayerOrOverlayRemainInsideVisibleBounds() {
        // when entering from split screen we use alpha animation, without overlay
    }

    @Presubmit
    @Test
    override fun pipLayerReduces() {
        // when entering from split screen we use alpha animation, so there is no size change
        Assume.assumeFalse(flicker.scenario.isGesturalNavigation)
        super.pipLayerReduces()
    }

    @Presubmit
    @Test
    override fun pipAppLayerAlwaysVisible() {
        // pip layer in gesture nav will disappear during transition with alpha animation
        Assume.assumeFalse(flicker.scenario.isGesturalNavigation)
        super.pipAppLayerAlwaysVisible()
    }

    @Presubmit
    @Test
    override fun pipWindowRemainInsideVisibleBounds() {
        if (tapl.isTablet) {
            flicker.assertWmVisibleRegion(pipApp) { coversAtMost(displayBounds) }
        } else {
            // on phones home does not rotate in landscape, PiP enters back to portrait
            // orientation so use display bounds from that orientation for assertion
            flicker.assertWmVisibleRegion(pipApp) { coversAtMost(portraitDisplayBounds) }
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams() =
            LegacyFlickerTestFactory.nonRotationTests(
                // TODO(b/176061063):The 3 buttons of nav bar do not exist in the hierarchy.
                supportedNavigationModes = listOf(NavBar.MODE_GESTURAL)
            )
    }
}
