/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.StatusBarState;

/**
 * View corresponding with udfps_keyguard_view.xml
 */
public class UdfpsKeyguardView extends UdfpsAnimationView {
    private final UdfpsKeyguardDrawable mFingerprintDrawable;
    private ImageView mFingerprintView;
    private int mWallpaperTextColor;
    private int mStatusBarState;

    // used when highlighting fp icon:
    private int mTextColorPrimary;
    private ImageView mBgProtection;
    boolean mUdfpsRequested;
    int mUdfpsRequestedColor;

    private AnimatorSet mAnimatorSet;
    private int mAlpha; // 0-255

    public UdfpsKeyguardView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mFingerprintDrawable = new UdfpsKeyguardDrawable(mContext);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mFingerprintView = findViewById(R.id.udfps_keyguard_animation_fp_view);
        mFingerprintView.setForeground(mFingerprintDrawable);

        mBgProtection = findViewById(R.id.udfps_keyguard_fp_bg);

        mWallpaperTextColor = Utils.getColorAttrDefaultColor(mContext,
                R.attr.wallpaperTextColorAccent);
        mTextColorPrimary = Utils.getColorAttrDefaultColor(mContext,
                android.R.attr.textColorPrimary);
        mUdfpsRequested = false;
    }

    @Override
    public UdfpsDrawable getDrawable() {
        return mFingerprintDrawable;
    }

    @Override
    void onIlluminationStarting() {
        setVisibility(View.INVISIBLE);
    }

    @Override
    void onIlluminationStopped() {
        setVisibility(View.VISIBLE);
    }

    @Override
    public boolean dozeTimeTick() {
        mFingerprintDrawable.dozeTimeTick();
        return true;
    }

    void requestUdfps(boolean request, int color) {
        if (request) {
            mUdfpsRequestedColor = color;
        } else {
            mUdfpsRequestedColor = -1;
        }
        mUdfpsRequested = request;
        updateColor();
    }

    void setStatusBarState(int statusBarState) {
        mStatusBarState = statusBarState;
        updateColor();
    }

    void updateColor() {
        mFingerprintView.setAlpha(1f);
        mFingerprintDrawable.setLockScreenColor(getColor());
    }

    private int getColor() {
        if (mUdfpsRequested && mUdfpsRequestedColor != -1) {
            return mUdfpsRequestedColor;
        } else {
            return mWallpaperTextColor;
        }
    }

    /**
     * @param alpha between 0 and 255
     */
    void setUnpausedAlpha(int alpha) {
        mAlpha = alpha;
        updateAlpha();
    }

    @Override
    int calculateAlpha() {
        if (mPauseAuth) {
            return 0;
        }
        return mAlpha;
    }

    void onDozeAmountChanged(float linear, float eased) {
        mFingerprintDrawable.onDozeAmountChanged(linear, eased);
    }

    void animateHint() {
        mFingerprintDrawable.animateHint();
    }

    /**
     * Animates in the bg protection circle behind the fp icon to highlight the icon.
     */
    void animateUdfpsBouncer(Runnable onEndAnimation) {
        if (mBgProtection.getVisibility() == View.VISIBLE && mBgProtection.getAlpha() == 1f) {
            // already fully highlighted, don't re-animate
            return;
        }

        if (mAnimatorSet != null) {
            mAnimatorSet.cancel();
        }
        ValueAnimator fpIconAnim;
        if (isShadeLocked()) {
            // set color and fade in since we weren't showing before
            mFingerprintDrawable.setLockScreenColor(mTextColorPrimary);
            fpIconAnim = ObjectAnimator.ofFloat(mFingerprintView, View.ALPHA, 0f, 1f);
        } else {
            // update icon color
            fpIconAnim = new ValueAnimator();
            fpIconAnim.setIntValues(getColor(), mTextColorPrimary);
            fpIconAnim.setEvaluator(new ArgbEvaluator());
            fpIconAnim.addUpdateListener(valueAnimator -> mFingerprintDrawable.setLockScreenColor(
                    (Integer) valueAnimator.getAnimatedValue()));
        }

        mAnimatorSet = new AnimatorSet();
        mAnimatorSet.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mAnimatorSet.setDuration(500);
        mAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mBgProtection.setVisibility(View.VISIBLE);
            }
        });

        mAnimatorSet.playTogether(
                ObjectAnimator.ofFloat(mBgProtection, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_X, 0f, 1f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_Y, 0f, 1f),
                fpIconAnim);
        mAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onEndAnimation != null) {
                    onEndAnimation.run();
                }
            }
        });
        mAnimatorSet.start();
    }

    private boolean isShadeLocked() {
        return mStatusBarState == StatusBarState.SHADE_LOCKED;
    }

    /**
     * Animates out the bg protection circle behind the fp icon to unhighlight the icon.
     */
    void animateAwayUdfpsBouncer(@Nullable Runnable onEndAnimation) {
        if (mBgProtection.getVisibility() == View.GONE) {
            // already hidden
            return;
        }

        if (mAnimatorSet != null) {
            mAnimatorSet.cancel();
        }
        ValueAnimator fpIconAnim;
        if (isShadeLocked()) {
            // fade out
            fpIconAnim = ObjectAnimator.ofFloat(mFingerprintView, View.ALPHA, 1f, 0f);
        } else {
            // update icon color
            fpIconAnim = new ValueAnimator();
            fpIconAnim.setIntValues(mTextColorPrimary, getColor());
            fpIconAnim.setEvaluator(new ArgbEvaluator());
            fpIconAnim.addUpdateListener(valueAnimator -> mFingerprintDrawable.setLockScreenColor(
                    (Integer) valueAnimator.getAnimatedValue()));
        }

        mAnimatorSet = new AnimatorSet();
        mAnimatorSet.playTogether(
                ObjectAnimator.ofFloat(mBgProtection, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_X, 1f, 0f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_Y, 1f, 0f),
                fpIconAnim);
        mAnimatorSet.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mAnimatorSet.setDuration(500);

        mAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBgProtection.setVisibility(View.GONE);
                if (onEndAnimation != null) {
                    onEndAnimation.run();
                }
            }
        });
        mAnimatorSet.start();
    }

    boolean isAnimating() {
        return mAnimatorSet != null && mAnimatorSet.isRunning();
    }
}
