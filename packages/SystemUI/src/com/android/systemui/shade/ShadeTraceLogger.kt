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
import com.android.app.tracing.coroutines.TrackTracer

/**
 * Centralized logging for shade-related events to a dedicated Perfetto track.
 *
 * Used by shade components to log events to a track named [TRACK_NAME]. This consolidates
 * shade-specific events into a single track for easier analysis in Perfetto, rather than scattering
 * them across various threads' logs.
 */
object ShadeTraceLogger {
    val t = TrackTracer(trackName = "ShadeTraceLogger", trackGroup = "shade")

    @JvmStatic
    fun logOnMovedToDisplay(displayId: Int, config: Configuration) {
        t.instant { "onMovedToDisplay(displayId=$displayId, dpi=${config.densityDpi})" }
    }

    @JvmStatic
    fun logOnConfigChanged(config: Configuration) {
        t.instant {
            "NotificationShadeWindowView#onConfigurationChanged(dpi=${config.densityDpi}, " +
                    "smallestWidthDp=${config.smallestScreenWidthDp})"
        }
    }

    @JvmStatic
    fun logMoveShadeWindowTo(displayId: Int) {
        t.instant { "moveShadeWindowTo(displayId=$displayId)" }
    }

    suspend fun traceReparenting(r: suspend () -> Unit) {
        t.traceAsync({ "reparenting" }) { r() }
    }

    inline fun traceWaitForExpansion(expansion: Float, r: () -> Unit) {
        t.traceAsync({ "waiting for shade expansion to match $expansion" }) { r() }
    }
}
