/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.view.animation.PathInterpolator;

/**
 * A helper interpolator to stagger the entrance animation in recents by offsetting the start time
 */
public class RecentsEntrancePathInterpolator extends PathInterpolator {
    final float mStartOffsetFraction;

    /**
     * Create an interpolator for a cubic Bezier curve with an offset play time. The end points
     * <code>(0, 0)</code> and <code>(1, 1)</code> are assumed.
     *
     * @param controlX1 The x coordinate of the first control point of the cubic Bezier.
     * @param controlY1 The y coordinate of the first control point of the cubic Bezier.
     * @param controlX2 The x coordinate of the second control point of the cubic Bezier.
     * @param controlY2 The y coordinate of the second control point of the cubic Bezier.
     * @param startOffsetFraction The fraction from 0 to 1 to start the animation from
     */
    public RecentsEntrancePathInterpolator(float controlX1, float controlY1, float controlX2,
            float controlY2, float startOffsetFraction) {
        super(controlX1, controlY1, controlX2, controlY2);
        mStartOffsetFraction = startOffsetFraction;
    }

    @Override
    public float getInterpolation(float t) {
        return super.getInterpolation(t + mStartOffsetFraction);
    }
}
