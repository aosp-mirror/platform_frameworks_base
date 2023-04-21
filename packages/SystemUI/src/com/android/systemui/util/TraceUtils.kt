/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util

import android.os.Handler
import android.os.TraceNameSupplier
import androidx.tracing.Trace

/**
 * Run a block within a [Trace] section. Calls [Trace.beginSection] before and [Trace.endSection]
 * after the passed block. If tracing is disabled, it will run the block directly to avoid using an
 * unnecessary try-finally block.
 */
inline fun <T> traceSection(tag: String, block: () -> T): T =
        if (Trace.isEnabled()) {
            Trace.beginSection(tag)
            try {
                block()
            } finally {
                Trace.endSection()
            }
        } else {
            block()
        }

class TraceUtils {
    companion object {
        inline fun traceRunnable(tag: String, crossinline block: () -> Unit): Runnable {
            return Runnable { traceSection(tag) { block() } }
        }

        /**
         * Helper function for creating a [Runnable] that implements [TraceNameSupplier]. This is
         * useful when posting to a [Handler] so that the [Runnable] has a meaningful name in the
         * trace. Otherwise, the class name of the [Runnable] is used, which is often something like
         * `pkg.MyClass$$ExternalSyntheticLambda0`.
         */
        inline fun namedRunnable(tag: String, crossinline block: () -> Unit): Runnable {
            return object : Runnable, TraceNameSupplier {
                override fun getTraceName(): String = tag
                override fun run() = block()
            }
        }
    }
}
