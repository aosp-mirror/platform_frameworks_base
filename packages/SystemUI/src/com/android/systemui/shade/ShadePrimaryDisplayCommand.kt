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
import com.android.systemui.shade.data.repository.ShadePositionRepository
import com.android.systemui.statusbar.commandline.Command
import java.io.PrintWriter

class ShadePrimaryDisplayCommand(private val positionRepository: ShadePositionRepository) :
    Command {

    override fun execute(pw: PrintWriter, args: List<String>) {
        if (args[0].lowercase() == "reset") {
            positionRepository.resetDisplayId()
            pw.println("Reset shade primary display id to ${Display.DEFAULT_DISPLAY}")
            return
        }

        val displayId: Int =
            try {
                args[0].toInt()
            } catch (e: NumberFormatException) {
                pw.println("Error: task id should be an integer")
                return
            }

        if (displayId < 0) {
            pw.println("Error: display id should be positive integer")
        }

        positionRepository.setDisplayId(displayId)
        pw.println("New shade primary display id is $displayId")
    }

    override fun help(pw: PrintWriter) {
        pw.println("shade_display_override <displayId> ")
        pw.println("Set the display which is holding the shade.")
        pw.println("shade_display_override reset ")
        pw.println("Reset the display which is holding the shade.")
    }
}
