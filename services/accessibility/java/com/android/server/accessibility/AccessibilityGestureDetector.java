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
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

import com.android.internal.R;

import java.util.ArrayList;

/**
 * This class handles gesture detection for the Touch Explorer.  It collects
 * touch events and determines when they match a gesture, as well as when they
 * won't match a gesture.  These state changes are then surfaced to mListener.
 */
class AccessibilityGestureDetector extends GestureDetector.SimpleOnGestureListener {

    private static final boolean DEBUG = false;

    // Tag for logging received events.
    private static final String LOG_TAG = "AccessibilityGestureDetector";

    /**
     * Listener functions are called as a result of onMoveEvent().  The current
     * MotionEvent in the context of these functions is the event passed into
     * onMotionEvent.
     */
    public interface Listener {
        /**
         * Called when the user has performed a double tap and then held down
         * the second tap.
         *
         * @param event The most recent MotionEvent received.
         * @param policyFlags The policy flags of the most recent event.
         */
        void onDoubleTapAndHold(MotionEvent event, int policyFlags);

        /**
         * Called when the user lifts their finger on the second tap of a double
         * tap.
         *
         * @param event The most recent MotionEvent received.
         * @param policyFlags The policy flags of the most recent event.
         *
         * @return true if the event is consumed, else false
         */
        boolean onDoubleTap(MotionEvent event, int policyFlags);

        /**
         * Called when the system has decided the event stream is a gesture.
         *
         * @return true if the event is consumed, else false
         */
        boolean onGestureStarted();

        /**
         * Called when an event stream is recognized as a gesture.
         *
         * @param gestureId ID of the gesture that was recognized.
         *
         * @return true if the event is consumed, else false
         */
        boolean onGestureCompleted(int gestureId);

        /**
         * Called when the system has decided an event stream doesn't match any
         * known gesture.
         *
         * @param event The most recent MotionEvent received.
         * @param policyFlags The policy flags of the most recent event.
         *
         * @return true if the event is consumed, else false
         */
        public boolean onGestureCancelled(MotionEvent event, int policyFlags);
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

    // Indicates that we've collected enough data to be sure it could be a
    // gesture.
    private boolean mGestureStarted;

    // Indicates that motion events from the second pointer are being checked
    // for a double tap.
    private boolean mSecondFingerDoubleTap;

    // Tracks the most recent time where ACTION_POINTER_DOWN was sent for the
    // second pointer.
    private long mSecondPointerDownTime;

    // Policy flags of the previous event.
    private int mPolicyFlags;

    // These values track the previous point that was saved to use for gesture
    // detection.  They are only updated when the user moves more than the
    // recognition threshold.
    private float mPreviousGestureX;
    private float mPreviousGestureY;

    // These values track the previous point that was used to determine if there
    // was a transition into or out of gesture detection.  They are updated when
    // the user moves more than the detection threshold.
    private float mBaseX;
    private float mBaseY;
    private long mBaseTime;

    // This is the calculated movement threshold used track if the user is still
    // moving their finger.
    private final float mGestureDetectionThreshold;

    // Buffer for storing points for gesture detection.
    private final ArrayList<GesturePoint> mStrokeBuffer = new ArrayList<GesturePoint>(100);

    // The minimal delta between moves to add a gesture point.
    private static final int TOUCH_TOLERANCE = 3;

    // The minimal score for accepting a predicted gesture.
    private static final float MIN_PREDICTION_SCORE = 2.0f;

    // Distance a finger must travel before we decide if it is a gesture or not.
    private static final int GESTURE_CONFIRM_MM = 10;

    // Time threshold used to determine if an interaction is a gesture or not.
    // If the first movement of 1cm takes longer than this value, we assume it's
    // a slow movement, and therefore not a gesture.
    //
    // This value was determined by measuring the time for the first 1cm
    // movement when gesturing, and touch exploring.  Based on user testing,
    // all gestures started with the initial movement taking less than 100ms.
    // When touch exploring, the first movement almost always takes longer than
    // 200ms.  From this data, 200ms seems the best value to decide what
    // kind of interaction it is.
    private static final long CANCEL_ON_PAUSE_THRESHOLD_NOT_STARTED_MS = 200;

    // Time threshold used to determine if a gesture should be cancelled.  If
    // the finger pauses for longer than this delay, the ongoing gesture is
    // cancelled.
    private static final long CANCEL_ON_PAUSE_THRESHOLD_STARTED_MS = 500;

    AccessibilityGestureDetector(Context context, Listener listener) {
        mListener = listener;

        mGestureDetector = new GestureDetector(context, this);
        mGestureDetector.setOnDoubleTapListener(this);

        mGestureLibrary = GestureLibraries.fromRawResource(context, R.raw.accessibility_gestures);
        mGestureLibrary.setOrientationStyle(8 /* GestureStore.ORIENTATION_SENSITIVE_8 */);
        mGestureLibrary.setSequenceType(GestureStore.SEQUENCE_SENSITIVE);
        mGestureLibrary.load();

        mGestureDetectionThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1,
                context.getResources().getDisplayMetrics()) * GESTURE_CONFIRM_MM;
    }

