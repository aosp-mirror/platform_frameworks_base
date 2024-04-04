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
@file:JvmName("Main")

package com.android.hoststubgen

import java.io.PrintWriter

const val COMMAND_NAME = "HostStubGen"

/**
 * Entry point.
 */
fun main(args: Array<String>) {
    var success = false
    var clanupOnError = false

    try {
        // Parse the command line arguments.
        val options = HostStubGenOptions.parseArgs(args)
        clanupOnError = options.cleanUpOnError.get

        log.v("HostStubGen started")
        log.v("Options: $options")

        // Run.
        HostStubGen(options).run()

        success = true
    } catch (e: Throwable) {
        log.e("$COMMAND_NAME: Error: ${e.message}")
        if (e !is UserErrorException) {
            e.printStackTrace(PrintWriter(log.getWriter(LogLevel.Error)))
        }
        if (clanupOnError) {
            TODO("Remove output jars here")
        }
    } finally {
        log.i("$COMMAND_NAME finished")
        log.flush()
    }

    System.exit(if (success) 0 else 1 )
}
