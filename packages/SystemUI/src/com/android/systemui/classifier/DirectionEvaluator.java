/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

public class DirectionEvaluator {
    public static float evaluate(float xDiff, float yDiff, int type) {
        float falsingEvaluation = 5.5f;
        boolean vertical = Math.abs(yDiff) >= Math.abs(xDiff);
        switch (type) {
            case Classifier.QUICK_SETTINGS:
            case Classifier.NOTIFICATION_DRAG_DOWN:
                if (!vertical || yDiff <= 0.0) {
                    return falsingEvaluation;
                }
                break;
            case Classifier.NOTIFICATION_DISMISS:
                if (vertical) {
                    return falsingEvaluation;
                }
                break;
            case Classifier.UNLOCK:
            case Classifier.BOUNCER_UNLOCK:
                if (!vertical || yDiff >= 0.0) {
                    return falsingEvaluation;
                }
                break;
            case Classifier.LEFT_AFFORDANCE:
                if (xDiff < 0.0 && yDiff > 0.0) {
                    return falsingEvaluation;
                }
                break;
            case Classifier.RIGHT_AFFORDANCE:
                if (xDiff > 0.0 && yDiff > 0.0) {
                    return falsingEvaluation;
                }
            default:
                break;
        }
        return 0.0f;
    }
}
