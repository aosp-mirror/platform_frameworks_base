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

import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.os.IBinder
import android.view.SurfaceControl
import android.view.WindowManager
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.TransitionInfo
import android.window.WindowContainerTransaction
import android.window.DesktopModeFlags
import android.window.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes.isExitDesktopModeTransition
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.shared.TransitionUtil
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions

/**
 * A [Transitions.TransitionObserver] that observes shell transitions and updates the
 * [DesktopRepository] state TODO: b/332682201 This observes transitions related to desktop mode and
 * other transitions that originate both within and outside shell.
 */
class DesktopTasksTransitionObserver(
    private val context: Context,
    private val desktopRepository: DesktopRepository,
    private val transitions: Transitions,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    shellInit: ShellInit
) : Transitions.TransitionObserver {

    private var transitionToCloseWallpaper: IBinder? = null

    init {
        if (DesktopModeStatus.canEnterDesktopMode(context)) {
            shellInit.addInitCallback(::onInit, this)
        }
    }

    fun onInit() {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "DesktopTasksTransitionObserver: onInit")
        transitions.registerObserver(this)
    }

    override fun onTransitionReady(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction
    ) {
        // TODO: b/332682201 Update repository state
        updateWallpaperToken(info)
        if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION.isTrue()) {
            handleBackNavigation(info)
            removeTaskIfNeeded(info)
        }
        removeWallpaperOnLastTaskClosingIfNeeded(transition, info)
    }

    private fun removeTaskIfNeeded(info: TransitionInfo) {
        // Since we are no longer removing all the tasks [onTaskVanished], we need to remove them by
        // checking the transitions.
        if (!(TransitionUtil.isOpeningType(info.type) || info.type.isExitDesktopModeTransition())) {
            return
        }
        // Remove a task from the repository if the app is launched outside of desktop.
        for (change in info.changes) {
            val taskInfo = change.taskInfo
            if (taskInfo == null || taskInfo.taskId == -1) continue

            if (desktopRepository.isActiveTask(taskInfo.taskId) &&
                taskInfo.windowingMode != WINDOWING_MODE_FREEFORM) {
                desktopRepository.removeFreeformTask(taskInfo.displayId, taskInfo.taskId)
            }
        }
    }

    private fun handleBackNavigation(info: TransitionInfo) {
        // When default back navigation happens, transition type is TO_BACK and the change is
        // TO_BACK. Mark the task going to back as minimized.
        if (info.type == TRANSIT_TO_BACK) {
            for (change in info.changes) {
                val taskInfo = change.taskInfo
                if (taskInfo == null || taskInfo.taskId == -1) {
                    continue
                }

                if (desktopRepository.getVisibleTaskCount(taskInfo.displayId) > 0 &&
                    change.mode == TRANSIT_TO_BACK &&
                    taskInfo.windowingMode == WINDOWING_MODE_FREEFORM) {
                    desktopRepository.minimizeTask(taskInfo.displayId, taskInfo.taskId)
                }
            }
        }
    }

    private fun removeWallpaperOnLastTaskClosingIfNeeded(
        transition: IBinder,
        info: TransitionInfo
    ) {
        for (change in info.changes) {
            val taskInfo = change.taskInfo
            if (taskInfo == null || taskInfo.taskId == -1) {
                continue
            }

            if (desktopRepository.getVisibleTaskCount(taskInfo.displayId) == 1 &&
                change.mode == TRANSIT_CLOSE &&
                taskInfo.windowingMode == WINDOWING_MODE_FREEFORM &&
                desktopRepository.wallpaperActivityToken != null) {
                transitionToCloseWallpaper = transition
            }
        }
    }

    override fun onTransitionStarting(transition: IBinder) {
        // TODO: b/332682201 Update repository state
    }

    override fun onTransitionMerged(merged: IBinder, playing: IBinder) {
        // TODO: b/332682201 Update repository state
    }

    override fun onTransitionFinished(transition: IBinder, aborted: Boolean) {
        // TODO: b/332682201 Update repository state
        if (transitionToCloseWallpaper == transition) {
            // TODO: b/362469671 - Handle merging the animation when desktop is also closing.
            desktopRepository.wallpaperActivityToken?.let { wallpaperActivityToken ->
                transitions.startTransition(
                    TRANSIT_CLOSE,
                    WindowContainerTransaction().removeTask(wallpaperActivityToken),
                    null)
            }
            transitionToCloseWallpaper = null
        }
    }

    private fun updateWallpaperToken(info: TransitionInfo) {
        if (!ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()) {
            return
        }
        info.changes.forEach { change ->
            change.taskInfo?.let { taskInfo ->
                if (DesktopWallpaperActivity.isWallpaperTask(taskInfo)) {
                    when (change.mode) {
                        WindowManager.TRANSIT_OPEN -> {
                            desktopRepository.wallpaperActivityToken = taskInfo.token
                            // After the task for the wallpaper is created, set it non-trimmable.
                            // This is important to prevent recents from trimming and removing the
                            // task.
                            shellTaskOrganizer.applyTransaction(
                                WindowContainerTransaction()
                                    .setTaskTrimmableFromRecents(taskInfo.token, false))
                        }
                        TRANSIT_CLOSE ->
                            desktopRepository.wallpaperActivityToken = null
                        else -> {}
                    }
                }
            }
        }
    }
}
