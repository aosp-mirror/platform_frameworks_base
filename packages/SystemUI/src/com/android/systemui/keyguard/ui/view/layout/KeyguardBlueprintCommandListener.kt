/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.view.layout

import androidx.core.text.isDigitsOnly
import com.android.systemui.keyguard.data.repository.KeyguardBlueprintRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject

/** Uses $ adb shell cmd statusbar blueprint <BlueprintId> */
class KeyguardBlueprintCommandListener
@Inject
constructor(
    private val commandRegistry: CommandRegistry,
    private val keyguardBlueprintRepository: KeyguardBlueprintRepository,
    private val keyguardBlueprintInteractor: KeyguardBlueprintInteractor,
) {
    private val layoutCommand = KeyguardLayoutManagerCommand()

    fun start() {
        commandRegistry.registerCommand(COMMAND) { layoutCommand }
    }

    internal inner class KeyguardLayoutManagerCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val arg = args.getOrNull(0)
            if (arg == null || arg.lowercase() == "help") {
                help(pw)
                return
            }

            when {
                arg.isDigitsOnly() -> pw.println("Invalid argument! Use string ids.")
                keyguardBlueprintInteractor.transitionOrRefreshBlueprint(arg) ->
                    pw.println("Transition succeeded!")
                else -> {
                    pw.println("Invalid argument! To see available blueprint ids, run:")
                    pw.println("$ adb shell cmd statusbar blueprint help")
                }
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: $ adb shell cmd statusbar blueprint <blueprintId>")
            pw.println("Existing Blueprint Ids: ")
            keyguardBlueprintRepository.printBlueprints(pw)
        }
    }

    companion object {
        internal const val COMMAND = "blueprint"
    }
}
