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

import android.os.Trace

/**
 * Run a block within a [Trace] section.
 * Calls [Trace.beginSection] before and [Trace.endSection] after the passed block.
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
        inline fun traceRunnable(tag: String, crossinline block: () -> Unit): Runnable {
            return Runnable { traceSection(tag) { block() } }
        }
    }
}
