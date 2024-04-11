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

package com.android.systemui.screenshot.policy

import android.app.ActivityTaskManager.RootTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT
import android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.content.ComponentName
import android.graphics.Rect
import android.os.UserHandle
import android.view.Display
import com.android.systemui.screenshot.data.model.ChildTaskModel
import com.android.systemui.screenshot.policy.ActivityType.Standard
import com.android.systemui.screenshot.policy.WindowingMode.FullScreen

/** An enum mapping to [android.app.WindowConfiguration] constants via [toInt]. */
enum class ActivityType(private val intValue: Int) {
    Undefined(ACTIVITY_TYPE_UNDEFINED),
    Standard(ACTIVITY_TYPE_STANDARD),
    Home(ACTIVITY_TYPE_HOME),
    Recents(ACTIVITY_TYPE_RECENTS),
    Assistant(ACTIVITY_TYPE_ASSISTANT),
    Dream(ACTIVITY_TYPE_DREAM);

    /** Returns the [android.app.WindowConfiguration] int constant for the type. */
    fun toInt() = intValue
}

/** An enum mapping to [android.app.WindowConfiguration] constants via [toInt]. */
enum class WindowingMode(private val intValue: Int) {
    Undefined(WINDOWING_MODE_UNDEFINED),
    FullScreen(WINDOWING_MODE_FULLSCREEN),
    PictureInPicture(WINDOWING_MODE_PINNED),
    Freeform(WINDOWING_MODE_FREEFORM),
    MultiWindow(WINDOWING_MODE_MULTI_WINDOW);

    /** Returns the [android.app.WindowConfiguration] int constant for the mode. */
    fun toInt() = intValue
}

/**
 * Constructs a child task for a [RootTaskInfo], copying [RootTaskInfo.bounds] and
 * [RootTaskInfo.userId] from the parent by default.
 */
fun RootTaskInfo.newChildTask(
    taskId: Int,
    name: String,
    bounds: Rect? = null,
    userId: Int? = null
): ChildTaskModel {
    return ChildTaskModel(taskId, name, bounds ?: this.bounds, userId ?: this.userId)
}

/** Constructs a new [RootTaskInfo]. */
fun newRootTaskInfo(
    taskId: Int,
    userId: Int = UserHandle.USER_SYSTEM,
    displayId: Int = Display.DEFAULT_DISPLAY,
    visible: Boolean = true,
    running: Boolean = true,
    activityType: ActivityType = Standard,
    windowingMode: WindowingMode = FullScreen,
    bounds: Rect? = null,
    topActivity: ComponentName? = null,
    topActivityType: ActivityType = Standard,
    numActivities: Int? = null,
    childTaskListBuilder: RootTaskInfo.() -> List<ChildTaskModel>,
): RootTaskInfo {
    return RootTaskInfo().apply {
        configuration.windowConfiguration.apply {
            setWindowingMode(windowingMode.toInt())
            setActivityType(activityType.toInt())
            setBounds(bounds)
        }
        this.bounds = bounds
        this.displayId = displayId
        this.userId = userId
        this.taskId = taskId
        this.visible = visible
        this.isVisible = visible
        this.isRunning = running
        this.topActivity = topActivity
        this.topActivityType = topActivityType.toInt()
        // NOTE: topActivityInfo is _not_ populated by this code

        val childTasks = childTaskListBuilder(this)
        this.numActivities = numActivities ?: childTasks.size

        childTaskNames = childTasks.map { it.name }.toTypedArray()
        childTaskIds = childTasks.map { it.id }.toIntArray()
        childTaskBounds = childTasks.map { it.bounds }.toTypedArray()
        childTaskUserIds = childTasks.map { it.userId }.toIntArray()
    }
}
