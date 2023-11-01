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
import android.os.TraceNameSupplier
import android.util.Log
import com.android.systemui.tracing.TraceData.Companion.FIRST_VALID_SPAN
import com.android.systemui.tracing.TraceData.Companion.INVALID_SPAN
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Run a block within a [Trace] section. Calls [Trace.beginSection] before and [Trace.endSection]
 * after the passed block.
 */
inline fun <T> traceSection(tag: String, block: () -> T): T =
    if (Trace.isTagEnabled(Trace.TRACE_TAG_APP)) {
        Trace.traceBegin(Trace.TRACE_TAG_APP, tag)
        try {
            block()
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_APP)
        }
    } else {
        block()
    }

class TraceUtils {
    companion object {
        const val TAG = "TraceUtils"
        private const val DEBUG_COROUTINE_TRACING = false
        const val DEFAULT_TRACK_NAME = "AsyncTraces"

        inline fun traceRunnable(tag: String, crossinline block: () -> Unit): Runnable {
            return Runnable { traceSection(tag) { block() } }
        }

        /**
         * Helper function for creating a Runnable object that implements TraceNameSupplier.
         *
         * This is useful for posting Runnables to Handlers with meaningful names.
         */
        inline fun namedRunnable(tag: String, crossinline block: () -> Unit): Runnable {
            return object : Runnable, TraceNameSupplier {
                override fun getTraceName(): String = tag
                override fun run() = block()
            }
        }

        /**
         * Cookie used for async traces. Shouldn't be public, but to use it inside inline methods
         * there is no other way around.
         */
        val lastCookie = AtomicInteger(0)

        /**
         * Creates an async slice in a track called "AsyncTraces".
         *
         * This can be used to trace coroutine code. Note that all usages of this method will appear
         * under a single track.
         */
        inline fun <T> traceAsync(method: String, block: () -> T): T =
            traceAsync(DEFAULT_TRACK_NAME, method, block)

        /**
         * Creates an async slice in a track with [trackName] while [block] runs.
         *
         * This can be used to trace coroutine code. [method] will be the name of the slice,
         * [trackName] of the track. The track is one of the rows visible in a perfetto trace inside
         * SystemUI process.
         */
        inline fun <T> traceAsync(trackName: String, method: String, block: () -> T): T {
            val cookie = lastCookie.incrementAndGet()
            Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, trackName, method, cookie)
            try {
                return block()
            } finally {
                Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, trackName, cookie)
            }
        }

        /**
         * Convenience function for calling [CoroutineScope.launch] with [traceCoroutine] enable
         * tracing.
         *
         * @see traceCoroutine
         */
        inline fun CoroutineScope.launch(
            crossinline spanName: () -> String,
            context: CoroutineContext = EmptyCoroutineContext,
            // TODO(b/306457056): DO NOT pass CoroutineStart; doing so will regress .odex size
            crossinline block: suspend CoroutineScope.() -> Unit
        ): Job = launch(context) { traceCoroutine(spanName) { block() } }

        /**
         * Convenience function for calling [CoroutineScope.launch] with [traceCoroutine] enable
         * tracing.
         *
         * @see traceCoroutine
         */
        inline fun CoroutineScope.launch(
            spanName: String,
            context: CoroutineContext = EmptyCoroutineContext,
            // TODO(b/306457056): DO NOT pass CoroutineStart; doing so will regress .odex size
            crossinline block: suspend CoroutineScope.() -> Unit
        ): Job = launch(context) { traceCoroutine(spanName) { block() } }

        /**
         * Convenience function for calling [CoroutineScope.async] with [traceCoroutine] enable
         * tracing
         *
         * @see traceCoroutine
         */
        inline fun <T> CoroutineScope.async(
            crossinline spanName: () -> String,
            context: CoroutineContext = EmptyCoroutineContext,
            // TODO(b/306457056): DO NOT pass CoroutineStart; doing so will regress .odex size
            crossinline block: suspend CoroutineScope.() -> T
        ): Deferred<T> = async(context) { traceCoroutine(spanName) { block() } }

        /**
         * Convenience function for calling [CoroutineScope.async] with [traceCoroutine] enable
         * tracing.
         *
         * @see traceCoroutine
         */
        inline fun <T> CoroutineScope.async(
            spanName: String,
            context: CoroutineContext = EmptyCoroutineContext,
            // TODO(b/306457056): DO NOT pass CoroutineStart; doing so will regress .odex size
            crossinline block: suspend CoroutineScope.() -> T
        ): Deferred<T> = async(context) { traceCoroutine(spanName) { block() } }

        /**
         * Convenience function for calling [runBlocking] with [traceCoroutine] to enable tracing.
         *
         * @see traceCoroutine
         */
        inline fun <T> runBlocking(
            crossinline spanName: () -> String,
            context: CoroutineContext,
            crossinline block: suspend () -> T
        ): T = runBlocking(context) { traceCoroutine(spanName) { block() } }

        /**
         * Convenience function for calling [runBlocking] with [traceCoroutine] to enable tracing.
         *
         * @see traceCoroutine
         */
        inline fun <T> runBlocking(
            spanName: String,
            context: CoroutineContext,
            crossinline block: suspend CoroutineScope.() -> T
        ): T = runBlocking(context) { traceCoroutine(spanName) { block() } }

        /**
         * Convenience function for calling [withContext] with [traceCoroutine] to enable tracing.
         *
         * @see traceCoroutine
         */
        suspend inline fun <T> withContext(
            spanName: String,
            context: CoroutineContext,
            crossinline block: suspend CoroutineScope.() -> T
        ): T = withContext(context) { traceCoroutine(spanName) { block() } }

        /**
         * Convenience function for calling [withContext] with [traceCoroutine] to enable tracing.
         *
         * @see traceCoroutine
         */
        suspend inline fun <T> withContext(
            crossinline spanName: () -> String,
            context: CoroutineContext,
            crossinline block: suspend CoroutineScope.() -> T
        ): T = withContext(context) { traceCoroutine(spanName) { block() } }

        /**
         * A hacky way to propagate the value of the COROUTINE_TRACING flag for static usage in this
         * file. It should only every be set to true during startup. Once true, it cannot be set to
         * false again.
         */
        var coroutineTracingIsEnabled = false
            set(v) {
                if (v) field = true
            }

        /**
         * Traces a section of work of a `suspend` [block]. The trace sections will appear on the
         * thread that is currently executing the [block] of work. If the [block] is suspended, all
         * trace sections added using this API will end until the [block] is resumed, which could
         * happen either on this thread or on another thread. If a child coroutine is started, it
         * will inherit the trace sections of its parent. The child will continue to print these
         * trace sections whether or not the parent coroutine is still running them.
         *
         * The current [CoroutineContext] must have a [TraceContextElement] for this API to work.
         * Otherwise, the trace sections will be dropped.
         *
         * For example, in the following trace, Thread #1 ran some work, suspended, then continued
         * working on Thread #2. Meanwhile, Thread #2 created a new child coroutine which inherited
         * its trace sections. Then, the original coroutine resumed on Thread #1 before ending.
         * Meanwhile Thread #3 is still printing trace sections from its parent because they were
         * copied when it was created. There is no way for the parent to communicate to the child
         * that it marked these slices as completed. While this might seem counterintuitive, it
         * allows us to pinpoint the origin of the child coroutine's work.
         *
         * ```
         * Thread #1 | [==== Slice A ====]                        [==== Slice A ====]
         *           |       [==== B ====]                        [=== B ===]
         * --------------------------------------------------------------------------------------
         * Thread #2 |                    [====== Slice A ======]
         *           |                    [========= B =========]
         *           |                        [===== C ======]
         * --------------------------------------------------------------------------------------
         * Thread #3 |                            [== Slice A ==]                [== Slice A ==]
         *           |                            [===== B =====]                [===== B =====]
         *           |                            [===== C =====]                [===== C =====]
         *           |                                                               [=== D ===]
         * ```
         *
         * @param name The name of the code section to appear in the trace
         * @see endSlice
         * @see traceCoroutine
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        suspend inline fun <T> traceCoroutine(
            spanName: Lazy<String>,
            crossinline block: suspend () -> T
        ): T {
            // For coroutine tracing to work, trace spans must be added and removed even when
            // tracing is not active (i.e. when TRACE_TAG_APP is disabled). Otherwise, when the
            // coroutine resumes when tracing is active, we won't know its name.
            val tracer = getTraceData(spanName)
            val coroutineSpanCookie = tracer?.beginSpan(spanName.value) ?: INVALID_SPAN

            // For now, also trace to "AsyncTraces". This will allow us to verify the correctness
            // of the COROUTINE_TRACING feature flag.
            val asyncTraceCookie =
                if (Trace.isTagEnabled(Trace.TRACE_TAG_APP))
                    Random.nextInt(FIRST_VALID_SPAN, Int.MAX_VALUE)
                else INVALID_SPAN
            if (asyncTraceCookie != INVALID_SPAN) {
                Trace.asyncTraceForTrackBegin(
                    Trace.TRACE_TAG_APP,
                    DEFAULT_TRACK_NAME,
                    spanName.value,
                    asyncTraceCookie
                )
            }
            try {
                return block()
            } finally {
                if (asyncTraceCookie != INVALID_SPAN) {
                    Trace.asyncTraceForTrackEnd(
                        Trace.TRACE_TAG_APP,
                        DEFAULT_TRACK_NAME,
                        asyncTraceCookie
                    )
                }
                tracer?.endSpan(coroutineSpanCookie)
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun getTraceData(spanName: Lazy<String>): TraceData? {
            if (!coroutineTracingIsEnabled) {
                logVerbose("Experimental flag COROUTINE_TRACING is off", spanName)
            } else if (coroutineContext[TraceContextElement] == null) {
                logVerbose("Current CoroutineContext is missing TraceContextElement", spanName)
            } else {
                return threadLocalTrace.get().also {
                    if (it == null) logVerbose("ThreadLocal TraceData is null", spanName)
                }
            }
            return null
        }

        private fun logVerbose(logMessage: String, spanName: Lazy<String>) {
            if (DEBUG_COROUTINE_TRACING && Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "$logMessage. Dropping trace section: \"${spanName.value}\"")
            }
        }

        /** @see traceCoroutine */
        suspend inline fun <T> traceCoroutine(
            spanName: String,
            crossinline block: suspend () -> T
        ): T = traceCoroutine(lazyOf(spanName)) { block() }

        /** @see traceCoroutine */
        suspend inline fun <T> traceCoroutine(
            crossinline spanName: () -> String,
            crossinline block: suspend () -> T
        ): T = traceCoroutine(lazy(LazyThreadSafetyMode.PUBLICATION) { spanName() }) { block() }

        /**
         * Writes a trace message to indicate that a given section of code has begun running __on
         * the current thread__. This must be followed by a corresponding call to [endSlice] in a
         * reasonably short amount of time __on the same thread__ (i.e. _before_ the thread becomes
         * idle again and starts running other, unrelated work).
         *
         * Calls to [beginSlice] and [endSlice] may be nested, and they will render in Perfetto as
         * follows:
         * ```
         * Thread #1 | [==========================]
         *           |       [==============]
         *           |           [====]
         * ```
         *
         * This function is provided for convenience to wrap a call to [Trace.traceBegin], which is
         * more verbose to call than [Trace.beginSection], but has the added benefit of not throwing
         * an [IllegalArgumentException] if the provided string is longer than 127 characters. We
         * use the term "slice" instead of "section" to be consistent with Perfetto.
         *
         * # Avoiding malformed traces
         *
         * Improper usage of this API will lead to malformed traces with long slices that sometimes
         * never end. This will look like the following:
         * ```
         * Thread #1 | [===================================================================== ...
         *           |       [==============]         [====================================== ...
         *           |           [=======]              [======]       [===================== ...
         *           |                                                       [=======]
         * ```
         *
         * To avoid this, [beginSlice] and [endSlice] should never be called from `suspend` blocks
         * (instead, use [traceCoroutine] for tracing suspending functions). While it would be
         * technically okay to call from a suspending function if that function were to only wrap
         * non-suspending blocks with [beginSlice] and [endSlice], doing so is risky because suspend
         * calls could be mistakenly added to that block as the code is refactored.
         *
         * Additionally, it is _not_ okay to call [beginSlice] when registering a callback and match
         * it with a call to [endSlice] inside that callback, even if the callback runs on the same
         * thread. Doing so would cause malformed traces because the [beginSlice] wasn't closed
         * before the thread became idle and started running unrelated work.
         *
         * @param sliceName The name of the code section to appear in the trace
         * @see endSlice
         * @see traceCoroutine
         */
        fun beginSlice(sliceName: String) {
            Trace.traceBegin(Trace.TRACE_TAG_APP, sliceName)
        }

        /**
         * Writes a trace message to indicate that a given section of code has ended. This call must
         * be preceded by a corresponding call to [beginSlice]. See [beginSlice] for important
         * information regarding usage.
         *
         * @see beginSlice
         * @see traceCoroutine
         */
        fun endSlice() {
            Trace.traceEnd(Trace.TRACE_TAG_APP)
        }

        /**
         * Writes a trace message indicating that an instant event occurred on the current thread.
         * Unlike slices, instant events have no duration and do not need to be matched with another
         * call. Perfetto will display instant events using an arrow pointing to the timestamp they
         * occurred:
         * ```
         * Thread #1 | [==============]               [======]
         *           |     [====]                        ^
         *           |        ^
         * ```
         *
         * @param eventName The name of the event to appear in the trace.
         */
        fun instant(eventName: String) {
            Trace.instant(Trace.TRACE_TAG_APP, eventName)
        }

        /**
         * Writes a trace message indicating that an instant event occurred on the given track.
         * Unlike slices, instant events have no duration and do not need to be matched with another
         * call. Perfetto will display instant events using an arrow pointing to the timestamp they
         * occurred:
         * ```
         * Async  | [==============]               [======]
         *  Track |     [====]                        ^
         *   Name |        ^
         * ```
         *
         * @param trackName The track where the event should appear in the trace.
         * @param eventName The name of the event to appear in the trace.
         */
        fun instantForTrack(trackName: String, eventName: String) {
            Trace.instantForTrack(Trace.TRACE_TAG_APP, trackName, eventName)
        }
    }
}
