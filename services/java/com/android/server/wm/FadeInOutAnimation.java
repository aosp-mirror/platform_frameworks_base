/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wm;

import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.Transformation;

/**
 * Animation that fade in after 0.5 interpolate time, or fade out in reverse order.
 * This is used for opening/closing transition for apps in compatible mode.
 */
class FadeInOutAnimation extends Animation {
    boolean mFadeIn;

    public FadeInOutAnimation(boolean fadeIn) {
        setInterpolator(new AccelerateInterpolator());
        setDuration(WindowManagerService.DEFAULT_FADE_IN_OUT_DURATION);
        mFadeIn = fadeIn;
    }

    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        float x = interpolatedTime;
        if (!mFadeIn) {
            x = 1.0f - x; // reverse the interpolation for fade out
        }
        t.setAlpha(x);
    }
}