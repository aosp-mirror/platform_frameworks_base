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
import android.os.Bundle
import android.system.helpers.ActivityHelper
import android.util.Log
import com.android.compatibility.common.util.SystemUtil
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.helpers.wakeUpAndGoToHomeScreen
import com.android.server.wm.flicker.navBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarLayerIsAlwaysVisible
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.wm.shell.flicker.helpers.AppPairsHelper
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import com.android.wm.shell.flicker.testapp.Components
import java.io.IOException

open class AppPairsTransition(
    protected val instrumentation: Instrumentation
) {
    internal val activityHelper = ActivityHelper.getInstance()

    internal val appPairsHelper = AppPairsHelper(instrumentation,
        Components.SplitScreenActivity.LABEL,
        Components.SplitScreenActivity.COMPONENT)

    internal val primaryApp = SplitScreenHelper.getPrimary(instrumentation)
    internal val secondaryApp = SplitScreenHelper.getSecondary(instrumentation)
    internal open val nonResizeableApp: SplitScreenHelper? =
        SplitScreenHelper.getNonResizeable(instrumentation)
    internal var primaryTaskId = ""
    internal var secondaryTaskId = ""
    internal var nonResizeableTaskId = ""

    internal open val transition: FlickerBuilder.(Bundle) -> Unit
        get() = { configuration ->
            setup {
                test {
                    device.wakeUpAndGoToHomeScreen()
                }
                eachRun {
                    this.setRotation(configuration.startRotation)
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
                    primaryApp.exit()
                    secondaryApp.exit()
                    nonResizeableApp?.exit()
                }
            }

            assertions {
                layersTrace {
                    navBarLayerIsAlwaysVisible()
                    statusBarLayerIsAlwaysVisible()
                }
                windowManagerTrace {
                    navBarWindowIsAlwaysVisible()
                    statusBarWindowIsAlwaysVisible()
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
        try {
            SystemUtil.runShellCommand(instrumentation, cmd)
        } catch (e: IOException) {
            Log.d("AppPairsTest", "executeShellCommand error! $e")
        }
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
}