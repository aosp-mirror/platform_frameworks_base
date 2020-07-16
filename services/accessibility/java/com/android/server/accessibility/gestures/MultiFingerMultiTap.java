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

import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * This class matches multi-finger multi-tap gestures. The number of fingers and the number of taps
 * for each instance is specified in the constructor.
 */
class MultiFingerMultiTap extends GestureMatcher {

    // The target number of taps.
    final int mTargetTapCount;
    // The target number of fingers.
    final int mTargetFingerCount;
    // The acceptable distance between two taps of a finger.
    private int mDoubleTapSlop;
    // The acceptable distance the pointer can move and still count as a tap.
    private int mTouchSlop;
    // A tap counts when target number of fingers are down and up once.
    protected int mCompletedTapCount;
    // A flag set to true when target number of fingers have touched down at once before.
    // Used to indicate what next finger action should be. Down when false and lift when true.
    protected boolean mIsTargetFingerCountReached = false;
    // Store initial down points for slop checking and update when next down if is inside slop.
    private PointF[] mBases;
    // The points in bases that already have slop checked when onDown or onPointerDown.
    // It prevents excluded points matched multiple times by other pointers from next check.
    private ArrayList<PointF> mExcludedPointsForDownSlopChecked;

    /**
     * @throws IllegalArgumentException if <code>fingers<code/> is less than 2
     *                                  or <code>taps<code/> is not positive.
     */
    MultiFingerMultiTap(
            Context context,
            int fingers,
            int taps,
            int gestureId,
            GestureMatcher.StateChangeListener listener) {
        super(gestureId, new Handler(context.getMainLooper()), listener);
        Preconditions.checkArgument(fingers >= 2);
        Preconditions.checkArgumentPositive(taps, "Tap count must greater than 0.");
        mTargetTapCount = taps;
        mTargetFingerCount = fingers;
        mDoubleTapSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop() * fingers;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop() * fingers;

        mBases = new PointF[mTargetFingerCount];
        for (int i = 0; i < mBases.length; i++) {
            mBases[i] = new PointF();
        }
        mExcludedPointsForDownSlopChecked = new ArrayList<>(mTargetFingerCount);
        clear();
    }

    @Override
    protected void clear() {
        mCompletedTapCount = 0;
        mIsTargetFingerCountReached = false;
        for (int i = 0; i < mBases.length; i++) {
            mBases[i].set(Float.NaN, Float.NaN);
        }
        mExcludedPointsForDownSlopChecked.clear();
        super.clear();
    }

    @Override
    protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Before the matcher state transit to completed,
        // Cancel when an additional down arrived after reaching the target number of taps.
        if (mCompletedTapCount == mTargetTapCount) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        cancelAfterTapTimeout(event, rawEvent, policyFlags);

