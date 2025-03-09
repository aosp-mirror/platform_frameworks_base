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

package com.android.wm.shell.desktopmode

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.window.DesktopModeFlags
import com.android.wm.shell.freeform.TaskChangeListener

/** Manages tasks handling specific to Android Desktop Mode. */
class DesktopTaskChangeListener(private val desktopUserRepositories: DesktopUserRepositories) :
    TaskChangeListener {

    override fun onTaskOpening(taskInfo: RunningTaskInfo) {
        val desktopRepository: DesktopRepository =
            desktopUserRepositories.getProfile(taskInfo.userId)
        if (!isFreeformTask(taskInfo) && desktopRepository.isActiveTask(taskInfo.taskId)) {
            desktopRepository.removeFreeformTask(taskInfo.displayId, taskInfo.taskId)
            return
        }
        if (isFreeformTask(taskInfo)) {
            desktopRepository.addTask(taskInfo.displayId, taskInfo.taskId, taskInfo.isVisible)
        }
    }

    override fun onTaskChanging(taskInfo: RunningTaskInfo) {
        val desktopRepository: DesktopRepository =
            desktopUserRepositories.getProfile(taskInfo.userId)
        if (!desktopRepository.isActiveTask(taskInfo.taskId)) return

        // Case 1: Freeform task is changed in Desktop Mode.
        if (isFreeformTask(taskInfo)) {
            if (taskInfo.isVisible) {
                desktopRepository.addTask(taskInfo.displayId, taskInfo.taskId, taskInfo.isVisible)
            }
            desktopRepository.updateTask(taskInfo.displayId, taskInfo.taskId, taskInfo.isVisible)
        } else {
            // Case 2: Freeform task is changed outside Desktop Mode.
            desktopRepository.removeFreeformTask(taskInfo.displayId, taskInfo.taskId)
        }
    }

    // This method should only be used for scenarios where the task info changes are not propagated
    // to
    // [DesktopTaskChangeListener#onTaskChanging] via [TransitionsObserver].
    // Any changes to [DesktopRepository] from this method should be made carefully to minimize risk
    // of race conditions and possible duplications with [onTaskChanging].
    override fun onNonTransitionTaskChanging(taskInfo: RunningTaskInfo) {
        // TODO: b/367268953 - Propapagate usages from FreeformTaskListener to this method.
    }

    override fun onTaskMovingToFront(taskInfo: RunningTaskInfo) {
        val desktopRepository: DesktopRepository =
            desktopUserRepositories.getProfile(taskInfo.userId)
        if (!desktopRepository.isActiveTask(taskInfo.taskId)) return
        if (!isFreeformTask(taskInfo)) {
            desktopRepository.removeFreeformTask(taskInfo.displayId, taskInfo.taskId)
        }
        // TODO: b/367268953 - Connect this with DesktopRepository for handling
        // task moving to front for tasks in windowing mode.
    }

    override fun onTaskMovingToBack(taskInfo: RunningTaskInfo) {
        // TODO: b/367268953 - Connect this with DesktopRepository.
    }

    override fun onTaskClosing(taskInfo: RunningTaskInfo) {
        val desktopRepository: DesktopRepository =
            desktopUserRepositories.getProfile(taskInfo.userId)
        if (!desktopRepository.isActiveTask(taskInfo.taskId)) return
        // TODO: b/370038902 - Handle Activity#finishAndRemoveTask.
        if (
            !DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION.isTrue() ||
                desktopRepository.isClosingTask(taskInfo.taskId)
        ) {
            // A task that's vanishing should be removed:
            // - If it's closed by the X button which means it's marked as a closing task.
            desktopRepository.removeClosingTask(taskInfo.taskId)
            desktopRepository.removeFreeformTask(taskInfo.displayId, taskInfo.taskId)
        } else {
            desktopRepository.updateTask(taskInfo.displayId, taskInfo.taskId, isVisible = false)
            desktopRepository.minimizeTask(taskInfo.displayId, taskInfo.taskId)
        }
    }

    private fun isFreeformTask(taskInfo: RunningTaskInfo): Boolean =
        taskInfo.windowingMode == WINDOWING_MODE_FREEFORM
}
