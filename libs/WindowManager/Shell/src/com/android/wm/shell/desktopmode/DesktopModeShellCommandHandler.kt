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

import com.android.window.flags.Flags
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource.UNKNOWN
import com.android.wm.shell.sysui.ShellCommandHandler
import java.io.PrintWriter

/** Handles the shell commands for the DesktopTasksController. */
class DesktopModeShellCommandHandler(private val controller: DesktopTasksController) :
    ShellCommandHandler.ShellCommandActionHandler {

    override fun onShellCommand(args: Array<String>, pw: PrintWriter): Boolean =
        when (args[0]) {
            "moveTaskToDesk" -> runMoveTaskToDesk(args, pw)
            "moveToNextDisplay" -> runMoveToNextDisplay(args, pw)
            "createDesk" -> runCreateDesk(args, pw)
            "activateDesk" -> runActivateDesk(args, pw)
            "removeDesk" -> runRemoveDesk(args, pw)
            "removeAllDesks" -> runRemoveAllDesks(args, pw)
            "moveTaskToFront" -> runMoveTaskToFront(args, pw)
            "moveTaskOutOfDesk" -> runMoveTaskOutOfDesk(args, pw)
            "canCreateDesk" -> runCanCreateDesk(args, pw)
            "getActiveDeskId" -> runGetActiveDeskId(args, pw)
            else -> {
                pw.println("Invalid command: ${args[0]}")
                false
            }
        }

    private fun runMoveTaskToDesk(args: Array<String>, pw: PrintWriter): Boolean {
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
        if (!Flags.enableMultipleDesktopsBackend()) {
            return controller.moveTaskToDesktop(taskId, transitionSource = UNKNOWN)
        }
        if (args.size < 3) {
            pw.println("Error: desk id should be provided as arguments")
            return false
        }
        val deskId =
            try {
                args[2].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: desk id should be an integer")
                return false
            }
        pw.println("Not implemented.")
        return false
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

        controller.moveToNextDisplay(taskId)
        return true
    }

    private fun runCreateDesk(args: Array<String>, pw: PrintWriter): Boolean {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("Not supported.")
            return false
        }
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: desk id should be provided as arguments")
            return false
        }
        val displayId =
            try {
                args[1].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: display id should be an integer")
                return false
            }
        controller.createDesk(displayId)
        return true
    }

    private fun runActivateDesk(args: Array<String>, pw: PrintWriter): Boolean {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("Not supported.")
            return false
        }
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: desk id should be provided as arguments")
            return false
        }
        val deskId =
            try {
                args[1].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: desk id should be an integer")
                return false
            }
        pw.println("Not implemented.")
        return false
    }

    private fun runRemoveDesk(args: Array<String>, pw: PrintWriter): Boolean {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("Not supported.")
            return false
        }
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: desk id should be provided as arguments")
            return false
        }
        val deskId =
            try {
                args[1].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: desk id should be an integer")
                return false
            }
        controller.removeDesk(deskId)
        return true
    }

    private fun runRemoveAllDesks(args: Array<String>, pw: PrintWriter): Boolean {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("Not supported.")
            return false
        }
        pw.println("Not implemented.")
        return false
    }

    private fun runMoveTaskToFront(args: Array<String>, pw: PrintWriter): Boolean {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("Not supported.")
            return false
        }
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
        pw.println("Not implemented.")
        return false
    }

    private fun runMoveTaskOutOfDesk(args: Array<String>, pw: PrintWriter): Boolean {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("Not supported.")
            return false
        }
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
        pw.println("Not implemented.")
        return false
    }

    private fun runCanCreateDesk(args: Array<String>, pw: PrintWriter): Boolean {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("Not supported.")
            return false
        }
        val displayId =
            try {
                args[1].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: display id should be an integer")
                return false
            }
        pw.println("Not implemented.")
        return false
    }

    private fun runGetActiveDeskId(args: Array<String>, pw: PrintWriter): Boolean {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("Not supported.")
            return false
        }
        if (args.size < 2) {
            // First argument is the action name.
            pw.println("Error: task id should be provided as arguments")
            return false
        }
        val displayId =
            try {
                args[1].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: display id should be an integer")
                return false
            }
        pw.println("Not implemented.")
        return false
    }

    override fun printShellCommandHelp(pw: PrintWriter, prefix: String) {
        if (!Flags.enableMultipleDesktopsBackend()) {
            pw.println("$prefix moveTaskToDesk <taskId> ")
            pw.println("$prefix  Move a task with given id to desktop mode.")
            pw.println("$prefix moveToNextDisplay <taskId> ")
            pw.println("$prefix  Move a task with given id to next display.")
            return
        }
        pw.println("$prefix moveTaskToDesk <taskId> <deskId>")
        pw.println("$prefix  Move a task with given id to the given desk and activate it.")
        pw.println("$prefix moveToNextDisplay <taskId>")
        pw.println("$prefix  Move a task with given id to next display.")
        pw.println("$prefix createDesk <displayId>")
        pw.println("$prefix  Creates a desk on the given display.")
        pw.println("$prefix activateDesk <deskId>")
        pw.println("$prefix  Activates the given desk.")
        pw.println("$prefix removeDesk <deskId> ")
        pw.println("$prefix  Removes the given desk and all of its windows.")
        pw.println("$prefix removeAllDesks")
        pw.println("$prefix  Removes all the desks and their windows across all displays")
        pw.println("$prefix moveTaskToFront <taskId>")
        pw.println("$prefix  Moves a task in front of its siblings.")
        pw.println("$prefix moveTaskOutOfDesk <taskId>")
        pw.println("$prefix  Moves the given desktop task out of the desk into fullscreen mode.")
        pw.println("$prefix canCreateDesk <displayId>")
        pw.println("$prefix  Whether creating a new desk in the given display is allowed.")
        pw.println("$prefix getActivateDeskId <displayId>")
        pw.println("$prefix  Print the id of the active desk in the given display.")
    }
}
