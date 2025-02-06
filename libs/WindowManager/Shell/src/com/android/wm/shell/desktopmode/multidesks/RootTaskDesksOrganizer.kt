/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.wm.shell.desktopmode.multidesks

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.util.SparseArray
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.TransitionInfo
import android.window.WindowContainerTransaction
import androidx.core.util.forEach
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.desktopmode.multidesks.DesksOrganizer.OnCreateCallback
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellInit
import java.io.PrintWriter

/** A [DesksOrganizer] that uses root tasks as the container of each desk. */
class RootTaskDesksOrganizer(
    shellInit: ShellInit,
    shellCommandHandler: ShellCommandHandler,
    private val shellTaskOrganizer: ShellTaskOrganizer,
) : DesksOrganizer, ShellTaskOrganizer.TaskListener {

    private val deskCreateRequests = mutableListOf<CreateRequest>()
    @VisibleForTesting val roots = SparseArray<DeskRoot>()

    init {
        if (Flags.enableMultipleDesktopsBackend()) {
            shellInit.addInitCallback(
                { shellCommandHandler.addDumpCallback(this::dump, this) },
                this,
            )
        }
    }

    override fun createDesk(displayId: Int, callback: OnCreateCallback) {
        logV("createDesk in display: %d", displayId)
        deskCreateRequests += CreateRequest(displayId, callback)
        shellTaskOrganizer.createRootTask(
            displayId,
            WINDOWING_MODE_FREEFORM,
            /* listener = */ this,
            /* removeWithTaskOrganizer = */ true,
        )
    }

    override fun removeDesk(wct: WindowContainerTransaction, deskId: Int) {
        logV("removeDesk %d", deskId)
        val desk = checkNotNull(roots[deskId]) { "Root not found for desk: $deskId" }
        wct.removeRootTask(desk.taskInfo.token)
    }

    override fun activateDesk(wct: WindowContainerTransaction, deskId: Int) {
        logV("activateDesk %d", deskId)
        val root = checkNotNull(roots[deskId]) { "Root not found for desk: $deskId" }
        wct.reorder(root.taskInfo.token, /* onTop= */ true)
        wct.setLaunchRoot(
            /* container= */ root.taskInfo.token,
            /* windowingModes= */ intArrayOf(WINDOWING_MODE_FREEFORM, WINDOWING_MODE_UNDEFINED),
            /* activityTypes= */ intArrayOf(ACTIVITY_TYPE_UNDEFINED, ACTIVITY_TYPE_STANDARD),
        )
    }

    override fun moveTaskToDesk(
        wct: WindowContainerTransaction,
        deskId: Int,
        task: RunningTaskInfo,
    ) {
        val root = roots[deskId] ?: error("Root not found for desk: $deskId")
        wct.setWindowingMode(task.token, WINDOWING_MODE_UNDEFINED)
        wct.reparent(task.token, root.taskInfo.token, /* onTop= */ true)
    }

    override fun getDeskAtEnd(change: TransitionInfo.Change): Int? =
        change.taskInfo?.parentTaskId?.takeIf { it in roots }

    override fun isDeskActiveAtEnd(change: TransitionInfo.Change, deskId: Int): Boolean =
        change.taskInfo?.taskId == deskId &&
            change.taskInfo?.isVisibleRequested == true &&
            change.mode == TRANSIT_TO_FRONT

    override fun onTaskAppeared(taskInfo: RunningTaskInfo, leash: SurfaceControl) {
        if (taskInfo.parentTaskId in roots) {
            val deskId = taskInfo.parentTaskId
            val taskId = taskInfo.taskId
            logV("Task #$taskId appeared in desk #$deskId")
            addChildToDesk(taskId = taskId, deskId = deskId)
            return
        }
        val deskId = taskInfo.taskId
        check(deskId !in roots) { "A root already exists for desk: $deskId" }
        val request =
            checkNotNull(deskCreateRequests.firstOrNull { it.displayId == taskInfo.displayId }) {
                "Task ${taskInfo.taskId} appeared without pending create request"
            }
        logV("Desk #$deskId appeared")
        roots[deskId] = DeskRoot(deskId, taskInfo, leash)
        deskCreateRequests.remove(request)
        request.onCreateCallback.onCreated(deskId)
    }

    override fun onTaskInfoChanged(taskInfo: RunningTaskInfo) {
        if (roots.contains(taskInfo.taskId)) {
            val deskId = taskInfo.taskId
            roots[deskId] = roots[deskId].copy(taskInfo = taskInfo)
        }
    }

    override fun onTaskVanished(taskInfo: RunningTaskInfo) {
        if (roots.contains(taskInfo.taskId)) {
            val deskId = taskInfo.taskId
            val deskRoot = roots[deskId]
            // Use the last saved taskInfo to obtain the displayId. Using the local one here will
            // return -1 since the task is not unassociated with a display.
            val displayId = deskRoot.taskInfo.displayId
            logV("Desk #$deskId vanished from display #$displayId")
            roots.remove(deskId)
            return
        }
        // At this point, [parentTaskId] may be unset even if this is a task vanishing from a desk,
        // so search through each root to remove this if it's a child.
        roots.forEach { deskId, deskRoot ->
            if (deskRoot.children.remove(taskInfo.taskId)) {
                logV("Task #${taskInfo.taskId} vanished from desk #$deskId")
                return
            }
        }
    }

    @VisibleForTesting
    data class DeskRoot(
        val deskId: Int,
        val taskInfo: RunningTaskInfo,
        val leash: SurfaceControl,
        val children: MutableSet<Int> = mutableSetOf(),
    )

    override fun dump(pw: PrintWriter, prefix: String) {
        val innerPrefix = "$prefix  "
        pw.println("$prefix$TAG")
        pw.println("${innerPrefix}Desk Roots:")
        roots.forEach { deskId, root ->
            pw.println("$innerPrefix  #$deskId visible=${root.taskInfo.isVisible}")
            pw.println("$innerPrefix    children=${root.children}")
        }
    }

    private fun addChildToDesk(taskId: Int, deskId: Int) {
        roots.forEach { _, deskRoot ->
            if (deskRoot.deskId == deskId) {
                deskRoot.children.add(taskId)
            } else {
                deskRoot.children.remove(taskId)
            }
        }
    }

    private data class CreateRequest(val displayId: Int, val onCreateCallback: OnCreateCallback)

    private fun logV(msg: String, vararg arguments: Any?) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    companion object {
        private const val TAG = "RootTaskDesksOrganizer"
    }
}
