/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.view.animation.Interpolator;

/**
 * An implementation of a bouncer interpolator optimized for unlock hinting.
 */
public class BounceInterpolator implements Interpolator {

    private final static float SCALE_FACTOR = 7.5625f;

    @Override
    public float getInterpolation(float t) {
        t *= 11f / 10f;
        if (t < 4f / 11f) {
            return SCALE_FACTOR * t * t;
        } else if (t < 8f / 11f) {
            float t2 = t - 6f / 11f;
            return SCALE_FACTOR * t2 * t2 + 3f / 4f;
        } else if (t < 10f / 11f) {
            float t2 = t - 9f / 11f;
            return SCALE_FACTOR * t2 * t2 + 15f / 16f;
        } else {
            return 1;
        }
    }
}
