/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.view.MotionEvent
import com.android.systemui.log.dagger.ShadeLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.plugins.log.LogMessage
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "systemui.shade"

/** Lightweight logging utility for the Shade. */
class ShadeLogger @Inject constructor(@ShadeLog private val buffer: LogBuffer) {
    fun v(@CompileTimeConstant msg: String) {
        buffer.log(TAG, LogLevel.VERBOSE, msg)
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }

    fun onQsInterceptMoveQsTrackingEnabled(h: Float) {
        log(
            LogLevel.VERBOSE,
            { double1 = h.toDouble() },
            { "onQsIntercept: move action, QS tracking enabled. h = $double1" }
        )
    }

    fun logQsTrackingNotStarted(
        initialTouchY: Float,
        y: Float,
        h: Float,
        touchSlop: Float,
        qsExpanded: Boolean,
        collapsedOnDown: Boolean,
        keyguardShowing: Boolean,
        qsExpansionEnabled: Boolean
    ) {
        log(
            LogLevel.VERBOSE,
            {
                int1 = initialTouchY.toInt()
                int2 = y.toInt()
                long1 = h.toLong()
                double1 = touchSlop.toDouble()
                bool1 = qsExpanded
                bool2 = collapsedOnDown
                bool3 = keyguardShowing
                bool4 = qsExpansionEnabled
            },
            {
                "QsTrackingNotStarted: initTouchY=$int1,y=$int2,h=$long1,slop=$double1,qsExpanded" +
                    "=$bool1,collapsedDown=$bool2,keyguardShowing=$bool3,qsExpansion=$bool4"
            }
        )
    }

    fun logMotionEvent(event: MotionEvent, message: String) {
        log(
            LogLevel.VERBOSE,
            {
                str1 = message
                long1 = event.eventTime
                long2 = event.downTime
                int1 = event.action
                int2 = event.classification
                double1 = event.y.toDouble()
            },
            {
                "$str1\neventTime=$long1,downTime=$long2,y=$double1,action=$int1,class=$int2"
            }
        )
    }
}
