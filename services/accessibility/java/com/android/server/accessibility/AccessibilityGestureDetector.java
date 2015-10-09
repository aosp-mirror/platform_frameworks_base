/*
 ** Copyright 2015, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

/**
 * This class handles gesture detection for the Touch Explorer.  It collects
 * touch events, and sends events to mListener as gestures are recognized.
 */
class AccessibilityGestureDetector extends GestureDetector.SimpleOnGestureListener {
    private final GestureDetector mGestureDetector;
    private final Listener mListener;
    private boolean mFirstTapDetected;
    private boolean mDoubleTapDetected;
    private int mPolicyFlags;

    AccessibilityGestureDetector(Context context, Listener listener) {
        mListener = listener;

        mGestureDetector = new GestureDetector(context, this);
        mGestureDetector.setOnDoubleTapListener(this);
    }

    public void onMotionEvent(MotionEvent event, int policyFlags) {
        mPolicyFlags = policyFlags;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDoubleTapDetected = false;
                break;

            case MotionEvent.ACTION_UP:
                maybeFinishDoubleTap(event, policyFlags);
                break;
        }
        mGestureDetector.onTouchEvent(event);
    }

    public void clear() {
        mFirstTapDetected = false;
        mDoubleTapDetected = false;
    }

    public boolean firstTapDetected() {
        return mFirstTapDetected;
    }

    @Override
    public boolean onDown(MotionEvent event) {
        return true;
    }

    @Override
    public void onLongPress(MotionEvent e) {
        maybeSendLongPress(e, mPolicyFlags);
    }

    @Override
    public boolean onSingleTapUp(MotionEvent event) {
        mFirstTapDetected = true;
        return false;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent event) {
        clear();
        return false;
    }

    @Override
    public boolean onDoubleTap(MotionEvent event) {
        // The processing of the double tap is deferred until the finger is
        // lifted, so that we can detect a long press on the second tap.
        mDoubleTapDetected = true;
        return true;
    }

    private void maybeSendLongPress(MotionEvent event, int policyFlags) {
        if (!mDoubleTapDetected) {
            return;
        }

        clear();

        mListener.onDoubleTapAndHold(event, policyFlags);
    }

    private void maybeFinishDoubleTap(MotionEvent event, int policyFlags) {
        if (!mDoubleTapDetected) {
            return;
        }

        clear();

        mListener.onDoubleTap(event, policyFlags);
    }

    public interface Listener {
        public void onDoubleTapAndHold(MotionEvent event, int policyFlags);
        public void onDoubleTap(MotionEvent event, int policyFlags);
    }
}
