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
 * limitations under the License
 */

package com.android.systemui.assist;

import com.android.systemui.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AnimationUtils;

/**
 * Visually discloses that contextual data was provided to an assistant.
 */
public class AssistDisclosure {
    private final Context mContext;
    private final WindowManager mWm;
    private final Handler mHandler;

    private AssistDisclosureView mView;
    private boolean mViewAdded;

    public AssistDisclosure(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mWm = mContext.getSystemService(WindowManager.class);
    }

    public void postShow() {
        mHandler.removeCallbacks(mShowRunnable);
        mHandler.post(mShowRunnable);
    }

    private void show() {
        if (mView == null) {
            mView = new AssistDisclosureView(mContext);
        }
        if (!mViewAdded) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("AssistDisclosure");

            mWm.addView(mView, lp);
            mViewAdded = true;
        }
    }

    private void hide() {
        if (mViewAdded) {
            mWm.removeView(mView);
            mViewAdded = false;
        }
    }

    private Runnable mShowRunnable = new Runnable() {
        @Override
        public void run() {
            show();
        }
    };

    private class AssistDisclosureView extends View
            implements ValueAnimator.AnimatorUpdateListener {

        public static final int TRACING_ANIMATION_DURATION = 600;
        public static final int ALPHA_IN_ANIMATION_DURATION = 450;
        public static final int ALPHA_OUT_ANIMATION_DURATION = 400;

        private float mThickness;
        private float mShadowThickness;
        private final Paint mPaint = new Paint();
        private final Paint mShadowPaint = new Paint();

        private final ValueAnimator mTracingAnimator;
        private final ValueAnimator mAlphaOutAnimator;
        private final ValueAnimator mAlphaInAnimator;
        private final AnimatorSet mAnimator;

        private float mTracingProgress = 0;
        private int mAlpha = 0;

        public AssistDisclosureView(Context context) {
            super(context);

            mTracingAnimator = ValueAnimator.ofFloat(0, 1).setDuration(TRACING_ANIMATION_DURATION);
            mTracingAnimator.addUpdateListener(this);
            mTracingAnimator.setInterpolator(AnimationUtils.loadInterpolator(mContext,
                    R.interpolator.assist_disclosure_trace));
            mAlphaInAnimator = ValueAnimator.ofInt(0, 255).setDuration(ALPHA_IN_ANIMATION_DURATION);
            mAlphaInAnimator.addUpdateListener(this);
            mAlphaInAnimator.setInterpolator(AnimationUtils.loadInterpolator(mContext,
                    android.R.interpolator.fast_out_slow_in));
            mAlphaOutAnimator = ValueAnimator.ofInt(255, 0).setDuration(
                    ALPHA_OUT_ANIMATION_DURATION);
            mAlphaOutAnimator.addUpdateListener(this);
            mAlphaOutAnimator.setInterpolator(AnimationUtils.loadInterpolator(mContext,
                    android.R.interpolator.fast_out_linear_in));
            mAnimator = new AnimatorSet();
            mAnimator.play(mAlphaInAnimator).with(mTracingAnimator);
            mAnimator.play(mAlphaInAnimator).before(mAlphaOutAnimator);
            mAnimator.addListener(new AnimatorListenerAdapter() {
                boolean mCancelled;

                @Override
                public void onAnimationStart(Animator animation) {
                    mCancelled = false;
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCancelled) {
                        hide();
                    }
                }
            });

            PorterDuffXfermode srcMode = new PorterDuffXfermode(PorterDuff.Mode.SRC);
            mPaint.setColor(Color.WHITE);
            mPaint.setXfermode(srcMode);
            mShadowPaint.setColor(Color.DKGRAY);
            mShadowPaint.setXfermode(srcMode);

            mThickness = getResources().getDimension(R.dimen.assist_disclosure_thickness);
            mShadowThickness = getResources().getDimension(
                    R.dimen.assist_disclosure_shadow_thickness);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();

            startAnimation();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();

            mAnimator.cancel();

            mTracingProgress = 0;
            mAlpha = 0;
        }

        private void startAnimation() {
            mAnimator.cancel();
            mAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            mPaint.setAlpha(mAlpha);
            mShadowPaint.setAlpha(mAlpha / 4);

            drawGeometry(canvas, mShadowPaint, mShadowThickness);
            drawGeometry(canvas, mPaint, 0);
        }

        private void drawGeometry(Canvas canvas, Paint paint, float padding) {
            final int width = getWidth();
            final int height = getHeight();
            float thickness = mThickness;
            final float pixelProgress = mTracingProgress * (width + height - 2 * thickness);

            float bottomProgress = Math.min(pixelProgress, width / 2f);
            if (bottomProgress > 0) {
                drawBeam(canvas,
                        width / 2f - bottomProgress,
                        height - thickness,
                        width / 2f + bottomProgress,
                        height, paint, padding);
            }

            float sideProgress = Math.min(pixelProgress - bottomProgress, height - thickness);
            if (sideProgress > 0) {
                drawBeam(canvas,
                        0,
                        (height - thickness) - sideProgress,
                        thickness,
                        height - thickness, paint, padding);
                drawBeam(canvas,
                        width - thickness,
                        (height - thickness) - sideProgress,
                        width,
                        height - thickness, paint, padding);
            }

            float topProgress = Math.min(pixelProgress - bottomProgress - sideProgress,
                    width / 2 - thickness);
            if (sideProgress > 0 && topProgress > 0) {
                drawBeam(canvas,
                        thickness,
                        0,
                        thickness + topProgress,
                        thickness, paint, padding);
                drawBeam(canvas,
                        (width - thickness) - topProgress,
                        0,
                        width - thickness,
                        thickness, paint, padding);
            }
        }

        private void drawBeam(Canvas canvas, float left, float top, float right, float bottom,
                Paint paint, float padding) {
            canvas.drawRect(left - padding,
                    top - padding,
                    right + padding,
                    bottom + padding,
                    paint);
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            if (animation == mAlphaOutAnimator) {
                mAlpha = (int) mAlphaOutAnimator.getAnimatedValue();
            } else if (animation == mAlphaInAnimator) {
                mAlpha = (int) mAlphaInAnimator.getAnimatedValue();
            } else if (animation == mTracingAnimator) {
                mTracingProgress = (float) mTracingAnimator.getAnimatedValue();
            }
            invalidate();
        }
    }
}