        if (mCompletedTapCount == 0) {
            initBaseLocation(rawEvent);
            return;
        }
        // As fingers go up and down, their pointer ids will not be the same.
        // Therefore we require that a given finger be in slop range of any one
        // of the fingers from the previous tap.
        final PointF nearest = findNearestPoint(rawEvent, mDoubleTapSlop, true);
        if (nearest != null) {
            // Update pointer location to nearest one as a new base for next slop check.
            final int index = event.getActionIndex();
            nearest.set(event.getX(index), event.getY(index));
        } else {
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelAfterDoubleTapTimeout(event, rawEvent, policyFlags);

        final PointF nearest = findNearestPoint(rawEvent, mTouchSlop, false);
        if ((getState() == STATE_GESTURE_STARTED || getState() == STATE_CLEAR) && null != nearest) {
            // Increase current tap count when the user have all fingers lifted
            // within the tap timeout since the target number of fingers are down.
            if (mIsTargetFingerCountReached) {
                mCompletedTapCount++;
                mIsTargetFingerCountReached = false;
                mExcludedPointsForDownSlopChecked.clear();
            }

            // Start gesture detection here to avoid the conflict to 2nd finger double tap
            // that never actually started gesture detection.
            if (mCompletedTapCount == 1) {
                startGesture(event, rawEvent, policyFlags);
            }
            if (mCompletedTapCount == mTargetTapCount) {
                // Done.
                completeAfterDoubleTapTimeout(event, rawEvent, policyFlags);
            }
        } else {
            // Either too many taps or nonsensical event stream.
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Outside the touch slop
        if (null == findNearestPoint(rawEvent, mTouchSlop, false)) {
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Reset timeout to ease the use for some people
        // with certain impairments to get all their fingers down.
        cancelAfterTapTimeout(event, rawEvent, policyFlags);
        final int currentFingerCount = event.getPointerCount();
        // Accept down only before target number of fingers are down
        // or the finger count is not more than target.
        if ((currentFingerCount > mTargetFingerCount) || mIsTargetFingerCountReached) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }

        final PointF nearest;
        if (mCompletedTapCount == 0) {
            nearest = initBaseLocation(rawEvent);
        } else {
            nearest = findNearestPoint(rawEvent, mDoubleTapSlop, true);
        }
        if ((getState() == STATE_GESTURE_STARTED || getState() == STATE_CLEAR) && nearest != null) {
            // The user have all fingers down within the tap timeout since first finger down,
            // setting the timeout for fingers to be lifted.
            if (currentFingerCount == mTargetFingerCount) {
                mIsTargetFingerCountReached = true;
            }
            // Update pointer location to nearest one as a new base for next slop check.
            final int index = event.getActionIndex();
            nearest.set(event.getX(index), event.getY(index));
        } else {
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    protected void onPointerUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        // Accept up only after target number of fingers are down.
        if (!mIsTargetFingerCountReached) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }

        if (getState() == STATE_GESTURE_STARTED || getState() == STATE_CLEAR) {
            // Needs more fingers lifted within the tap timeout
            // after reaching the target number of fingers are down.
            cancelAfterTapTimeout(event, rawEvent, policyFlags);
        } else {
            cancelGesture(event, rawEvent, policyFlags);
        }
    }

    @Override
    public String getGestureName() {
        final StringBuilder builder = new StringBuilder();
        builder.append(mTargetFingerCount).append("-Finger ");
        if (mTargetTapCount == 1) {
            builder.append("Single");
        } else if (mTargetTapCount == 2) {
            builder.append("Double");
        } else if (mTargetTapCount == 3) {
            builder.append("Triple");
        } else if (mTargetTapCount > 3) {
            builder.append(mTargetTapCount);
        }
        return builder.append(" Tap").toString();
    }

    private PointF initBaseLocation(MotionEvent event) {
        final int index = event.getActionIndex();
        final int baseIndex = event.getPointerCount() - 1;
        final PointF p = mBases[baseIndex];
        if (Float.isNaN(p.x) && Float.isNaN(p.y)) {
            p.set(event.getX(index), event.getY(index));
        }
        return p;
    }

    /**
     * Find the nearest location to the given event in the bases. If no one found, it could be not
     * inside {@code slop}, filtered or empty bases. When {@code filterMatched} is true, if the
     * location of given event matches one of the points in {@link
     * #mExcludedPointsForDownSlopChecked} it would be ignored. Otherwise, the location will be
     * added to {@link #mExcludedPointsForDownSlopChecked}.
     *
     * @param event to find nearest point in bases.
     * @param slop to check to the given location of the event.
     * @param filterMatched true to exclude points already matched other pointers.
     * @return the point in bases closed to the location of the given event.
     */
    private PointF findNearestPoint(MotionEvent event, float slop, boolean filterMatched) {
        float moveDelta = Float.MAX_VALUE;
        PointF nearest = null;
        for (int i = 0; i < mBases.length; i++) {
            final PointF p = mBases[i];
            if (Float.isNaN(p.x) && Float.isNaN(p.y)) {
                continue;
            }
            if (filterMatched && mExcludedPointsForDownSlopChecked.contains(p)) {
                continue;
            }
            final int index = event.getActionIndex();
            final float dX = p.x - event.getX(index);
            final float dY = p.y - event.getY(index);
            if (dX == 0 && dY == 0) {
                if (filterMatched) {
                    mExcludedPointsForDownSlopChecked.add(p);
                }
                return p;
            }
            final float delta = (float) Math.hypot(dX, dY);
            if (moveDelta > delta) {
                moveDelta = delta;
                nearest = p;
            }
        }
        if (moveDelta < slop) {
            if (filterMatched) {
                mExcludedPointsForDownSlopChecked.add(nearest);
            }
            return nearest;
        }
        return null;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder(super.toString());
        if (getState() != STATE_GESTURE_CANCELED) {
            builder.append(", CompletedTapCount: ");
            builder.append(mCompletedTapCount);
            builder.append(", IsTargetFingerCountReached: ");
            builder.append(mIsTargetFingerCountReached);
            builder.append(", Bases: ");
            builder.append(Arrays.toString(mBases));
            builder.append(", ExcludedPointsForDownSlopChecked: ");
            builder.append(mExcludedPointsForDownSlopChecked.toString());
        }
        return builder.toString();
    }
}
