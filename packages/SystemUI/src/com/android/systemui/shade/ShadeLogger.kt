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
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.ShadeLog
import com.android.systemui.shade.ShadeViewController.Companion.FLING_COLLAPSE
import com.android.systemui.shade.ShadeViewController.Companion.FLING_EXPAND
import com.android.systemui.shade.ShadeViewController.Companion.FLING_HIDE
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
        keyguardShowing: Boolean,
        qsExpansionEnabled: Boolean,
        downTime: Long
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
                bool2 = keyguardShowing
                bool3 = qsExpansionEnabled
                str1 = downTime.toString()
            },
            {
                "QsTrackingNotStarted: downTime=$str1,initTouchY=$int1,y=$int2,h=$long1," +
                    "slop=$double1,qsExpanded=$bool1,keyguardShowing=$bool2,qsExpansion=$bool3"
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
            },
            { "$str1: eventTime=$long1,downTime=$long2,action=$int1,class=$int2" }
        )
    }

    /** Logs motion event dispatch results from NotificationShadeWindowViewController. */
    fun logShadeWindowDispatch(event: MotionEvent, message: String, result: Boolean?) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = message
                long1 = event.eventTime
                long2 = event.downTime
            },
            {
                val prefix =
                    when (result) {
                        true -> "SHADE TOUCH REROUTED"
                        false -> "SHADE TOUCH BLOCKED"
                        null -> "SHADE TOUCH DISPATCHED"
                    }
                "$prefix: eventTime=$long1,downTime=$long2, reason=$str1"
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
                    "tracking=$bool2," +
                    "dragDownPxAmount=$dragDownPxAmount"
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

    fun logQsExpandImmediateChanged(newValue: Boolean) {
        buffer.log(TAG, LogLevel.VERBOSE, { bool1 = newValue }, { "qsExpandImmediate=$bool1" })
    }

    fun logQsExpansionChanged(
        message: String,
        qsExpanded: Boolean,
        qsMinExpansionHeight: Int,
        qsMaxExpansionHeight: Int,
        stackScrollerOverscrolling: Boolean,
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
                bool3 = qsAnimatorExpand
                // 0 = false, 1 = true
                long1 = animatingQs.compareTo(false).toLong()
            },
            {
                "$str1 qsExpanded=$bool1,qsMinExpansionHeight=$int1,qsMaxExpansionHeight=$int2," +
                    "stackScrollerOverscrolling=$bool2,qsAnimatorExpand=$bool3," +
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
                    "tap to be detected: isDozing: $bool1, singleTapEnabled: $bool2," +
                    " isNotDocked: $bool3"
            }
        )
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

    fun logLastFlingWasExpanding(expand: Boolean) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            { bool1 = expand },
            { "NPVC mLastFlingWasExpanding set to: $bool1" }
        )
    }

    fun logFlingExpands(
        vel: Float,
        vectorVel: Float,
        interactionType: Int,
        minVelocityPxPerSecond: Float,
        expansionOverHalf: Boolean,
        allowExpandForSmallExpansion: Boolean
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                int1 = interactionType
                long1 = vel.toLong()
                long2 = vectorVel.toLong()
                double1 = minVelocityPxPerSecond.toDouble()
                bool1 = expansionOverHalf
                bool2 = allowExpandForSmallExpansion
            },
            {
                "NPVC flingExpands called with vel: $long1, vectorVel: $long2, " +
                    "interactionType: $int1, minVelocityPxPerSecond: $double1 " +
                    "expansionOverHalf: $bool1, allowExpandForSmallExpansion: $bool2"
            }
        )
    }

    fun logEndMotionEvent(
        msg: String,
        forceCancel: Boolean,
        expand: Boolean,
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = msg
                bool1 = forceCancel
                bool2 = expand
            },
            { "$str1; force=$bool1; expand=$bool2" }
        )
    }

    fun logPanelClosedOnDown(
        msg: String,
        panelClosedOnDown: Boolean,
        expandFraction: Float,
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = msg
                bool1 = panelClosedOnDown
                double1 = expandFraction.toDouble()
            },
            { "$str1; mPanelClosedOnDown=$bool1; mExpandedFraction=$double1" }
        )
    }

    fun logPanelStateChanged(@PanelState panelState: Int) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            { str1 = panelState.panelStateToString() },
            { "New panel State: $str1" }
        )
    }

    fun flingQs(flingType: Int, isClick: Boolean) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = flingTypeToString(flingType)
                bool1 = isClick
            },
            { "QS fling with type $str1, originated from click: $isClick" }
        )
    }

    private fun flingTypeToString(flingType: Int) =
        when (flingType) {
            FLING_EXPAND -> "FLING_EXPAND"
            FLING_COLLAPSE -> "FLING_COLLAPSE"
            FLING_HIDE -> "FLING_HIDE"
            else -> "UNKNOWN"
        }

    fun logSplitShadeChanged(splitShadeEnabled: Boolean) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            { bool1 = splitShadeEnabled },
            { "Split shade state changed: split shade ${if (bool1) "enabled" else "disabled"}" }
        )
    }

    fun logUpdateNotificationPanelTouchState(
        disabled: Boolean,
        isGoingToSleep: Boolean,
        shouldControlScreenOff: Boolean,
        deviceInteractive: Boolean,
        isPulsing: Boolean,
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                bool1 = disabled
                bool2 = isGoingToSleep
                bool3 = shouldControlScreenOff
                bool4 = deviceInteractive
                str1 = isPulsing.toString()
            },
            {
                "CentralSurfaces updateNotificationPanelTouchState set disabled to: $bool1\n" +
                    "isGoingToSleep: $bool2, !shouldControlScreenOff: $bool3," +
                    "!mDeviceInteractive: $bool4, !isPulsing: $str1"
            }
        )
    }

    fun logNoTouchDispatch(
        isTrackingBarGesture: Boolean,
        isExpandAnimationRunning: Boolean,
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                bool1 = isTrackingBarGesture
                bool2 = isExpandAnimationRunning
            },
            {
                "NSWVC: touch not dispatched: isTrackingBarGesture: $bool1, " +
                    "isExpandAnimationRunning: $bool2"
            }
        )
    }

    fun logKeyguardStatudBarVisibiliy(
        visibility: Boolean,
        isOnAod: Boolean,
        animatingUnlockedShadeToKeyguardBypass: Boolean,
        oldShadeState: Int,
        newShadeState: Int,
    ) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                bool1 = visibility
                bool2 = isOnAod
                bool3 = animatingUnlockedShadeToKeyguardBypass
                int1 = oldShadeState
                int2 = newShadeState
            },
            {
                "Setting keyguard status bar visibility to: $bool1, isOnAod: $bool2" +
                    "oldShadeState: $int1, newShadeState: $int2," +
                    "animatingUnlockedShadeToKeyguardBypass: $bool3"
            }
        )
    }
}
