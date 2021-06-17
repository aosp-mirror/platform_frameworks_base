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

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Returns a false touch if the most two recent gestures are not taps or are too far apart.
 */
public class DoubleTapClassifier extends FalsingClassifier {

    private final SingleTapClassifier mSingleTapClassifier;
    private final float mDoubleTapSlop;
    private final long mDoubleTapTimeMs;

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
    Result calculateFalsingResult(
            @Classifier.InteractionType int interactionType,
            double historyBelief, double historyConfidence) {
        List<MotionEvent> secondTapEvents = getRecentMotionEvents();
        List<MotionEvent> firstTapEvents = getPriorMotionEvents();

        StringBuilder reason = new StringBuilder();

        if (firstTapEvents == null) {
            return falsed(0, "Only one gesture recorded");
        }

        return !isDoubleTap(firstTapEvents, secondTapEvents, reason)
                ? falsed(0.5, reason.toString()) : Result.passed(0.5);
    }

    /** Returns true if the two supplied lists of {@link MotionEvent}s look like a double-tap. */
    public boolean isDoubleTap(List<MotionEvent> firstEvents, List<MotionEvent> secondEvents,
            StringBuilder reason) {

        Result firstTap = mSingleTapClassifier.isTap(firstEvents, 0.5);
        if (firstTap.isFalse()) {
            reason.append("First gesture is not a tap. ").append(firstTap.getReason());
            return false;
        }

        Result secondTap = mSingleTapClassifier.isTap(secondEvents, 0.5);
        if (secondTap.isFalse()) {
            reason.append("Second gesture is not a tap. ").append(secondTap.getReason());
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
}
