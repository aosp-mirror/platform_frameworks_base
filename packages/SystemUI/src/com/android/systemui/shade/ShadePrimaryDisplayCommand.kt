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

package com.android.systemui.shade

import android.view.Display
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
class ShadePrimaryDisplayCommand
@Inject
constructor(
    private val commandRegistry: CommandRegistry,
    private val displaysRepository: DisplayRepository,
    private val positionRepository: ShadeDisplaysRepository,
) : Command, CoreStartable {

    override fun start() {
        commandRegistry.registerCommand("shade_display_override") { this }
    }

    override fun help(pw: PrintWriter) {
        pw.println("shade_display_override <displayId> ")
        pw.println("Set the display which is holding the shade.")
        pw.println()
        pw.println("shade_display_override reset ")
        pw.println("Reset the display which is holding the shade.")
        pw.println()
        pw.println("shade_display_override (list|status) ")
        pw.println("Lists available displays and which has the shade")
        pw.println()
        pw.println("shade_display_override any_external")
        pw.println("Moves the shade to the first not-default display available")
    }

    override fun execute(pw: PrintWriter, args: List<String>) {
        CommandHandler(pw, args).execute()
    }

    /** Wrapper class to avoid propagating [PrintWriter] to all methods. */
    private inner class CommandHandler(
        private val pw: PrintWriter,
        private val args: List<String>,
    ) {

        fun execute() {
            when (val command = args.getOrNull(0)?.lowercase()) {
                "reset" -> reset()
                "list",
                "status" -> printStatus()
                "any_external" -> anyExternal()
                else -> {
                    val cmdAsInteger = command?.toIntOrNull()
                    if (cmdAsInteger != null) {
                        changeDisplay(displayId = cmdAsInteger)
                    } else {
                        help(pw)
                    }
                }
            }
        }

        private fun reset() {
            positionRepository.resetDisplayId()
            pw.println("Reset shade primary display id to ${Display.DEFAULT_DISPLAY}")
        }

        private fun printStatus() {
            val displays = displaysRepository.displays.value
            val shadeDisplay = positionRepository.displayId.value
            pw.println("Available displays: ")
            displays.forEach {
                pw.print(" - ${it.displayId}")
                pw.println(if (it.displayId == shadeDisplay) " (Shade window is here)" else "")
            }
        }

        private fun anyExternal() {
            val anyExternalDisplay =
                displaysRepository.displays.value.firstOrNull {
                    it.displayId != Display.DEFAULT_DISPLAY
                }
            if (anyExternalDisplay == null) {
                pw.println("No external displays available.")
                return
            }
            setDisplay(anyExternalDisplay.displayId)
        }

        private fun changeDisplay(displayId: Int) {
            if (displayId < 0) {
                pw.println("Error: display id should be positive integer")
            }

            setDisplay(displayId)
        }

        private fun setDisplay(id: Int) {
            positionRepository.setDisplayId(id)
            pw.println("New shade primary display id is $id")
        }
    }
}
