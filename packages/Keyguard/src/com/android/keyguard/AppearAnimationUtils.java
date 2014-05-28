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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

/**
 * A class to make nice appear transitions for views in a tabular layout.
 */
public class AppearAnimationUtils {

    public static final long APPEAR_DURATION = 220;

    private final Interpolator mLinearOutSlowIn;
    private final float mStartTranslation;

    public AppearAnimationUtils(Context ctx) {
        mLinearOutSlowIn = AnimationUtils.loadInterpolator(
                ctx, android.R.interpolator.linear_out_slow_in);
        mStartTranslation =
                ctx.getResources().getDimensionPixelOffset(R.dimen.appear_y_translation_start);
    }

    public void startAppearAnimation(View[][] views, final Runnable finishListener) {
        long maxDelay = 0;
        ViewPropertyAnimator maxDelayAnimator = null;
        for (int row = 0; row < views.length; row++) {
            View[] columns = views[row];
            for (int col = 0; col < columns.length; col++) {
                long delay = calculateDelay(row, col);
                ViewPropertyAnimator animator = startAppearAnimation(columns[col], delay);
                if (animator != null && delay > maxDelay) {
                    maxDelay = delay;
                    maxDelayAnimator = animator;
                }
            }
        }
        if (maxDelayAnimator != null) {
            maxDelayAnimator.setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    finishListener.run();
                }
            });
        } else {
            finishListener.run();
        }
    }

    private ViewPropertyAnimator startAppearAnimation(View view, long delay) {
        if (view == null) return null;
        view.setAlpha(0f);
        view.setTranslationY(mStartTranslation);
        view.animate()
                .alpha(1f)
                .translationY(0)
                .setInterpolator(mLinearOutSlowIn)
                .setDuration(APPEAR_DURATION)
                .setStartDelay(delay)
                .setListener(null);
        if (view.hasOverlappingRendering()) {
            view.animate().withLayer();
        }
        return view.animate();
    }

    private long calculateDelay(int row, int col) {
        return (long) (row * 40 + col * (Math.pow(row, 0.4) + 0.4) * 20);
    }

    public TimeInterpolator getInterpolator() {
        return mLinearOutSlowIn;
    }

    public float getStartTranslation() {
        return mStartTranslation;
    }
}
