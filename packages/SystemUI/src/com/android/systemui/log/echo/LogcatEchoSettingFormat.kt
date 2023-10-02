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

import android.util.Log
import com.android.systemui.log.core.LogLevel
import java.util.StringJoiner

/**
 * Encodes/decodes the list of tags/buffers that [LogcatEchoTrackerDebug] echoes to logcat to/from a
 * string format (that can be stored in a permanent place like a setting).
 */
class LogcatEchoSettingFormat {
    fun parseOverrides(str: String): List<LogcatEchoOverride> {
        // The format begins with a schema version specifier formatted as "<number>;", followed by
        // the encoded data.

        // First, read the schema version:
        val split = str.split(";", limit = 2)
        if (split.size != 2) {
            Log.e(TAG, "Unrecognized echo override format: \"$str\"")
            return emptyList()
        }
        val formatVersion =
            try {
                split[0].toInt()
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Unrecognized echo override formation version: ${split[0]}")
                return emptyList()
            }

        // Then, dispatch to the appropriate parser based on format
        return when (formatVersion) {
            0 -> parseOverridesV0(split[1])
            else -> {
                Log.e(TAG, "Unrecognized echo override formation version: $formatVersion")
                emptyList()
            }
        }
    }

    fun stringifyOverrides(
        overrides: List<LogcatEchoOverride>,
    ): String {
        return stringifyOverridesV0(overrides)
    }

    private fun parseOverridesV0(
        str: String,
    ): List<LogcatEchoOverride> {
        // Format: <type>;<name>;<level>(;...)
        // Where
        // <type> = "b" | "t"
        // <name> = string
        // <level> = "v" | "d" | "i" | "w" | "e" | "!"

        val list = mutableListOf<LogcatEchoOverride>()

        // Split on any ";" that is not preceded by a "\"
        val pieces = str.split(Regex("""(?<!\\);"""))

        var i = 0
        while (i < pieces.size) {
            if (pieces.size - i < 3) {
                break
            }
            val type =
                when (pieces[i]) {
                    "b" -> EchoOverrideType.BUFFER
                    "t" -> EchoOverrideType.TAG
                    else -> break
                }
            val name = pieces[i + 1].replace("\\;", ";")
            val level =
                when (pieces[i + 2]) {
                    "v" -> LogLevel.VERBOSE
                    "d" -> LogLevel.DEBUG
                    "i" -> LogLevel.INFO
                    "w" -> LogLevel.WARNING
                    "e" -> LogLevel.ERROR
                    "!" -> LogLevel.WTF
                    else -> break
                }
            i += 3

            list.add(LogcatEchoOverride(type, name, level))
        }

        return list
    }

    private fun stringifyOverridesV0(
        overrides: List<LogcatEchoOverride>,
    ): String {
        val sj = StringJoiner(";")

        sj.add("0")

        for (override in overrides) {
            sj.add(
                when (override.type) {
                    EchoOverrideType.BUFFER -> "b"
                    EchoOverrideType.TAG -> "t"
                }
            )
            sj.add(override.name.replace(";", "\\;"))
            sj.add(
                when (override.level) {
                    LogLevel.VERBOSE -> "v"
                    LogLevel.DEBUG -> "d"
                    LogLevel.INFO -> "i"
                    LogLevel.WARNING -> "w"
                    LogLevel.ERROR -> "e"
                    LogLevel.WTF -> "!"
                }
            )
        }

        return sj.toString()
    }
}

data class LogcatEchoOverride(val type: EchoOverrideType, val name: String, val level: LogLevel)

private const val TAG = "EchoFormat"
