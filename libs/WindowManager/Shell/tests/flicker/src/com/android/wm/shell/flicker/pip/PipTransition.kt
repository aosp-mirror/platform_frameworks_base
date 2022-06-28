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
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.entireScreenCovered
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule.Companion.removeAllTasksButHome
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.wm.shell.flicker.helpers.PipAppHelper
import com.android.wm.shell.flicker.testapp.Components
import org.junit.Test

abstract class PipTransition(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val pipApp = PipAppHelper(instrumentation)
    protected val displayBounds = WindowUtils.getDisplayBounds(testSpec.startRotation)
    protected val broadcastActionTrigger = BroadcastActionTrigger(instrumentation)
    protected abstract val transition: FlickerBuilder.() -> Unit
    // Helper class to process test actions by broadcast.
    protected class BroadcastActionTrigger(private val instrumentation: Instrumentation) {
        private fun createIntentWithAction(broadcastAction: String): Intent {
            return Intent(broadcastAction).setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }

        fun doAction(broadcastAction: String) {
            instrumentation.context
                .sendBroadcast(createIntentWithAction(broadcastAction))
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

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition(this)
        }
    }

    /**
     * Gets a configuration that handles basic setup and teardown of pip tests
     */
    protected val setupAndTeardown: FlickerBuilder.() -> Unit
        get() = {
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
                    pipApp.exit(wmHelper)
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
    protected open fun buildTransition(
        eachRun: Boolean,
        stringExtras: Map<String, String> = mapOf(Components.PipActivity.EXTRA_ENTER_PIP to "true"),
        extraSpec: FlickerBuilder.() -> Unit = {}
    ): FlickerBuilder.() -> Unit {
        return {
            setupAndTeardown(this)

            setup {
                test {
                    if (!eachRun) {
                        pipApp.launchViaIntentAndWaitForPip(wmHelper, stringExtras = stringExtras)
                        wmHelper.waitPipShown()
                    }
                }
                eachRun {
                    if (eachRun) {
                        pipApp.launchViaIntentAndWaitForPip(wmHelper, stringExtras = stringExtras)
                        wmHelper.waitPipShown()
                    }
                }
            }
            teardown {
                eachRun {
                    if (eachRun) {
                        pipApp.exit(wmHelper)
                    }
                }
                test {
                    if (!eachRun) {
                        pipApp.exit(wmHelper)
                    }
                }
            }

            extraSpec(this)
        }
    }

    @Presubmit
    @Test
    open fun navBarWindowIsVisible() = testSpec.navBarWindowIsVisible()

    @Presubmit
    @Test
    open fun statusBarWindowIsVisible() = testSpec.statusBarWindowIsVisible()

    @Presubmit
    @Test
    open fun navBarLayerIsVisible() = testSpec.navBarLayerIsVisible()

    @Presubmit
    @Test
    open fun statusBarLayerIsVisible() = testSpec.statusBarLayerIsVisible()

    @Presubmit
    @Test
    open fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    @Presubmit
    @Test
    open fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()

    @Presubmit
    @Test
    open fun entireScreenCovered() = testSpec.entireScreenCovered()
}