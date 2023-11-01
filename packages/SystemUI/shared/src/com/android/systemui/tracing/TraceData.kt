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

import android.os.Build
import android.util.Log
import com.android.systemui.tracing.TraceUtils.Companion.beginSlice
import com.android.systemui.tracing.TraceUtils.Companion.endSlice
import com.android.systemui.tracing.TraceUtils.Companion.traceCoroutine
import kotlin.random.Random

/**
 * Used for giving each thread a unique [TraceData] for thread-local storage. `null` by default.
 * [threadLocalTrace] can only be used when it is paired with a [TraceContextElement].
 *
 * This ThreadLocal will be `null` if either 1) we aren't in a coroutine, or 2) the coroutine we are
 * in does not have a [TraceContextElement].
 *
 * This is internal machinery for [traceCoroutine]. It cannot be made `internal` or `private`
 * because [traceCoroutine] is a Public-API inline function.
 *
 * @see traceCoroutine
 */
val threadLocalTrace = ThreadLocal<TraceData?>()

/**
 * Used for storing trace sections so that they can be added and removed from the currently running
 * thread when the coroutine is suspended and resumed.
 *
 * This is internal machinery for [traceCoroutine]. It cannot be made `internal` or `private`
 * because [traceCoroutine] is a Public-API inline function.
 *
 * @see traceCoroutine
 */
class TraceData {
    private var slices = mutableListOf<TraceSection>()

    /** Adds current trace slices back to the current thread. Called when coroutine is resumed. */
    fun beginAllOnThread() {
        slices.forEach { beginSlice(it.name) }
    }

    /**
     * Removes all current trace slices from the current thread. Called when coroutine is suspended.
     */
    fun endAllOnThread() {
        for (i in 0..slices.size) {
            endSlice()
        }
    }

    /**
     * Creates a new trace section with a unique ID and adds it to the current trace data. The slice
     * will also be added to the current thread immediately. This slice will not propagate to parent
     * coroutines, or to child coroutines that have already started. The unique ID is used to verify
     * that the [endSpan] is corresponds to a [beginSpan].
     */
    fun beginSpan(name: String): Int {
        val newSlice = TraceSection(name, Random.nextInt(FIRST_VALID_SPAN, Int.MAX_VALUE))
        slices.add(newSlice)
        beginSlice(name)
        return newSlice.id
    }

    /**
     * Used by [TraceContextElement] when launching a child coroutine so that the child coroutine's
     * state is isolated from the parent.
     */
    fun copy(): TraceData {
        return TraceData().also { it.slices.addAll(slices) }
    }

    /**
     * Ends the trace section and validates it corresponds with an earlier call to [beginSpan]. The
     * trace slice will immediately be removed from the current thread. This information will not
     * propagate to parent coroutines, or to child coroutines that have already started.
     */
    fun endSpan(id: Int) {
        val v = slices.removeLast()
        if (v.id != id) {
            if (STRICT_MODE) {
                throw IllegalArgumentException(errorMsg)
            } else if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, errorMsg)
            }
        }
        endSlice()
    }

    companion object {
        private const val TAG = "TraceData"
        const val INVALID_SPAN = -1
        const val FIRST_VALID_SPAN = 1

        /**
         * If true, throw an exception instead of printing a warning when trace sections beginnings
         * and ends are mismatched.
         */
        private val STRICT_MODE = Build.IS_ENG

        private const val errorMsg =
            "Mismatched trace section. This likely means you are accessing the trace local " +
                "storage (threadLocalTrace) without a corresponding CopyableThreadContextElement." +
                " This could happen if you are using a global dispatcher like Dispatchers.IO." +
                " To fix this, use one of the coroutine contexts provided by the dagger scope " +
                "(e.g. \"@Main CoroutineContext\")."
    }
}
