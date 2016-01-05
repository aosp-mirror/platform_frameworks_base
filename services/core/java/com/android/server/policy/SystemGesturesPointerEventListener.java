/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.policy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Slog;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy.PointerEventListener;
import android.widget.OverScroller;

/*
 * Listens for system-wide input gestures, firing callbacks when detected.
 * @hide
 */
public class SystemGesturesPointerEventListener implements PointerEventListener {
    private static final String TAG = "SystemGestures";
    private static final boolean DEBUG = false;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final int MAX_TRACKED_POINTERS = 32;  // max per input system
    private static final int UNTRACKED_POINTER = -1;
    private static final int MAX_FLING_TIME_MILLIS = 5000;

    private static final int SWIPE_NONE = 0;
    private static final int SWIPE_FROM_TOP = 1;
    private static final int SWIPE_FROM_BOTTOM = 2;
    private static final int SWIPE_FROM_RIGHT = 3;

    private final Context mContext;
    private final int mSwipeStartThreshold;
    private final int mSwipeDistanceThreshold;
    private final Callbacks mCallbacks;
    private final int[] mDownPointerId = new int[MAX_TRACKED_POINTERS];
    private final float[] mDownX = new float[MAX_TRACKED_POINTERS];
    private final float[] mDownY = new float[MAX_TRACKED_POINTERS];
    private final long[] mDownTime = new long[MAX_TRACKED_POINTERS];

    private GestureDetector mGestureDetector;
    private OverScroller mOverscroller;

    int screenHeight;
    int screenWidth;
    private int mDownPointers;
    private boolean mSwipeFireable;
    private boolean mDebugFireable;
    private boolean mMouseHoveringAtEdge;
    private long mLastFlingTime;

    public SystemGesturesPointerEventListener(Context context, Callbacks callbacks) {
        mContext = context;
        mCallbacks = checkNull("callbacks", callbacks);
        mSwipeStartThreshold = checkNull("context", context).getResources()
                .getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mSwipeDistanceThreshold = mSwipeStartThreshold;
        if (DEBUG) Slog.d(TAG,  "mSwipeStartThreshold=" + mSwipeStartThreshold
                + " mSwipeDistanceThreshold=" + mSwipeDistanceThreshold);
    }

    private static <T> T checkNull(String name, T arg) {
        if (arg == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return arg;
    }

    public void systemReady() {
        Handler h = new Handler(Looper.myLooper());
        mGestureDetector = new GestureDetector(mContext, new FlingGestureDetector(), h);
        mOverscroller = new OverScroller(mContext);
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (mGestureDetector != null && event.isTouchEvent()) {
            mGestureDetector.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwipeFireable = true;
                mDebugFireable = true;
                mDownPointers = 0;
                captureDown(event, 0);
                if (mMouseHoveringAtEdge) {
                    mMouseHoveringAtEdge = false;
                    mCallbacks.onMouseLeaveFromEdge();
                }
                mCallbacks.onDown();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                captureDown(event, event.getActionIndex());
                if (mDebugFireable) {
                    mDebugFireable = event.getPointerCount() < 5;
                    if (!mDebugFireable) {
                        if (DEBUG) Slog.d(TAG, "Firing debug");
                        mCallbacks.onDebug();
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSwipeFireable) {
                    final int swipe = detectSwipe(event);
                    mSwipeFireable = swipe == SWIPE_NONE;
                    if (swipe == SWIPE_FROM_TOP) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromTop");
                        mCallbacks.onSwipeFromTop();
                    } else if (swipe == SWIPE_FROM_BOTTOM) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromBottom");
                        mCallbacks.onSwipeFromBottom();
                    } else if (swipe == SWIPE_FROM_RIGHT) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromRight");
                        mCallbacks.onSwipeFromRight();
                    }
                }
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    if (!mMouseHoveringAtEdge && event.getY() == 0) {
                        mCallbacks.onMouseHoverAtTop();
                        mMouseHoveringAtEdge = true;
                    } else if (!mMouseHoveringAtEdge && event.getY() >= screenHeight - 1) {
                        mCallbacks.onMouseHoverAtBottom();
                        mMouseHoveringAtEdge = true;
                    } else if (mMouseHoveringAtEdge
                            && (event.getY() > 0 && event.getY() < screenHeight - 1)) {
                        mCallbacks.onMouseLeaveFromEdge();
                        mMouseHoveringAtEdge = false;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mSwipeFireable = false;
                mDebugFireable = false;
                mCallbacks.onUpOrCancel();
                break;
            default:
                if (DEBUG) Slog.d(TAG, "Ignoring " + event);
        }
    }

