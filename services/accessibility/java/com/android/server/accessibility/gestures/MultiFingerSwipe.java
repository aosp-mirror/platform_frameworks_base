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

package com.android.server.accessibility.gestures;

import static android.view.MotionEvent.INVALID_POINTER_ID;

import static com.android.server.accessibility.gestures.GestureUtils.getActionIndex;
import static com.android.server.accessibility.gestures.TouchExplorer.DEBUG;

import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * This class is responsible for matching one-finger swipe gestures. Each instance matches one swipe
 * gesture. A swipe is specified as a series of one or more directions e.g. left, left and up, etc.
 * At this time swipes with more than two directions are not supported.
 */
class MultiFingerSwipe extends GestureMatcher {

    // Direction constants.
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int UP = 2;
    public static final int DOWN = 3;

    // Buffer for storing points for gesture detection.
    private final ArrayList<PointF>[] mStrokeBuffers;

    // The swipe direction for this matcher.
    private int mDirection;
    private int[] mPointerIds;
    // The starting point of each finger's path in the gesture.
    private PointF[] mBase;
    // The most recent entry in each finger's gesture path.
    private PointF[] mPreviousGesturePoint;
    private int mTargetFingerCount;
    private int mCurrentFingerCount;
    // Whether the appropriate number of fingers have gone down at some point. This is reset only on
    // clear.
    private boolean mTargetFingerCountReached = false;
    // Constants for sampling motion event points.
    // We sample based on a minimum distance between points, primarily to improve accuracy by
    // reducing noisy minor changes in direction.
    private static final float MIN_CM_BETWEEN_SAMPLES = 0.25f;
    private final float mMinPixelsBetweenSamplesX;
    private final float mMinPixelsBetweenSamplesY;
    // The minmimum distance the finger must travel before we evaluate the initial direction of the
    // swipe.
    // Anything less is still considered a touch.
    private int mTouchSlop;

    MultiFingerSwipe(
            Context context,
            int fingerCount,
            int direction,
            int gesture,
            GestureMatcher.StateChangeListener listener) {
        super(gesture, new Handler(context.getMainLooper()), listener);
        mTargetFingerCount = fingerCount;
        mPointerIds = new int[mTargetFingerCount];
        mBase = new PointF[mTargetFingerCount];
        mPreviousGesturePoint = new PointF[mTargetFingerCount];
        mStrokeBuffers = new ArrayList[mTargetFingerCount];
        mDirection = direction;
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        // Calculate gesture sampling interval.
        final float pixelsPerCmX = displayMetrics.xdpi / GestureUtils.CM_PER_INCH;
        final float pixelsPerCmY = displayMetrics.ydpi / GestureUtils.CM_PER_INCH;
        mMinPixelsBetweenSamplesX = MIN_CM_BETWEEN_SAMPLES * pixelsPerCmX;
        mMinPixelsBetweenSamplesY = MIN_CM_BETWEEN_SAMPLES * pixelsPerCmY;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        clear();
    }

    @Override
    public void clear() {
        mTargetFingerCountReached = false;
        mCurrentFingerCount = 0;
        for (int i = 0; i < mTargetFingerCount; ++i) {
            mPointerIds[i] = INVALID_POINTER_ID;
            if (mBase[i] == null) {
                mBase[i] = new PointF();
            }
            mBase[i].x = Float.NaN;
            mBase[i].y = Float.NaN;
            if (mPreviousGesturePoint[i] == null) {
                mPreviousGesturePoint[i] = new PointF();
            }
            mPreviousGesturePoint[i].x = Float.NaN;
            mPreviousGesturePoint[i].y = Float.NaN;
            if (mStrokeBuffers[i] == null) {
                mStrokeBuffers[i] = new ArrayList<>(100);
            }
            mStrokeBuffers[i].clear();
        }
        super.clear();
    }

