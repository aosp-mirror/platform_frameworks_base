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

package com.android.wm.shell.desktopmode

import android.app.ActivityManager
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.app.WindowConfiguration.WindowingMode
import android.content.Context
import android.os.IBinder
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import androidx.annotation.BinderThread
import com.android.internal.protolog.common.ProtoLog
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.ExecutorUtils
import com.android.wm.shell.common.ExternalInterfaceBinder
import com.android.wm.shell.common.RemoteCallable
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.annotations.ExternalThread
import com.android.wm.shell.common.annotations.ShellMainThread
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository.VisibleTasksListener
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.ShellSharedConstants
import com.android.wm.shell.transition.Transitions
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Handles moving tasks in and out of desktop */
class DesktopTasksController(
    private val context: Context,
    shellInit: ShellInit,
    private val shellController: ShellController,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val transitions: Transitions,
    private val desktopModeTaskRepository: DesktopModeTaskRepository,
    @ShellMainThread private val mainExecutor: ShellExecutor
) : RemoteCallable<DesktopTasksController>, Transitions.TransitionHandler {

    private val desktopMode: DesktopModeImpl

    init {
        desktopMode = DesktopModeImpl()
        if (DesktopModeStatus.isProto2Enabled()) {
            shellInit.addInitCallback({ onInit() }, this)
        }
    }

    private fun onInit() {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "Initialize DesktopTasksController")
        shellController.addExternalInterface(
            ShellSharedConstants.KEY_EXTRA_SHELL_DESKTOP_MODE,
            { createExternalInterface() },
            this
        )
        transitions.addHandler(this)
    }

    /** Show all tasks, that are part of the desktop, on top of launcher */
    fun showDesktopApps() {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "showDesktopApps")
        val wct = WindowContainerTransaction()
        bringDesktopAppsToFront(wct, force = true)

        // Execute transaction if there are pending operations
        if (!wct.isEmpty) {
            if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                transitions.startTransition(TRANSIT_TO_FRONT, wct, null /* handler */)
            } else {
                shellTaskOrganizer.applyTransaction(wct)
            }
        }
    }

    /** Get number of tasks that are marked as visible */
    fun getVisibleTaskCount(): Int {
        return desktopModeTaskRepository.getVisibleTaskCount()
    }

    /** Move a task with given `taskId` to desktop */
    fun moveToDesktop(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task -> moveToDesktop(task) }
    }

    /** Move a task to desktop */
    fun moveToDesktop(task: ActivityManager.RunningTaskInfo) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "moveToDesktop: %d", task.taskId)

        val wct = WindowContainerTransaction()
        // Bring other apps to front first
        bringDesktopAppsToFront(wct)

        wct.setWindowingMode(task.getToken(), WINDOWING_MODE_FREEFORM)
        wct.reorder(task.getToken(), true /* onTop */)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /** Move a task with given `taskId` to fullscreen */
    fun moveToFullscreen(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task -> moveToFullscreen(task) }
    }

    /** Move a task to fullscreen */
    fun moveToFullscreen(task: ActivityManager.RunningTaskInfo) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "moveToFullscreen: %d", task.taskId)

        val wct = WindowContainerTransaction()
        wct.setWindowingMode(task.getToken(), WINDOWING_MODE_FULLSCREEN)
        wct.setBounds(task.getToken(), null)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /**
     * Get windowing move for a given `taskId`
     *
     * @return [WindowingMode] for the task or [WINDOWING_MODE_UNDEFINED] if task is not found
     */
    @WindowingMode
    fun getTaskWindowingMode(taskId: Int): Int {
        return shellTaskOrganizer.getRunningTaskInfo(taskId)?.windowingMode
            ?: WINDOWING_MODE_UNDEFINED
    }

    private fun bringDesktopAppsToFront(wct: WindowContainerTransaction, force: Boolean = false) {
        val activeTasks = desktopModeTaskRepository.getActiveTasks()

        // Skip if all tasks are already visible
        if (!force && activeTasks.all(desktopModeTaskRepository::isVisibleTask)) {
            ProtoLog.d(
                WM_SHELL_DESKTOP_MODE,
                "bringDesktopAppsToFront: active tasks are already in front, skipping."
            )
            return
        }

        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "bringDesktopAppsToFront")

        // First move home to front and then other tasks on top of it
        moveHomeTaskToFront(wct)

        val allTasksInZOrder = desktopModeTaskRepository.getFreeformTasksInZOrder()
        activeTasks
            // Sort descending as the top task is at index 0. It should be ordered to top last
            .sortedByDescending { taskId -> allTasksInZOrder.indexOf(taskId) }
            .mapNotNull { taskId -> shellTaskOrganizer.getRunningTaskInfo(taskId) }
            .forEach { task -> wct.reorder(task.token, true /* onTop */) }
    }

    private fun moveHomeTaskToFront(wct: WindowContainerTransaction) {
        shellTaskOrganizer
            .getRunningTasks(context.displayId)
            .firstOrNull { task -> task.activityType == ACTIVITY_TYPE_HOME }
            ?.let { homeTask -> wct.reorder(homeTask.getToken(), true /* onTop */) }
    }

    override fun getContext(): Context {
        return context
    }

    override fun getRemoteCallExecutor(): ShellExecutor {
        return mainExecutor
    }

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback
    ): Boolean {
        // This handler should never be the sole handler, so should not animate anything.
        return false
    }

    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo
    ): WindowContainerTransaction? {
        // Check if we should skip handling this transition
        val task: ActivityManager.RunningTaskInfo? = request.triggerTask
        val shouldHandleRequest =
            when {
                // Only handle open or to front transitions
                request.type != TRANSIT_OPEN && request.type != TRANSIT_TO_FRONT -> false
                // Only handle when it is a task transition
                task == null -> false
                // Only handle standard type tasks
                task.activityType != ACTIVITY_TYPE_STANDARD -> false
                // Only handle fullscreen or freeform tasks
                task.windowingMode != WINDOWING_MODE_FULLSCREEN &&
                    task.windowingMode != WINDOWING_MODE_FREEFORM -> false
                // Otherwise process it
                else -> true
            }

        if (!shouldHandleRequest) {
            return null
        }

        val activeTasks = desktopModeTaskRepository.getActiveTasks()

        // Check if we should switch a fullscreen task to freeform
        if (task?.windowingMode == WINDOWING_MODE_FULLSCREEN) {
            // If there are any visible desktop tasks, switch the task to freeform
            if (activeTasks.any { desktopModeTaskRepository.isVisibleTask(it) }) {
                ProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController#handleRequest: switch fullscreen task to freeform," +
                        " taskId=%d",
                    task.taskId
                )
                return WindowContainerTransaction().apply {
                    setWindowingMode(task.token, WINDOWING_MODE_FREEFORM)
                }
            }
        }

        // CHeck if we should switch a freeform task to fullscreen
        if (task?.windowingMode == WINDOWING_MODE_FREEFORM) {
            // If no visible desktop tasks, switch this task to freeform as the transition came
            // outside of this controller
            if (activeTasks.none { desktopModeTaskRepository.isVisibleTask(it) }) {
                ProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController#handleRequest: switch freeform task to fullscreen," +
                        " taskId=%d",
                    task.taskId
                )
                return WindowContainerTransaction().apply {
                    setWindowingMode(task.token, WINDOWING_MODE_FULLSCREEN)
                    setBounds(task.token, null)
                }
            }
        }
        return null
    }

    /** Creates a new instance of the external interface to pass to another process. */
    private fun createExternalInterface(): ExternalInterfaceBinder {
        return IDesktopModeImpl(this)
    }

    /** Get connection interface between sysui and shell */
    fun asDesktopMode(): DesktopMode {
        return desktopMode
    }

    /**
     * Adds a listener to find out about changes in the visibility of freeform tasks.
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    fun addListener(listener: VisibleTasksListener, callbackExecutor: Executor) {
        desktopModeTaskRepository.addVisibleTasksListener(listener, callbackExecutor)
    }

    /** The interface for calls from outside the shell, within the host process. */
    @ExternalThread
    private inner class DesktopModeImpl : DesktopMode {
        override fun addListener(listener: VisibleTasksListener, callbackExecutor: Executor) {
            mainExecutor.execute {
                this@DesktopTasksController.addListener(listener, callbackExecutor)
            }
        }
    }

    /** The interface for calls from outside the host process. */
    @BinderThread
    private class IDesktopModeImpl(private var controller: DesktopTasksController?) :
        IDesktopMode.Stub(), ExternalInterfaceBinder {
        /** Invalidates this instance, preventing future calls from updating the controller. */
        override fun invalidate() {
            controller = null
        }

        override fun showDesktopApps() {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "showDesktopApps",
                Consumer(DesktopTasksController::showDesktopApps)
            )
        }

        override fun getVisibleTaskCount(): Int {
            val result = IntArray(1)
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "getVisibleTaskCount",
                { controller -> result[0] = controller.getVisibleTaskCount() },
                true /* blocking */
            )
            return result[0]
        }
    }
}
