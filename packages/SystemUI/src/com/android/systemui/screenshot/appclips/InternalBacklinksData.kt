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
    open val appIcon: Drawable,
    open var displayLabel: String
) {
    data class BacklinksData(val clipData: ClipData, override val appIcon: Drawable) :
        InternalBacklinksData(appIcon, clipData.description.label.toString())

    data class CrossProfileError(
        override val appIcon: Drawable,
        override var displayLabel: String
    ) : InternalBacklinksData(appIcon, displayLabel)
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
    fun getTopActivityNameForDebugLogging(): String = topActivityInfo.name

    fun getTopActivityPackageName(): String = topActivityInfo.packageName

    fun getTopActivityAppName(): String = topActivityInfo.loadLabel(packageManager).toString()

    fun getTopActivityAppIcon(): Drawable = topActivityInfo.loadIcon(packageManager)
}
