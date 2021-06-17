/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.server.accessibility.gestures.GestureMatcher;

/**
 * This class is responsible for detecting that the user is using two fingers to perform
 * swiping gestures or just stay pressed on the screen. The gesture matching result is determined
 * in a duration.
 */
final class TwoFingersDownOrSwipe extends GestureMatcher {

    private final int mDoubleTapTimeout;
    private final int mDetectionDurationMillis;
    private final int mSwipeMinDistance;
    private MotionEvent mFirstPointerDown;
    private MotionEvent mSecondPointerDown;

    TwoFingersDownOrSwipe(Context context) {
        super(MagnificationGestureMatcher.GESTURE_TWO_FINGERS_DOWN_OR_SWIPE,
                new Handler(context.getMainLooper()), null);
        mDetectionDurationMillis = MagnificationGestureMatcher.getMagnificationMultiTapTimeout(
                context);
        mDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
        mSwipeMinDistance = ViewConfiguration.get(context).getScaledTouchSlop();

    }

    @Override
    protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        mFirstPointerDown = MotionEvent.obtain(event);
        cancelAfter(mDetectionDurationMillis, event, rawEvent, policyFlags);
    }

    @Override
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mFirstPointerDown == null) {
            cancelGesture(event, rawEvent, policyFlags);
        }
        if (event.getPointerCount() == 2) {
            mSecondPointerDown = MotionEvent.obtain(event);
            completeAfter(mDoubleTapTimeout, event, rawEvent, policyFlags);
        } else {
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mFirstPointerDown == null || mSecondPointerDown == null) {
            return;
        }
        if (distance(mFirstPointerDown, /* move */ event) > mSwipeMinDistance) {
            completeGesture(event, rawEvent, policyFlags);
            return;
        }
        if (distance(mSecondPointerDown, /* move */ event) > mSwipeMinDistance) {
            // The second pointer is swiping.
            completeGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onPointerUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelGesture(event, rawEvent, policyFlags);
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelGesture(event, rawEvent, policyFlags);
    }

    @Override
    public void clear() {
        if (mFirstPointerDown != null) {
            mFirstPointerDown.recycle();
            mFirstPointerDown = null;
        }
        if (mSecondPointerDown != null) {
            mSecondPointerDown.recycle();
            mSecondPointerDown = null;
        }
        super.clear();
    }

    @Override
    protected String getGestureName() {
        return this.getClass().getSimpleName();
    }

    private static double distance(@NonNull MotionEvent downEvent, @NonNull MotionEvent moveEvent) {
        final int downActionIndex = downEvent.getActionIndex();
        final int downPointerId = downEvent.getPointerId(downActionIndex);
        final int moveActionIndex = moveEvent.findPointerIndex(downPointerId);
        if (moveActionIndex < 0) {
            return -1;
        }
        return MathUtils.dist(downEvent.getX(downActionIndex), downEvent.getY(downActionIndex),
                moveEvent.getX(moveActionIndex), moveEvent.getY(moveActionIndex));
    }
}
