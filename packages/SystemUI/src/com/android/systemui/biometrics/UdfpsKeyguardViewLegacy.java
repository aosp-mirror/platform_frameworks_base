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
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.asynclayoutinflater.view.AsyncLayoutInflater;

import com.android.app.animation.Interpolators;
import com.android.settingslib.Utils;
import com.android.systemui.res.R;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * View corresponding with udfps_keyguard_view_legacy.xml
 */
public class UdfpsKeyguardViewLegacy extends UdfpsAnimationView {
    private UdfpsDrawable mFingerprintDrawable; // placeholder
    private LottieAnimationView mAodFp;
    private LottieAnimationView mLockScreenFp;

    // used when highlighting fp icon:
    private int mTextColorPrimary;
    private ImageView mBgProtection;
    boolean mUdfpsRequested;

    private AnimatorSet mBackgroundInAnimator = new AnimatorSet();
    private int mAlpha; // 0-255
    private float mScaleFactor = 1;
    private Rect mSensorBounds = new Rect();

    // AOD anti-burn-in offsets
    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;
    private float mInterpolatedDarkAmount;
    private int mAnimationType = ANIMATION_NONE;
    private boolean mFullyInflated;
    private Runnable mOnFinishInflateRunnable;

    public UdfpsKeyguardViewLegacy(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mFingerprintDrawable = new UdfpsFpDrawable(context);

        mMaxBurnInOffsetX = context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x);
        mMaxBurnInOffsetY = context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);
    }

    /**
     * Inflate internal udfps view on a background thread and call the onFinishRunnable
     * when inflation is finished.
     */
    public void startIconAsyncInflate(Runnable onFinishInflate) {
        mOnFinishInflateRunnable = onFinishInflate;
        // inflate Lottie views on a background thread in case it takes a while to inflate
        AsyncLayoutInflater inflater = new AsyncLayoutInflater(mContext);
        inflater.inflate(R.layout.udfps_keyguard_view_internal, this,
                mLayoutInflaterFinishListener);
    }

    @Override
    public UdfpsDrawable getDrawable() {
        return mFingerprintDrawable;
    }

    @Override
    void onSensorRectUpdated(RectF bounds) {
        super.onSensorRectUpdated(bounds);
        bounds.round(this.mSensorBounds);
        postInvalidate();
    }

    @Override
    void onDisplayConfiguring() {
    }

    @Override
    void onDisplayUnconfigured() {
    }

    @Override
    public boolean dozeTimeTick() {
        updateBurnInOffsets();
        return true;
    }

    private void updateBurnInOffsets() {
        if (!mFullyInflated) {
            return;
        }

        // if we're animating from screen off, we can immediately place the icon in the
        // AoD-burn in location, else we need to translate the icon from LS => AoD.
        final float darkAmountForAnimation = mAnimationType == ANIMATE_APPEAR_ON_SCREEN_OFF
                ? 1f : mInterpolatedDarkAmount;
        final float burnInOffsetX = MathUtils.lerp(0f,
            getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                - mMaxBurnInOffsetX, darkAmountForAnimation);
        final float burnInOffsetY = MathUtils.lerp(0f,
            getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                - mMaxBurnInOffsetY, darkAmountForAnimation);
        final float burnInProgress = MathUtils.lerp(0f, getBurnInProgressOffset(),
                darkAmountForAnimation);

        if (mAnimationType == ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN && !mPauseAuth) {
            mLockScreenFp.setTranslationX(burnInOffsetX);
            mLockScreenFp.setTranslationY(burnInOffsetY);
            mBgProtection.setAlpha(1f - mInterpolatedDarkAmount);
            mLockScreenFp.setAlpha(1f - mInterpolatedDarkAmount);
        } else if (darkAmountForAnimation == 0f) {
            // we're on the lockscreen and should use mAlpha (changes based on shade expansion)
            mLockScreenFp.setTranslationX(0);
            mLockScreenFp.setTranslationY(0);
            mBgProtection.setAlpha(mAlpha / 255f);
            mLockScreenFp.setAlpha(mAlpha / 255f);
        } else {
            mBgProtection.setAlpha(0f);
            mLockScreenFp.setAlpha(0f);
        }
        mLockScreenFp.setProgress(1f - mInterpolatedDarkAmount);

        mAodFp.setTranslationX(burnInOffsetX);
        mAodFp.setTranslationY(burnInOffsetY);
        mAodFp.setProgress(burnInProgress);
        mAodFp.setAlpha(mInterpolatedDarkAmount);

        // done animating
        final boolean doneAnimatingBetweenAodAndLS =
                mAnimationType == ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN
                        && (mInterpolatedDarkAmount == 0f || mInterpolatedDarkAmount == 1f);
        final boolean doneAnimatingUnlockedScreenOff =
                mAnimationType == ANIMATE_APPEAR_ON_SCREEN_OFF
                        && (mInterpolatedDarkAmount == 1f);
        if (doneAnimatingBetweenAodAndLS || doneAnimatingUnlockedScreenOff) {
            mAnimationType = ANIMATION_NONE;
        }
    }

    void requestUdfps(boolean request, int color) {
        mUdfpsRequested = request;
    }

    void updateColor() {
        if (!mFullyInflated) {
            return;
        }

        mTextColorPrimary = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.materialColorOnSurface);
        final int backgroundColor = Utils.getColorAttrDefaultColor(getContext(),
                com.android.internal.R.attr.materialColorSurfaceContainerHigh);
        mBgProtection.setImageTintList(ColorStateList.valueOf(backgroundColor));
        mLockScreenFp.invalidate(); // updated with a valueCallback
    }

    void setScaleFactor(float scale) {
        mScaleFactor = scale;
    }

    void updatePadding() {
        if (mLockScreenFp == null || mAodFp == null) {
            return;
        }

        final int defaultPaddingPx =
                getResources().getDimensionPixelSize(R.dimen.lock_icon_padding);
        final int padding = (int) (defaultPaddingPx * mScaleFactor);
        mLockScreenFp.setPadding(padding, padding, padding, padding);
        mAodFp.setPadding(padding, padding, padding, padding);
    }

    /**
     * @param alpha between 0 and 255
     */
    void setUnpausedAlpha(int alpha) {
        mAlpha = alpha;
        updateAlpha();
    }

    /**
     * @return alpha between 0 and 255
     */
    int getUnpausedAlpha() {
        return mAlpha;
    }

    @Override
    protected int updateAlpha() {
        int alpha = super.updateAlpha();
        updateBurnInOffsets();
        return alpha;
    }

    @Override
    int calculateAlpha() {
        if (mPauseAuth) {
            return 0;
        }
        return mAlpha;
    }

    static final int ANIMATION_NONE = 0;
    static final int ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN = 1;
    static final int ANIMATE_APPEAR_ON_SCREEN_OFF = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ANIMATION_NONE, ANIMATION_BETWEEN_AOD_AND_LOCKSCREEN, ANIMATE_APPEAR_ON_SCREEN_OFF})
    private @interface AnimationType {}

    void onDozeAmountChanged(float linear, float eased, @AnimationType int animationType) {
        mAnimationType = animationType;
        mInterpolatedDarkAmount = eased;
        updateAlpha();
    }

    void updateSensorLocation(@NonNull Rect sensorBounds) {
        mSensorBounds.set(sensorBounds);
    }

    /**
     * Animates in the bg protection circle behind the fp icon to highlight the icon.
     */
    void animateInUdfpsBouncer(Runnable onEndAnimation) {
        if (mBackgroundInAnimator.isRunning() || !mFullyInflated) {
            // already animating in or not yet inflated
            return;
        }

        // fade in and scale up
        mBackgroundInAnimator = new AnimatorSet();
        mBackgroundInAnimator.playTogether(
                ObjectAnimator.ofFloat(mBgProtection, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_X, 0f, 1f),
                ObjectAnimator.ofFloat(mBgProtection, View.SCALE_Y, 0f, 1f));
        mBackgroundInAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mBackgroundInAnimator.setDuration(500);
        mBackgroundInAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onEndAnimation != null) {
                    onEndAnimation.run();
                }
            }
        });
        mBackgroundInAnimator.start();
    }

    /**
     * Print debugging information for this class.
     */
    public void dump(PrintWriter pw) {
        pw.println("UdfpsKeyguardView (" + this + ")");
        pw.println("    mPauseAuth=" + mPauseAuth);
        pw.println("    mUnpausedAlpha=" + getUnpausedAlpha());
        pw.println("    mUdfpsRequested=" + mUdfpsRequested);
        pw.println("    mInterpolatedDarkAmount=" + mInterpolatedDarkAmount);
        pw.println("    mAnimationType=" + mAnimationType);
    }

    private final AsyncLayoutInflater.OnInflateFinishedListener mLayoutInflaterFinishListener =
            new AsyncLayoutInflater.OnInflateFinishedListener() {
                @Override
                public void onInflateFinished(View view, int resid, ViewGroup parent) {
                    mFullyInflated = true;
                    mAodFp = view.findViewById(R.id.udfps_aod_fp);
                    mLockScreenFp = view.findViewById(R.id.udfps_lockscreen_fp);
                    mBgProtection = view.findViewById(R.id.udfps_keyguard_fp_bg);

                    updatePadding();
                    updateColor();
                    updateAlpha();

                    final LayoutParams lp = (LayoutParams) view.getLayoutParams();
                    lp.width = mSensorBounds.width();
                    lp.height = mSensorBounds.height();
                    RectF relativeToView = getBoundsRelativeToView(new RectF(mSensorBounds));
                    lp.setMarginsRelative((int) relativeToView.left, (int) relativeToView.top,
                            (int) relativeToView.right, (int) relativeToView.bottom);
                    parent.addView(view, lp);

                    // requires call to invalidate to update the color
                    mLockScreenFp.addValueCallback(new KeyPath("**"), LottieProperty.COLOR_FILTER,
                            frameInfo -> new PorterDuffColorFilter(mTextColorPrimary,
                                    PorterDuff.Mode.SRC_ATOP));
                    mOnFinishInflateRunnable.run();
                }
            };
}