    private void captureDown(MotionEvent event, int pointerIndex) {
        final int pointerId = event.getPointerId(pointerIndex);
        final int i = findIndex(pointerId);
        if (DEBUG) Slog.d(TAG, "pointer " + pointerId +
                " down pointerIndex=" + pointerIndex + " trackingIndex=" + i);
        if (i != UNTRACKED_POINTER) {
            mDownX[i] = event.getX(pointerIndex);
            mDownY[i] = event.getY(pointerIndex);
            mDownTime[i] = event.getEventTime();
            if (DEBUG) Slog.d(TAG, "pointer " + pointerId +
                    " down x=" + mDownX[i] + " y=" + mDownY[i]);
        }
    }

    private int findIndex(int pointerId) {
        for (int i = 0; i < mDownPointers; i++) {
            if (mDownPointerId[i] == pointerId) {
                return i;
            }
        }
        if (mDownPointers == MAX_TRACKED_POINTERS || pointerId == MotionEvent.INVALID_POINTER_ID) {
            return UNTRACKED_POINTER;
        }
        mDownPointerId[mDownPointers++] = pointerId;
        return mDownPointers - 1;
    }

    private int detectSwipe(MotionEvent move) {
        final int historySize = move.getHistorySize();
        final int pointerCount = move.getPointerCount();
        for (int p = 0; p < pointerCount; p++) {
            final int pointerId = move.getPointerId(p);
            final int i = findIndex(pointerId);
            if (i != UNTRACKED_POINTER) {
                for (int h = 0; h < historySize; h++) {
                    final long time = move.getHistoricalEventTime(h);
                    final float x = move.getHistoricalX(p, h);
                    final float y = move.getHistoricalY(p,  h);
                    final int swipe = detectSwipe(i, time, x, y);
                    if (swipe != SWIPE_NONE) {
                        return swipe;
                    }
                }
                final int swipe = detectSwipe(i, move.getEventTime(), move.getX(p), move.getY(p));
                if (swipe != SWIPE_NONE) {
                    return swipe;
                }
            }
        }
        return SWIPE_NONE;
    }

    private int detectSwipe(int i, long time, float x, float y) {
        final float fromX = mDownX[i];
        final float fromY = mDownY[i];
        final long elapsed = time - mDownTime[i];
        if (DEBUG) Slog.d(TAG, "pointer " + mDownPointerId[i]
                + " moved (" + fromX + "->" + x + "," + fromY + "->" + y + ") in " + elapsed);
        if (fromY <= mSwipeStartThreshold
                && y > fromY + mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_TOP;
        }
        if (fromY >= screenHeight - mSwipeStartThreshold
                && y < fromY - mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_BOTTOM;
        }
        if (fromX >= screenWidth - mSwipeStartThreshold
                && x < fromX - mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_RIGHT;
        }
        return SWIPE_NONE;
    }

    private final class FlingGestureDetector extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (!mOverscroller.isFinished()) {
                mOverscroller.forceFinished(true);
            }
            return true;
        }
        @Override
        public boolean onFling(MotionEvent down, MotionEvent up,
                float velocityX, float velocityY) {
            mOverscroller.computeScrollOffset();
            long now = SystemClock.uptimeMillis();

            if (mLastFlingTime != 0 && now > mLastFlingTime + MAX_FLING_TIME_MILLIS) {
                mOverscroller.forceFinished(true);
            }
            mOverscroller.fling(0, 0, (int)velocityX, (int)velocityY,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int duration = mOverscroller.getDuration();
            if (duration > MAX_FLING_TIME_MILLIS) {
                duration = MAX_FLING_TIME_MILLIS;
            }
            mLastFlingTime = now;
            mCallbacks.onFling(duration);
            return true;
        }
    }

    interface Callbacks {
        void onSwipeFromTop();
        void onSwipeFromBottom();
        void onSwipeFromRight();
        void onFling(int durationMs);
        void onDown();
        void onUpOrCancel();
        void onMouseHoverAtTop();
        void onMouseHoverAtBottom();
        void onMouseLeaveFromEdge();
        void onDebug();
    }
}
