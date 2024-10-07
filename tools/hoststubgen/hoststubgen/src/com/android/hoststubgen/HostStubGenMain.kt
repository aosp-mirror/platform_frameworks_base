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
@file:JvmName("HostStubGenMain")

package com.android.hoststubgen

import java.io.PrintWriter

/**
 * Entry point.
 */
fun main(args: Array<String>) {
    executableName = "HostStubGen"
    runMainWithBoilerplate {
        // Parse the command line arguments.
        var clanupOnError = false
        try {
            val options = HostStubGenOptions.parseArgs(args)
            clanupOnError = options.cleanUpOnError.get

            log.v("$executableName started")
            log.v("Options: $options")

            // Run.
            HostStubGen(options).run()
        } catch (e: Throwable) {
            if (clanupOnError) {
                TODO("Remove output jars here")
            }
            throw e
        }
    }
}

inline fun runMainWithBoilerplate(realMain: () -> Unit) {
    var success = false

    try {
        realMain()

        success = true
    } catch (e: Throwable) {
        log.e("$executableName: Error: ${e.message}")
        if (e !is UserErrorException) {
            e.printStackTrace(PrintWriter(log.getWriter(LogLevel.Error)))
        }
    } finally {
        log.i("$executableName finished")
        log.flush()
    }

    System.exit(if (success) 0 else 1 )
}
