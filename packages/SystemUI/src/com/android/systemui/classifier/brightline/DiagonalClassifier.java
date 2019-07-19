/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.classifier.brightline;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_DIAGONAL_HORIZONTAL_ANGLE_RANGE;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_DIAGONAL_VERTICAL_ANGLE_RANGE;
import static com.android.systemui.classifier.Classifier.LEFT_AFFORDANCE;
import static com.android.systemui.classifier.Classifier.RIGHT_AFFORDANCE;

import android.provider.DeviceConfig;

/**
 * False on swipes that are too close to 45 degrees.
 *
 * Horizontal swipes may have a different threshold than vertical.
 *
 * This falser should not run on "affordance" swipes, as they will always be close to 45.
 */
class DiagonalClassifier extends FalsingClassifier {

    private static final float HORIZONTAL_ANGLE_RANGE = (float) (5f / 360f * Math.PI * 2f);
    private static final float VERTICAL_ANGLE_RANGE = (float) (5f / 360f * Math.PI * 2f);
    private static final float DIAGONAL = (float) (Math.PI / 4); // 45 deg
    private static final float NINETY_DEG = (float) (Math.PI / 2);
    private static final float ONE_HUNDRED_EIGHTY_DEG = (float) (Math.PI);
    private static final float THREE_HUNDRED_SIXTY_DEG = (float) (2 * Math.PI);

    private final float mHorizontalAngleRange;
    private final float mVerticalAngleRange;

    DiagonalClassifier(FalsingDataProvider dataProvider) {
        super(dataProvider);

        mHorizontalAngleRange = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_DIAGONAL_HORIZONTAL_ANGLE_RANGE,
                HORIZONTAL_ANGLE_RANGE);
        mVerticalAngleRange = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_DIAGONAL_VERTICAL_ANGLE_RANGE,
                VERTICAL_ANGLE_RANGE);
    }

    @Override
    boolean isFalseTouch() {
        float angle = getAngle();

        if (angle == Float.MAX_VALUE) {  // Unknown angle
            return false;
        }

        if (getInteractionType() == LEFT_AFFORDANCE
                || getInteractionType() == RIGHT_AFFORDANCE) {
            return false;
        }

        float minAngle = DIAGONAL - mHorizontalAngleRange;
        float maxAngle = DIAGONAL + mHorizontalAngleRange;
        if (isVertical()) {
            minAngle = DIAGONAL - mVerticalAngleRange;
            maxAngle = DIAGONAL + mVerticalAngleRange;
        }

        return angleBetween(angle, minAngle, maxAngle)
                || angleBetween(angle, minAngle + NINETY_DEG, maxAngle + NINETY_DEG)
                || angleBetween(angle, minAngle - NINETY_DEG, maxAngle - NINETY_DEG)
                || angleBetween(angle, minAngle + ONE_HUNDRED_EIGHTY_DEG,
                maxAngle + ONE_HUNDRED_EIGHTY_DEG);
    }

    private boolean angleBetween(float angle, float min, float max) {
        // No need to normalize angle as it is guaranteed to be between 0 and 2*PI.
        min = normalizeAngle(min);
        max = normalizeAngle(max);

        if (min > max) {  // Can happen when angle is close to 0.
            return angle >= min || angle <= max;
        }

        return angle >= min && angle <= max;
    }

    private float normalizeAngle(float angle) {
        if (angle < 0) {
            return THREE_HUNDRED_SIXTY_DEG + (angle % THREE_HUNDRED_SIXTY_DEG);
        } else if (angle > THREE_HUNDRED_SIXTY_DEG) {
            return angle % THREE_HUNDRED_SIXTY_DEG;
        }
        return angle;
    }
}
