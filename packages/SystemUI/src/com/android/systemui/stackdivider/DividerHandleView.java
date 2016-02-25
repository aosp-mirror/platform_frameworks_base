/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Property;
import android.view.View;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

/**
 * View for the handle in the docked stack divider.
 */
public class DividerHandleView extends View {

    private final static Property<DividerHandleView, Integer> WIDTH_PROPERTY
            = new Property<DividerHandleView, Integer>(Integer.class, "width") {

        @Override
        public Integer get(DividerHandleView object) {
            return object.mCurrentWidth;
        }

        @Override
        public void set(DividerHandleView object, Integer value) {
            object.mCurrentWidth = value;
            object.invalidate();
        }
    };

    private final static Property<DividerHandleView, Integer> HEIGHT_PROPERTY
            = new Property<DividerHandleView, Integer>(Integer.class, "height") {

        @Override
        public Integer get(DividerHandleView object) {
            return object.mCurrentHeight;
        }

        @Override
        public void set(DividerHandleView object, Integer value) {
            object.mCurrentHeight = value;
            object.invalidate();
        }
    };

    private final Paint mPaint = new Paint();
    private final int mWidth;
    private final int mHeight;
    private final int mCircleDiameter;
    private int mCurrentWidth;
    private int mCurrentHeight;
    private AnimatorSet mAnimator;
    private boolean mTouching;

    public DividerHandleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setColor(getResources().getColor(R.color.docked_divider_handle, null));
        mPaint.setAntiAlias(true);
        mWidth = getResources().getDimensionPixelSize(R.dimen.docked_divider_handle_width);
        mHeight = getResources().getDimensionPixelSize(R.dimen.docked_divider_handle_height);
        mCurrentWidth = mWidth;
        mCurrentHeight = mHeight;
        mCircleDiameter = (mWidth + mHeight) / 3;
    }

    public void setTouching(boolean touching, boolean animate) {
        if (touching == mTouching) {
            return;
        }
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        if (!animate) {
            if (touching) {
                mCurrentWidth = mCircleDiameter;
                mCurrentHeight = mCircleDiameter;
            } else {
                mCurrentWidth = mWidth;
                mCurrentHeight = mHeight;
            }
            invalidate();
        } else {
            animateToTarget(touching ? mCircleDiameter : mWidth,
                    touching ? mCircleDiameter : mHeight, touching);
        }
        mTouching = touching;
    }

    private void animateToTarget(int targetWidth, int targetHeight, boolean touching) {
        ObjectAnimator widthAnimator = ObjectAnimator.ofInt(this, WIDTH_PROPERTY,
                mCurrentWidth, targetWidth);
        ObjectAnimator heightAnimator = ObjectAnimator.ofInt(this, HEIGHT_PROPERTY,
                mCurrentHeight, targetHeight);
        mAnimator = new AnimatorSet();
        mAnimator.playTogether(widthAnimator, heightAnimator);
        mAnimator.setDuration(touching
                ? DividerView.TOUCH_ANIMATION_DURATION
                : DividerView.TOUCH_RELEASE_ANIMATION_DURATION);
        mAnimator.setInterpolator(touching
                ? Interpolators.TOUCH_RESPONSE
                : Interpolators.FAST_OUT_SLOW_IN);
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimator = null;
            }
        });
        mAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int left = getWidth() / 2 - mCurrentWidth / 2;
        int top = getHeight() / 2 - mCurrentHeight / 2;
        int radius = Math.min(mCurrentWidth, mCurrentHeight) / 2;
        canvas.drawRoundRect(left, top, left + mCurrentWidth, top + mCurrentHeight,
                radius, radius, mPaint);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
