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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.MathUtils;

import androidx.annotation.NonNull;

import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.doze.DozeReceiver;

/**
 * UDFPS animations that should be shown when authenticating on keyguard.
 */
public class UdfpsKeyguardDrawable extends UdfpsDrawable implements DozeReceiver {

    private static final String TAG = "UdfpsAnimationKeyguard";
    private final int mAmbientDisplayColor;
    static final float DEFAULT_AOD_STROKE_WIDTH = 1f;

    @NonNull private final Context mContext;
    private int mLockScreenColor;

    // AOD anti-burn-in offsets
    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;
    private float mInterpolatedDarkAmount;
    private float mBurnInOffsetX;
    private float mBurnInOffsetY;

    private final ValueAnimator mHintAnimator = ValueAnimator.ofFloat(
            UdfpsKeyguardDrawable.DEFAULT_STROKE_WIDTH,
            .5f,
            UdfpsKeyguardDrawable.DEFAULT_STROKE_WIDTH);

    UdfpsKeyguardDrawable(@NonNull Context context) {
        super(context);
        mContext = context;

        mMaxBurnInOffsetX = context.getResources()
                .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x);
        mMaxBurnInOffsetY = context.getResources()
                .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);

        mHintAnimator.setDuration(2000);
        mHintAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mHintAnimator.addUpdateListener(anim -> setStrokeWidth((float) anim.getAnimatedValue()));

        mLockScreenColor = Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor);
        mAmbientDisplayColor = Color.WHITE;

        updateIcon();
    }

    private void updateIcon() {
        mBurnInOffsetX = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                        - mMaxBurnInOffsetX,
                mInterpolatedDarkAmount);
        mBurnInOffsetY = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                        - mMaxBurnInOffsetY,
                mInterpolatedDarkAmount);

        mFingerprintDrawable.setTint(ColorUtils.blendARGB(mLockScreenColor,
                mAmbientDisplayColor, mInterpolatedDarkAmount));
        setStrokeWidth(MathUtils.lerp(DEFAULT_STROKE_WIDTH, DEFAULT_AOD_STROKE_WIDTH,
                mInterpolatedDarkAmount));
        invalidateSelf();
    }

    @Override
    public void dozeTimeTick() {
        updateIcon();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (isIlluminationShowing()) {
            return;
        }
        canvas.save();
        canvas.translate(mBurnInOffsetX, mBurnInOffsetY);
        mFingerprintDrawable.draw(canvas);
        canvas.restore();
    }

    void animateHint() {
        mHintAnimator.start();
    }

    void onDozeAmountChanged(float linear, float eased) {
        mHintAnimator.cancel();
        mInterpolatedDarkAmount = eased;
        updateIcon();
    }

    void setLockScreenColor(int color) {
        if (mLockScreenColor == color) return;
        mLockScreenColor = color;
        updateIcon();
    }
}
