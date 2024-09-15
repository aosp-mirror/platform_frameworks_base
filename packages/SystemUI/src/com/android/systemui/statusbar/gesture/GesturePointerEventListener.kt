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
package com.android.systemui.statusbar.gesture

import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManagerGlobal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.GestureDetector
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT
import android.view.MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE
import android.view.ViewRootImpl.CLIENT_TRANSIENT
import android.widget.OverScroller
import com.android.internal.R
import com.android.systemui.CoreStartable
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Watches for gesture events that may trigger system bar related events and notify the registered
 * callbacks. Add callback to this listener by calling {@link setCallbacks}.
 */
class GesturePointerEventListener
@Inject
constructor(context: Context, gestureDetector: GesturePointerEventDetector) : CoreStartable {
    private val mContext: Context
    private val mHandler = Handler(Looper.getMainLooper())
    private var mGestureDetector: GesturePointerEventDetector
    private var mFlingGestureDetector: GestureDetector? = null
    private var mDisplayCutoutTouchableRegionSize = 0

    // The thresholds for each edge of the display
    private val mSwipeStartThreshold = Rect()
    private var mSwipeDistanceThreshold = 0
    private var mCallbacks: Callbacks? = null
    private val mDownPointerId = IntArray(MAX_TRACKED_POINTERS)
    private val mDownX = FloatArray(MAX_TRACKED_POINTERS)
    private val mDownY = FloatArray(MAX_TRACKED_POINTERS)
    private val mDownTime = LongArray(MAX_TRACKED_POINTERS)
    var screenHeight = 0
    var screenWidth = 0
    private var mDownPointers = 0
    private var mSwipeFireable = false
    private var mDebugFireable = false
    private var mMouseHoveringAtLeft = false
    private var mMouseHoveringAtTop = false
    private var mMouseHoveringAtRight = false
    private var mMouseHoveringAtBottom = false
    private var mLastFlingTime: Long = 0

    init {
        mContext = checkNull("context", context)
        mGestureDetector = checkNull("gesture detector", gestureDetector)
        onConfigurationChanged()
    }

    override fun start() {
        if (!CLIENT_TRANSIENT) {
            return
        }
        mGestureDetector.addOnGestureDetectedCallback(TAG) { ev -> onInputEvent(ev) }
        mGestureDetector.startGestureListening()

        mFlingGestureDetector =
            object : GestureDetector(mContext, FlingGestureDetector(), mHandler) {}
    }

    fun onDisplayInfoChanged(info: DisplayInfo) {
        screenWidth = info.logicalWidth
        screenHeight = info.logicalHeight
        onConfigurationChanged()
    }

    fun onConfigurationChanged() {
        if (!CLIENT_TRANSIENT) {
            return
        }
        val r = mContext.resources
        val startThreshold = r.getDimensionPixelSize(R.dimen.system_gestures_start_threshold)
        mSwipeStartThreshold[startThreshold, startThreshold, startThreshold] = startThreshold
        mSwipeDistanceThreshold =
            r.getDimensionPixelSize(R.dimen.system_gestures_distance_threshold)
        val display = DisplayManagerGlobal.getInstance().getRealDisplay(mContext.displayId)
        val displayCutout = display.cutout
        if (displayCutout != null) {
            // Expand swipe start threshold such that we can catch touches that just start beyond
            // the notch area
            mDisplayCutoutTouchableRegionSize =
                r.getDimensionPixelSize(R.dimen.display_cutout_touchable_region_size)
            val bounds = displayCutout.boundingRectsAll
            if (bounds[DisplayCutout.BOUNDS_POSITION_LEFT] != null) {
                mSwipeStartThreshold.left =
                    Math.max(
                        mSwipeStartThreshold.left,
                        bounds[DisplayCutout.BOUNDS_POSITION_LEFT]!!.width() +
                            mDisplayCutoutTouchableRegionSize
                    )
            }
            if (bounds[DisplayCutout.BOUNDS_POSITION_TOP] != null) {
                mSwipeStartThreshold.top =
                    Math.max(
                        mSwipeStartThreshold.top,
                        bounds[DisplayCutout.BOUNDS_POSITION_TOP]!!.height() +
                            mDisplayCutoutTouchableRegionSize
                    )
            }
            if (bounds[DisplayCutout.BOUNDS_POSITION_RIGHT] != null) {
                mSwipeStartThreshold.right =
                    Math.max(
                        mSwipeStartThreshold.right,
                        bounds[DisplayCutout.BOUNDS_POSITION_RIGHT]!!.width() +
                            mDisplayCutoutTouchableRegionSize
                    )
            }
            if (bounds[DisplayCutout.BOUNDS_POSITION_BOTTOM] != null) {
                mSwipeStartThreshold.bottom =
                    Math.max(
                        mSwipeStartThreshold.bottom,
                        bounds[DisplayCutout.BOUNDS_POSITION_BOTTOM]!!.height() +
                            mDisplayCutoutTouchableRegionSize
                    )
            }
        }
        if (DEBUG)
            Log.d(
                TAG,
                "mSwipeStartThreshold=$mSwipeStartThreshold" +
                    " mSwipeDistanceThreshold=$mSwipeDistanceThreshold"
            )
    }

    fun onInputEvent(ev: InputEvent) {
        if (ev !is MotionEvent) {
            return
        }
        if (DEBUG) Log.d(TAG, "Received motion event $ev")
        if (ev.isTouchEvent) {
            mFlingGestureDetector?.onTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mSwipeFireable = true
                mDebugFireable = true
                mDownPointers = 0
                captureDown(ev, 0)
                if (mMouseHoveringAtLeft) {
                    mMouseHoveringAtLeft = false
                    mCallbacks?.onMouseLeaveFromLeft()
                }
                if (mMouseHoveringAtTop) {
                    mMouseHoveringAtTop = false
                    mCallbacks?.onMouseLeaveFromTop()
                }
                if (mMouseHoveringAtRight) {
                    mMouseHoveringAtRight = false
                    mCallbacks?.onMouseLeaveFromRight()
                }
                if (mMouseHoveringAtBottom) {
                    mMouseHoveringAtBottom = false
                    mCallbacks?.onMouseLeaveFromBottom()
                }
                mCallbacks?.onDown()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                captureDown(ev, ev.actionIndex)
                if (mDebugFireable) {
                    mDebugFireable = ev.pointerCount < 5
                    if (!mDebugFireable) {
                        if (DEBUG) Log.d(TAG, "Firing debug")
                        mCallbacks?.onDebug()
                    }
                }
            }
            MotionEvent.ACTION_MOVE ->
                if (mSwipeFireable) {
                    val trackpadSwipe = detectTrackpadThreeFingerSwipe(ev)
                    mSwipeFireable = trackpadSwipe == TRACKPAD_SWIPE_NONE
                    if (!mSwipeFireable) {
                        if (trackpadSwipe == TRACKPAD_SWIPE_FROM_TOP) {
                            if (DEBUG) Log.d(TAG, "Firing onSwipeFromTop from trackpad")
                            mCallbacks?.onSwipeFromTop()
                        } else if (trackpadSwipe == TRACKPAD_SWIPE_FROM_BOTTOM) {
                            if (DEBUG) Log.d(TAG, "Firing onSwipeFromBottom from trackpad")
                            mCallbacks?.onSwipeFromBottom()
                        } else if (trackpadSwipe == TRACKPAD_SWIPE_FROM_RIGHT) {
                            if (DEBUG) Log.d(TAG, "Firing onSwipeFromRight from trackpad")
                            mCallbacks?.onSwipeFromRight()
                        } else if (trackpadSwipe == TRACKPAD_SWIPE_FROM_LEFT) {
                            if (DEBUG) Log.d(TAG, "Firing onSwipeFromLeft from trackpad")
                            mCallbacks?.onSwipeFromLeft()
                        }
                    } else {
                        val swipe = detectSwipe(ev)
                        mSwipeFireable = swipe == SWIPE_NONE
                        if (swipe == SWIPE_FROM_TOP) {
                            if (DEBUG) Log.d(TAG, "Firing onSwipeFromTop")
                            mCallbacks?.onSwipeFromTop()
                        } else if (swipe == SWIPE_FROM_BOTTOM) {
                            if (DEBUG) Log.d(TAG, "Firing onSwipeFromBottom")
                            mCallbacks?.onSwipeFromBottom()
                        } else if (swipe == SWIPE_FROM_RIGHT) {
                            if (DEBUG) Log.d(TAG, "Firing onSwipeFromRight")
                            mCallbacks?.onSwipeFromRight()
                        } else if (swipe == SWIPE_FROM_LEFT) {
                            if (DEBUG) Log.d(TAG, "Firing onSwipeFromLeft")
                            mCallbacks?.onSwipeFromLeft()
                        }
                    }
                }
            MotionEvent.ACTION_HOVER_MOVE ->
                if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    val eventX = ev.x
                    val eventY = ev.y
                    if (!mMouseHoveringAtLeft && eventX == 0f) {
                        mCallbacks?.onMouseHoverAtLeft()
                        mMouseHoveringAtLeft = true
                    } else if (mMouseHoveringAtLeft && eventX > 0) {
                        mCallbacks?.onMouseLeaveFromLeft()
                        mMouseHoveringAtLeft = false
                    }
                    if (!mMouseHoveringAtTop && eventY == 0f) {
                        mCallbacks?.onMouseHoverAtTop()
                        mMouseHoveringAtTop = true
                    } else if (mMouseHoveringAtTop && eventY > 0) {
                        mCallbacks?.onMouseLeaveFromTop()
                        mMouseHoveringAtTop = false
                    }
                    if (!mMouseHoveringAtRight && eventX >= screenWidth - 1) {
                        mCallbacks?.onMouseHoverAtRight()
                        mMouseHoveringAtRight = true
                    } else if (mMouseHoveringAtRight && eventX < screenWidth - 1) {
                        mCallbacks?.onMouseLeaveFromRight()
                        mMouseHoveringAtRight = false
                    }
                    if (!mMouseHoveringAtBottom && eventY >= screenHeight - 1) {
                        mCallbacks?.onMouseHoverAtBottom()
                        mMouseHoveringAtBottom = true
                    } else if (mMouseHoveringAtBottom && eventY < screenHeight - 1) {
                        mCallbacks?.onMouseLeaveFromBottom()
                        mMouseHoveringAtBottom = false
                    }
                }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mSwipeFireable = false
                mDebugFireable = false
                mCallbacks?.onUpOrCancel()
            }
            else -> if (DEBUG) Log.d(TAG, "Ignoring $ev")
        }
    }

    fun setCallbacks(callbacks: Callbacks) {
        mCallbacks = callbacks
    }

    private fun captureDown(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)
        val i = findIndex(pointerId)
        if (DEBUG) Log.d(TAG, "pointer $pointerId down pointerIndex=$pointerIndex trackingIndex=$i")
        if (i != UNTRACKED_POINTER) {
            mDownX[i] = event.getX(pointerIndex)
            mDownY[i] = event.getY(pointerIndex)
            mDownTime[i] = event.eventTime
            if (DEBUG)
                Log.d(TAG, "pointer " + pointerId + " down x=" + mDownX[i] + " y=" + mDownY[i])
        }
    }

    protected fun currentGestureStartedInRegion(r: Region): Boolean {
        return r.contains(mDownX[0].toInt(), mDownY[0].toInt())
    }

    private fun findIndex(pointerId: Int): Int {
        for (i in 0 until mDownPointers) {
            if (mDownPointerId[i] == pointerId) {
                return i
            }
        }
        if (mDownPointers == MAX_TRACKED_POINTERS || pointerId == MotionEvent.INVALID_POINTER_ID) {
            return UNTRACKED_POINTER
        }
        mDownPointerId[mDownPointers++] = pointerId
        return mDownPointers - 1
    }

    private fun detectTrackpadThreeFingerSwipe(move: MotionEvent): Int {
        if (!isTrackpadThreeFingerSwipe(move)) {
            return TRACKPAD_SWIPE_NONE
        }
        val dx = move.x - mDownX[0]
        val dy = move.y - mDownY[0]
        if (Math.abs(dx) < Math.abs(dy)) {
            if (Math.abs(dy) > mSwipeDistanceThreshold) {
                return if (dy > 0) TRACKPAD_SWIPE_FROM_TOP else TRACKPAD_SWIPE_FROM_BOTTOM
            }
        } else {
            if (Math.abs(dx) > mSwipeDistanceThreshold) {
                return if (dx > 0) TRACKPAD_SWIPE_FROM_LEFT else TRACKPAD_SWIPE_FROM_RIGHT
            }
        }
        return TRACKPAD_SWIPE_NONE
    }

    private fun isTrackpadThreeFingerSwipe(event: MotionEvent): Boolean {
        return (event.classification == CLASSIFICATION_MULTI_FINGER_SWIPE &&
            event.getAxisValue(AXIS_GESTURE_SWIPE_FINGER_COUNT) == 3f)
    }
    private fun detectSwipe(move: MotionEvent): Int {
        val historySize = move.historySize
        val pointerCount = move.pointerCount
        for (p in 0 until pointerCount) {
            val pointerId = move.getPointerId(p)
            val i = findIndex(pointerId)
            if (i != UNTRACKED_POINTER) {
                for (h in 0 until historySize) {
                    val time = move.getHistoricalEventTime(h)
                    val x = move.getHistoricalX(p, h)
                    val y = move.getHistoricalY(p, h)
                    val swipe = detectSwipe(i, time, x, y)
                    if (swipe != SWIPE_NONE) {
                        return swipe
                    }
                }
                val swipe = detectSwipe(i, move.eventTime, move.getX(p), move.getY(p))
                if (swipe != SWIPE_NONE) {
                    return swipe
                }
            }
        }
        return SWIPE_NONE
    }

    private fun detectSwipe(i: Int, time: Long, x: Float, y: Float): Int {
        val fromX = mDownX[i]
        val fromY = mDownY[i]
        val elapsed = time - mDownTime[i]
        if (DEBUG)
            Log.d(
                TAG,
                "pointer " +
                    mDownPointerId[i] +
                    " moved (" +
                    fromX +
                    "->" +
                    x +
                    "," +
                    fromY +
                    "->" +
                    y +
                    ") in " +
                    elapsed
            )
        if (
            fromY <= mSwipeStartThreshold.top &&
                y > fromY + mSwipeDistanceThreshold &&
                elapsed < SWIPE_TIMEOUT_MS
        ) {
            return SWIPE_FROM_TOP
        }
        if (
            fromY >= screenHeight - mSwipeStartThreshold.bottom &&
                y < fromY - mSwipeDistanceThreshold &&
                elapsed < SWIPE_TIMEOUT_MS
        ) {
            return SWIPE_FROM_BOTTOM
        }
        if (
            fromX >= screenWidth - mSwipeStartThreshold.right &&
                x < fromX - mSwipeDistanceThreshold &&
                elapsed < SWIPE_TIMEOUT_MS
        ) {
            return SWIPE_FROM_RIGHT
        }
        return if (
            fromX <= mSwipeStartThreshold.left &&
                x > fromX + mSwipeDistanceThreshold &&
                elapsed < SWIPE_TIMEOUT_MS
        ) {
            SWIPE_FROM_LEFT
        } else SWIPE_NONE
    }

    fun dump(pw: PrintWriter, prefix: String) {
        val inner = "$prefix  "
        pw.println(prefix + TAG + ":")
        pw.print(inner)
        pw.print("mDisplayCutoutTouchableRegionSize=")
        pw.println(mDisplayCutoutTouchableRegionSize)
        pw.print(inner)
        pw.print("mSwipeStartThreshold=")
        pw.println(mSwipeStartThreshold)
        pw.print(inner)
        pw.print("mSwipeDistanceThreshold=")
        pw.println(mSwipeDistanceThreshold)
    }

    private inner class FlingGestureDetector internal constructor() :
        GestureDetector.SimpleOnGestureListener() {
        private val mOverscroller: OverScroller = OverScroller(mContext)

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!mOverscroller.isFinished) {
                mOverscroller.forceFinished(true)
            }
            return true
        }

        override fun onFling(
            down: MotionEvent?,
            up: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            mOverscroller.computeScrollOffset()
            val now = SystemClock.uptimeMillis()
            if (mLastFlingTime != 0L && now > mLastFlingTime + MAX_FLING_TIME_MILLIS) {
                mOverscroller.forceFinished(true)
            }
            mOverscroller.fling(
                0,
                0,
                velocityX.toInt(),
                velocityY.toInt(),
                Int.MIN_VALUE,
                Int.MAX_VALUE,
                Int.MIN_VALUE,
                Int.MAX_VALUE
            )
            var duration = mOverscroller.duration
            if (duration > MAX_FLING_TIME_MILLIS) {
                duration = MAX_FLING_TIME_MILLIS
            }
            mLastFlingTime = now
            mCallbacks?.onFling(duration)
            return true
        }
    }

    interface Callbacks {
        fun onSwipeFromTop()
        fun onSwipeFromBottom()
        fun onSwipeFromRight()
        fun onSwipeFromLeft()
        fun onFling(durationMs: Int)
        fun onDown()
        fun onUpOrCancel()
        fun onMouseHoverAtLeft()
        fun onMouseHoverAtTop()
        fun onMouseHoverAtRight()
        fun onMouseHoverAtBottom()
        fun onMouseLeaveFromLeft()
        fun onMouseLeaveFromTop()
        fun onMouseLeaveFromRight()
        fun onMouseLeaveFromBottom()
        fun onDebug()
    }

    companion object {
        private const val TAG = "GesturePointerEventHandler"
        private const val DEBUG = false
        private const val SWIPE_TIMEOUT_MS: Long = 500
        private const val MAX_TRACKED_POINTERS = 32 // max per input system
        private const val UNTRACKED_POINTER = -1
        private const val MAX_FLING_TIME_MILLIS = 5000
        private const val SWIPE_NONE = 0
        private const val SWIPE_FROM_TOP = 1
        private const val SWIPE_FROM_BOTTOM = 2
        private const val SWIPE_FROM_RIGHT = 3
        private const val SWIPE_FROM_LEFT = 4
        private const val TRACKPAD_SWIPE_NONE = 0
        private const val TRACKPAD_SWIPE_FROM_TOP = 1
        private const val TRACKPAD_SWIPE_FROM_BOTTOM = 2
        private const val TRACKPAD_SWIPE_FROM_RIGHT = 3
        private const val TRACKPAD_SWIPE_FROM_LEFT = 4

        private fun <T> checkNull(name: String, arg: T?): T {
            requireNotNull(arg) { "$name must not be null" }
            return arg
        }
    }
}
