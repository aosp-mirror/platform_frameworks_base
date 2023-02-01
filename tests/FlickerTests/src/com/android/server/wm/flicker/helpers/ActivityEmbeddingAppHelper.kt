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
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.window.extensions.WindowExtensions
import androidx.window.extensions.WindowExtensionsProvider
import androidx.window.extensions.embedding.ActivityEmbeddingComponent
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.windowmanager.WindowManagerState.Companion.STATE_RESUMED
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import org.junit.Assume.assumeNotNull

class ActivityEmbeddingAppHelper
@JvmOverloads
constructor(
    instr: Instrumentation,
    launcherName: String = ActivityOptions.ActivityEmbedding.MainActivity.LABEL,
    component: ComponentNameMatcher = MAIN_ACTIVITY_COMPONENT
) : StandardAppHelper(instr, launcherName, component) {

    /**
     * Clicks the button to launch the placeholder primary activity, which should launch the
     * placeholder secondary activity based on the placeholder rule.
     */
    fun launchPlaceholderSplit(wmHelper: WindowManagerStateHelper) {
        val launchButton =
            uiDevice.wait(
                Until.findObject(By.res(getPackage(), "launch_placeholder_split_button")),
                FIND_TIMEOUT
            )
        require(launchButton != null) { "Can't find launch placeholder split button on screen." }
        launchButton.click()
        wmHelper
            .StateSyncBuilder()
            .withActivityState(PLACEHOLDER_PRIMARY_COMPONENT, STATE_RESUMED)
            .withActivityState(PLACEHOLDER_SECONDARY_COMPONENT, STATE_RESUMED)
            .waitForAndVerify()
    }

    companion object {
        private const val TAG = "ActivityEmbeddingAppHelper"

        val MAIN_ACTIVITY_COMPONENT =
            ActivityOptions.ActivityEmbedding.MainActivity.COMPONENT.toFlickerComponent()

        val PLACEHOLDER_PRIMARY_COMPONENT =
            ActivityOptions.ActivityEmbedding.PlaceholderPrimaryActivity.COMPONENT
                .toFlickerComponent()

        val PLACEHOLDER_SECONDARY_COMPONENT =
            ActivityOptions.ActivityEmbedding.PlaceholderSecondaryActivity.COMPONENT
                .toFlickerComponent()

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
