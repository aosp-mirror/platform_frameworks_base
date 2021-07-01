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

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInProgressOffset;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.StatusBarState;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;
/**
 * View corresponding with udfps_keyguard_view.xml
 */
public class UdfpsKeyguardView extends UdfpsAnimationView {
    private UdfpsDrawable mFingerprintDrawable; // placeholder
    private LottieAnimationView mAodFp;
    private LottieAnimationView mLockScreenFp;
    private int mUdfpsBouncerColor;
    private int mWallpaperTextColor;
    private int mStatusBarState;

    // used when highlighting fp icon:
    private int mTextColorPrimary;
    private ImageView mBgProtection;
    boolean mUdfpsRequested;
    int mUdfpsRequestedColor;

    private AnimatorSet mAnimatorSet;
    private int mAlpha; // 0-255

    // AOD anti-burn-in offsets
    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;
    private float mBurnInOffsetX;
    private float mBurnInOffsetY;
    private float mBurnInProgress;
    private float mInterpolatedDarkAmount;

    private ValueAnimator mHintAnimator;

    public UdfpsKeyguardView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mFingerprintDrawable = new UdfpsFpDrawable(context);

        mMaxBurnInOffsetX = context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x);
        mMaxBurnInOffsetY = context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAodFp = findViewById(R.id.udfps_aod_fp);
        mLockScreenFp = findViewById(R.id.udfps_lockscreen_fp);

        mBgProtection = findViewById(R.id.udfps_keyguard_fp_bg);

        mWallpaperTextColor = Utils.getColorAttrDefaultColor(mContext,
                R.attr.wallpaperTextColorAccent);
        mTextColorPrimary = Utils.getColorAttrDefaultColor(mContext,
                android.R.attr.textColorPrimary);

        // requires call to invalidate to update the color (see #updateColor)
        mLockScreenFp.addValueCallback(
                new KeyPath("**"), LottieProperty.COLOR_FILTER,
                frameInfo -> new PorterDuffColorFilter(getColor(), PorterDuff.Mode.SRC_ATOP)
        );
        mUdfpsRequested = false;

        mHintAnimator = ObjectAnimator.ofFloat(mLockScreenFp, "progress", 1f, 0f, 1f);
        mHintAnimator.setDuration(4000);
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
        updateBurnInOffsets();
        return true;
    }

    private void updateBurnInOffsets() {
        mBurnInOffsetX = MathUtils.lerp(0f,
            getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                - mMaxBurnInOffsetX, mInterpolatedDarkAmount);
        mBurnInOffsetY = MathUtils.lerp(0f,
            getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                - mMaxBurnInOffsetY, mInterpolatedDarkAmount);
        mBurnInProgress = MathUtils.lerp(0f, getBurnInProgressOffset(), mInterpolatedDarkAmount);

        mAodFp.setTranslationX(mBurnInOffsetX);
        mAodFp.setTranslationY(mBurnInOffsetY);
        mAodFp.setProgress(mBurnInProgress);

        mLockScreenFp.setTranslationX(mBurnInOffsetX);
        mLockScreenFp.setTranslationY(mBurnInOffsetY);
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
    }

    void updateColor() {
        mLockScreenFp.invalidate();
    }

    private boolean showingUdfpsBouncer() {
        return mBgProtection.getVisibility() == View.VISIBLE;
    }


    private int getColor() {
        if (isUdfpsColorRequested()) {
            return mUdfpsRequestedColor;
        } else if (showingUdfpsBouncer()) {
            return mUdfpsBouncerColor;
        } else {
            return mWallpaperTextColor;
        }
    }

    private boolean isUdfpsColorRequested() {
        return mUdfpsRequested && mUdfpsRequestedColor != -1;
    }

    /**
     * @param alpha between 0 and 255
     */
    void setUnpausedAlpha(int alpha) {
        mAlpha = alpha;
        updateAlpha();
    }

    @Override
    protected int updateAlpha() {
        int alpha = super.updateAlpha();
        mLockScreenFp.setImageAlpha(alpha);
        return alpha;
    }

    @Override
    int calculateAlpha() {
        if (mPauseAuth) {
            return 0;
        }
        return mAlpha;
    }

    void onDozeAmountChanged(float linear, float eased) {
        mHintAnimator.cancel();
        mInterpolatedDarkAmount = eased;
        updateBurnInOffsets();
        mLockScreenFp.setProgress(1f - mInterpolatedDarkAmount);
        mAodFp.setAlpha(mInterpolatedDarkAmount);

        if (linear == 1f) {
            mLockScreenFp.setVisibility(View.INVISIBLE);
        } else {
            mLockScreenFp.setVisibility(View.VISIBLE);
        }
    }

    void animateHint() {
        if (!isShadeLocked() && !mUdfpsRequested && mAlpha == 255
                && mLockScreenFp.isVisibleToUser()) {
            mHintAnimator.start();
        }
    }

    /**
     * Animates in the bg protection circle behind the fp icon to highlight the icon.
     */
    void animateUdfpsBouncer(Runnable onEndAnimation) {
        if (showingUdfpsBouncer() && mBgProtection.getAlpha() == 1f) {
            // already fully highlighted, don't re-animate
            return;
        }

        if (mAnimatorSet != null) {
            mAnimatorSet.cancel();
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

        ValueAnimator fpIconColorAnim;
        if (isShadeLocked()) {
            // set color and fade in since we weren't showing before
            mUdfpsBouncerColor = mTextColorPrimary;
            fpIconColorAnim = ValueAnimator.ofInt(0, 255);
            fpIconColorAnim.addUpdateListener(valueAnimator ->
                    mLockScreenFp.setImageAlpha((int) valueAnimator.getAnimatedValue()));
        } else {
            // update icon color
            fpIconColorAnim = new ValueAnimator();
            fpIconColorAnim.setIntValues(
                    isUdfpsColorRequested() ? mUdfpsRequestedColor : mWallpaperTextColor,
                    mTextColorPrimary);
            fpIconColorAnim.setEvaluator(ArgbEvaluator.getInstance());
            fpIconColorAnim.addUpdateListener(valueAnimator -> {
                mUdfpsBouncerColor = (int) valueAnimator.getAnimatedValue();
                updateColor();
            });
        }

        mAnimatorSet.playTogether(
                ObjectAnimator.ofFloat(mBgProtection, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_X, 0f, 1f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_Y, 0f, 1f),
                fpIconColorAnim);
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

    /**
     * Animates out the bg protection circle behind the fp icon to unhighlight the icon.
     */
    void animateAwayUdfpsBouncer(@Nullable Runnable onEndAnimation) {
        if (!showingUdfpsBouncer()) {
            // already hidden
            return;
        }

        if (mAnimatorSet != null) {
            mAnimatorSet.cancel();
        }

        ValueAnimator fpIconColorAnim;
        if (isShadeLocked()) {
            // fade out
            mUdfpsBouncerColor = mTextColorPrimary;
            fpIconColorAnim = ValueAnimator.ofInt(255, 0);
            fpIconColorAnim.addUpdateListener(valueAnimator ->
                    mLockScreenFp.setImageAlpha((int) valueAnimator.getAnimatedValue()));
        } else {
            // update icon color
            fpIconColorAnim = new ValueAnimator();
            fpIconColorAnim.setIntValues(
                    mTextColorPrimary,
                    isUdfpsColorRequested() ? mUdfpsRequestedColor : mWallpaperTextColor);
            fpIconColorAnim.setEvaluator(ArgbEvaluator.getInstance());
            fpIconColorAnim.addUpdateListener(valueAnimator -> {
                mUdfpsBouncerColor = (int) valueAnimator.getAnimatedValue();
                updateColor();
            });
        }

        mAnimatorSet = new AnimatorSet();
        mAnimatorSet.playTogether(
                ObjectAnimator.ofFloat(mBgProtection, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_X, 1f, 0f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_Y, 1f, 0f),
                fpIconColorAnim);
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

    private boolean isShadeLocked() {
        return mStatusBarState == StatusBarState.SHADE_LOCKED;
    }
}
