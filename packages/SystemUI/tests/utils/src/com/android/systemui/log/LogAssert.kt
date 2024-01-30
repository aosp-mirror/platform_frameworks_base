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

package com.android.systemui.log

import android.util.Log
import android.util.Log.TerribleFailureHandler
import junit.framework.Assert

/**
 * Assert that the given block makes a call to Log.wtf
 *
 * @return the details of the log
 */
fun assertLogsWtf(
    message: String = "Expected Log.wtf to be called",
    allowMultiple: Boolean = false,
    loggingBlock: () -> Unit,
): TerribleFailureLog {
    var caught: TerribleFailureLog? = null
    var count = 0
    val newHandler = TerribleFailureHandler { tag, failure, system ->
        if (caught == null) {
            caught = TerribleFailureLog(tag, failure, system)
        }
        count++
    }
    val oldHandler = Log.setWtfHandler(newHandler)
    try {
        loggingBlock()
    } finally {
        Log.setWtfHandler(oldHandler)
    }
    Assert.assertNotNull(message, caught)
    if (!allowMultiple && count != 1) {
        Assert.fail("Unexpectedly caught Log.Wtf $count times; expected only 1.  First: $caught")
    }
    return caught!!
}

@JvmOverloads
fun assertLogsWtf(
    message: String = "Expected Log.wtf to be called",
    allowMultiple: Boolean = false,
    loggingRunnable: Runnable,
): TerribleFailureLog =
    assertLogsWtf(message = message, allowMultiple = allowMultiple) { loggingRunnable.run() }

fun assertLogsWtfs(
    message: String = "Expected Log.wtf to be called once or more",
    loggingBlock: () -> Unit,
): TerribleFailureLog = assertLogsWtf(message, allowMultiple = true, loggingBlock)

@JvmOverloads
fun assertLogsWtfs(
    message: String = "Expected Log.wtf to be called once or more",
    loggingRunnable: Runnable,
): TerribleFailureLog = assertLogsWtfs(message) { loggingRunnable.run() }

/** The data passed to [TerribleFailureHandler.onTerribleFailure] */
data class TerribleFailureLog(
    val tag: String,
    val failure: Log.TerribleFailure,
    val system: Boolean
)
