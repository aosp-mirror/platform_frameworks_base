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

package com.android.systemui.log.echo

import android.util.IndentingPrintWriter
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.echo.Outcome.Failure
import com.android.systemui.log.echo.Outcome.Success
import com.android.systemui.statusbar.commandline.ParseableCommand
import com.android.systemui.statusbar.commandline.Type
import java.io.PrintWriter

/**
 * Implementation of command-line interface for modifying echo tracking.
 *
 * Invoked via $adb shell cmd statusbar echo <usage>. See [usage] below for usage summary.
 */
internal class LogcatEchoTrackerCommand(private val echoTracker: LogcatEchoTrackerDebug) :
    ParseableCommand(ECHO_TRACKER_COMMAND_NAME) {

    val buffer by
        param(
            longName = "buffer",
            shortName = "b",
            description =
                "Modifies the echo level of a buffer. Use the form <name>:<level>, e.g." +
                    " 'Foo:V'. Valid levels are V,D,I,W,E, and -. The - level clears any" +
                    " pre-existing override.",
            valueParser = Type.String,
        )

    val tag by
        param(
            longName = "tag",
            shortName = "t",
            description =
                "Modifies the echo level of a tag. Use the form <name>:<level>, e.g." +
                    " 'Foo:V'. Valid levels are V,D,I,W,E, and -. The - level clears any" +
                    " pre-existing override.",
            valueParser = Type.String
        )

    val clearAll by
        flag(
            longName = "clear-all",
            description = "Removes all local echo level overrides",
        )

    val list by
        flag(
            longName = "list",
            description = "Lists all local echo level overrides",
        )

    override fun usage(pw: IndentingPrintWriter) {
        pw.println("Usage:")
        pw.println()
        pw.println("echo -b MyBufferName:V    // Set echo level of a buffer to verbose")
        pw.println("echo -t MyTagName:V       // Set echo level of a tag to verbose")
        pw.println()
        pw.println("echo -b MyBufferName:-    // Clear any echo overrides for a buffer")
        pw.println("echo -t MyTagName:-       // Clear any echo overrides for a tag")
        pw.println()
        pw.println("echo --list               // List all current echo overrides")
        pw.println("echo --clear-all          // Clear all echo overrides")
        pw.println()
    }

    override fun execute(pw: PrintWriter) {
        val buffer = buffer
        val tag = tag

        when {
            buffer != null -> {
                parseTagStructure(buffer, EchoOverrideType.BUFFER).ifFailureThenPrintElse(pw) {
                    echoTracker.setEchoLevel(it.type, it.name, it.level)
                }
            }
            tag != null -> {
                parseTagStructure(tag, EchoOverrideType.TAG).ifFailureThenPrintElse(pw) {
                    echoTracker.setEchoLevel(it.type, it.name, it.level)
                }
            }
            clearAll -> {
                echoTracker.clearAllOverrides()
            }
            list -> {
                for (override in echoTracker.listEchoOverrides()) {
                    pw.print(override.type.toString().padEnd(8))
                    pw.print(override.level.toString().padEnd(10))
                    pw.print(override.name)
                    pw.println()
                }
            }
            else -> {
                pw.println("You must specify one of --buffer, --tag, --list, or --clear-all")
            }
        }
    }

    private fun parseTagStructure(
        str: String,
        type: EchoOverrideType,
    ): Outcome<ParsedOverride> {
        val result =
            OVERRIDE_PATTERN.matchEntire(str)
                ?: return Failure("Cannot parse override format, must be `<name>:<level>`")

        val name = result.groupValues[1]
        val levelStr = result.groupValues[2]

        if (levelStr == "-") {
            return Success(ParsedOverride(type, name, null))
        } else {
            val parsedLevel =
                parseLevel(levelStr)
                    ?: return Failure("Unrecognized level $levelStr. Must be one of 'v,d,i,w,e,-'")
            return Success(ParsedOverride(type, name, parsedLevel))
        }
    }

    private fun parseLevel(str: String): LogLevel? {
        return when (str.lowercase()) {
            "verbose" -> LogLevel.VERBOSE
            "v" -> LogLevel.VERBOSE
            "debug" -> LogLevel.DEBUG
            "d" -> LogLevel.DEBUG
            "info" -> LogLevel.INFO
            "i" -> LogLevel.INFO
            "warning" -> LogLevel.WARNING
            "warn" -> LogLevel.WARNING
            "w" -> LogLevel.WARNING
            "error" -> LogLevel.ERROR
            "e" -> LogLevel.ERROR
            "assert" -> LogLevel.WTF
            "wtf" -> LogLevel.WTF
            else -> null
        }
    }

    companion object {
        const val ECHO_TRACKER_COMMAND_NAME = "echo"
    }
}

private val OVERRIDE_PATTERN = Regex("([^:]+):(.*)")

private class ParsedOverride(val type: EchoOverrideType, val name: String, val level: LogLevel?)

private sealed interface Outcome<out T> {
    class Success<out T>(val value: T) : Outcome<T>
    class Failure(val message: String) : Outcome<Nothing>
}

private inline fun <T> Outcome<T>.ifFailureThenPrintElse(
    pw: PrintWriter,
    handler: (value: T) -> Unit,
) {
    when (this) {
        is Success<T> -> handler(value)
        is Failure -> pw.println(message)
    }
}

/*
TODO (b/310006154): Investigate using varargs instead of parameterized flags

Current structure uses param flags, e.g.

adb shell cmd statusbar echo -b MyBufferName:V
adb shell cmd statusbar echo -b MyBufferName:-
adb shell cmd statusbar echo -t MyTagName:V
adb shell cmd statusbar echo -t MyTagName:-
adb shell cmd statusbar echo --clear-all
adb shell cmd statusbar echo --list

A better structure might use non-flag varargs like (but will require updates to the CLI lib):

adb shell cmd statusbar echo buffer MyBufferName:V
adb shell cmd statusbar echo buffer MyBufferName:-
adb shell cmd statusbar echo tag MyTagName:V
adb shell cmd statusbar echo tag MyTagName:-
adb shell cmd statusbar echo clear-all
adb shell cmd statusbar echo list

*/
