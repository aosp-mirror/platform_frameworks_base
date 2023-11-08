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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.tools.common.PlatformConsts
import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.apphelpers.StandardAppHelper
import android.tools.device.helpers.FIND_TIMEOUT
import android.tools.device.traces.parsers.WindowManagerStateHelper
import android.tools.device.traces.parsers.toFlickerComponent
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.window.extensions.WindowExtensions
import androidx.window.extensions.WindowExtensionsProvider
import androidx.window.extensions.embedding.ActivityEmbeddingComponent
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.Assume.assumeNotNull

class ActivityEmbeddingAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.ActivityEmbedding.MainActivity.LABEL,
    component: ComponentNameMatcher = MAIN_ACTIVITY_COMPONENT
) : StandardAppHelper(instr, launcherName, component) {

    /**
     * Clicks the button to launch the secondary activity, which should split with the main activity
     * based on the split pair rule.
     */
    fun launchSecondaryActivity(wmHelper: WindowManagerStateHelper) {
        launchSecondaryActivityFromButton(wmHelper, "launch_secondary_activity_button")
    }

    /**
     * Clicks the button to launch the secondary activity in RTL, which should split with the main
     * activity based on the split pair rule.
     */
    fun launchSecondaryActivityRTL(wmHelper: WindowManagerStateHelper) {
        launchSecondaryActivityFromButton(wmHelper, "launch_secondary_activity_rtl_button")
    }

    /** Clicks the button to launch the secondary activity in a horizontal split. */
    fun launchSecondaryActivityHorizontally(wmHelper: WindowManagerStateHelper) {
        launchSecondaryActivityFromButton(wmHelper, "launch_secondary_activity_horizontally_button")
    }

    /** Clicks the button to launch a third activity over a secondary activity. */
    fun launchThirdActivity(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(packageName, "launch_third_activity_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) { "Can't find launch third activity button on screen." }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(MAIN_ACTIVITY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .withActivityState(SECONDARY_ACTIVITY_COMPONENT, PlatformConsts.STATE_STOPPED)
            .withActivityState(THIRD_ACTIVITY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .waitForAndVerify()
    }

    /**
     * Clicks the button to launch the trampoline activity, which should launch the secondary
     * activity and finish itself.
     */
    fun launchTrampolineActivity(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(packageName, "launch_trampoline_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) { "Can't find launch trampoline activity button on screen." }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(SECONDARY_ACTIVITY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .withActivityRemoved(TRAMPOLINE_ACTIVITY_COMPONENT)
            .waitForAndVerify()
    }

    /**
     * Clicks the button to finishes the secondary activity launched through
     * [launchSecondaryActivity], waits for the main activity to resume.
     */
    fun finishSecondaryActivity(wmHelper: WindowManagerStateHelper) {
        val finishButton =
            uiDevice.wait(
                Until.findObject(By.res(packageName, "finish_secondary_activity_button")),
                FIND_TIMEOUT
            )
        require(finishButton != null) { "Can't find finish secondary activity button on screen." }
        finishButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityRemoved(SECONDARY_ACTIVITY_COMPONENT)
            .waitForAndVerify()
    }

    /** Clicks the button to toggle the split ratio of secondary activity. */
    fun changeSecondaryActivityRatio(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(packageName, "toggle_split_ratio_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) {
            "Can't find toggle ratio for secondary activity button on screen."
        }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withAppTransitionIdle()
            .withTransitionSnapshotGone()
            .waitForAndVerify()
    }

    fun secondaryActivityEnterPip(wmHelper: WindowManagerStateHelper) {
        val pipButton =
            uiDevice.wait(
                Until.findObject(By.res(packageName, "secondary_enter_pip_button")),
                FIND_TIMEOUT
            )
        require(pipButton != null) { "Can't find enter pip button on screen." }
        pipButton.click()
        wmHelper.StateSyncBuilder().withAppTransitionIdle().withPipShown().waitForAndVerify()
    }

    /**
     * Clicks the button to launch a secondary activity with alwaysExpand enabled, which will launch
     * a fullscreen window on top of the visible region.
     */
    fun launchAlwaysExpandActivity(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(packageName, "launch_always_expand_activity_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) {
            "Can't find launch always expand activity button on screen."
        }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(ALWAYS_EXPAND_ACTIVITY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .withActivityState(
                MAIN_ACTIVITY_COMPONENT,
                PlatformConsts.STATE_PAUSED,
                PlatformConsts.STATE_STOPPED
            )
            .waitForAndVerify()
    }

    private fun launchSecondaryActivityFromButton(
        wmHelper: WindowManagerStateHelper,
        buttonName: String
    ) {
        val launchButton =
            uiDevice.wait(Until.findObject(By.res(packageName, buttonName)), FIND_TIMEOUT)
        require(launchButton != null) {
            "Can't find launch secondary activity button : " + buttonName + "on screen."
        }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(SECONDARY_ACTIVITY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .withActivityState(MAIN_ACTIVITY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .waitForAndVerify()
    }

    /**
     * Clicks the button to launch the placeholder primary activity, which should launch the
     * placeholder secondary activity based on the placeholder rule.
     */
    fun launchPlaceholderSplit(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(packageName, "launch_placeholder_split_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) { "Can't find launch placeholder split button on screen." }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(PLACEHOLDER_PRIMARY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .withActivityState(PLACEHOLDER_SECONDARY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .waitForAndVerify()
    }

    /**
     * Clicks the button to launch the placeholder primary activity in RTL, which should launch the
     * placeholder secondary activity based on the placeholder rule.
     */
    fun launchPlaceholderSplitRTL(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(packageName, "launch_placeholder_split_rtl_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) { "Can't find launch placeholder split button on screen." }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(PLACEHOLDER_PRIMARY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .withActivityState(PLACEHOLDER_SECONDARY_COMPONENT, PlatformConsts.STATE_RESUMED)
            .waitForAndVerify()
    }

    companion object {
        private const val TAG = "ActivityEmbeddingAppHelper"

        val MAIN_ACTIVITY_COMPONENT =
            ActivityOptions.ActivityEmbedding.MainActivity.COMPONENT.toFlickerComponent()

        val SECONDARY_ACTIVITY_COMPONENT =
            ActivityOptions.ActivityEmbedding.SecondaryActivity.COMPONENT.toFlickerComponent()

        val THIRD_ACTIVITY_COMPONENT =
            ActivityOptions.ActivityEmbedding.ThirdActivity.COMPONENT.toFlickerComponent()

        val ALWAYS_EXPAND_ACTIVITY_COMPONENT =
            ActivityOptions.ActivityEmbedding.AlwaysExpandActivity.COMPONENT.toFlickerComponent()

        val PLACEHOLDER_PRIMARY_COMPONENT =
            ActivityOptions.ActivityEmbedding.PlaceholderPrimaryActivity.COMPONENT
                .toFlickerComponent()

        val PLACEHOLDER_SECONDARY_COMPONENT =
            ActivityOptions.ActivityEmbedding.PlaceholderSecondaryActivity.COMPONENT
                .toFlickerComponent()

        val TRAMPOLINE_ACTIVITY_COMPONENT =
            ActivityOptions.ActivityEmbedding.TrampolineActivity.COMPONENT.toFlickerComponent()

        @JvmStatic
        fun getWindowExtensions(): WindowExtensions? {
            try {
                return WindowExtensionsProvider.getWindowExtensions()
            } catch (e: NoClassDefFoundError) {
                Log.d(TAG, "Extension implementation not found")
            } catch (e: UnsupportedOperationException) {
                Log.d(TAG, "Stub Extension")
            }
            return null
        }

        @JvmStatic
        fun getActivityEmbeddingComponent(): ActivityEmbeddingComponent? {
            return getWindowExtensions()?.activityEmbeddingComponent
        }

        @JvmStatic
        fun assumeActivityEmbeddingSupportedDevice() {
            assumeNotNull(getActivityEmbeddingComponent())
        }
    }
}
