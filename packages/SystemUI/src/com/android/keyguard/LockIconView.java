/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.android.settingslib.Utils;
import com.android.systemui.Dumpable;
import com.android.systemui.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A view positioned under the notification shade.
 */
public class LockIconView extends FrameLayout implements Dumpable {
    @NonNull private final RectF mSensorRect;
    @NonNull private PointF mLockIconCenter = new PointF(0f, 0f);
    private int mRadius;

    private ImageView mLockIcon;
    private ImageView mUnlockBgView;

    private AnimatorSet mBgAnimator;
    private int mLockIconColor;
    private int mUnlockStartColor;
    private int mUnlockEndColor;

    public LockIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSensorRect = new RectF();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mLockIcon = findViewById(R.id.lock_icon);
        mUnlockBgView = findViewById(R.id.lock_icon_bg);
    }

    void updateColor() {
        mLockIconColor = Utils.getColorAttrDefaultColor(getContext(),
            R.attr.wallpaperTextColorAccent);
        mUnlockStartColor = mLockIconColor;
        mUnlockEndColor = Utils.getColorAttrDefaultColor(getContext(),
            android.R.attr.textColorPrimary);
        mUnlockBgView.setBackground(getContext().getDrawable(R.drawable.fingerprint_bg));
    }

    void setImageDrawable(Drawable drawable) {
        mLockIcon.setImageDrawable(drawable);
    }

    void hideBg() {
        mUnlockBgView.setVisibility(View.INVISIBLE);
        mLockIcon.setImageTintList(ColorStateList.valueOf(mLockIconColor));
    }

    void animateBg() {
        ValueAnimator bgAlphaAnimator = ObjectAnimator.ofFloat(mUnlockBgView, View.ALPHA, 0f, 1f);
        bgAlphaAnimator.setDuration(133);

        Interpolator interpolator = new PathInterpolator(0f, 0f, 0f, 1f);
        Animator scaleXAnimator = ObjectAnimator.ofFloat(mUnlockBgView, View.SCALE_X, .9f, 1f);
        scaleXAnimator.setInterpolator(interpolator);
        scaleXAnimator.setDuration(300);
        Animator scaleYAnimator = ObjectAnimator.ofFloat(mUnlockBgView, View.SCALE_Y, .9f, 1f);
        scaleYAnimator.setDuration(300);
        scaleYAnimator.setInterpolator(interpolator);

        ValueAnimator lockIconColorAnimator =
                ValueAnimator.ofObject(new ArgbEvaluator(), mUnlockStartColor, mUnlockEndColor);
        lockIconColorAnimator.addUpdateListener(
                animation -> mLockIcon.setImageTintList(
                    ColorStateList.valueOf((int) animation.getAnimatedValue())));
        lockIconColorAnimator.setDuration(150);

        if (mBgAnimator != null) {
            if (mBgAnimator.isRunning()) {
                return;
            }
            mBgAnimator.cancel();
        }
        mBgAnimator = new AnimatorSet();
        mBgAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mBgAnimator = null;
            }
        });
        mBgAnimator.playTogether(
                bgAlphaAnimator,
                scaleYAnimator,
                scaleXAnimator,
                lockIconColorAnimator);
        mBgAnimator.setStartDelay(167);
        mUnlockBgView.setAlpha(0f);
        mUnlockBgView.setScaleX(0);
        mUnlockBgView.setScaleY(0);
        mUnlockBgView.setVisibility(View.VISIBLE);

        mBgAnimator.start();
    }

    void setCenterLocation(@NonNull PointF center, int radius) {
        mLockIconCenter = center;
        mRadius = radius;

        // mSensorProps coordinates assume portrait mode which is OK b/c the keyguard is always in
        // portrait.
        mSensorRect.set(mLockIconCenter.x - mRadius,
                mLockIconCenter.y - mRadius,
                mLockIconCenter.x + mRadius,
                mLockIconCenter.y + mRadius);

        setX(mSensorRect.left);
        setY(mSensorRect.top);
        setLayoutParams(new FrameLayout.LayoutParams(
                (int) (mSensorRect.right - mSensorRect.left),
                (int) (mSensorRect.bottom - mSensorRect.top)));
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    float getLocationTop() {
        return mLockIconCenter.y - mRadius;
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("Center in px (x, y)= (" + mLockIconCenter.x + ", " + mLockIconCenter.y + ")");
        pw.println("Radius in pixels: " + mRadius);
    }
}
