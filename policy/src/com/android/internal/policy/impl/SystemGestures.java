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

package com.android.internal.policy.impl;

import android.content.Context;
import android.os.Looper;
import android.util.Slog;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

/*
 * Listens for system-wide input gestures, firing callbacks when detected.
 * @hide
 */
public class SystemGestures extends InputEventReceiver {
    private static final String TAG = "SystemGestures";
    private static final boolean DEBUG = false;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final int MAX_TRACKED_POINTERS = 32;  // max per input system
    private static final int UNTRACKED_POINTER = -1;

    private final int mSwipeStartThreshold;
    private final int mSwipeEndThreshold;
    private final Callbacks mCallbacks;
    private final int[] mDownPointerId = new int[MAX_TRACKED_POINTERS];
    private final float[] mDownY = new float[MAX_TRACKED_POINTERS];
    private final long[] mDownTime = new long[MAX_TRACKED_POINTERS];

    private int mDownPointers;
    private boolean mSwipeFromTopFireable;

    public SystemGestures(InputChannel inputChannel, Looper looper,
            Context context, Callbacks callbacks) {
        super(inputChannel, looper);
        mCallbacks = checkNull("callbacks", callbacks);
        mSwipeStartThreshold = checkNull("context", context).getResources()
                .getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mSwipeEndThreshold = mSwipeStartThreshold * 2;
    }

    private static <T> T checkNull(String name, T arg) {
        if (arg == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return arg;
    }

    @Override
    public void onInputEvent(InputEvent event) {
        if (event instanceof MotionEvent && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            onPointerMotionEvent((MotionEvent) event);
        }
        finishInputEvent(event, false /*handled*/);
    }

    private void onPointerMotionEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwipeFromTopFireable = true;
                mDownPointers = 0;
                captureDown(event, 0);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                captureDown(event, event.getActionIndex());
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSwipeFromTopFireable && detectSwipeFromTop(event)) {
                    mSwipeFromTopFireable = false;
                    if (DEBUG) Slog.d(TAG, "Firing onSwipeFromTop");
                    mCallbacks.onSwipeFromTop();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mSwipeFromTopFireable = false;
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
            mDownY[i] = event.getY(pointerIndex);
            mDownTime[i] = event.getEventTime();
            if (DEBUG) Slog.d(TAG, "pointer " + pointerId + " down y=" + mDownY[i]);
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

    private boolean detectSwipeFromTop(MotionEvent move) {
        final int historySize = move.getHistorySize();
        final int pointerCount = move.getPointerCount();
        for (int p = 0; p < pointerCount; p++) {
            final int pointerId = move.getPointerId(p);
            final int i = findIndex(pointerId);
            if (i != UNTRACKED_POINTER) {
                for (int h = 0; h < historySize; h++) {
                    final long time = move.getHistoricalEventTime(h);
                    final float y = move.getHistoricalY(p,  h);
                    if (detectSwipeFromTop(i, time, y)) {
                        return true;
                    }
                }
                if (detectSwipeFromTop(i, move.getEventTime(), move.getY(p))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean detectSwipeFromTop(int i, long time, float y) {
        final float fromY = mDownY[i];
        final long elapsed = time - mDownTime[i];
        if (DEBUG) Slog.d(TAG, "pointer " + mDownPointerId[i]
                + " moved from.y=" + fromY + " to.y=" + y + " in " + elapsed);
        return fromY <= mSwipeStartThreshold
                && y > fromY + mSwipeEndThreshold
                && elapsed < SWIPE_TIMEOUT_MS;
    }

    interface Callbacks {
        void onSwipeFromTop();
    }
}
