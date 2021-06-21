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

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_PROXIMITY_PERCENT_COVERED_THRESHOLD;
import static com.android.systemui.classifier.Classifier.BRIGHTNESS_SLIDER;
import static com.android.systemui.classifier.Classifier.QS_COLLAPSE;
import static com.android.systemui.classifier.Classifier.QS_SWIPE;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;

import android.provider.DeviceConfig;
import android.view.MotionEvent;

import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.util.DeviceConfigProxy;

import java.util.Locale;

import javax.inject.Inject;


/**
 * False touch if proximity sensor is covered for more than a certain percentage of the gesture.
 *
 * This classifier is essentially a no-op for QUICK_SETTINGS, as we assume the sensor may be
 * covered when swiping from the top.
 */
class ProximityClassifier extends FalsingClassifier {

    private static final float PERCENT_COVERED_THRESHOLD = 0.1f;
    private final DistanceClassifier mDistanceClassifier;
    private final float mPercentCoveredThreshold;

    private boolean mNear;
    private long mGestureStartTimeNs;
    private long mPrevNearTimeNs;
    private long mNearDurationNs;
    private float mPercentNear;

    @Inject
    ProximityClassifier(DistanceClassifier distanceClassifier,
            FalsingDataProvider dataProvider, DeviceConfigProxy deviceConfigProxy) {
        super(dataProvider);
        mDistanceClassifier = distanceClassifier;

        mPercentCoveredThreshold = deviceConfigProxy.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_PROXIMITY_PERCENT_COVERED_THRESHOLD,
                PERCENT_COVERED_THRESHOLD);
    }

    @Override
    void onSessionStarted() {
        mPrevNearTimeNs = 0;
        mPercentNear = 0;
    }

    @Override
    void onSessionEnded() {
        mPrevNearTimeNs = 0;
        mPercentNear = 0;
    }

    @Override
    public void onTouchEvent(MotionEvent motionEvent) {
        int action = motionEvent.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            mGestureStartTimeNs = motionEvent.getEventTimeNano();
            if (mPrevNearTimeNs > 0) {
                // We only care about if the proximity sensor is triggered while a move event is
                // happening.
                mPrevNearTimeNs = motionEvent.getEventTimeNano();
            }
            logDebug("Gesture start time: " + mGestureStartTimeNs);
            mNearDurationNs = 0;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            update(mNear, motionEvent.getEventTimeNano());
            long duration = motionEvent.getEventTimeNano() - mGestureStartTimeNs;

            logDebug("Gesture duration, Proximity duration: " + duration + ", " + mNearDurationNs);

            if (duration == 0) {
                mPercentNear = mNear ? 1.0f : 0.0f;
            } else {
                mPercentNear = (float) mNearDurationNs / (float) duration;
            }
        }

    }

    @Override
    public void onProximityEvent(
            FalsingManager.ProximityEvent proximityEvent) {
        boolean covered = proximityEvent.getCovered();
        long timestampNs = proximityEvent.getTimestampNs();
        logDebug("Sensor is: " + covered + " at time " + timestampNs);
        update(covered, timestampNs);
    }

    @Override
    Result calculateFalsingResult(
            @Classifier.InteractionType int interactionType,
            double historyBelief, double historyConfidence) {
        if (interactionType == QUICK_SETTINGS || interactionType == BRIGHTNESS_SLIDER
                || interactionType == QS_COLLAPSE || interactionType == QS_SWIPE) {
            return Result.passed(0);
        }

        if (mPercentNear > mPercentCoveredThreshold) {
            Result longSwipeResult = mDistanceClassifier.isLongSwipe();
            return longSwipeResult.isFalse()
                    ? falsed(
                            0.5, getReason(longSwipeResult, mPercentNear, mPercentCoveredThreshold))
                    : Result.passed(0.5);
        }

        return Result.passed(0.5);
    }

    private static String getReason(Result longSwipeResult, float percentNear,
            float percentCoveredThreshold) {
        return String.format(
                (Locale) null,
                "{percentInProximity=%f, threshold=%f, distanceClassifier=%s}",
                percentNear,
                percentCoveredThreshold,
                longSwipeResult.getReason());
    }

    /**
     * @param near        is the sensor showing the near state right now
     * @param timeStampNs time of this event in nanoseconds
     */
    private void update(boolean near, long timeStampNs) {
        if (mPrevNearTimeNs != 0 && timeStampNs > mPrevNearTimeNs && mNear) {
            mNearDurationNs += timeStampNs - mPrevNearTimeNs;
            logDebug("Updating duration: " + mNearDurationNs);
        }

        if (near) {
            logDebug("Set prevNearTimeNs: " + timeStampNs);
            mPrevNearTimeNs = timeStampNs;
        }

        mNear = near;
    }
}
