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
package com.android.platform.test.ravenwood.ravenhelper.sourcemap

import com.android.hoststubgen.ArgIterator
import com.android.hoststubgen.ArgumentsException
import com.android.hoststubgen.SetOnce
import com.android.hoststubgen.ensureFileExists
import com.android.hoststubgen.log

/**
 * Options for the "ravenhelper map" subcommand.
 */
class MapOptions(
    /** Source files or directories. */
    var sourceFilesOrDirectories: MutableList<String> = mutableListOf(),

    /** Files containing target methods */
    var targetMethodFiles: MutableList<String> = mutableListOf(),

    /** Output script file. */
    var outputScriptFile: SetOnce<String?> = SetOnce(null),

    /** Text to insert. */
    var text: SetOnce<String?> = SetOnce(null),
) {
    companion object {
        fun parseArgs(args: List<String>): MapOptions {
            val ret = MapOptions()
            val ai = ArgIterator.withAtFiles(args.toTypedArray())

            while (true) {
                val arg = ai.nextArgOptional() ?: break

                fun nextArg(): String = ai.nextArgRequired(arg)

                if (log.maybeHandleCommandLineArg(arg) { nextArg() }) {
                    continue
                }
                try {
                    when (arg) {
                        // TODO: Write help
                        "-h", "--help" -> TODO("Help is not implemented yet")

                        "-s", "--src" ->
                            ret.sourceFilesOrDirectories.add(nextArg().ensureFileExists())

                        "-i", "--input" ->
                            ret.targetMethodFiles.add(nextArg().ensureFileExists())

                        "-o", "--output-script" ->
                            ret.outputScriptFile.set(nextArg())

                        "-t", "--text" ->
                            ret.text.set(nextArg())

                        else -> throw ArgumentsException("Unknown option: $arg")
                    }
                } catch (e: SetOnce.SetMoreThanOnceException) {
                    throw ArgumentsException("Duplicate or conflicting argument found: $arg")
                }
            }

            if (ret.sourceFilesOrDirectories.size == 0) {
                throw ArgumentsException("Must specify at least one source path")
            }

            return ret
        }
    }

    override fun toString(): String {
        return """
            PtaOptions{
              sourceFilesOrDirectories=$sourceFilesOrDirectories
              targetMethods=$targetMethodFiles
              outputScriptFile=$outputScriptFile
              text=$text
            }
            """.trimIndent()
    }
}