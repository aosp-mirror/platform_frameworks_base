/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.accessibility.gestures;

import static android.view.MotionEvent.INVALID_POINTER_ID;

import static com.android.server.accessibility.gestures.GestureUtils.getActionIndex;

import android.content.Context;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * This class matches second-finger multi-tap gestures. A second-finger multi-tap gesture is where
 * one finger is held down and a second finger executes the taps. The number of taps for each
 * instance is specified in the constructor.
 */
class SecondFingerMultiTap extends GestureMatcher {
    final int mTargetTaps;
    int mDoubleTapSlop;
    int mTouchSlop;
    int mTapTimeout;
    int mDoubleTapTimeout;
    int mCurrentTaps;
    int mSecondFingerPointerId;
    float mBaseX;
    float mBaseY;

    SecondFingerMultiTap(
            Context context, int taps, int gesture, GestureMatcher.StateChangeListener listener) {
        super(gesture, new Handler(context.getMainLooper()), listener);
        mTargetTaps = taps;
        mDoubleTapSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTapTimeout = ViewConfiguration.getTapTimeout();
        mDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
        clear();
    }

    @Override
    public void clear() {
        mCurrentTaps = 0;
        mBaseX = Float.NaN;
        mBaseY = Float.NaN;
        mSecondFingerPointerId = INVALID_POINTER_ID;
        super.clear();
    }

    @Override
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (event.getPointerCount() > 2) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        // Second finger has gone down.
        int index = getActionIndex(event);
        mSecondFingerPointerId = event.getPointerId(index);
        cancelAfterTapTimeout(event, rawEvent, policyFlags);
        if (Float.isNaN(mBaseX) && Float.isNaN(mBaseY)) {
            mBaseX = event.getX(index);
            mBaseY = event.getY(index);
        }
        if (!isSecondFingerInsideSlop(rawEvent, mDoubleTapSlop)) {
            cancelGesture(event, rawEvent, policyFlags);
        }
        mBaseX = event.getX(index);
        mBaseY = event.getY(index);
    }

    @Override
    protected void onPointerUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (event.getPointerCount() > 2) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        cancelAfterDoubleTapTimeout(event, rawEvent, policyFlags);
        if (!isSecondFingerInsideSlop(rawEvent, mTouchSlop)) {
            cancelGesture(event, rawEvent, policyFlags);
        }
        if (getState() == STATE_GESTURE_STARTED || getState() == STATE_CLEAR) {
            mCurrentTaps++;
            if (mCurrentTaps == mTargetTaps) {
                // Done.
                completeGesture(event, rawEvent, policyFlags);
                return;
            }
            // Needs more taps.
            cancelAfterDoubleTapTimeout(event, rawEvent, policyFlags);
        } else {
            // Nonsensical event stream.
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        switch (event.getPointerCount()) {
            case 1:
                // We don't need to track anything about one-finger movements.
                break;
            case 2:
                if (!isSecondFingerInsideSlop(rawEvent, mTouchSlop)) {
                    cancelGesture(event, rawEvent, policyFlags);
                }
                break;
            default:
                // More than two fingers means we stop tracking.
                cancelGesture(event, rawEvent, policyFlags);
                break;
        }
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Cancel early when possible, or it will take precedence over two-finger double tap.
        cancelGesture(event, rawEvent, policyFlags);
    }

    @Override
    public String getGestureName() {
        switch (mTargetTaps) {
            case 2:
                return "Second Finger Double Tap";
            case 3:
                return "Second Finger Triple Tap";
            default:
                return "Second Finger " + Integer.toString(mTargetTaps) + " Taps";
        }
    }

    private boolean isSecondFingerInsideSlop(MotionEvent rawEvent, int slop) {
        int pointerIndex = rawEvent.findPointerIndex(mSecondFingerPointerId);
        if (pointerIndex == -1) {
            return false;
        }
        final float deltaX = mBaseX - rawEvent.getX(pointerIndex);
        final float deltaY = mBaseY - rawEvent.getY(pointerIndex);
        if (deltaX == 0 && deltaY == 0) {
            return true;
        }
        final double moveDelta = Math.hypot(deltaX, deltaY);
        return moveDelta <= slop;
    }

    @Override
    public String toString() {
        return super.toString()
                + ", Taps:"
                + mCurrentTaps
                + ", mBaseX: "
                + Float.toString(mBaseX)
                + ", mBaseY: "
                + Float.toString(mBaseY);
    }
}
