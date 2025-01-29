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
import android.service.dreams.Flags.allowDreamWhenPostured
import com.android.app.tracing.coroutines.launchInTraced
import com.android.systemui.CoreStartable
import com.android.systemui.communal.posturing.domain.interactor.PosturingInteractor
import com.android.systemui.communal.posturing.shared.model.PosturedState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.dagger.CommunalTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

@SysUISingleton
class DevicePosturingListener
@Inject
constructor(
    private val commandRegistry: CommandRegistry,
    private val dreamManager: DreamManager,
    private val interactor: PosturingInteractor,
    @Background private val bgScope: CoroutineScope,
    @CommunalTableLog private val tableLogBuffer: TableLogBuffer,
) : CoreStartable {
    private val command = DevicePosturingCommand()

    @SuppressLint("MissingPermission")
    override fun start() {
        if (!allowDreamWhenPostured()) {
            return
        }

        interactor.postured
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnName = "postured",
                initialValue = false,
            )
            .onEach { postured -> dreamManager.setDevicePostured(postured) }
            .launchInTraced("$TAG#collectPostured", bgScope)

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

            val state =
                when (arg.lowercase()) {
                    "true" -> PosturedState.Postured(confidence = 1f)
                    "false" -> PosturedState.NotPostured
                    "clear" -> PosturedState.Unknown
                    else -> {
                        pw.println("Invalid argument!")
                        help(pw)
                        null
                    }
                }
            state?.let { interactor.setValueForDebug(it) }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: $ adb shell cmd statusbar device-postured <true|false|clear>")
        }
    }

    private companion object {
        const val COMMAND_ROOT = "device-postured"
        const val TAG = "DevicePosturingListener"
    }
}
