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

@file:JvmName("AppCompatUtils")

package com.android.wm.shell.compatui

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import com.android.internal.R

// TODO(b/347289970): Consider replacing with API
/**
 * If the top activity should be exempt from desktop windowing and forced back to fullscreen.
 * Currently includes all system ui activities and modal dialogs. However if the top activity is not
 * being displayed, regardless of its configuration, we will not exempt it as to remain in the
 * desktop windowing environment.
 */
fun isTopActivityExemptFromDesktopWindowing(context: Context, task: RunningTaskInfo) =
    (isSystemUiTask(context, task) || isTransparentTask(task))
            && !task.isTopActivityNoDisplay

/**
 * Returns true if all activities in a tasks stack are transparent. If there are no activities will
 * return false.
 */
fun isTransparentTask(task: RunningTaskInfo): Boolean = task.isActivityStackTransparent
        && task.numActivities > 0

private fun isSystemUiTask(context: Context, task: RunningTaskInfo): Boolean {
    val sysUiPackageName: String =
        context.resources.getString(R.string.config_systemUi)
    return task.baseActivity?.packageName == sysUiPackageName
}
