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
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isRotated
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.noUncoveredRegions
import com.android.server.wm.flicker.repetitions
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule.Companion.removeAllTasksButHome
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.helpers.PipAppHelper
import com.android.wm.shell.flicker.testapp.Components
import org.junit.Test

abstract class PipTransition(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val isRotated = testSpec.config.startRotation.isRotated()
    protected val pipApp = PipAppHelper(instrumentation)
    protected val displayBounds = WindowUtils.getDisplayBounds(testSpec.config.startRotation)
    protected val broadcastActionTrigger = BroadcastActionTrigger(instrumentation)
    protected abstract val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
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

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            withTestName { testSpec.name }
            repeat { testSpec.config.repetitions }
            transition(this, testSpec.config)
        }
    }

    /**
     * Gets a configuration that handles basic setup and teardown of pip tests
     */
    protected val setupAndTeardown: FlickerBuilder.(Map<String, Any?>) -> Unit
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
        extraSpec: FlickerBuilder.(Map<String, Any?>) -> Unit = {}
    ): FlickerBuilder.(Map<String, Any?>) -> Unit {
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
                        pipApp.exit(wmHelper)
                    }
                }
                test {
                    if (!eachRun) {
                        pipApp.exit(wmHelper)
                    }
                    removeAllTasksButHome()
                }
            }

            extraSpec(this, configuration)
        }
    }

    @Presubmit
    @Test
    open fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    open fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    open fun navBarLayerIsAlwaysVisible() = testSpec.navBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    open fun statusBarLayerIsAlwaysVisible() = testSpec.statusBarLayerIsAlwaysVisible()

    @Presubmit
    @Test
    open fun navBarLayerRotatesAndScales() =
        testSpec.navBarLayerRotatesAndScales(testSpec.config.startRotation, Surface.ROTATION_0)

    @Presubmit
    @Test
    open fun statusBarLayerRotatesScales() =
        testSpec.statusBarLayerRotatesScales(testSpec.config.startRotation, Surface.ROTATION_0)

    @Presubmit
    @Test
    open fun noUncoveredRegions() =
        testSpec.noUncoveredRegions(testSpec.config.startRotation, Surface.ROTATION_0)
}