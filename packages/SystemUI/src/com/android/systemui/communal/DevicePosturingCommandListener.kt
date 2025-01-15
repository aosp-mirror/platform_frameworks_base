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

package com.android.systemui.communal

import android.annotation.SuppressLint
import android.app.DreamManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
class DevicePosturingCommandListener
@Inject
constructor(private val commandRegistry: CommandRegistry, private val dreamManager: DreamManager) :
    CoreStartable {
    private val command = DevicePosturingCommand()

    override fun start() {
        commandRegistry.registerCommand(COMMAND_ROOT) { command }
    }

    internal inner class DevicePosturingCommand : Command {
        @SuppressLint("MissingPermission")
        override fun execute(pw: PrintWriter, args: List<String>) {
            val arg = args.getOrNull(0)
            if (arg == null || arg.lowercase() == "help") {
                help(pw)
                return
            }

            when (arg.lowercase()) {
                "true" -> dreamManager.setDevicePostured(true)
                "false" -> dreamManager.setDevicePostured(false)
                else -> {
                    pw.println("Invalid argument!")
                    help(pw)
                }
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: $ adb shell cmd statusbar device-postured <true|false>")
        }
    }

    private companion object {
        const val COMMAND_ROOT = "device-postured"
    }
}
