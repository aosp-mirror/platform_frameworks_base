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

import android.os.Build
import android.util.Log
import android.util.Log.LOG_ID_MAIN

/**
 * A simplified debug logger built as a wrapper around Android's [Log]. Internal for development.
 *
 * The main advantages are:
 * - Sensible defaults, automatically retrieving the class name from the call-site (i.e., tag);
 * - The messages are purged from source on release builds (keep in mind they are visible on AOSP);
 * - Lazily evaluate Strings for zero impact in production builds or when disabled;
 *
 * Usage example:
 * ```kotlin
 * // Logging a message:
 * debugLog { "message" }
 *
 * // Logging an error:
 * debugLog(error = exception) { "message" }
 *
 * // Logging the current stack trace, for debugging:
 * debugLog(error = Throwable()) { "message" }
 * ```
 */
object DebugLogger {

    /**
     * Log a debug message, with sensible defaults.
     *
     * For example:
     * ```kotlin
     * val one = 1
     * debugLog { "message#$one" }
     * ```
     *
     * The output will be: `D/NoteTaskController: message#1`
     *
     * Beware, the [debugLog] content is **REMOVED FROM SOURCE AND BINARY** in Release builds.
     *
     * @param enabled: whether or not the message should be logged. By default, it is
     *   [Build.IS_DEBUGGABLE].
     * @param priority: type of this log. By default, it is [Log.DEBUG].
     * @param tag: identifies the source of a log. By default, it is the receiver's simple name.
     * @param error: a [Throwable] to log.
     * @param message: a lazily evaluated message you wish to log.
     */
    @JvmOverloads
    @JvmName("logcatMessage")
    inline fun Any.debugLog(
        enabled: Boolean = Build.IS_DEBUGGABLE,
        priority: Int = Log.DEBUG,
        tag: String = this::class.simpleName.orEmpty(),
        error: Throwable? = null,
        message: () -> String,
    ) {
        if (enabled) {
            if (error == null) {
                Log.println(priority, tag, message())
            } else {
                Log.printlns(LOG_ID_MAIN, priority, tag, message(), error)
            }
        }
    }
}
