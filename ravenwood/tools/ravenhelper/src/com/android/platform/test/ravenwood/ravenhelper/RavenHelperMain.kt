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
@file:JvmName("RavenHelperMain")
package com.android.platform.test.ravenwood.ravenhelper

/*
 * This file contains the main entry point for the "ravenhelper" command, which
 * contains subcommands to help various tasks.
 */

import com.android.hoststubgen.GeneralUserErrorException
import com.android.hoststubgen.LogLevel
import com.android.hoststubgen.executableName
import com.android.hoststubgen.log
import com.android.hoststubgen.runMainWithBoilerplate
import com.android.platform.test.ravenwood.ravenhelper.policytoannot.PtaProcessor
import com.android.platform.test.ravenwood.ravenhelper.sourcemap.MarkMethodHandler

interface SubcommandHandler {
    fun handle(args: List<String>)
}

fun usage() {
    System.out.println("""
        Usage:
          ravenhelper SUBCOMMAND options...

        Subcommands:
          pta:        "policy-to-annotations" Convert policy file to annotations.
                      (See the pta-framework.sh script for usage.)

          mm:         "mark methods" Used to add annotations (such as @DisabledOnRavenwood)
                      to methods.

        """.trimIndent())
}

fun main(args: Array<String>) {
    executableName = "RavenHelper"
    log.setConsoleLogLevel(LogLevel.Info)

    runMainWithBoilerplate {
        log.i("$executableName started")

        if (args.size == 0) {
            usage()
            return
        }

        // Find the subcommand handler.
        val subcommand = args[0]
        val handler: SubcommandHandler = when (subcommand) {
            "pta" -> PtaProcessor()
            "mm" -> MarkMethodHandler()
            else -> {
                usage()
                throw GeneralUserErrorException("Unknown subcommand '$subcommand'")
            }
        }

        // Run the subcommand.
        handler.handle(args.copyOfRange(1, args.size).toList())
    }
}
