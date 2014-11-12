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

package com.android.keyguard;

import android.content.Context;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

/**
 * A class to make nice disappear transitions for views in a tabular layout.
 */
public class DisappearAnimationUtils extends AppearAnimationUtils {

    public DisappearAnimationUtils(Context ctx) {
        this(ctx, DEFAULT_APPEAR_DURATION,
                1.0f, 1.0f,
                AnimationUtils.loadInterpolator(ctx, android.R.interpolator.linear_out_slow_in));
    }

    public DisappearAnimationUtils(Context ctx, long duration, float translationScaleFactor,
            float delayScaleFactor, Interpolator interpolator) {
        super(ctx, duration, translationScaleFactor, delayScaleFactor, interpolator);
        mScaleTranslationWithRow = true;
        mAppearing = false;
    }

    protected long calculateDelay(int row, int col) {
        return (long) ((row * 60 + col * (Math.pow(row, 0.4) + 0.4) * 10) * mDelayScale);
    }
}
