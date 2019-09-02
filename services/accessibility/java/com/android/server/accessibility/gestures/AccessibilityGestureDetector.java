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

package com.android.server.accessibility.gestures;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.gesture.GesturePoint;
import android.graphics.PointF;
import android.util.Slog;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;

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

    // Constants for sampling motion event points.
    // We sample based on a minimum distance between points, primarily to improve accuracy by
    // reducing noisy minor changes in direction.
    private static final float MIN_INCHES_BETWEEN_SAMPLES = 0.1f;
    private final float mMinPixelsBetweenSamplesX;
    private final float mMinPixelsBetweenSamplesY;

    // Constants for separating gesture segments
    private static final float ANGLE_THRESHOLD = 0.0f;

    // Constants for line segment directions
    private static final int LEFT = 0;
    private static final int RIGHT = 1;
    private static final int UP = 2;
    private static final int DOWN = 3;
    private static final int[][] DIRECTIONS_TO_GESTURE_ID = {
        {
            AccessibilityService.GESTURE_SWIPE_LEFT,
            AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT,
            AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP,
            AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN
        },
        {
            AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT,
            AccessibilityService.GESTURE_SWIPE_RIGHT,
            AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP,
            AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN
        },
        {
            AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT,
            AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT,
            AccessibilityService.GESTURE_SWIPE_UP,
            AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN
        },
        {
            AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT,
            AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT,
            AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP,
            AccessibilityService.GESTURE_SWIPE_DOWN
        }
    };


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
         * @param gestureEvent Information about the gesture.
         *
         * @return true if the event is consumed, else false
         */
        boolean onGestureCompleted(AccessibilityGestureEvent gestureEvent);

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
    private final Context mContext;  // Retained for on-demand construction of GestureDetector.
    private final GestureDetector mGestureDetector;  // Double-tap detector.

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
    // 200ms.
    private static final long CANCEL_ON_PAUSE_THRESHOLD_NOT_STARTED_MS = 150;

    // Time threshold used to determine if a gesture should be cancelled.  If
    // the finger takes more than this time to move 1cm, the ongoing gesture is
    // cancelled.
    private static final long CANCEL_ON_PAUSE_THRESHOLD_STARTED_MS = 300;

    /**
     * Construct the gesture detector for {@link TouchExplorer}.
     *
     * @see #AccessibilityGestureDetector(Context, Listener, GestureDetector)
     */
    AccessibilityGestureDetector(Context context, Listener listener) {
        this(context, listener, null);
    }

    /**
     * Construct the gesture detector for {@link TouchExplorer}.
     *
     * @param context A context handle for accessing resources.
     * @param listener A listener to callback with gesture state or information.
     * @param detector The gesture detector to handle touch event. If null the default one created
     *                 in place, or for testing purpose.
     */
    AccessibilityGestureDetector(Context context, Listener listener, GestureDetector detector) {
        mListener = listener;
        mContext = context;

        // Break the circular dependency between constructors and let the class to be testable
        if (detector == null) {
            mGestureDetector = new GestureDetector(context, this);
        } else {
            mGestureDetector = detector;
        }
        mGestureDetector.setOnDoubleTapListener(this);
        mGestureDetectionThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1,
                context.getResources().getDisplayMetrics()) * GESTURE_CONFIRM_MM;

        // Calculate minimum gesture velocity
        final float pixelsPerInchX = context.getResources().getDisplayMetrics().xdpi;
        final float pixelsPerInchY = context.getResources().getDisplayMetrics().ydpi;
        mMinPixelsBetweenSamplesX = MIN_INCHES_BETWEEN_SAMPLES * pixelsPerInchX;
        mMinPixelsBetweenSamplesY = MIN_INCHES_BETWEEN_SAMPLES * pixelsPerInchY;
    }

    /**
     * Handle a motion event.  If an action is completed, the appropriate
     * callback on mListener is called, and the return value of the callback is
     * passed to the caller.
     *
     * @param event The transformed motion event to be handled.
     * @param rawEvent The raw motion event.  It's important that this be the raw
     * event, before any transformations have been applied, so that measurements
     * can be made in physical units.
     * @param policyFlags Policy flags for the event.
     *
     * @return true if the event is consumed, else false
     */
    public boolean onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // The accessibility gesture detector is interested in the movements in physical space,
        // so it uses the rawEvent to ignore magnification and other transformations.
        final float x = rawEvent.getX();
        final float y = rawEvent.getY();
        final long time = rawEvent.getEventTime();

        mPolicyFlags = policyFlags;
        switch (rawEvent.getActionMasked()) {
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
                            return mListener.onGestureCancelled(rawEvent, policyFlags);
                        }
                    }

                    final float dX = Math.abs(x - mPreviousGestureX);
                    final float dY = Math.abs(y - mPreviousGestureY);
                    if (dX >= mMinPixelsBetweenSamplesX || dY >= mMinPixelsBetweenSamplesY) {
                        mPreviousGestureX = x;
                        mPreviousGestureY = y;
                        mStrokeBuffer.add(new GesturePoint(x, y, time));
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mDoubleTapDetected) {
                    return finishDoubleTap(rawEvent, policyFlags);
                }
                if (mGestureStarted) {
                    final float dX = Math.abs(x - mPreviousGestureX);
                    final float dY = Math.abs(y - mPreviousGestureY);
                    if (dX >= mMinPixelsBetweenSamplesX || dY >= mMinPixelsBetweenSamplesY) {
                        mStrokeBuffer.add(new GesturePoint(x, y, time));
                    }
                    return recognizeGesture(rawEvent, policyFlags);
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                // Once a second finger is used, we're definitely not
                // recognizing a gesture.
                cancelGesture();

                if (rawEvent.getPointerCount() == 2) {
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
                    return finishDoubleTap(rawEvent, policyFlags);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                clear();
                break;
        }

        // If we're detecting taps on the second finger, map events from the
        // finger to the first finger.
        if (mSecondFingerDoubleTap) {
            MotionEvent newEvent = mapSecondPointerToFirstPointer(rawEvent);
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

        // Pass the transformed event on to the standard gesture detector.
        return mGestureDetector.onTouchEvent(event);
    }

    public void clear() {
        mFirstTapDetected = false;
        mDoubleTapDetected = false;
        mSecondFingerDoubleTap = false;
        mGestureStarted = false;
        mGestureDetector.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL,
                0.0f, 0.0f, 0));
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

    /**
     * Looks at the sequence of motions in mStrokeBuffer, classifies the gesture, then calls
     * Listener callbacks for success or failure.
     *
     * @param event The raw motion event to pass to the listener callbacks.
     * @param policyFlags Policy flags for the event.
     *
     * @return true if the event is consumed, else false
     */
    private boolean recognizeGesture(MotionEvent event, int policyFlags) {
        if (mStrokeBuffer.size() < 2) {
            return mListener.onGestureCancelled(event, policyFlags);
        }

        // Look at mStrokeBuffer and extract 2 line segments, delimited by near-perpendicular
        // direction change.
        // Method: for each sampled motion event, check the angle of the most recent motion vector
        // versus the preceding motion vector, and segment the line if the angle is about
        // 90 degrees.

        ArrayList<PointF> path = new ArrayList<>();
        PointF lastDelimiter = new PointF(mStrokeBuffer.get(0).x, mStrokeBuffer.get(0).y);
        path.add(lastDelimiter);

        float dX = 0;  // Sum of unit vectors from last delimiter to each following point
        float dY = 0;
        int count = 0;  // Number of points since last delimiter
        float length = 0;  // Vector length from delimiter to most recent point

        PointF next = new PointF();
        for (int i = 1; i < mStrokeBuffer.size(); ++i) {
            next = new PointF(mStrokeBuffer.get(i).x, mStrokeBuffer.get(i).y);
            if (count > 0) {
                // Average of unit vectors from delimiter to following points
                float currentDX = dX / count;
                float currentDY = dY / count;

                // newDelimiter is a possible new delimiter, based on a vector with length from
                // the last delimiter to the previous point, but in the direction of the average
                // unit vector from delimiter to previous points.
                // Using the averaged vector has the effect of "squaring off the curve",
                // creating a sharper angle between the last motion and the preceding motion from
                // the delimiter. In turn, this sharper angle achieves the splitting threshold
                // even in a gentle curve.
                PointF newDelimiter = new PointF(length * currentDX + lastDelimiter.x,
                    length * currentDY + lastDelimiter.y);

                // Unit vector from newDelimiter to the most recent point
                float nextDX = next.x - newDelimiter.x;
                float nextDY = next.y - newDelimiter.y;
                float nextLength = (float) Math.sqrt(nextDX * nextDX + nextDY * nextDY);
                nextDX = nextDX / nextLength;
                nextDY = nextDY / nextLength;

                // Compare the initial motion direction to the most recent motion direction,
                // and segment the line if direction has changed by about 90 degrees.
                float dot = currentDX * nextDX + currentDY * nextDY;
                if (dot < ANGLE_THRESHOLD) {
                    path.add(newDelimiter);
                    lastDelimiter = newDelimiter;
                    dX = 0;
                    dY = 0;
                    count = 0;
                }
            }

            // Vector from last delimiter to most recent point
            float currentDX = next.x - lastDelimiter.x;
            float currentDY = next.y - lastDelimiter.y;
            length = (float) Math.sqrt(currentDX * currentDX + currentDY * currentDY);

            // Increment sum of unit vectors from delimiter to each following point
            count = count + 1;
            dX = dX + currentDX / length;
            dY = dY + currentDY / length;
        }

        path.add(next);
        Slog.i(LOG_TAG, "path=" + path.toString());

        // Classify line segments, and call Listener callbacks.
        return recognizeGesturePath(event, policyFlags, path);
    }

    /**
     * Classifies a pair of line segments, by direction.
     * Calls Listener callbacks for success or failure.
     *
     * @param event The raw motion event to pass to the listener's onGestureCanceled method.
     * @param policyFlags Policy flags for the event.
     * @param path A sequence of motion line segments derived from motion points in mStrokeBuffer.
     *
     * @return true if the event is consumed, else false
     */
    private boolean recognizeGesturePath(MotionEvent event, int policyFlags,
            ArrayList<PointF> path) {

        final int displayId = event.getDisplayId();
        if (path.size() == 2) {
            PointF start = path.get(0);
            PointF end = path.get(1);

            float dX = end.x - start.x;
            float dY = end.y - start.y;
            int direction = toDirection(dX, dY);
            switch (direction) {
                case LEFT:
                    return mListener.onGestureCompleted(
                            new AccessibilityGestureEvent(AccessibilityService.GESTURE_SWIPE_LEFT,
                                    displayId));
                case RIGHT:
                    return mListener.onGestureCompleted(
                            new AccessibilityGestureEvent(AccessibilityService.GESTURE_SWIPE_RIGHT,
                                    displayId));
                case UP:
                    return mListener.onGestureCompleted(
                            new AccessibilityGestureEvent(AccessibilityService.GESTURE_SWIPE_UP,
                                    displayId));
                case DOWN:
                    return mListener.onGestureCompleted(
                            new AccessibilityGestureEvent(AccessibilityService.GESTURE_SWIPE_DOWN,
                                    displayId));
                default:
                    // Do nothing.
            }

        } else if (path.size() == 3) {
            PointF start = path.get(0);
            PointF mid = path.get(1);
            PointF end = path.get(2);

            float dX0 = mid.x - start.x;
            float dY0 = mid.y - start.y;

            float dX1 = end.x - mid.x;
            float dY1 = end.y - mid.y;

            int segmentDirection0 = toDirection(dX0, dY0);
            int segmentDirection1 = toDirection(dX1, dY1);
            int gestureId = DIRECTIONS_TO_GESTURE_ID[segmentDirection0][segmentDirection1];
            return mListener.onGestureCompleted(
                    new AccessibilityGestureEvent(gestureId, displayId));
        }
        // else if (path.size() < 2 || 3 < path.size()) then no gesture recognized.
        return mListener.onGestureCancelled(event, policyFlags);
    }

    /** Maps a vector to a dominant direction in set {LEFT, RIGHT, UP, DOWN}. */
    private static int toDirection(float dX, float dY) {
        if (Math.abs(dX) > Math.abs(dY)) {
            // Horizontal
            return (dX < 0) ? LEFT : RIGHT;
        } else {
            // Vertical
            return (dY < 0) ? UP : DOWN;
        }
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
