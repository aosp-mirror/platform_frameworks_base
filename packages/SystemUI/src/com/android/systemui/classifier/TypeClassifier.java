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


import static com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK;
import static com.android.systemui.classifier.Classifier.BRIGHTNESS_SLIDER;
import static com.android.systemui.classifier.Classifier.LEFT_AFFORDANCE;
import static com.android.systemui.classifier.Classifier.NOTIFICATION_DISMISS;
import static com.android.systemui.classifier.Classifier.NOTIFICATION_DRAG_DOWN;
import static com.android.systemui.classifier.Classifier.PULSE_EXPAND;
import static com.android.systemui.classifier.Classifier.QS_COLLAPSE;
import static com.android.systemui.classifier.Classifier.QS_SWIPE;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.classifier.Classifier.RIGHT_AFFORDANCE;
import static com.android.systemui.classifier.Classifier.SHADE_DRAG;
import static com.android.systemui.classifier.Classifier.UNLOCK;

import javax.inject.Inject;

/**
 * Ensure that the swipe direction generally matches that of the interaction type.
 */
public class TypeClassifier extends FalsingClassifier {
    @Inject
    TypeClassifier(FalsingDataProvider dataProvider) {
        super(dataProvider);
    }

    @Override
    Result calculateFalsingResult(
            @Classifier.InteractionType int interactionType,
            double historyBelief, double historyConfidence) {
        if (interactionType == Classifier.UDFPS_AUTHENTICATION
                || interactionType == Classifier.LOCK_ICON) {
            return Result.passed(0);
        }

        boolean vertical = isVertical();
        boolean up = isUp();
        boolean right = isRight();

        double confidence = 1;
        boolean wrongDirection = true;
        switch (interactionType) {
            case QUICK_SETTINGS:
            case PULSE_EXPAND:
            case NOTIFICATION_DRAG_DOWN:
                wrongDirection = !vertical || up;
                break;
            case BRIGHTNESS_SLIDER:
                confidence = 0;  // Owners may return to original brightness.
                // A more sophisticated thing to do here would be to look at the size of the
                // vertical change relative to the screen size. _Some_ amount of vertical
                // change should be expected.
            case NOTIFICATION_DISMISS:
                wrongDirection = vertical;
                break;
            case UNLOCK:
            case BOUNCER_UNLOCK:
                wrongDirection = !vertical || !up;
                break;
            case LEFT_AFFORDANCE:  // Swiping from the bottom left corner for camera or similar.
                wrongDirection = !right || !up;
                break;
            case RIGHT_AFFORDANCE:  // Swiping from the bottom right corner for camera or similar.
                wrongDirection = right || !up;
                break;
            case SHADE_DRAG:
                wrongDirection = !vertical;
                break;
            case QS_COLLAPSE:
                wrongDirection = !vertical || !up;
                break;
            case QS_SWIPE:
                wrongDirection = vertical;
                break;
            default:
                wrongDirection = true;
                break;
        }

        return wrongDirection ? falsed(confidence, getReason(interactionType)) : Result.passed(0.5);
    }

    private String getReason(int interactionType) {
        return String.format("{interaction=%s, vertical=%s, up=%s, right=%s}",
                interactionType, isVertical(), isUp(), isRight());
    }
}
