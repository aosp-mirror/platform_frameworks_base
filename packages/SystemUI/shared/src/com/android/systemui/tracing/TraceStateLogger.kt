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

package com.android.systemui.tracing

import android.os.Trace

/**
 * Utility class used to log state changes easily in a track with a custom name.
 *
 * Example of usage:
 * ```kotlin
 * class MyClass {
 *    val screenStateLogger = TraceStateLogger("Screen state")
 *
 *    fun onTurnedOn() { screenStateLogger.log("on") }
 *    fun onTurnedOff() { screenStateLogger.log("off") }
 * }
 * ```
 *
 * This creates a new slice in a perfetto trace only if the state is different than the previous
 * one.
 */
class TraceStateLogger(
    private val trackName: String,
    private val logOnlyIfDifferent: Boolean = true,
    private val instantEvent: Boolean = true
) {

    private var previousValue: String? = null

    /** If needed, logs the value to a track with name [trackName]. */
    fun log(newValue: String) {
        if (instantEvent) {
            Trace.instantForTrack(Trace.TRACE_TAG_APP, trackName, newValue)
        }
        if (logOnlyIfDifferent && previousValue == newValue) return
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, trackName, 0)
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, trackName, newValue, 0)
        previousValue = newValue
    }
}
