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

package com.android.systemui.classifier;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_DISTANCE_HORIZONTAL_FLING_THRESHOLD_IN;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_DISTANCE_HORIZONTAL_SWIPE_THRESHOLD_IN;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_DISTANCE_SCREEN_FRACTION_MAX_DISTANCE;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_DISTANCE_VELOCITY_TO_DISTANCE;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_DISTANCE_VERTICAL_FLING_THRESHOLD_IN;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_DISTANCE_VERTICAL_SWIPE_THRESHOLD_IN;

import android.provider.DeviceConfig;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import com.android.systemui.util.DeviceConfigProxy;

import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

/**
 * Ensure that the swipe + momentum covers a minimum distance.
 */
class DistanceClassifier extends FalsingClassifier {

    private static final float HORIZONTAL_FLING_THRESHOLD_DISTANCE_IN = 1;
    private static final float VERTICAL_FLING_THRESHOLD_DISTANCE_IN = 1.5f;
    private static final float HORIZONTAL_SWIPE_THRESHOLD_DISTANCE_IN = 3;
    private static final float VERTICAL_SWIPE_THRESHOLD_DISTANCE_IN = 3;
    private static final float VELOCITY_TO_DISTANCE = 30f;
    private static final float SCREEN_FRACTION_MAX_DISTANCE = 0.8f;

    private final float mVerticalFlingThresholdPx;
    private final float mHorizontalFlingThresholdPx;
    private final float mVerticalSwipeThresholdPx;
    private final float mHorizontalSwipeThresholdPx;
    private final float mVelocityToDistanceMultiplier;

    private boolean mDistanceDirty;
    private DistanceVectors mCachedDistance;

    @Inject
    DistanceClassifier(FalsingDataProvider dataProvider, DeviceConfigProxy deviceConfigProxy) {
        super(dataProvider);

        mVelocityToDistanceMultiplier = deviceConfigProxy.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_DISTANCE_VELOCITY_TO_DISTANCE,
                VELOCITY_TO_DISTANCE);