    /**
     * Handle a motion event.  If an action is completed, the appropriate
     * callback on mListener is called, and the return value of the callback is
     * passed to the caller.
     *
     * @param event The raw motion event.  It's important that this be the raw
     * event, before any transformations have been applied, so that measurements
     * can be made in physical units.
     * @param policyFlags Policy flags for the event.
     *
     * @return true if the event is consumed, else false
     */
    public boolean onMotionEvent(MotionEvent event, int policyFlags) {
        final float x = event.getX();
        final float y = event.getY();
        final long time = event.getEventTime();

        mPolicyFlags = policyFlags;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDoubleTapDetected = false;
                mSecondFingerDoubleTap = false;
                mRecognizingGesture = true;
                mGestureStarted = false;
                mPreviousGestureX = x;
                mPreviousGestureY = y;
                mStrokeBuffer.clear();
                mStrokeBuffer.add(new GesturePoint(x, y, time));

                mBaseX = x;
                mBaseY = y;
                mBaseTime = time;
                break;

            case MotionEvent.ACTION_MOVE:
                if (mRecognizingGesture) {
                    final float deltaX = mBaseX - x;
                    final float deltaY = mBaseY - y;
                    final double moveDelta = Math.hypot(deltaX, deltaY);
                    if (moveDelta > mGestureDetectionThreshold) {
                        // If the pointer has moved more than the threshold,
                        // update the stored values.
                        mBaseX = x;
                        mBaseY = y;
                        mBaseTime = time;

                        // Since the pointer has moved, this is not a double
                        // tap.
                        mFirstTapDetected = false;
                        mDoubleTapDetected = false;

                        // If this hasn't been confirmed as a gesture yet, send
                        // the event.
                        if (!mGestureStarted) {
                            mGestureStarted = true;
                            return mListener.onGestureStarted();
                        }
                    } else if (!mFirstTapDetected) {
                        // The finger may not move if they are double tapping.
                        // In that case, we shouldn't cancel the gesture.
                        final long timeDelta = time - mBaseTime;
                        final long threshold = mGestureStarted ?
                            CANCEL_ON_PAUSE_THRESHOLD_STARTED_MS :
                            CANCEL_ON_PAUSE_THRESHOLD_NOT_STARTED_MS;

                        // If the pointer hasn't moved for longer than the
                        // timeout, cancel gesture detection.
                        if (timeDelta > threshold) {
                            cancelGesture();
                            return mListener.onGestureCancelled(event, policyFlags);
                        }
                    }

                    final float dX = Math.abs(x - mPreviousGestureX);
                    final float dY = Math.abs(y - mPreviousGestureY);
                    if (dX >= TOUCH_TOLERANCE || dY >= TOUCH_TOLERANCE) {
                        mPreviousGestureX = x;
                        mPreviousGestureY = y;
                        mStrokeBuffer.add(new GesturePoint(x, y, time));
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mDoubleTapDetected) {
                    return finishDoubleTap(event, policyFlags);
                }
                if (mGestureStarted) {
                    mStrokeBuffer.add(new GesturePoint(x, y, time));

                    return recognizeGesture(event, policyFlags);
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                // Once a second finger is used, we're definitely not
                // recognizing a gesture.
                cancelGesture();

                if (event.getPointerCount() == 2) {
                    // If this was the second finger, attempt to recognize double
                    // taps on it.
                    mSecondFingerDoubleTap = true;
                    mSecondPointerDownTime = time;
                } else {
                    // If there are more than two fingers down, stop watching
                    // for a double tap.
                    mSecondFingerDoubleTap = false;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                // If we're detecting taps on the second finger, see if we
                // should finish the double tap.
                if (mSecondFingerDoubleTap && mDoubleTapDetected) {
                    return finishDoubleTap(event, policyFlags);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                clear();
                break;
        }

        // If we're detecting taps on the second finger, map events from the
        // finger to the first finger.
        if (mSecondFingerDoubleTap) {
            MotionEvent newEvent = mapSecondPointerToFirstPointer(event);
            if (newEvent == null) {
                return false;
            }
            boolean handled = mGestureDetector.onTouchEvent(newEvent);
            newEvent.recycle();
            return handled;
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
        mSecondFingerDoubleTap = false;
        mGestureStarted = false;
        cancelGesture();
    }

    public boolean firstTapDetected() {
        return mFirstTapDetected;
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
        return false;
    }

    private void maybeSendLongPress(MotionEvent event, int policyFlags) {
        if (!mDoubleTapDetected) {
            return;
        }

        clear();

        mListener.onDoubleTapAndHold(event, policyFlags);
    }

    private boolean finishDoubleTap(MotionEvent event, int policyFlags) {
        clear();

        return mListener.onDoubleTap(event, policyFlags);
    }

    private void cancelGesture() {
        mRecognizingGesture = false;
        mGestureStarted = false;
        mStrokeBuffer.clear();
    }

    private boolean recognizeGesture(MotionEvent event, int policyFlags) {
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
                    return mListener.onGestureCompleted(gestureId);
                } catch (NumberFormatException nfe) {
                    Slog.w(LOG_TAG, "Non numeric gesture id:" + bestPrediction.name);
                }
            }
        }

        return mListener.onGestureCancelled(event, policyFlags);
    }

    private MotionEvent mapSecondPointerToFirstPointer(MotionEvent event) {
        // Only map basic events when two fingers are down.
        if (event.getPointerCount() != 2 ||
                (event.getActionMasked() != MotionEvent.ACTION_POINTER_DOWN &&
                 event.getActionMasked() != MotionEvent.ACTION_POINTER_UP &&
                 event.getActionMasked() != MotionEvent.ACTION_MOVE)) {
            return null;
        }

        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            action = MotionEvent.ACTION_DOWN;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            action = MotionEvent.ACTION_UP;
        }

        // Map the information from the second pointer to the first.
        return MotionEvent.obtain(mSecondPointerDownTime, event.getEventTime(), action,
                event.getX(1), event.getY(1), event.getPressure(1), event.getSize(1),
                event.getMetaState(), event.getXPrecision(), event.getYPrecision(),
                event.getDeviceId(), event.getEdgeFlags());
    }
}
