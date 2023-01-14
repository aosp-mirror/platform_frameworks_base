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

package com.android.wm.shell.flicker.pip

import android.app.Instrumentation
import android.content.Intent
import android.platform.test.annotations.Postsubmit
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.helpers.PipAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule.Companion.removeAllTasksButHome
import com.android.server.wm.flicker.testapp.ActivityOptions
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.service.PlatformConsts
import com.android.wm.shell.flicker.BaseTest
import com.google.common.truth.Truth
import org.junit.Test

abstract class PipTransition(flicker: FlickerTest) : BaseTest(flicker) {
    protected val pipApp = PipAppHelper(instrumentation)
    protected val displayBounds = WindowUtils.getDisplayBounds(flicker.scenario.startRotation)
    protected val broadcastActionTrigger = BroadcastActionTrigger(instrumentation)

    // Helper class to process test actions by broadcast.
    protected class BroadcastActionTrigger(private val instrumentation: Instrumentation) {
        private fun createIntentWithAction(broadcastAction: String): Intent {
            return Intent(broadcastAction).setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }

        fun doAction(broadcastAction: String) {
            instrumentation.context.sendBroadcast(createIntentWithAction(broadcastAction))
        }

        companion object {
            // Corresponds to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            @JvmStatic val ORIENTATION_LANDSCAPE = 0

            // Corresponds to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            @JvmStatic val ORIENTATION_PORTRAIT = 1
        }
    }

    /**
     * Gets a configuration that handles basic setup and teardown of pip tests and that launches the
     * Pip app for test
     *
     * @param stringExtras Arguments to pass to the PIP launch intent
     * @param extraSpec Additional segment of flicker specification
     */
    @JvmOverloads
    protected open fun buildTransition(
        stringExtras: Map<String, String> = mapOf(ActivityOptions.Pip.EXTRA_ENTER_PIP to "true"),
        extraSpec: FlickerBuilder.() -> Unit = {}
    ): FlickerBuilder.() -> Unit {
        return {
            setup {
                setRotation(PlatformConsts.Rotation.ROTATION_0)
                removeAllTasksButHome()
                pipApp.launchViaIntentAndWaitForPip(wmHelper, stringExtras = stringExtras)
            }
            teardown { pipApp.exit(wmHelper) }

            extraSpec(this)
        }
    }

    @Postsubmit
    @Test
    fun hasAtMostOnePipDismissOverlayWindow() {
        val matcher = ComponentNameMatcher("", "pip-dismiss-overlay")
        flicker.assertWm {
            val overlaysPerState =
                trace.entries.map { entry ->
                    entry.windowStates.count { window -> matcher.windowMatchesAnyOf(window) } <= 1
                }

            Truth.assertWithMessage("Number of dismiss overlays per state")
                .that(overlaysPerState)
                .doesNotContain(false)
        }
    }
}
