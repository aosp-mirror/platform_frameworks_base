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

package com.android.systemui.screenshot.appclips

import android.app.TaskInfo
import android.content.ClipData
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * A class to hold the [ClipData] for backlinks, the corresponding app's [Drawable] icon, and
 * represent error when necessary.
 */
internal sealed class InternalBacklinksData(
    // Fields from this object are made accessible through accessors to keep call sites simpler.
    private val backlinkDisplayInfo: BacklinkDisplayInfo,
) {
    // Use separate field to access display label so that callers don't have to access through
    // internal object.
    var displayLabel: String
        get() = backlinkDisplayInfo.displayLabel
        set(value) {
            backlinkDisplayInfo.displayLabel = value
        }

    // Use separate field to access app icon so that callers don't have to access through internal
    // object.
    val appIcon: Drawable
        get() = backlinkDisplayInfo.appIcon

    data class BacklinksData(val clipData: ClipData, private val icon: Drawable) :
        InternalBacklinksData(BacklinkDisplayInfo(icon, clipData.description.label.toString()))

    data class CrossProfileError(private val icon: Drawable, private var label: String) :
        InternalBacklinksData(BacklinkDisplayInfo(icon, label))
}

/**
 * A class to hold important members of [TaskInfo] and its associated user's [PackageManager] for
 * ease of querying.
 *
 * @note A task can have a different app running on top. For example, an app "A" can use camera app
 *   to capture an image. In this case the top app will be the camera app even though the task
 *   belongs to app A. This is expected behaviour because user will be taking a screenshot of the
 *   content rendered by the camera (top) app.
 */
internal data class InternalTaskInfo(
    private val topActivityInfo: ActivityInfo,
    val taskId: Int,
    val userId: Int,
    val packageManager: PackageManager
) {
    val topActivityNameForDebugLogging: String = topActivityInfo.name
    val topActivityPackageName: String = topActivityInfo.packageName
    val topActivityAppName: String by lazy { topActivityInfo.getAppName(packageManager) }
    val topActivityAppIcon: Drawable by lazy { topActivityInfo.loadIcon(packageManager) }
}

internal fun ActivityInfo.getAppName(packageManager: PackageManager) =
    loadLabel(packageManager).toString()

internal fun ActivityInfo.getAppIcon(packageManager: PackageManager) = loadIcon(packageManager)

/** A class to hold data that is used for displaying backlink to user in SysUI activity. */
internal data class BacklinkDisplayInfo(val appIcon: Drawable, var displayLabel: String)
