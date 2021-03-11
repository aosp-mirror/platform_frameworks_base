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

    List<MotionEvent> getPriorMotionEvents() {
        return mDataProvider.getPriorMotionEvents();
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
     * Returns whether a gesture looks like a false touch.
     *
     * See also {@link #classifyGesture(double, double)}.
     */
    Result classifyGesture() {
        return calculateFalsingResult(0, 0);
    }

    /**
     * Returns whether a gesture looks like a false touch, with the option to consider history.
     *
     * Unlike the parameter-less version of this method, this method allows the classifier to take
     * history into account, penalizing or boosting confidence in a gesture based on recent results.
     *
     * See also {@link #classifyGesture()}.
     */
    Result classifyGesture(double historyPenalty, double historyConfidence) {
        return calculateFalsingResult(historyPenalty, historyConfidence);
    }

    /**
     * Calculate a result based on available data.
     *
     * When passed a historyConfidence of 0, the history penalty should be wholly ignored.
     */
    abstract Result calculateFalsingResult(double historyPenalty, double historyConfidence);

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

    /**
     * A Falsing result that encapsulates the boolean result along with confidence and a reason.
     */
    static class Result {
        private final boolean mFalsed;
        private final double mConfidence;
        private final String mReason;

        /**
         * See {@link #falsed(double, String)} abd {@link #passed(double)}.
         */
        private Result(boolean falsed, double confidence, String reason) {
            mFalsed = falsed;
            mConfidence = confidence;
            mReason = reason;
        }

        public boolean isFalse() {
            return mFalsed;
        }

        public double getConfidence() {
            return mConfidence;
        }

        public String getReason() {
            return mReason;
        }

        /**
         * Construct a "falsed" result indicating that a gesture should be treated as accidental.
         */
        static Result falsed(double confidence, String reason) {
            return new Result(true, confidence, reason);
        }

        /**
         * Construct a "passed" result indicating that a gesture should be allowed.
         */
        static Result passed(double confidence) {
            return new Result(false, confidence, null);
        }
    }
}
