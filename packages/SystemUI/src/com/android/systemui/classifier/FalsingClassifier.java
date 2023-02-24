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

import com.android.systemui.plugins.FalsingManager;

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

    protected String getFalsingContext() {
        return getClass().getSimpleName();
    }

    protected Result falsed(double confidence, String reason) {
        return Result.falsed(confidence, getFalsingContext(), reason);
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

    void cleanup() {
        mDataProvider.removeMotionEventListener(mMotionEventListener);
    }

    /**
     * Called whenever a MotionEvent occurs.
     *
     * Useful for classifiers that need to see every MotionEvent, but most can probably
     * use {@link #getRecentMotionEvents()} instead, which will return a list of MotionEvents.
     */
    void onTouchEvent(MotionEvent motionEvent) {}

    /**
     * Called when a ProximityEvent occurs (change in near/far).
     */
    void onProximityEvent(FalsingManager.ProximityEvent proximityEvent) {}

    /**
     * The phone screen has turned on and we need to begin falsing detection.
     */
    void onSessionStarted() {}

    /**
     * The phone screen has turned off and falsing data can be discarded.
     */
    void onSessionEnded() {}

    /**
     * Returns whether a gesture looks like a false touch, taking history into consideration.
     *
     * See {@link HistoryTracker#falseBelief()} and {@link HistoryTracker#falseConfidence()}.
     */
    Result classifyGesture(
            @Classifier.InteractionType int interactionType,
            double historyBelief, double historyConfidence) {
        return calculateFalsingResult(interactionType, historyBelief, historyConfidence);
    }

    /**
     * Calculate a result based on available data.
     *
     * When passed a historyConfidence of 0, the history belief should be wholly ignored.
     */
    abstract Result calculateFalsingResult(
            @Classifier.InteractionType int interactionType,
            double historyBelief, double historyConfidence);

    /** */
    public static void logDebug(String msg) {
        BrightLineFalsingManager.logDebug(msg);
    }

    /** */
    public static void logVerbose(String msg) {
        BrightLineFalsingManager.logVerbose(msg);
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
    public static class Result {
        private final boolean mFalsed;
        private final double mConfidence;
        private final String mContext;
        private final String mReason;

        /**
         * See {@link #falsed(double, String, String)} abd {@link #passed(double)}.
         */
        private Result(boolean falsed, double confidence, String context, String reason) {
            mFalsed = falsed;
            mConfidence = confidence;
            mContext = context;
            mReason = reason;
        }

        public boolean isFalse() {
            return mFalsed;
        }

        public double getConfidence() {
            return mConfidence;
        }

        public String getReason() {
            return String.format("{context=%s reason=%s}", mContext, mReason);
        }

        /**
         * Construct a "falsed" result indicating that a gesture should be treated as accidental.
         */
        public static Result falsed(double confidence, String context, String reason) {
            return new Result(true, confidence, context, reason);
        }

        /**
         * Construct a "passed" result indicating that a gesture should be allowed.
         */
        public static Result passed(double confidence) {
            return new Result(false, confidence, null, null);
        }
    }
}
