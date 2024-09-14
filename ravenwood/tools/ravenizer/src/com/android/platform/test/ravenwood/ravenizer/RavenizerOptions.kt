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
package com.android.platform.test.ravenwood.ravenizer

import com.android.hoststubgen.ArgIterator
import com.android.hoststubgen.ArgumentsException
import com.android.hoststubgen.SetOnce
import com.android.hoststubgen.ensureFileExists
import com.android.hoststubgen.log
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * If this file exits, we also read options from it. This is "unsafe" because it could break
 * incremental builds, if it sets any flag that affects the output file.
 * (however, for now, there's no such options.)
 *
 * For example, to enable verbose logging, do `echo '-v' > ~/.raveniezr-unsafe`
 *
 * (but even the content of this file changes, soong won't rerun the command, so you need to
 * remove the output first and then do a build again.)
 */
private val RAVENIZER_DOTFILE = System.getenv("HOME") + "/.raveniezr-unsafe"

class RavenizerOptions(
    /** Input jar file*/
    var inJar: SetOnce<String> = SetOnce(""),

    /** Output jar file */
    var outJar: SetOnce<String> = SetOnce(""),

    /** Whether to enable test validation. */
    var enableValidation: SetOnce<Boolean> = SetOnce(true),

    /** Whether the validation failure is fatal or not. */
    var fatalValidation: SetOnce<Boolean> = SetOnce(false),
) {
    companion object {

        fun parseArgs(origArgs: Array<String>): RavenizerOptions {
            val args = origArgs.toMutableList()
            if (Paths.get(RAVENIZER_DOTFILE).exists()) {
                log.i("Reading options from $RAVENIZER_DOTFILE")
                args.add(0, "@$RAVENIZER_DOTFILE")
            }

            val ret = RavenizerOptions()
            val ai = ArgIterator.withAtFiles(args.toTypedArray())

            while (true) {
                val arg = ai.nextArgOptional()
                if (arg == null) {
                    break
                }

                fun nextArg(): String = ai.nextArgRequired(arg)

                if (log.maybeHandleCommandLineArg(arg) { nextArg() }) {
                    continue
                }
                try {
                    when (arg) {
                        // TODO: Write help
                        "-h", "--help" -> TODO("Help is not implemented yet")

                        "--in-jar" -> ret.inJar.set(nextArg()).ensureFileExists()
                        "--out-jar" -> ret.outJar.set(nextArg())

                        "--enable-validation" -> ret.enableValidation.set(true)
                        "--disable-validation" -> ret.enableValidation.set(false)

                        "--fatal-validation" -> ret.fatalValidation.set(true)
                        "--no-fatal-validation" -> ret.fatalValidation.set(false)

                        else -> throw ArgumentsException("Unknown option: $arg")
                    }
                } catch (e: SetOnce.SetMoreThanOnceException) {
                    throw ArgumentsException("Duplicate or conflicting argument found: $arg")
                }
            }

            if (!ret.inJar.isSet) {
                throw ArgumentsException("Required option missing: --in-jar")
            }
            if (!ret.outJar.isSet) {
                throw ArgumentsException("Required option missing: --out-jar")
            }
           return ret
        }
    }

    override fun toString(): String {
        return """
            RavenizerOptions{
              inJar=$inJar,
              outJar=$outJar,
              enableValidation=$enableValidation,
              fatalValidation=$fatalValidation,
            }
            """.trimIndent()
    }
}
