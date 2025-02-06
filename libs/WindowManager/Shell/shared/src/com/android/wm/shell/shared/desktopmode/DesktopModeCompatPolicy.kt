/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.shared.desktopmode

import android.app.TaskInfo
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED
import android.content.pm.ActivityInfo.OVERRIDE_ENABLE_INSETS_DECOUPLED_CONFIGURATION
import android.window.DesktopModeFlags
import com.android.internal.R
import com.android.window.flags.Flags

/**
 * Class to decide whether to apply app compat policies in desktop mode.
 */
// TODO(b/347289970): Consider replacing with API
class DesktopModeCompatPolicy(private val context: Context) {

    private val systemUiPackage: String = context.resources.getString(R.string.config_systemUi)
    private val defaultHomePackage: String?
        get() = context.getPackageManager().getHomeActivities(ArrayList())?.packageName

    /**
     * If the top activity should be exempt from desktop windowing and forced back to fullscreen.
     * Currently includes all system ui, default home and transparent stack activities. However if
     * the top activity is not being displayed, regardless of its configuration, we will not exempt
     * it as to remain in the desktop windowing environment.
     */
    fun isTopActivityExemptFromDesktopWindowing(task: TaskInfo) =
        isTopActivityExemptFromDesktopWindowing(task.baseActivity?.packageName,
            task.numActivities, task.isTopActivityNoDisplay, task.isActivityStackTransparent)

    fun isTopActivityExemptFromDesktopWindowing(packageName: String?,
        numActivities: Int, isTopActivityNoDisplay: Boolean, isActivityStackTransparent: Boolean) =
        DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODALS_POLICY.isTrue
                && ((isSystemUiTask(packageName)
                || isPartOfDefaultHomePackage(packageName)
                || isTransparentTask(isActivityStackTransparent, numActivities))
                && !isTopActivityNoDisplay)

    /**
     * Whether the caption insets should be excluded from configuration for system to handle.
     *
     * The treatment is enabled when all the of the following is true:
     * * Any flags to forcibly consume caption insets are enabled.
     * * Top activity have configuration coupled with insets.
     * * Task is not resizeable.
     */
    fun shouldExcludeCaptionFromAppBounds(taskInfo: TaskInfo): Boolean =
        Flags.excludeCaptionFromAppBounds()
                && isAnyForceConsumptionFlagsEnabled()
                && taskInfo.topActivityInfo?.let {
            isInsetsCoupledWithConfiguration(it) && !taskInfo.isResizeable
        } ?: false

    /**
     * Returns true if all activities in a tasks stack are transparent. If there are no activities
     * will return false.
     */
    fun isTransparentTask(task: TaskInfo): Boolean =
        isTransparentTask(task.isActivityStackTransparent, task.numActivities)

    private fun isTransparentTask(isActivityStackTransparent: Boolean, numActivities: Int) =
        isActivityStackTransparent && numActivities > 0

    private fun isSystemUiTask(packageName: String?) = packageName == systemUiPackage

    /**
     * Returns true if the tasks base activity is part of the default home package.
     */
    private fun isPartOfDefaultHomePackage(packageName: String?) =
        packageName != null && packageName == defaultHomePackage

    private fun isAnyForceConsumptionFlagsEnabled(): Boolean =
        DesktopModeFlags.ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS.isTrue
            || DesktopModeFlags.ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION.isTrue

    private fun isInsetsCoupledWithConfiguration(info: ActivityInfo): Boolean =
        !(info.isChangeEnabled(OVERRIDE_ENABLE_INSETS_DECOUPLED_CONFIGURATION)
                || info.isChangeEnabled(INSETS_DECOUPLED_CONFIGURATION_ENFORCED))
}
