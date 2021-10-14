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

import static com.android.systemui.classifier.FalsingModule.SINGLE_TAP_TOUCH_SLOP;

import android.view.MotionEvent;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Falsing classifier that accepts or rejects a single gesture as a tap.
 */
public class SingleTapClassifier extends FalsingClassifier {
    private final float mTouchSlop;

    @Inject
    SingleTapClassifier(FalsingDataProvider dataProvider,
            @Named(SINGLE_TAP_TOUCH_SLOP) float touchSlop) {
        super(dataProvider);
        mTouchSlop = touchSlop;
    }

    @Override
    Result calculateFalsingResult(
            @Classifier.InteractionType int interactionType,
            double historyBelief, double historyConfidence) {
        return isTap(getRecentMotionEvents(), 0.5);
    }

    /** Given a list of {@link android.view.MotionEvent}'s, returns true if the look like a tap. */
    public Result isTap(List<MotionEvent> motionEvents, double falsePenalty) {
        if (motionEvents.isEmpty()) {
            return falsed(0, "no motion events");
        }
        float downX = motionEvents.get(0).getX();
        float downY = motionEvents.get(0).getY();

        for (MotionEvent event : motionEvents) {
            String reason;
            if (Math.abs(event.getX() - downX) >= mTouchSlop) {
                reason = "dX too big for a tap: "
                        + Math.abs(event.getX() - downX)
                        + "vs "
                        + mTouchSlop;
                return falsed(falsePenalty, reason);
            } else if (Math.abs(event.getY() - downY) >= mTouchSlop) {
                reason = "dY too big for a tap: "
                        + Math.abs(event.getY() - downY)
                        + " vs "
                        + mTouchSlop;
                return falsed(falsePenalty, reason);
            }
        }
        return Result.passed(0);
    }
}
