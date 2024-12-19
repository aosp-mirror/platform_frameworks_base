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

@file:OptIn(InternalComposeTracingApi::class)

package com.android.systemui.compose

import android.os.Trace
import android.util.Log
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.commandline.ParseableCommand
import java.io.PrintWriter
import javax.inject.Inject

private const val TAG = "ComposeTracingStartable"
private const val COMMAND_NAME = "composition-tracing"
private const val SUBCOMMAND_ENABLE = "enable"
private const val SUBCOMMAND_DISABLE = "disable"

/**
 * Sets up a [Command] to enable or disable Composition tracing.
 *
 * Usage:
 * ```
 * adb shell cmd statusbar composition-tracing [enable|disable]
 * ${ANDROID_BUILD_TOP}/external/perfetto/tools/record_android_trace -c ${ANDROID_BUILD_TOP}/prebuilts/tools/linux-x86_64/perfetto/configs/trace_config_detailed.textproto
 * ```
 */
@SysUISingleton
class ComposeTracingStartable @Inject constructor(private val commandRegistry: CommandRegistry) :
    CoreStartable {
    @OptIn(InternalComposeTracingApi::class)
    override fun start() {
        Log.i(TAG, "Set up Compose tracing command")
        commandRegistry.registerCommand(COMMAND_NAME) { CompositionTracingCommand() }
    }
}

private class CompositionTracingCommand : ParseableCommand(COMMAND_NAME) {
    val enable by subCommand(EnableCommand())
    val disable by subCommand(DisableCommand())

    override fun execute(pw: PrintWriter) {
        if ((enable != null) xor (disable != null)) {
            enable?.execute(pw)
            disable?.execute(pw)
        } else {
            help(pw)
        }
    }
}

private class EnableCommand : ParseableCommand(SUBCOMMAND_ENABLE) {
    override fun execute(pw: PrintWriter) {
        val msg = "Enabled Composition tracing"
        Log.i(TAG, msg)
        pw.println(msg)
        enableCompositionTracing()
    }

    private fun enableCompositionTracing() {
        Composer.setTracer(
            object : CompositionTracer {
                override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                    Trace.traceBegin(Trace.TRACE_TAG_APP, info)
                }

                override fun traceEventEnd() = Trace.traceEnd(Trace.TRACE_TAG_APP)

                override fun isTraceInProgress(): Boolean = Trace.isEnabled()
            }
        )
    }
}

private class DisableCommand : ParseableCommand(SUBCOMMAND_DISABLE) {
    override fun execute(pw: PrintWriter) {
        val msg = "Disabled Composition tracing"
        Log.i(TAG, msg)
        pw.println(msg)
        disableCompositionTracing()
    }

    private fun disableCompositionTracing() {
        Composer.setTracer(null)
    }
}
