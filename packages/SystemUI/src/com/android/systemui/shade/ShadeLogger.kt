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

    fun d(@CompileTimeConstant msg: String) {
        buffer.log(TAG, LogLevel.DEBUG, msg)
    }

    fun onQsInterceptMoveQsTrackingEnabled(h: Float) {
        buffer.log(
            TAG,
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
        buffer.log(
            TAG,
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
        buffer.log(
            TAG,
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

    fun logMotionEventStatusBarState(event: MotionEvent, statusBarState: Int, message: String) {
        buffer.log(
                TAG,
                LogLevel.VERBOSE,
                {
                    str1 = message
                    long1 = event.eventTime
                    long2 = event.downTime
                    int1 = event.action
                    int2 = statusBarState
                    double1 = event.y.toDouble()
                },
                {
                    "$str1\neventTime=$long1,downTime=$long2,y=$double1,action=$int1," +
                            "statusBarState=${when (int2) {
                                0 -> "SHADE"
                                1 -> "KEYGUARD"
                                2 -> "SHADE_LOCKED"
                                else -> "UNKNOWN:$int2"
                            }}"
                }
        )
    }

    fun logExpansionChanged(
            message: String,
            fraction: Float,
            expanded: Boolean,
            tracking: Boolean,
            dragDownPxAmount: Float,
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = message
                double1 = fraction.toDouble()
                bool1 = expanded
                bool2 = tracking
                long1 = dragDownPxAmount.toLong()
            },
            {
                "$str1 fraction=$double1,expanded=$bool1," +
                    "tracking=$bool2," + "dragDownPxAmount=$dragDownPxAmount"
            }
        )
    }

    fun logHasVibrated(hasVibratedOnOpen: Boolean, fraction: Float) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                bool1 = hasVibratedOnOpen
                double1 = fraction.toDouble()
            },
            { "hasVibratedOnOpen=$bool1, expansionFraction=$double1" }
        )
    }

    fun logQsExpansionChanged(
            message: String,
            qsExpanded: Boolean,
            qsMinExpansionHeight: Int,
            qsMaxExpansionHeight: Int,
            stackScrollerOverscrolling: Boolean,
            dozing: Boolean,
            qsAnimatorExpand: Boolean,
            animatingQs: Boolean
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = message
                bool1 = qsExpanded
                int1 = qsMinExpansionHeight
                int2 = qsMaxExpansionHeight
                bool2 = stackScrollerOverscrolling
                bool3 = dozing
                bool4 = qsAnimatorExpand
                // 0 = false, 1 = true
                long1 = animatingQs.compareTo(false).toLong()
            },
            {
                "$str1 qsExpanded=$bool1,qsMinExpansionHeight=$int1,qsMaxExpansionHeight=$int2," +
                    "stackScrollerOverscrolling=$bool2,dozing=$bool3,qsAnimatorExpand=$bool4," +
                    "animatingQs=$long1"
            }
        )
    }

    fun logSingleTapUp(isDozing: Boolean, singleTapEnabled: Boolean, isNotDocked: Boolean) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = isDozing
                bool2 = singleTapEnabled
                bool3 = isNotDocked
            },
            {
                "PulsingGestureListener#onSingleTapUp all of this must true for single " +
               "tap to be detected: isDozing: $bool1, singleTapEnabled: $bool2, isNotDocked: $bool3"
        })
    }

    fun logSingleTapUpFalsingState(proximityIsNotNear: Boolean, isNotFalseTap: Boolean) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = proximityIsNotNear
                bool2 = isNotFalseTap
            },
            {
                "PulsingGestureListener#onSingleTapUp all of this must true for single " +
                    "tap to be detected: proximityIsNotNear: $bool1, isNotFalseTap: $bool2"
            }
        )
    }

    fun logNotInterceptingTouchInstantExpanding(
            instantExpanding: Boolean,
            notificationsDragEnabled: Boolean,
            touchDisabled: Boolean
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                bool1 = instantExpanding
                bool2 = notificationsDragEnabled
                bool3 = touchDisabled
            },
            {
                "NPVC not intercepting touch, instantExpanding: $bool1, " +
                    "!notificationsDragEnabled: $bool2, touchDisabled: $bool3"
            }
        )
    }
}
