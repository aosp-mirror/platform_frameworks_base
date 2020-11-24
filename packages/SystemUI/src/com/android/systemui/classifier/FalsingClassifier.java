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

import android.view.MotionEvent;

import com.android.systemui.util.sensors.ProximitySensor;

import java.util.List;
import java.util.Queue;

/**
 * Base class for rules that determine False touches.
 */
public abstract class FalsingClassifier {
    private final FalsingDataProvider mDataProvider;

    private final FalsingDataProvider.MotionEventListener mMotionEventListener = this::onTouchEvent;

    FalsingClassifier(FalsingDataProvider dataProvider) {
        mDataProvider = dataProvider;
        mDataProvider.addMotionEventListener(mMotionEventListener);
    }

    List<MotionEvent> getRecentMotionEvents() {
        return mDataProvider.getRecentMotionEvents();
    }

    Queue<? extends List<MotionEvent>> getHistoricalEvents() {
        return mDataProvider.getHistoricalMotionEvents();
    }

    MotionEvent getFirstMotionEvent() {
        return mDataProvider.getFirstRecentMotionEvent();
    }

    MotionEvent getLastMotionEvent() {
        return mDataProvider.getLastMotionEvent();
    }

    boolean isHorizontal() {
        return mDataProvider.isHorizontal();
    }

    boolean isRight() {
        return mDataProvider.isRight();
    }

    boolean isVertical() {
        return mDataProvider.isVertical();
    }

    boolean isUp() {
        return mDataProvider.isUp();
    }

    float getAngle() {
        return mDataProvider.getAngle();
    }

    int getWidthPixels() {
        return mDataProvider.getWidthPixels();
    }

    int getHeightPixels() {
        return mDataProvider.getHeightPixels();
    }

    float getXdpi() {
        return mDataProvider.getXdpi();
    }

    float getYdpi() {
        return mDataProvider.getYdpi();
    }

    final @Classifier.InteractionType int getInteractionType() {
        return mDataProvider.getInteractionType();
    }

    void cleanup() {
        mDataProvider.removeMotionEventListener(mMotionEventListener);
    }

    /**
     * Called whenever a MotionEvent occurs.
     *
     * Useful for classifiers that need to see every MotionEvent, but most can probably
     * use {@link #getRecentMotionEvents()} instead, which will return a list of MotionEvents.
     */
    void onTouchEvent(MotionEvent motionEvent) {};

    /**
     * Called when a ProximityEvent occurs (change in near/far).
     */
    void onProximityEvent(ProximitySensor.ThresholdSensorEvent proximityEvent) {};

    /**
     * The phone screen has turned on and we need to begin falsing detection.
     */
    void onSessionStarted() {};

    /**
     * The phone screen has turned off and falsing data can be discarded.
     */
    void onSessionEnded() {};

    /**
     * Returns true if the data captured so far looks like a false touch.
     */
    Result classifyGesture() {
        return calculateFalsingResult(0, 0);
    }

    boolean classifyGesture(double historyPenalty, double historyConfidence) {
        return calculateFalsingResult(historyPenalty, historyConfidence).isFalse();
    }

    abstract Result calculateFalsingResult(double historyPenalty, double historyConfidence);

    /**
     * Give the classifier a chance to log more details about why it triggered.
     *
     * This should only be called after a call to {@link #classifyGesture()}, and only if
     * {@link #classifyGesture()} returns true;
     */
    abstract String getReason();

    /** */
    public static void logDebug(String msg) {
        BrightLineFalsingManager.logDebug(msg);
    }

    /** */
    public static void logInfo(String msg) {
        BrightLineFalsingManager.logInfo(msg);
    }

    /** */
    public static void logError(String msg) {
        BrightLineFalsingManager.logError(msg);
    }

    static class Result {
        private final boolean mFalsed;
        private final double mConfidence;

        Result(boolean falsed, double confidence) {
            mFalsed = falsed;
            mConfidence = confidence;
        }

        public boolean isFalse() {
            return mFalsed;
        }

        public double getConfidence() {
            return mConfidence;
        }
    }
}
