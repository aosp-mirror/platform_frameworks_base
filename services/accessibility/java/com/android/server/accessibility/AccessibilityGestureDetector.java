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
import android.gesture.Gesture;
import android.gesture.GestureLibraries;
import android.gesture.GestureLibrary;
import android.gesture.GesturePoint;
import android.gesture.GestureStore;
import android.gesture.GestureStroke;
import android.gesture.Prediction;
import android.util.Slog;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.android.internal.R;

import java.util.ArrayList;

/**
 * This class handles gesture detection for the Touch Explorer.  It collects
 * touch events, and sends events to mListener as gestures are recognized.
 */
class AccessibilityGestureDetector extends GestureDetector.SimpleOnGestureListener {

    private static final boolean DEBUG = false;

    // Tag for logging received events.
    private static final String LOG_TAG = "AccessibilityGestureDetector";

    public interface Listener {
        public void onDoubleTapAndHold(MotionEvent event, int policyFlags);
        public boolean onDoubleTap(MotionEvent event, int policyFlags);
        public boolean onGesture(int gestureId);
    }

    private final Listener mListener;
    private final GestureDetector mGestureDetector;

    // The library for gesture detection.
    private final GestureLibrary mGestureLibrary;

    // Indicates that a single tap has occurred.
    private boolean mFirstTapDetected;

    // Indicates that the down event of a double tap has occured.
    private boolean mDoubleTapDetected;

    // Indicates that motion events are being collected to match a gesture.
    private boolean mRecognizingGesture;

    // Policy flags of the previous event.
    private int mPolicyFlags;

    // The X of the previous event.
    private float mPreviousX;

    // The Y of the previous event.
    private float mPreviousY;

    // Buffer for storing points for gesture detection.
    private final ArrayList<GesturePoint> mStrokeBuffer = new ArrayList<GesturePoint>(100);

    // The minimal delta between moves to add a gesture point.
    private static final int TOUCH_TOLERANCE = 3;

    // The minimal score for accepting a predicted gesture.
    private static final float MIN_PREDICTION_SCORE = 2.0f;

    AccessibilityGestureDetector(Context context, Listener listener) {
        mListener = listener;

        mGestureDetector = new GestureDetector(context, this);
        mGestureDetector.setOnDoubleTapListener(this);

        mGestureLibrary = GestureLibraries.fromRawResource(context, R.raw.accessibility_gestures);
        mGestureLibrary.setOrientationStyle(8 /* GestureStore.ORIENTATION_SENSITIVE_8 */);
        mGestureLibrary.setSequenceType(GestureStore.SEQUENCE_SENSITIVE);
        mGestureLibrary.load();
    }

    public boolean onMotionEvent(MotionEvent event, int policyFlags) {
        final float x = event.getX();
        final float y = event.getY();

        mPolicyFlags = policyFlags;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDoubleTapDetected = false;
                mRecognizingGesture = true;
                mPreviousX = x;
                mPreviousY = y;
                mStrokeBuffer.clear();
                mStrokeBuffer.add(new GesturePoint(x, y, event.getEventTime()));
                break;

            case MotionEvent.ACTION_MOVE:
                if (mRecognizingGesture) {
                    final float dX = Math.abs(x - mPreviousX);
                    final float dY = Math.abs(y - mPreviousY);
                    if (dX >= TOUCH_TOLERANCE || dY >= TOUCH_TOLERANCE) {
                        mPreviousX = x;
                        mPreviousY = y;
                        mStrokeBuffer.add(new GesturePoint(x, y, event.getEventTime()));
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (maybeFinishDoubleTap(event, policyFlags)) {
                    return true;
                }
                if (mRecognizingGesture) {
                    mStrokeBuffer.add(new GesturePoint(x, y, event.getEventTime()));

                    if (recognizeGesture()) {
                        return true;
                    }
                }
                break;
        }

        if (!mRecognizingGesture) {
            return false;
        }

        // Pass the event on to the standard gesture detector.
        return mGestureDetector.onTouchEvent(event);
    }

    public void clear() {
        mFirstTapDetected = false;
        mDoubleTapDetected = false;
        cancelGesture();
        mStrokeBuffer.clear();
    }

    public boolean firstTapDetected() {
        return mFirstTapDetected;
    }

    public void cancelGesture() {
        mRecognizingGesture = false;
        mStrokeBuffer.clear();
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

    private boolean maybeFinishDoubleTap(MotionEvent event, int policyFlags) {
        if (!mDoubleTapDetected) {
            return false;
        }

        clear();

        return mListener.onDoubleTap(event, policyFlags);
    }

    private boolean recognizeGesture() {
        Gesture gesture = new Gesture();
        gesture.addStroke(new GestureStroke(mStrokeBuffer));

        ArrayList<Prediction> predictions = mGestureLibrary.recognize(gesture);
        if (!predictions.isEmpty()) {
            Prediction bestPrediction = predictions.get(0);
            if (bestPrediction.score >= MIN_PREDICTION_SCORE) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "gesture: " + bestPrediction.name + " score: "
                            + bestPrediction.score);
                }
                try {
                    final int gestureId = Integer.parseInt(bestPrediction.name);
                    if (mListener.onGesture(gestureId)) {
                        return true;
                    }
                } catch (NumberFormatException nfe) {
                    Slog.w(LOG_TAG, "Non numeric gesture id:" + bestPrediction.name);
                }
            }
        }

        return false;
    }
}
