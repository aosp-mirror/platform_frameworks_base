/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.mediaprojection.appselector.data

import android.annotation.ColorInt
import android.annotation.UserIdInt
import android.app.ActivityManager.RecentTaskInfo
import android.content.ComponentName
import com.android.wm.shell.shared.split.SplitBounds

data class RecentTask(
    val taskId: Int,
    val displayId: Int,
    @UserIdInt val userId: Int,
    val topActivityComponent: ComponentName?,
    val baseIntentComponent: ComponentName?,
    @ColorInt val colorBackground: Int?,
    val isForegroundTask: Boolean,
    val userType: UserType,
    val splitBounds: SplitBounds?,
) {
    constructor(
        taskInfo: RecentTaskInfo,
        isForegroundTask: Boolean,
        userType: UserType,
        splitBounds: SplitBounds? = null
    ) : this(
        taskInfo.taskId,
        taskInfo.displayId,
        taskInfo.userId,
        taskInfo.topActivity,
        taskInfo.baseIntent?.component,
        taskInfo.taskDescription?.backgroundColor,
        isForegroundTask,
        userType,
        splitBounds
    )

    enum class UserType {
        STANDARD,
        WORK,
        PRIVATE,
        CLONED
    }
}