    @Override
    protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mCurrentFingerCount > 0) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        mCurrentFingerCount = 1;
        final int actionIndex = getActionIndex(rawEvent);
        final int pointerId = rawEvent.getPointerId(actionIndex);
        int pointerIndex = rawEvent.getPointerCount() - 1;
        if (pointerId < 0) {
            // Nonsensical pointer id.
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        if (mPointerIds[pointerIndex] != INVALID_POINTER_ID) {
            // Inconsistent event stream.
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        mPointerIds[pointerIndex] = pointerId;
        if (Float.isNaN(mBase[pointerIndex].x) && Float.isNaN(mBase[pointerIndex].y)) {
            final float x = rawEvent.getX(actionIndex);
            final float y = rawEvent.getY(actionIndex);
            if (x < 0f || y < 0f) {
                cancelGesture(event, rawEvent, policyFlags);
                return;
            }
            mBase[pointerIndex].x = x;
            mBase[pointerIndex].y = y;
            mPreviousGesturePoint[pointerIndex].x = x;
            mPreviousGesturePoint[pointerIndex].y = y;
        } else {
            // This  event doesn't make sense in the middle of a gesture.
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
    }

    @Override
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (event.getPointerCount() > mTargetFingerCount) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        mCurrentFingerCount += 1;
        if (mCurrentFingerCount != rawEvent.getPointerCount()) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        if (mCurrentFingerCount == mTargetFingerCount) {
            mTargetFingerCountReached = true;
        }
        final int actionIndex = getActionIndex(rawEvent);
        final int pointerId = rawEvent.getPointerId(actionIndex);
        if (pointerId < 0) {
            // Nonsensical pointer id.
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        int pointerIndex = mCurrentFingerCount - 1;
        if (mPointerIds[pointerIndex] != INVALID_POINTER_ID) {
            // Inconsistent event stream.
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        mPointerIds[pointerIndex] = pointerId;
        if (Float.isNaN(mBase[pointerIndex].x) && Float.isNaN(mBase[pointerIndex].y)) {
            final float x = rawEvent.getX(actionIndex);
            final float y = rawEvent.getY(actionIndex);
            if (x < 0f || y < 0f) {
                cancelGesture(event, rawEvent, policyFlags);
                return;
            }
            mBase[pointerIndex].x = x;
            mBase[pointerIndex].y = y;
            mPreviousGesturePoint[pointerIndex].x = x;
            mPreviousGesturePoint[pointerIndex].y = y;
        } else {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
    }

    @Override
    protected void onPointerUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (!mTargetFingerCountReached) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        mCurrentFingerCount -= 1;
        final int actionIndex = getActionIndex(event);
        final int pointerId = event.getPointerId(actionIndex);
        if (pointerId < 0) {
            // Nonsensical pointer id.
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        final int pointerIndex = Arrays.binarySearch(mPointerIds, pointerId);
        if (pointerIndex < 0) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        final float x = rawEvent.getX(actionIndex);
        final float y = rawEvent.getY(actionIndex);
        if (x < 0f || y < 0f) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        final float dX = Math.abs(x - mPreviousGesturePoint[pointerIndex].x);
        final float dY = Math.abs(y - mPreviousGesturePoint[pointerIndex].y);
        if (dX >= mMinPixelsBetweenSamplesX || dY >= mMinPixelsBetweenSamplesY) {
            mStrokeBuffers[pointerIndex].add(new PointF(x, y));
        }
        // We will evaluate all the paths on ACTION_UP.
    }

    @Override
    protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        for (int pointerIndex = 0; pointerIndex < mTargetFingerCount; ++pointerIndex) {
            if (mPointerIds[pointerIndex] == INVALID_POINTER_ID) {
                // Fingers have started to move before the required number of fingers are down.
                // However, they can still move less than the touch slop and still be considered
                // touching, not moving.
                // So we just ignore fingers that haven't been assigned a pointer id and process
                // those who have.
                continue;
            }
            if (DEBUG) {
                Slog.d(getGestureName(), "Processing move on finger " + pointerIndex);
            }
            int index = rawEvent.findPointerIndex(mPointerIds[pointerIndex]);
            if (index < 0) {
                // This finger is not present in this event. It could have gone up just before this
                // movement.
                if (DEBUG) {
                    Slog.d(
                            getGestureName(),
                            "Finger " + pointerIndex + " not found in this event. skipping.");
                }
                continue;
            }
            final float x = rawEvent.getX(index);
            final float y = rawEvent.getY(index);
            if (x < 0f || y < 0f) {
                cancelGesture(event, rawEvent, policyFlags);
                return;
            }
            final float dX = Math.abs(x - mPreviousGesturePoint[pointerIndex].x);
            final float dY = Math.abs(y - mPreviousGesturePoint[pointerIndex].y);
            final double moveDelta =
                    Math.hypot(
                            Math.abs(x - mBase[pointerIndex].x),
                            Math.abs(y - mBase[pointerIndex].y));
            if (DEBUG) {
                Slog.d(getGestureName(), "moveDelta:" + moveDelta);
            }
            if (getState() == STATE_CLEAR) {
                if (moveDelta < (mTargetFingerCount * mTouchSlop)) {
                    // This still counts as a touch not a swipe.
                    continue;
                }
                // First, make sure we have the right number of fingers down.
                if (mCurrentFingerCount != mTargetFingerCount) {
                    cancelGesture(event, rawEvent, policyFlags);
                    return;
                }
                // Then, make sure the pointer is going in the right direction.
                int direction = toDirection(x - mBase[pointerIndex].x, y - mBase[pointerIndex].y);
                if (direction != mDirection) {
                    cancelGesture(event, rawEvent, policyFlags);
                    return;
                }
                // This is confirmed to be some kind of swipe so start tracking points.
                startGesture(event, rawEvent, policyFlags);
                for (int i = 0; i < mTargetFingerCount; ++i) {
                    mStrokeBuffers[i].add(new PointF(mBase[i]));
                }
            } else if (getState() == STATE_GESTURE_STARTED) {
                // Cancel if the finger starts to go the wrong way.
                // Note that this only works because this matcher assumes one direction.
                int direction = toDirection(x - mBase[pointerIndex].x, y - mBase[pointerIndex].y);
                if (direction != mDirection) {
                    cancelGesture(event, rawEvent, policyFlags);
                    return;
                }
                if (dX >= mMinPixelsBetweenSamplesX || dY >= mMinPixelsBetweenSamplesY) {
                    // Sample every 2.5 MM in order to guard against minor variations in path.
                    mPreviousGesturePoint[pointerIndex].x = x;
                    mPreviousGesturePoint[pointerIndex].y = y;
                    mStrokeBuffers[pointerIndex].add(new PointF(x, y));
                }
            }
        }
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (getState() != STATE_GESTURE_STARTED) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        mCurrentFingerCount = 0;
        final int actionIndex = getActionIndex(event);
        final int pointerId = event.getPointerId(actionIndex);
        final int pointerIndex = Arrays.binarySearch(mPointerIds, pointerId);
        if (pointerIndex < 0) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        final float x = rawEvent.getX(actionIndex);
        final float y = rawEvent.getY(actionIndex);
        if (x < 0f || y < 0f) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        final float dX = Math.abs(x - mPreviousGesturePoint[pointerIndex].x);
        final float dY = Math.abs(y - mPreviousGesturePoint[pointerIndex].y);
        if (dX >= mMinPixelsBetweenSamplesX || dY >= mMinPixelsBetweenSamplesY) {
            mStrokeBuffers[pointerIndex].add(new PointF(x, y));
        }
        recognizeGesture(event, rawEvent, policyFlags);
    }

    /**
     * Looks at the sequence of motions in mStrokeBuffer, classifies the gesture, then transitions
     * to the complete or cancel state depending on the result.
     */
    private void recognizeGesture(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Check the path of each finger against the specified direction.
        // Note that we sample every 2.5 MMm, and the direction matching is extremely tolerant (each
        // direction has a 90-degree arch of tolerance) meaning that minor perpendicular movements
        // should not create false negatives.
        for (int i = 0; i < mTargetFingerCount; ++i) {
            if (DEBUG) {
                Slog.d(getGestureName(), "Recognizing finger: " + i);
            }
            if (mStrokeBuffers[i].size() < 2) {
                Slog.d(getGestureName(), "Too few points.");
                cancelGesture(event, rawEvent, policyFlags);
                return;
            }
            ArrayList<PointF> path = mStrokeBuffers[i];

            if (DEBUG) {
                Slog.d(getGestureName(), "path=" + path.toString());
            }
            // Classify line segments, and call Listener callbacks.
            if (!recognizeGesturePath(event, rawEvent, policyFlags, path)) {
                cancelGesture(event, rawEvent, policyFlags);
                return;
            }
        }
        // If we reach this point then all paths match.
        completeGesture(event, rawEvent, policyFlags);
    }

    /**
     * Tests the path of a given finger against the direction specified in this matcher.
     *
     * @return True if the path matches the specified direction for this matcher, otherwise false.
     */
    private boolean recognizeGesturePath(
            MotionEvent event, MotionEvent rawEvent, int policyFlags, ArrayList<PointF> path) {

        final int displayId = event.getDisplayId();
        for (int i = 0; i < path.size() - 1; ++i) {
            PointF start = path.get(i);
            PointF end = path.get(i + 1);

            float dX = end.x - start.x;
            float dY = end.y - start.y;
            int direction = toDirection(dX, dY);
            if (direction != mDirection) {
                if (DEBUG) {
                    Slog.d(
                            getGestureName(),
                            "Found direction "
                                    + directionToString(direction)
                                    + " when expecting "
                                    + directionToString(mDirection));
                }
                return false;
            }
        }
        if (DEBUG) {
            Slog.d(getGestureName(), "Completed.");
        }
        return true;
    }

    private static int toDirection(float dX, float dY) {
        if (Math.abs(dX) > Math.abs(dY)) {
            // Horizontal
            return (dX < 0) ? LEFT : RIGHT;
        } else {
            // Vertical
            return (dY < 0) ? UP : DOWN;
        }
    }

    public static String directionToString(int direction) {
        switch (direction) {
            case LEFT:
                return "left";
            case RIGHT:
                return "right";
            case UP:
                return "up";
            case DOWN:
                return "down";
            default:
                return "Unknown Direction";
        }
    }

    @Override
    protected String getGestureName() {
        StringBuilder builder = new StringBuilder();
        builder.append(mTargetFingerCount).append("-finger ");
        builder.append("Swipe ").append(directionToString(mDirection));
        return builder.toString();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(super.toString());
        if (getState() != STATE_GESTURE_CANCELED) {
            builder.append(", mBase: ")
                    .append(mBase.toString())
                    .append(", mMinPixelsBetweenSamplesX:")
                    .append(mMinPixelsBetweenSamplesX)
                    .append(", mMinPixelsBetweenSamplesY:")
                    .append(mMinPixelsBetweenSamplesY);
        }
        return builder.toString();
    }
}
