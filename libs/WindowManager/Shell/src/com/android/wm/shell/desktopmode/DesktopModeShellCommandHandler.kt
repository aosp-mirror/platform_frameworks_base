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

import android.content.res.Resources
import android.util.TypedValue
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.window.WindowContainerTransaction
import com.android.wm.shell.protolog.ShellProtoLogGroup
import com.android.wm.shell.shared.DesktopModeStatus
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.util.KtProtoLog
import com.android.wm.shell.windowdecor.ResizeHandleSizeRepository
import java.io.PrintWriter
import java.util.Optional

/**
 * Handles the shell commands for desktop windowing mode.
 *
 * <p>Use with {@code adb shell dumpsys activity service SystemUIService WMShell desktopmode ...}.
 */
class DesktopModeShellCommandHandler(
    shellInit: ShellInit,
    private val shellCommandHandler: ShellCommandHandler,
    private val controller: Optional<DesktopTasksController>,
    private val resizeHandleSizeRepository: ResizeHandleSizeRepository
) : ShellCommandHandler.ShellCommandActionHandler {

    private var resources: Resources? = null

    init {
        if (DesktopModeStatus.isEnabled()) {
            shellInit.addInitCallback(::onInit, this)
        }
    }

    private fun onInit() {
        KtProtoLog.d(ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
            "Initialize DesktopModeShellCommandHandler")
        shellCommandHandler.addCommandCallback("desktopmode", this, this)
        resources = if (controller.isPresent) controller.get().context.resources else null
    }

    override fun onShellCommand(args: Array<String>, pw: PrintWriter): Boolean {
        return when (args[0]) {
            "moveToDesktop" -> {
                if (!runMoveToDesktop(args, pw)) {
                    pw.println("Task not found. Please enter a valid taskId.")
                    false
                } else {
                    true
                }
            }
            "moveToNextDisplay" -> {
                if (!runMoveToNextDisplay(args, pw)) {
                    pw.println("Task not found. Please enter a valid taskId.")
                    false
                } else {
                    true
                }
            }
            "edgeResizeHandle" -> {
                return updateEdgeResizeHandle(args, pw)
            }
            else -> {
                pw.println("Invalid command: ${args[0]}")
                false
            }
        }
    }

    private fun runMoveToDesktop(args: Array<String>, pw: PrintWriter): Boolean {
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: task id should be provided as arguments")
            return false
        }

        val taskId =
            try {
                args[1].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: task id should be an integer")
                return false
            }

        return if (controller.isPresent) {
            controller.get().moveToDesktop(taskId, WindowContainerTransaction())
        } else {
            false
        }
    }

    private fun runMoveToNextDisplay(args: Array<String>, pw: PrintWriter): Boolean {
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: task id should be provided as arguments")
            return false
        }

        val taskId =
            try {
                args[1].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: task id should be an integer")
                return false
            }

        if (controller.isPresent) {
            controller.get().moveToNextDisplay(taskId)
            return true
        } else {
            return false
        }
    }

    private fun updateEdgeResizeHandle(args: Array<String>, pw: PrintWriter): Boolean {
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: edge resize action should be provided as arguments [set|reset|get]")
            return false
        }
        when (val action = args[1]) {
            "set" -> {
                if (resources == null) {
                    pw.println("Error: unable to convert given dp to pixel units")
                    return false
                }
                if (args.size < 3) {
                    pw.println(
                        "Error: edge resize size should be provided as argument " +
                            "<positive integer>"
                    )
                    return false
                }
                val newEdgeSizeDp =
                    try {
                        args[2].toFloat()
                    } catch (e: NumberFormatException) {
                        pw.println("Error: edge resize width should be an integer")
                        return false
                    }
                if (newEdgeSizeDp <= 0) {
                    pw.println("Error: edge resize width should be a positive integer")
                    return false
                }
                val newEdgeSizePixels =
                    TypedValue.convertDimensionToPixels(
                            COMPLEX_UNIT_DIP,
                            newEdgeSizeDp,
                            resources!!.displayMetrics
                        )
                        .toInt()
                resizeHandleSizeRepository.setResizeEdgeHandlePixels(newEdgeSizePixels)
                pw.println(
                    "Handling set request for edge handle size of $newEdgeSizeDp dp " +
                        "(or $newEdgeSizePixels px)"
                )
            }
            "reset" -> {
                resizeHandleSizeRepository.resetResizeEdgeHandlePixels()
                pw.println("Handling reset request for edge handle size")
            }
            "get" -> {
                if (resources == null) {
                    pw.println("Error: unable to retrieve edge handle size")
                    return false
                }
                val edgeSizePixels = resizeHandleSizeRepository
                                .getResizeEdgeHandlePixels(resources!!)
                                .toFloat()
                val edgeSizeDp =
                    TypedValue.deriveDimension(
                        COMPLEX_UNIT_DIP,
                        edgeSizePixels,
                        resources!!.displayMetrics
                    )
                pw.println("Current edge handle size is $edgeSizeDp dp (or $edgeSizePixels px)")
            }
            else -> {
                pw.println(
                    "Error: must provide a valid argument (set, reset, or get); received " + action
                )
                return false
            }
        }
        return true
    }

    override fun printShellCommandHelp(pw: PrintWriter, prefix: String) {
        pw.println("$prefix moveToDesktop <taskId> ")
        pw.println("$prefix  Move a task with given id to desktop mode.")
        pw.println("$prefix moveToNextDisplay <taskId> ")
        pw.println("$prefix  Move a task with given id to next display.")
        pw.println("$prefix edgeResizeHandle set <positive integer>")
        pw.println("$prefix  Sets the width of the handle, in dp, to use for edge resizing.")
        pw.println("$prefix edgeResizeHandle reset")
        pw.println("$prefix  Restore the original width of the handle to use for edge resizing.")
        pw.println("$prefix edgeResizeHandle get")
        pw.println(
            "$prefix  Retrieves the current width, in dp, of the handle to use for edge " +
                "resizing."
        )
    }
}
