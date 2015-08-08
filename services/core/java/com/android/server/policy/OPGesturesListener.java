/*
 * Copyright (C) 2015 The Euphoria-OS Project
 * Copyright (C) 2015 The SudaMod Project
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
import android.util.Slog;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

public class OPGesturesListener implements PointerEventListener {
    private static final String TAG = "OPGestures";
    private static final boolean DEBUG = false;
    private static final int NUM_POINTER_SCREENSHOT = 3;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final int MAX_TRACKED_POINTERS = 32;
    private static final int UNTRACKED_POINTER = -1;
    private static final int THREE_SWIPE_DISTANCE = 350;
    private final int GESTURE_THREE_SWIPE_MASK = 15;
    private final int POINTER_1_MASK = 2;
    private final int POINTER_2_MASK = 4;
    private final int POINTER_3_MASK = 8;
    private final int POINTER_NONE_MASK = 1;
    private final Callbacks mCallbacks;
    private final int[] mDownPointerId = new int[MAX_TRACKED_POINTERS];
    private final float[] mDownX = new float[MAX_TRACKED_POINTERS];
    private final float[] mDownY = new float[MAX_TRACKED_POINTERS];
    private final long[] mDownTime = new long[MAX_TRACKED_POINTERS];
    private int mDownPointers;
    private boolean mSwipeFireable = false;
    private int mSwipeMask = 1;

    public OPGesturesListener(Context paramContext, Callbacks callbacks) {
        mCallbacks = checkNull("callbacks", callbacks);
    }

    private static <T> T checkNull(String name, T arg) {
        if (arg == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return arg;
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwipeFireable = true;
                mDownPointers = 0;
                captureDown(event, 0);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                captureDown(event, event.getActionIndex());
                break;
            case MotionEvent.ACTION_MOVE:
                if (DEBUG) Slog.d(TAG, "count3" + event.getPointerCount());
                if (mSwipeFireable) {
                    detectSwipe(event);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mSwipeMask == GESTURE_THREE_SWIPE_MASK) {
                    mSwipeMask = 1;
                    mCallbacks.onSwipeThreeFinger();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mSwipeFireable = false;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                break;
            default:
                if (DEBUG) Slog.d(TAG, "Ignoring " + event);
        }
    }

    private void captureDown(MotionEvent event, int pointerIndex) {
        final int pointerId = event.getPointerId(pointerIndex);
        final int i = findIndex(pointerId);
        final int pointerCount  = event.getPointerCount();
        if (DEBUG) Slog.d(TAG, "pointer " + pointerId +
                " down pointerIndex=" + pointerIndex + " trackingIndex=" + i);
        if (i != UNTRACKED_POINTER) {
            mDownX[i] = event.getX(pointerIndex);
            mDownY[i] = event.getY(pointerIndex);
            mDownTime[i] = event.getEventTime();
            if (DEBUG) Slog.d(TAG, "pointer " + pointerId +
                    " down x=" + mDownX[i] + " y=" + mDownY[i]);
        }
        if (pointerCount == NUM_POINTER_SCREENSHOT) {
            mSwipeFireable = true;
            return;
        }
        mSwipeFireable = false;
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

    private void detectSwipe(MotionEvent move) {
        move.getHistorySize();
        final int pointerCount = move.getPointerCount();
        for (int p = 0; p < pointerCount; p++) {
            final int pointerId = move.getPointerId(p);
            final int i = findIndex(pointerId);
            if (i != UNTRACKED_POINTER) {
                detectSwipe(i, move.getEventTime(), move.getX(p), move.getY(p));
            }
        }
    }

    private void detectSwipe(int i, long time, float x, float y) {
        final float fromX = mDownX[i];
        final float fromY = mDownY[i];
        final long elapsed = time - mDownTime[i];
        if (DEBUG) Slog.d(TAG, "pointer " + mDownPointerId[i]
                + " moved (" + fromX + "->" + x + "," + fromY + "->" + y + ") in " + elapsed);
        if (mSwipeMask < GESTURE_THREE_SWIPE_MASK
                && y > fromY + THREE_SWIPE_DISTANCE
                && elapsed < SWIPE_TIMEOUT_MS) {
            mSwipeMask |= 1 << i + 1;
            if (DEBUG) Slog.d(TAG, "swipe mask = " + mSwipeMask);
        }
    }

    interface Callbacks {
        void onSwipeThreeFinger();
    }
}
