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

package com.android.systemui.shade

import android.content.res.Configuration
import android.os.Trace
import com.android.app.tracing.TraceUtils.traceAsync

/**
 * Centralized logging for shade-related events to a dedicated Perfetto track.
 *
 * Used by shade components to log events to a track named [TAG]. This consolidates shade-specific
 * events into a single track for easier analysis in Perfetto, rather than scattering them across
 * various threads' logs.
 */
object ShadeTraceLogger {
    private const val TAG = "ShadeTraceLogger"

    @JvmStatic
    fun logOnMovedToDisplay(displayId: Int, config: Configuration) {
        if (!Trace.isEnabled()) return
        Trace.instantForTrack(
            Trace.TRACE_TAG_APP,
            TAG,
            "onMovedToDisplay(displayId=$displayId, dpi=" + config.densityDpi + ")",
        )
    }

    @JvmStatic
    fun logOnConfigChanged(config: Configuration) {
        if (!Trace.isEnabled()) return
        Trace.instantForTrack(
            Trace.TRACE_TAG_APP,
            TAG,
            "onConfigurationChanged(dpi=" + config.densityDpi + ")",
        )
    }

    fun logMoveShadeWindowTo(displayId: Int) {
        if (!Trace.isEnabled()) return
        Trace.instantForTrack(Trace.TRACE_TAG_APP, TAG, "moveShadeWindowTo(displayId=$displayId)")
    }

    fun traceReparenting(r: () -> Unit) {
        traceAsync(TAG, { "reparenting" }) { r() }
    }
}