        float horizontalFlingThresholdIn = deviceConfigProxy.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_DISTANCE_HORIZONTAL_FLING_THRESHOLD_IN,
                HORIZONTAL_FLING_THRESHOLD_DISTANCE_IN);

        float verticalFlingThresholdIn = deviceConfigProxy.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_DISTANCE_VERTICAL_FLING_THRESHOLD_IN,
                VERTICAL_FLING_THRESHOLD_DISTANCE_IN);

        float horizontalSwipeThresholdIn = deviceConfigProxy.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_DISTANCE_HORIZONTAL_SWIPE_THRESHOLD_IN,
                HORIZONTAL_SWIPE_THRESHOLD_DISTANCE_IN);

        float verticalSwipeThresholdIn = deviceConfigProxy.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_DISTANCE_VERTICAL_SWIPE_THRESHOLD_IN,
                VERTICAL_SWIPE_THRESHOLD_DISTANCE_IN);

        float screenFractionMaxDistance = deviceConfigProxy.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_DISTANCE_SCREEN_FRACTION_MAX_DISTANCE,
                SCREEN_FRACTION_MAX_DISTANCE);

        mHorizontalFlingThresholdPx = Math
                .min(getWidthPixels() * screenFractionMaxDistance,
                        horizontalFlingThresholdIn * getXdpi());
        mVerticalFlingThresholdPx = Math
                .min(getHeightPixels() * screenFractionMaxDistance,
                        verticalFlingThresholdIn * getYdpi());
        mHorizontalSwipeThresholdPx = Math
                .min(getWidthPixels() * screenFractionMaxDistance,
                        horizontalSwipeThresholdIn * getXdpi());
        mVerticalSwipeThresholdPx = Math
                .min(getHeightPixels() * screenFractionMaxDistance,
                        verticalSwipeThresholdIn * getYdpi());
        mDistanceDirty = true;
    }

    private DistanceVectors getDistances() {
        if (mDistanceDirty) {
            mCachedDistance = calculateDistances();
            mDistanceDirty = false;
        }

        return mCachedDistance;
    }

    private DistanceVectors calculateDistances() {
        // This code assumes that there will be no missed DOWN or UP events.
        List<MotionEvent> motionEvents = getRecentMotionEvents();

        if (motionEvents.size() < 3) {
            logDebug("Only " + motionEvents.size() + " motion events recorded.");
            return new DistanceVectors(0, 0, 0, 0);
        }

        VelocityTracker velocityTracker = VelocityTracker.obtain();
        for (MotionEvent motionEvent : motionEvents) {
            velocityTracker.addMovement(motionEvent);
        }
        velocityTracker.computeCurrentVelocity(1);

        float vX = velocityTracker.getXVelocity();
        float vY = velocityTracker.getYVelocity();

        velocityTracker.recycle();

        float dX = getLastMotionEvent().getX() - getFirstMotionEvent().getX();
        float dY = getLastMotionEvent().getY() - getFirstMotionEvent().getY();

        return new DistanceVectors(dX, dY, vX, vY);
    }

    @Override
    public void onTouchEvent(MotionEvent motionEvent) {
        mDistanceDirty = true;
    }

    @Override
    Result calculateFalsingResult(
            @Classifier.InteractionType int interactionType,
            double historyBelief, double historyConfidence) {
        if (interactionType == Classifier.BRIGHTNESS_SLIDER) {
            return Result.passed(0);
        }

        return !getPassedFlingThreshold() ? falsed(0.5, getReason()) : Result.passed(0.5);
    }

    String getReason() {
        DistanceVectors distanceVectors = getDistances();

        return String.format(
                (Locale) null,
                "{distanceVectors=%s, isHorizontal=%s, velocityToDistanceMultiplier=%f, "
                        + "horizontalFlingThreshold=%f, verticalFlingThreshold=%f, "
                        + "horizontalSwipeThreshold=%f, verticalSwipeThreshold=%s}",
                distanceVectors,
                isHorizontal(),
                mVelocityToDistanceMultiplier,
                mHorizontalFlingThresholdPx,
                mVerticalFlingThresholdPx,
                mHorizontalSwipeThresholdPx,
                mVerticalSwipeThresholdPx);
    }

    Result isLongSwipe() {
        boolean longSwipe = getPassedDistanceThreshold();
        logDebug("Is longSwipe? " + longSwipe);
        return longSwipe ? Result.passed(0.5) : falsed(0.5, getReason());
    }

    private boolean getPassedDistanceThreshold() {
        DistanceVectors distanceVectors = getDistances();
        if (isHorizontal()) {
            logDebug("Horizontal swipe distance: " + Math.abs(distanceVectors.mDx));
            logDebug("Threshold: " + mHorizontalSwipeThresholdPx);

            return Math.abs(distanceVectors.mDx) >= mHorizontalSwipeThresholdPx;
        }

        logDebug("Vertical swipe distance: " + Math.abs(distanceVectors.mDy));
        logDebug("Threshold: " + mVerticalSwipeThresholdPx);
        return Math.abs(distanceVectors.mDy) >= mVerticalSwipeThresholdPx;
    }

    private boolean getPassedFlingThreshold() {
        DistanceVectors distanceVectors = getDistances();

        float dX = distanceVectors.mDx + distanceVectors.mVx * mVelocityToDistanceMultiplier;
        float dY = distanceVectors.mDy + distanceVectors.mVy * mVelocityToDistanceMultiplier;

        if (isHorizontal()) {
            logDebug("Horizontal swipe and fling distance: " + distanceVectors.mDx + ", "
                    + distanceVectors.mVx * mVelocityToDistanceMultiplier);
            logDebug("Threshold: " + mHorizontalFlingThresholdPx);
            return Math.abs(dX) >= mHorizontalFlingThresholdPx;
        }

        logDebug("Vertical swipe and fling distance: " + distanceVectors.mDy + ", "
                + distanceVectors.mVy * mVelocityToDistanceMultiplier);
        logDebug("Threshold: " + mVerticalFlingThresholdPx);
        return Math.abs(dY) >= mVerticalFlingThresholdPx;
    }

    private class DistanceVectors {
        final float mDx;
        final float mDy;
        private final float mVx;
        private final float mVy;

        DistanceVectors(float dX, float dY, float vX, float vY) {
            this.mDx = dX;
            this.mDy = dY;
            this.mVx = vX;
            this.mVy = vY;
        }

        @Override
        public String toString() {
            return String.format((Locale) null, "{dx=%f, vx=%f, dy=%f, vy=%f}", mDx, mVx, mDy, mVy);
        }
    }
}
