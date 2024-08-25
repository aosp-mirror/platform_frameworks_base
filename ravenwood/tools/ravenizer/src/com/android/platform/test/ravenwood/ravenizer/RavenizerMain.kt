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
@file:JvmName("RavenizerMain")

package com.android.platform.test.ravenwood.ravenizer

import com.android.hoststubgen.LogLevel
import com.android.hoststubgen.executableName
import com.android.hoststubgen.log
import com.android.hoststubgen.runMainWithBoilerplate

/**
 * Entry point.
 */
fun main(args: Array<String>) {
    executableName = "Ravenizer"
    log.setConsoleLogLevel(LogLevel.Info)

    runMainWithBoilerplate {
        val options = RavenizerOptions.parseArgs(args)

        log.i("$executableName started")
        log.v("Options: $options")

        // Run.
        Ravenizer(options).run()
    }
}
