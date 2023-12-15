/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.back;

import android.annotation.FloatRange;
import android.os.SystemProperties;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.window.BackEvent;
import android.window.BackMotionEvent;

/**
 * Helper class to record the touch location for gesture and generate back events.
 */
class TouchTracker {
    private static final String PREDICTIVE_BACK_LINEAR_DISTANCE_PROP =
            "persist.wm.debug.predictive_back_linear_distance";
    private static final int LINEAR_DISTANCE = SystemProperties
            .getInt(PREDICTIVE_BACK_LINEAR_DISTANCE_PROP, -1);
    private float mLinearDistance = LINEAR_DISTANCE;
    private float mMaxDistance;
    private float mNonLinearFactor;
    /**
     * Location of the latest touch event
     */
    private float mLatestTouchX;
    private float mLatestTouchY;
    private boolean mTriggerBack;

    /**
     * Location of the initial touch event of the back gesture.
     */
    private float mInitTouchX;
    private float mInitTouchY;
    private float mLatestVelocityX;
    private float mLatestVelocityY;
    private float mStartThresholdX;
    private int mSwipeEdge;
    private TouchTrackerState mState = TouchTrackerState.INITIAL;

    void update(float touchX, float touchY, float velocityX, float velocityY) {
        /**
         * If back was previously cancelled but the user has started swiping in the forward
         * direction again, restart back.
         */
        if ((touchX < mStartThresholdX && mSwipeEdge == BackEvent.EDGE_LEFT)
                || (touchX > mStartThresholdX && mSwipeEdge == BackEvent.EDGE_RIGHT)) {
            mStartThresholdX = touchX;
        }
        mLatestTouchX = touchX;
        mLatestTouchY = touchY;
        mLatestVelocityX = velocityX;
        mLatestVelocityY = velocityY;
    }

    void setTriggerBack(boolean triggerBack) {
        if (mTriggerBack != triggerBack && !triggerBack) {
            mStartThresholdX = mLatestTouchX;
        }
        mTriggerBack = triggerBack;
    }

    boolean getTriggerBack() {
        return mTriggerBack;
    }

    void setState(TouchTrackerState state) {
        mState = state;
    }

    boolean isInInitialState() {
        return mState == TouchTrackerState.INITIAL;
    }

    boolean isActive() {
        return mState == TouchTrackerState.ACTIVE;
    }

    boolean isFinished() {
        return mState == TouchTrackerState.FINISHED;
    }

    void setGestureStartLocation(float touchX, float touchY, int swipeEdge) {
        mInitTouchX = touchX;
        mInitTouchY = touchY;
        mLatestTouchX = touchX;
        mLatestTouchY = touchY;
        mSwipeEdge = swipeEdge;
        mStartThresholdX = mInitTouchX;
    }

    /** Update the start location used to compute the progress
     * to the latest touch location.
     */
    void updateStartLocation() {
        mInitTouchX = mLatestTouchX;
        mInitTouchY = mLatestTouchY;
        mStartThresholdX = mInitTouchX;
    }

    void reset() {
        mInitTouchX = 0;
        mInitTouchY = 0;
        mStartThresholdX = 0;
        mTriggerBack = false;
        mState = TouchTrackerState.INITIAL;
        mSwipeEdge = BackEvent.EDGE_LEFT;
    }

    BackMotionEvent createStartEvent(RemoteAnimationTarget target) {
        return new BackMotionEvent(
                /* touchX = */ mInitTouchX,
                /* touchY = */ mInitTouchY,
                /* progress = */ 0,
                /* velocityX = */ 0,
                /* velocityY = */ 0,
                /* swipeEdge = */ mSwipeEdge,
                /* departingAnimationTarget = */ target);
    }

    BackMotionEvent createProgressEvent() {
        float progress = getProgress(mLatestTouchX);
        return createProgressEvent(progress);
    }

    /**
     * Progress value computed from the touch position.
     *
     * @param touchX the X touch position of the {@link MotionEvent}.
     * @return progress value
     */
    @FloatRange(from = 0.0, to = 1.0)
    float getProgress(float touchX) {
        // If back is committed, progress is the distance between the last and first touch
        // point, divided by the max drag distance. Otherwise, it's the distance between
        // the last touch point and the starting threshold, divided by max drag distance.
        // The starting threshold is initially the first touch location, and updated to
        // the location everytime back is restarted after being cancelled.
        float startX = mTriggerBack ? mInitTouchX : mStartThresholdX;
        float distance;
        if (mSwipeEdge == BackEvent.EDGE_LEFT) {
            distance = touchX - startX;
        } else {
            distance = startX - touchX;
        }
        float deltaX = Math.max(0f, distance);
        float linearDistance = mLinearDistance;
        float maxDistance = getMaxDistance();
        maxDistance = maxDistance == 0 ? 1 : maxDistance;
        float progress;
        if (linearDistance < maxDistance) {
            // Up to linearDistance it behaves linearly, then slowly reaches 1f.

            // maxDistance is composed of linearDistance + nonLinearDistance
            float nonLinearDistance = maxDistance - linearDistance;
            float initialTarget = linearDistance + nonLinearDistance * mNonLinearFactor;

            boolean isLinear = deltaX <= linearDistance;
            if (isLinear) {
                progress = deltaX / initialTarget;
            } else {
                float nonLinearDeltaX = deltaX - linearDistance;
                float nonLinearProgress = nonLinearDeltaX / nonLinearDistance;
                float currentTarget = MathUtils.lerp(
                        /* start = */ initialTarget,
                        /* stop = */ maxDistance,
                        /* amount = */ nonLinearProgress);
                progress = deltaX / currentTarget;
            }
        } else {
            // Always linear behavior.
            progress = deltaX / maxDistance;
        }
        return MathUtils.constrain(progress, 0, 1);
    }

    /**
     * Maximum distance in pixels.
     * Progress is considered to be completed (1f) when this limit is exceeded.
     */
    float getMaxDistance() {
        return mMaxDistance;
    }

    BackMotionEvent createProgressEvent(float progress) {
        return new BackMotionEvent(
                /* touchX = */ mLatestTouchX,
                /* touchY = */ mLatestTouchY,
                /* progress = */ progress,
                /* velocityX = */ mLatestVelocityX,
                /* velocityY = */ mLatestVelocityY,
                /* swipeEdge = */ mSwipeEdge,
                /* departingAnimationTarget = */ null);
    }

    public void setProgressThresholds(float linearDistance, float maxDistance,
            float nonLinearFactor) {
        if (LINEAR_DISTANCE >= 0) {
            mLinearDistance = LINEAR_DISTANCE;
        } else {
            mLinearDistance = linearDistance;
        }
        mMaxDistance = maxDistance;
        mNonLinearFactor = nonLinearFactor;
    }

    enum TouchTrackerState {
        INITIAL, ACTIVE, FINISHED
    }

}
