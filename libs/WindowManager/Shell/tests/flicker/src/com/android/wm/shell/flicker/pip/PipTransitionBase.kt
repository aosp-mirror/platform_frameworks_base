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
import android.os.Bundle
import android.view.Surface
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.buildTestTag
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.repetitions
import com.android.wm.shell.flicker.helpers.PipAppHelper
import com.android.wm.shell.flicker.removeAllTasksButHome
import com.android.wm.shell.flicker.testapp.Components

abstract class PipTransitionBase(protected val instrumentation: Instrumentation) {
    // Helper class to process test actions by broadcast.
    protected class BroadcastActionTrigger(private val instrumentation: Instrumentation) {
        private fun createIntentWithAction(broadcastAction: String): Intent {
            return Intent(broadcastAction).setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }

        fun doAction(broadcastAction: String) {
            instrumentation.context
                .sendBroadcast(createIntentWithAction(broadcastAction))
        }

        fun requestOrientationForPip(orientation: Int) {
            instrumentation.context.sendBroadcast(
                    createIntentWithAction(Components.PipActivity.ACTION_SET_REQUESTED_ORIENTATION)
                    .putExtra(Components.PipActivity.EXTRA_PIP_ORIENTATION, orientation.toString())
            )
        }

        companion object {
            // Corresponds to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            @JvmStatic
            val ORIENTATION_LANDSCAPE = 0

            // Corresponds to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            @JvmStatic
            val ORIENTATION_PORTRAIT = 1
        }
    }

    protected val pipApp = PipAppHelper(instrumentation)
    protected val broadcastActionTrigger = BroadcastActionTrigger(instrumentation)

    /**
     * Gets a configuration that handles basic setup and teardown of pip tests
     */
    protected val setupAndTeardown: FlickerBuilder.(Bundle) -> Unit
        get() = { configuration ->
            withTestName { buildTestTag(configuration) }
            repeat { configuration.repetitions }
            setup {
                test {
                    removeAllTasksButHome()
                    device.wakeUpAndGoToHomeScreen()
                }
            }
            teardown {
                eachRun {
                    setRotation(Surface.ROTATION_0)
                }
                test {
                    removeAllTasksButHome()
                    pipApp.exit()
                }
            }
        }

    /**
     * Gets a configuration that handles basic setup and teardown of pip tests and that
     * launches the Pip app for test
     *
     * @param eachRun If the pip app should be launched in each run (otherwise only 1x per test)
     * @param stringExtras Arguments to pass to the PIP launch intent
     * @param extraSpec Addicional segment of flicker specification
     */
    @JvmOverloads
    open fun getTransition(
        eachRun: Boolean,
        stringExtras: Map<String, String> = mapOf(Components.PipActivity.EXTRA_ENTER_PIP to "true"),
        extraSpec: FlickerBuilder.(Bundle) -> Unit = {}
    ): FlickerBuilder.(Bundle) -> Unit {
        return { configuration ->
            setupAndTeardown(this, configuration)

            setup {
                test {
                    removeAllTasksButHome()
                    if (!eachRun) {
                        pipApp.launchViaIntent(wmHelper, stringExtras = stringExtras)
                        wmHelper.waitPipWindowShown()
                    }
                }
                eachRun {
                    if (eachRun) {
                        pipApp.launchViaIntent(wmHelper, stringExtras = stringExtras)
                        wmHelper.waitPipWindowShown()
                    }
                }
            }
            teardown {
                eachRun {
                    if (eachRun) {
                        pipApp.exit()
                    }
                }
                test {
                    if (!eachRun) {
                        pipApp.exit()
                    }
                    removeAllTasksButHome()
                }
            }

            extraSpec(this, configuration)
        }
    }
}