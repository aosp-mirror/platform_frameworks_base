/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.tv.animations;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.res.Resources;
import android.view.View;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.tv.views.TaskCardView;

/**
 * Recents row's focus animation with PIP controls.
 */
public class RecentsRowFocusAnimationHolder {
    private final View mView;
    private final View mTitleView;

    private AnimatorSet mFocusGainAnimatorSet;
    private AnimatorSet mFocusLossAnimatorSet;

    public RecentsRowFocusAnimationHolder(View view, View titleView) {
        mView = view;
        mTitleView = titleView;

        Resources res = view.getResources();
        int duration = res.getInteger(R.integer.recents_tv_pip_focus_anim_duration);
        float dimAlpha = res.getFloat(R.dimen.recents_recents_row_dim_alpha);

        mFocusGainAnimatorSet = new AnimatorSet();
        mFocusGainAnimatorSet.playTogether(
                ObjectAnimator.ofFloat(mView, "alpha", 1f),
                ObjectAnimator.ofFloat(mTitleView, "alpha", 1f));
        mFocusGainAnimatorSet.setDuration(duration);
        mFocusGainAnimatorSet.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);

        mFocusLossAnimatorSet = new AnimatorSet();
        mFocusLossAnimatorSet.playTogether(
                // Animation doesn't start from the current value (1f) sometimes,
                // so specify the desired initial value here.
                ObjectAnimator.ofFloat(mView, "alpha", 1f, dimAlpha),
                ObjectAnimator.ofFloat(mTitleView, "alpha", 0f));
        mFocusLossAnimatorSet.setDuration(duration);
        mFocusLossAnimatorSet.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
    }

    /**
     * Starts the Recents row's focus gain animation.
     */
    public void startFocusGainAnimation() {
        cancelAnimator(mFocusLossAnimatorSet);
        mFocusGainAnimatorSet.start();
    }

    /**
     * Starts the Recents row's focus loss animation.
     */
    public void startFocusLossAnimation() {
        cancelAnimator(mFocusGainAnimatorSet);
        mFocusLossAnimatorSet.start();
    }

    /**
     * Resets the views immediately and ends the animations.
     */
    public void reset() {
        cancelAnimator(mFocusLossAnimatorSet);
        cancelAnimator(mFocusGainAnimatorSet);
        mView.setAlpha(1f);
        mTitleView.setAlpha(1f);
    }

    private static void cancelAnimator(Animator animator) {
        if (animator.isStarted()) {
            animator.cancel();
        }
    }
}
