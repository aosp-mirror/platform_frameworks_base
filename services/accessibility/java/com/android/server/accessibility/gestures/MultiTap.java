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

import android.content.Context;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * This class matches multi-tap gestures. The number of taps for each instance is specified in the
 * constructor.
 */
class MultiTap extends GestureMatcher {

    // Maximum reasonable number of taps.
    public static final int MAX_TAPS = 10;
    final int mTargetTaps;
    // The acceptable distance between two taps
    int mDoubleTapSlop;
    // The acceptable distance the pointer can move and still count as a tap.
    int mTouchSlop;
    int mTapTimeout;
    int mDoubleTapTimeout;
    int mCurrentTaps;
    float mBaseX;
    float mBaseY;

    MultiTap(Context context, int taps, int gesture, GestureMatcher.StateChangeListener listener) {
        super(gesture, new Handler(context.getMainLooper()), listener);
        mTargetTaps = taps;
        mDoubleTapSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTapTimeout = ViewConfiguration.getTapTimeout();
        mDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
        clear();
    }

    @Override
    protected void clear() {
        mCurrentTaps = 0;
        mBaseX = Float.NaN;
        mBaseY = Float.NaN;
        super.clear();
    }

    @Override
    protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelAfterTapTimeout(event, rawEvent, policyFlags);
        if (Float.isNaN(mBaseX) && Float.isNaN(mBaseY)) {
            mBaseX = event.getX();
            mBaseY = event.getY();
        }
        if (!isInsideSlop(rawEvent, mDoubleTapSlop)) {
            cancelGesture(event, rawEvent, policyFlags);
        }
        mBaseX = event.getX();
        mBaseY = event.getY();
        if (mCurrentTaps + 1 == mTargetTaps) {
            // Start gesture detecting on down of final tap.
            // Note that if this instance is matching double tap,
            // and the service is not requesting to handle double tap, GestureManifold will
            // ignore this.
            startGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelAfterDoubleTapTimeout(event, rawEvent, policyFlags);
        if (!isInsideSlop(rawEvent, mTouchSlop)) {
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
            // Either too many taps or nonsensical event stream.
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (!isInsideSlop(rawEvent, mTouchSlop)) {
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelGesture(event, rawEvent, policyFlags);
    }

    @Override
    protected void onPointerUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelGesture(event, rawEvent, policyFlags);
    }

    @Override
    public String getGestureName() {
        switch (mTargetTaps) {
            case 2:
                return "Double Tap";
            case 3:
                return "Triple Tap";
            default:
                return Integer.toString(mTargetTaps) + " Taps";
        }
    }

    private boolean isInsideSlop(MotionEvent rawEvent, int slop) {
        final float deltaX = mBaseX - rawEvent.getX();
        final float deltaY = mBaseY - rawEvent.getY();
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
