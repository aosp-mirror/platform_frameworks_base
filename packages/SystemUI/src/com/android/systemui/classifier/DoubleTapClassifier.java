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

import static com.android.systemui.classifier.FalsingModule.DOUBLE_TAP_TIMEOUT_MS;
import static com.android.systemui.classifier.FalsingModule.DOUBLE_TAP_TOUCH_SLOP;

import android.view.MotionEvent;

import java.util.List;
import java.util.Queue;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Returns a false touch if the most two recent gestures are not taps or are too far apart.
 */
public class DoubleTapClassifier extends FalsingClassifier {

    private final SingleTapClassifier mSingleTapClassifier;
    private final float mDoubleTapSlop;
    private final long mDoubleTapTimeMs;

    private StringBuilder mReason = new StringBuilder();

    @Inject
    DoubleTapClassifier(FalsingDataProvider dataProvider, SingleTapClassifier singleTapClassifier,
            @Named(DOUBLE_TAP_TOUCH_SLOP) float doubleTapSlop,
            @Named(DOUBLE_TAP_TIMEOUT_MS) long doubleTapTimeMs) {
        super(dataProvider);
        mSingleTapClassifier = singleTapClassifier;
        mDoubleTapSlop = doubleTapSlop;
        mDoubleTapTimeMs = doubleTapTimeMs;
    }

    @Override
    Result calculateFalsingResult(double historyPenalty, double historyConfidence) {
        List<MotionEvent> secondTapEvents = getRecentMotionEvents();
        Queue<? extends List<MotionEvent>> historicalEvents = getHistoricalEvents();
        List<MotionEvent> firstTapEvents = historicalEvents.peek();

        mReason = new StringBuilder();

        if (firstTapEvents == null) {
            mReason.append("Only one gesture recorded");
            return new Result(true, 1);
        }

        return new Result(!isDoubleTap(firstTapEvents, secondTapEvents, mReason), 0.5);
    }

    /** Returns true if the two supplied lists of {@link MotionEvent}s look like a double-tap. */
    public boolean isDoubleTap(List<MotionEvent> firstEvents, List<MotionEvent> secondEvents,
            StringBuilder reason) {

        if (!mSingleTapClassifier.isTap(firstEvents)) {
            reason.append("First gesture is not a tap. ").append(mSingleTapClassifier.getReason());
            return false;
        }

        if (!mSingleTapClassifier.isTap(secondEvents)) {
            reason.append("Second gesture is not a tap. ").append(mSingleTapClassifier.getReason());
            return false;
        }

        MotionEvent firstFinalEvent = firstEvents.get(firstEvents.size() - 1);
        MotionEvent secondFinalEvent = secondEvents.get(secondEvents.size() - 1);

        long dt = secondFinalEvent.getEventTime() - firstFinalEvent.getEventTime();

        if (dt > mDoubleTapTimeMs) {
            reason.append("Time between taps too large: ").append(dt).append("ms");
            return false;
        }

        if (Math.abs(firstFinalEvent.getX() - secondFinalEvent.getX()) >= mDoubleTapSlop) {
            reason.append("Delta X between taps too large:")
                    .append(Math.abs(firstFinalEvent.getX() - secondFinalEvent.getX()))
                    .append(" vs ")
                    .append(mDoubleTapSlop);
            return false;
        }

        if (Math.abs(firstFinalEvent.getY() - secondFinalEvent.getY()) >= mDoubleTapSlop) {
            reason.append("Delta Y between taps too large:")
                    .append(Math.abs(firstFinalEvent.getY() - secondFinalEvent.getY()))
                    .append(" vs ")
                    .append(mDoubleTapSlop);
            return false;
        }

        return true;
    }

    @Override
    String getReason() {
        return mReason.toString();
    }
}
