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

import android.app.ActivityManager
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.os.IBinder
import android.view.SurfaceControl
import android.view.WindowManager
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.DesktopModeFlags
import android.window.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
import android.window.TransitionInfo
import android.window.WindowContainerTransaction
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.back.BackAnimationController
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
    private val desktopUserRepositories: DesktopUserRepositories,
    private val transitions: Transitions,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val desktopMixedTransitionHandler: DesktopMixedTransitionHandler,
    private val backAnimationController: BackAnimationController,
    shellInit: ShellInit,
) : Transitions.TransitionObserver {

    private var transitionToCloseWallpaper: IBinder? = null
    private var currentProfileId: Int

    init {
        if (DesktopModeStatus.canEnterDesktopMode(context)) {
            shellInit.addInitCallback(::onInit, this)
        }
        currentProfileId = ActivityManager.getCurrentUser()
    }

    fun onInit() {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "DesktopTasksTransitionObserver: onInit")
        transitions.registerObserver(this)
    }

    override fun onTransitionReady(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
    ) {
        // TODO: b/332682201 Update repository state
        updateWallpaperToken(info)
        if (DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION.isTrue()) {
            handleBackNavigation(transition, info)
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

            val desktopRepository = desktopUserRepositories.getProfile(taskInfo.userId)
            if (
                desktopRepository.isActiveTask(taskInfo.taskId) &&
                    taskInfo.windowingMode != WINDOWING_MODE_FREEFORM
            ) {
                desktopRepository.removeFreeformTask(taskInfo.displayId, taskInfo.taskId)
            }
        }
    }

    private fun handleBackNavigation(transition: IBinder, info: TransitionInfo) {
        // When default back navigation happens, transition type is TO_BACK and the change is
        // TO_BACK. Mark the task going to back as minimized.
        if (info.type == TRANSIT_TO_BACK) {
            for (change in info.changes) {
                val taskInfo = change.taskInfo
                if (taskInfo == null || taskInfo.taskId == -1) {
                    continue
                }
                val desktopRepository = desktopUserRepositories.getProfile(taskInfo.userId)
                val visibleTaskCount = desktopRepository.getVisibleTaskCount(taskInfo.displayId)
                if (
                    visibleTaskCount > 0 &&
                        change.mode == TRANSIT_TO_BACK &&
                        taskInfo.windowingMode == WINDOWING_MODE_FREEFORM
                ) {
                    desktopRepository.minimizeTask(taskInfo.displayId, taskInfo.taskId)
                    desktopMixedTransitionHandler.addPendingMixedTransition(
                        DesktopMixedTransitionHandler.PendingMixedTransition.Minimize(
                            transition,
                            taskInfo.taskId,
                            visibleTaskCount == 1,
                        )
                    )
                }
            }
        } else if (info.type == TRANSIT_CLOSE) {
            // In some cases app will be closing as a result of back navigation but we would like
            // to minimize. Mark the task closing as minimized.
            var hasWallpaperClosing = false
            var minimizingTask: Int? = null
            for (change in info.changes) {
                val taskInfo = change.taskInfo
                if (taskInfo == null || taskInfo.taskId == -1) continue
                if (change.mode != TRANSIT_CLOSE) continue

                if (minimizingTask == null) {
                    minimizingTask = getMinimizingTaskForClosingTransition(taskInfo)
                }

                if (DesktopWallpaperActivity.isWallpaperTask(taskInfo)) {
                    hasWallpaperClosing = true
                }
            }

            if (minimizingTask == null) return
            // If the transition has wallpaper closing, it means we are moving out of desktop.
            desktopMixedTransitionHandler.addPendingMixedTransition(
                DesktopMixedTransitionHandler.PendingMixedTransition.Minimize(
                    transition,
                    minimizingTask,
                    isLastTask = hasWallpaperClosing,
                )
            )
        }
    }

    /**
     * Given this a closing task in a closing transition, a task is assumed to be closed by back
     * navigation if:
     * 1) Desktop mode is visible.
     * 2) Task is in freeform.
     * 3) Task is the latest task that the back gesture is triggered on.
     * 4) It's not marked as a closing task as a result of closing it by the app header.
     *
     * This doesn't necessarily mean all the cases are because of back navigation but those cases
     * will be rare. E.g. triggering back navigation on an app that pops up a close dialog, and
     * closing it will minimize it here.
     */
    private fun getMinimizingTaskForClosingTransition(
        taskInfo: ActivityManager.RunningTaskInfo
    ): Int? {
        val desktopRepository = desktopUserRepositories.getProfile(taskInfo.userId)
        val visibleTaskCount = desktopRepository.getVisibleTaskCount(taskInfo.displayId)
        if (
            visibleTaskCount > 0 &&
                taskInfo.windowingMode == WINDOWING_MODE_FREEFORM &&
                backAnimationController.latestTriggerBackTask == taskInfo.taskId &&
                !desktopRepository.isClosingTask(taskInfo.taskId)
        ) {
            desktopRepository.minimizeTask(taskInfo.displayId, taskInfo.taskId)
            return taskInfo.taskId
        }
        return null
    }

    private fun removeWallpaperOnLastTaskClosingIfNeeded(
        transition: IBinder,
        info: TransitionInfo,
    ) {
        // TODO: 380868195 - Smooth animation for wallpaper activity closing just by itself
        for (change in info.changes) {
            val taskInfo = change.taskInfo
            if (taskInfo == null || taskInfo.taskId == -1) {
                continue
            }

            val desktopRepository = desktopUserRepositories.getProfile(taskInfo.userId)
            if (
                desktopRepository.getVisibleTaskCount(taskInfo.displayId) == 0 &&
                    change.mode == TRANSIT_CLOSE &&
                    taskInfo.windowingMode == WINDOWING_MODE_FREEFORM &&
                    desktopRepository.wallpaperActivityToken != null
            ) {
                transitionToCloseWallpaper = transition
                currentProfileId = taskInfo.userId
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
            val desktopRepository = desktopUserRepositories.getProfile(currentProfileId)
            desktopRepository.wallpaperActivityToken?.let { wallpaperActivityToken ->
                transitions.startTransition(
                    TRANSIT_CLOSE,
                    WindowContainerTransaction().removeTask(wallpaperActivityToken),
                    null,
                )
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
                    val desktopRepository = desktopUserRepositories.getProfile(taskInfo.userId)
                    when (change.mode) {
                        WindowManager.TRANSIT_OPEN -> {
                            desktopRepository.wallpaperActivityToken = taskInfo.token
                            // After the task for the wallpaper is created, set it non-trimmable.
                            // This is important to prevent recents from trimming and removing the
                            // task.
                            shellTaskOrganizer.applyTransaction(
                                WindowContainerTransaction()
                                    .setTaskTrimmableFromRecents(taskInfo.token, false)
                            )
                        }
                        TRANSIT_CLOSE -> desktopRepository.wallpaperActivityToken = null
                        else -> {}
                    }
                }
            }
        }
    }
}
