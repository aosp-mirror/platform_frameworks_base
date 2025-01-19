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
package com.android.platform.test.ravenwood.ravenhelper.policytoannot

import com.android.hoststubgen.ArgIterator
import com.android.hoststubgen.ArgumentsException
import com.android.hoststubgen.SetOnce
import com.android.hoststubgen.ensureFileExists
import com.android.hoststubgen.log

/**
 * Options for the "ravenhelper pta" subcommand.
 */
class PtaOptions(
    /** Text policy files */
    var policyOverrideFiles: MutableList<String> = mutableListOf(),

    /** Annotation allowed list file. */
    var annotationAllowedClassesFile: SetOnce<String?> = SetOnce(null),

    /** Source files or directories. */
    var sourceFilesOrDirectories: MutableList<String> = mutableListOf(),

    /** Output script file. */
    var outputScriptFile: SetOnce<String?> = SetOnce(null),

    /** Dump the operations (for debugging) */
    var dumpOperations: SetOnce<Boolean> = SetOnce(false),
) {
    companion object {
        fun parseArgs(args: List<String>): PtaOptions {
            val ret = PtaOptions()
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

                        "-p", "--policy-override-file" ->
                            ret.policyOverrideFiles.add(nextArg().ensureFileExists())

                        "-a", "--annotation-allowed-classes-file" ->
                            ret.annotationAllowedClassesFile.set(nextArg().ensureFileExists())

                        "-s", "--src" ->
                            ret.sourceFilesOrDirectories.add(nextArg().ensureFileExists())

                        "--dump" ->
                            ret.dumpOperations.set(true)

                        "-o", "--output-script" ->
                            ret.outputScriptFile.set(nextArg())

                        else -> throw ArgumentsException("Unknown option: $arg")
                    }
                } catch (e: SetOnce.SetMoreThanOnceException) {
                    throw ArgumentsException("Duplicate or conflicting argument found: $arg")
                }
            }

            if (ret.policyOverrideFiles.size == 0) {
                throw ArgumentsException("Must specify at least one policy file")
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
              policyOverrideFiles=$policyOverrideFiles
              annotationAllowedClassesFile=$annotationAllowedClassesFile
              sourceFilesOrDirectories=$sourceFilesOrDirectories
              outputScriptFile=$outputScriptFile
              dumpOperations=$dumpOperations
            }
            """.trimIndent()
    }
}