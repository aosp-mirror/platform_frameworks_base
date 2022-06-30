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

package com.android.wm.shell.flicker.apppairs

import android.app.Instrumentation
import android.content.Context
import android.system.helpers.ActivityHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.FlickerBuilderProvider
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsVisible
import com.android.server.wm.flicker.navBarLayerRotatesAndScales
import com.android.server.wm.flicker.navBarWindowIsVisible
import com.android.server.wm.flicker.statusBarLayerIsVisible
import com.android.server.wm.flicker.statusBarLayerRotatesScales
import com.android.server.wm.flicker.statusBarWindowIsVisible
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.BaseAppHelper
import com.android.wm.shell.flicker.helpers.MultiWindowHelper.Companion.getDevEnableNonResizableMultiWindow
import com.android.wm.shell.flicker.helpers.MultiWindowHelper.Companion.setDevEnableNonResizableMultiWindow
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import com.android.wm.shell.flicker.testapp.Components
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

abstract class AppPairsTransition(protected val testSpec: FlickerTestParameter) {
    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context: Context = instrumentation.context
    protected val activityHelper = ActivityHelper.getInstance()
    protected val appPairsHelper = AppPairsHelper(instrumentation,
        Components.SplitScreenActivity.LABEL,
        Components.SplitScreenActivity.COMPONENT.toFlickerComponent())

    protected val primaryApp = SplitScreenHelper.getPrimary(instrumentation)
    protected val secondaryApp = SplitScreenHelper.getSecondary(instrumentation)
    protected open val nonResizeableApp: SplitScreenHelper? =
        SplitScreenHelper.getNonResizeable(instrumentation)
    protected var primaryTaskId = ""
    protected var secondaryTaskId = ""
    protected var nonResizeableTaskId = ""
    private var prevDevEnableNonResizableMultiWindow = 0

    @Before
    open fun setup() {
        prevDevEnableNonResizableMultiWindow = getDevEnableNonResizableMultiWindow(context)
        if (prevDevEnableNonResizableMultiWindow != 0) {
            // Turn off the development option
            setDevEnableNonResizableMultiWindow(context, 0)
        }
    }

    @After
    open fun teardown() {
        setDevEnableNonResizableMultiWindow(context, prevDevEnableNonResizableMultiWindow)
    }

    @FlickerBuilderProvider
    fun buildFlicker(): FlickerBuilder {
        return FlickerBuilder(instrumentation).apply {
            transition(this)
        }
    }

    internal open val transition: FlickerBuilder.() -> Unit
        get() = {
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                }
                eachRun {
                    this.setRotation(testSpec.startRotation)
                    primaryApp.launchViaIntent(wmHelper)
                    secondaryApp.launchViaIntent(wmHelper)
                    nonResizeableApp?.launchViaIntent(wmHelper)
                    updateTasksId()
                }
            }
            teardown {
                eachRun {
                    executeShellCommand(composePairsCommand(
                        primaryTaskId, secondaryTaskId, pair = false))
                    executeShellCommand(composePairsCommand(
                        primaryTaskId, nonResizeableTaskId, pair = false))
                    primaryApp.exit(wmHelper)
                    secondaryApp.exit(wmHelper)
                    nonResizeableApp?.exit(wmHelper)
                }
            }
        }

    protected fun updateTasksId() {
        primaryTaskId = getTaskIdForActivity(
            primaryApp.component.packageName, primaryApp.component.className).toString()
        secondaryTaskId = getTaskIdForActivity(
            secondaryApp.component.packageName, secondaryApp.component.className).toString()
        val nonResizeableApp = nonResizeableApp
        if (nonResizeableApp != null) {
            nonResizeableTaskId = getTaskIdForActivity(
                nonResizeableApp.component.packageName,
                nonResizeableApp.component.className).toString()
        }
    }

    private fun getTaskIdForActivity(pkgName: String, activityName: String): Int {
        return activityHelper.getTaskIdForActivity(pkgName, activityName)
    }

    internal fun executeShellCommand(cmd: String) {
        BaseAppHelper.executeShellCommand(instrumentation, cmd)
    }

    internal fun composePairsCommand(
        primaryApp: String,
        secondaryApp: String,
        pair: Boolean
    ): String = buildString {
        // dumpsys activity service SystemUIService WMShell {pair|unpair} ${TASK_ID_1} ${TASK_ID_2}
        append("dumpsys activity service SystemUIService WMShell ")
        if (pair) {
            append("pair ")
        } else {
            append("unpair ")
        }
        append("$primaryApp $secondaryApp")
    }

    @Ignore
    @Test
    open fun navBarLayerIsVisible() {
        testSpec.navBarLayerIsVisible()
    }

    @Ignore
    @Test
    open fun statusBarLayerIsVisible() {
        testSpec.statusBarLayerIsVisible()
    }

    @Ignore
    @Test
    open fun navBarWindowIsVisible() {
        testSpec.navBarWindowIsVisible()
    }

    @Ignore
    @Test
    open fun statusBarWindowIsVisible() {
        testSpec.statusBarWindowIsVisible()
    }

    @Ignore
    @Test
    open fun navBarLayerRotatesAndScales() = testSpec.navBarLayerRotatesAndScales()

    @Ignore
    @Test
    open fun statusBarLayerRotatesScales() = testSpec.statusBarLayerRotatesScales()
}