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
 * limitations under the License.
 */

package com.android.systemui.volume;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

public class IconPulser {
    private static final float PULSE_SCALE = 1.1f;

    private final Interpolator mFastOutSlowInInterpolator;

    public IconPulser(Context context) {
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.fast_out_slow_in);
    }

    public void start(final View target) {
        if (target == null || target.getScaleX() != 1) return;  // n/a, or already running
        target.animate().cancel();
        target.animate().scaleX(PULSE_SCALE).scaleY(PULSE_SCALE)
                .setInterpolator(mFastOutSlowInInterpolator)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        target.animate().scaleX(1).scaleY(1).setListener(null);
                    }
                });
    }
}
