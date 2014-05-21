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

package com.android.systemui.qs;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;

/** Helper for view-level circular clip animations. **/
public class CircularClipper {

    private final View mTarget;

    private Utils mUtils;
    private ValueAnimator mAnimator;

    public CircularClipper(View target) {
        mTarget = target;
    }

    public void setUtils(Utils utils) {
        mUtils = utils;
    }

    public void animateCircularClip(int x, int y, boolean in, AnimatorListener listener) {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        final int w = mTarget.getWidth() - x;
        final int h = mTarget.getHeight() - y;
        int r = (int) Math.ceil(Math.sqrt(x * x + y * y));
        r = (int) Math.max(r, Math.ceil(Math.sqrt(w * w + y * y)));
        r = (int) Math.max(r, Math.ceil(Math.sqrt(w * w + h * h)));
        r = (int) Math.max(r, Math.ceil(Math.sqrt(x * x + h * h)));

        if (mUtils == null) {
                mTarget.setVisibility(in ? View.VISIBLE : View.GONE);
            if (listener != null) {
                listener.onAnimationEnd(null);
            }
            return;
        }
        mAnimator = mUtils.createRevealAnimator(mTarget, x, y, 0, r);
        mAnimator.removeAllListeners();
        if (listener != null) {
            mAnimator.addListener(listener);
        }
        if (in) {
            mAnimator.addListener(mVisibleOnStart);
            mAnimator.start();
        } else {
            mAnimator.addListener(mGoneOnEnd);
            mAnimator.reverse();
        }
    }

    private final AnimatorListenerAdapter mVisibleOnStart = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationStart(Animator animation) {
            mTarget.setVisibility(View.VISIBLE);
        }
    };

    private final AnimatorListenerAdapter mGoneOnEnd = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mTarget.setVisibility(View.GONE);
        };
    };

    public interface Utils {
        ValueAnimator createRevealAnimator(View v, int centerX,  int centerY,
                float startRadius, float endRadius);
    }
}
