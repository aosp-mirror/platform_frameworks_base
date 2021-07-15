/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.server.accessibility.gestures.GestureUtils.distance;

import android.content.Context;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.server.accessibility.gestures.GestureMatcher;

/**
 * This class is responsible for matching one-finger swipe gestures with any direction.
 */
class SimpleSwipe extends GestureMatcher {

    private final int mSwipeMinDistance;
    private MotionEvent mLastDown;
    private final int mDetectionDurationMillis;

    SimpleSwipe(Context context) {
        super(MagnificationGestureMatcher.GESTURE_SWIPE,
                new Handler(context.getMainLooper()), null);
        mSwipeMinDistance = ViewConfiguration.get(context).getScaledTouchSlop();
        mDetectionDurationMillis = MagnificationGestureMatcher.getMagnificationMultiTapTimeout(
                context);
    }

    @Override
    protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        mLastDown = MotionEvent.obtain(event);
        cancelAfter(mDetectionDurationMillis, event, rawEvent, policyFlags);
    }

    @Override
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelGesture(event, rawEvent, policyFlags);
    }

    @Override
    protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (gestureMatched(event, rawEvent, policyFlags)) {
            completeGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (gestureMatched(event, rawEvent, policyFlags)) {
            completeGesture(event, rawEvent, policyFlags);
        } else {
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    private boolean gestureMatched(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        return mLastDown != null && (distance(mLastDown, event) > mSwipeMinDistance);
    }

    @Override
    public void clear() {
        if (mLastDown != null) {
            mLastDown.recycle();
        }
        mLastDown = null;
        super.clear();
    }

    @Override
    protected String getGestureName() {
        return this.getClass().getSimpleName();
    }
}
