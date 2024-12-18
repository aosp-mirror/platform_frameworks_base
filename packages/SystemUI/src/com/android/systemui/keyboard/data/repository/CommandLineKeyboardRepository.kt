/*
 *  Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyboard.data.repository

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyboard.data.model.Keyboard
import com.android.systemui.keyboard.shared.model.BacklightModel
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * Helper class for development to mock various keyboard states with command line. Alternative for
 * [KeyboardRepositoryImpl] which relies on real data from framework. [KeyboardRepositoryImpl] is
 * the default implementation so to use this class you need to substitute it in [KeyboardModule].
 *
 * For usage information: see [KeyboardCommand.help] or run `adb shell cmd statusbar keyboard`.
 */
@SysUISingleton
class CommandLineKeyboardRepository @Inject constructor(commandRegistry: CommandRegistry) :
    KeyboardRepository {

    private val _isAnyKeyboardConnected = MutableStateFlow(false)
    override val isAnyKeyboardConnected: Flow<Boolean> = _isAnyKeyboardConnected

    private val _backlightState: MutableStateFlow<BacklightModel?> = MutableStateFlow(null)
    // filtering to make sure backlight doesn't have default initial value
    override val backlight: Flow<BacklightModel> = _backlightState.filterNotNull()

    private val _newlyConnectedKeyboard: MutableStateFlow<Keyboard?> = MutableStateFlow(null)
    override val newlyConnectedKeyboard: Flow<Keyboard> = _newlyConnectedKeyboard.filterNotNull()

    init {
        Log.i(TAG, "initializing shell command $COMMAND")
        commandRegistry.registerCommand(COMMAND) { KeyboardCommand() }
    }

    inner class KeyboardCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            Log.i(TAG, "$COMMAND command was called with args: $args")
            if (args.isEmpty()) {
                help(pw)
                return
            }
            when (args[0]) {
                "keyboard-connected" -> _isAnyKeyboardConnected.value = args[1].toBoolean()
                "backlight" -> {
                    @Suppress("Since15")
                    val level = Math.clamp(args[1].toInt().toLong(), 0, MAX_BACKLIGHT_LEVEL)
                    _backlightState.value = BacklightModel(level, MAX_BACKLIGHT_LEVEL)
                }
                "new-keyboard" -> {
                    _newlyConnectedKeyboard.value =
                        Keyboard(vendorId = args[1].toInt(), productId = args[2].toInt())
                }
                else -> help(pw)
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $COMMAND <command>")
            pw.println(
                "Note: this command only mocks setting these values on the framework level" +
                    " but in reality doesn't change anything and is only used for testing UI"
            )
            pw.println("Available commands:")
            pw.println("  keyboard-connected [true|false]")
            pw.println("     Notify any physical keyboard connected/disconnected.")
            pw.println("  backlight <level>")
            pw.println("     Notify new keyboard backlight level: min 0, max $MAX_BACKLIGHT_LEVEL.")
            pw.println("  new-keyboard <vendor-id> <product-id>")
            pw.println("     Notify new physical keyboard with specified parameters got connected.")
        }
    }

    companion object {
        private const val TAG = "CommandLineKeyboardRepository"
        private const val COMMAND = "keyboard"
        private const val MAX_BACKLIGHT_LEVEL = 5
    }
}
