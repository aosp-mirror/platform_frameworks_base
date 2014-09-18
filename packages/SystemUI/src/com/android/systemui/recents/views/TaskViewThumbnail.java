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

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.model.Task;


/** The task thumbnail view */
public class TaskViewThumbnail extends View {

    private final int mCornerRadius;
    private final Matrix mScaleMatrix = new Matrix();
    RecentsConfiguration mConfig;

    // Task bar clipping
    Rect mClipRect = new Rect();
    Paint mDrawPaint = new Paint();
    LightingColorFilter mLightingColorFilter = new LightingColorFilter(0xffffffff, 0);
    private final RectF mBitmapRect = new RectF();
    private final RectF mLayoutRect = new RectF();
    private BitmapShader mBitmapShader;
    private float mBitmapAlpha;
    private float mDimAlpha;
    private View mTaskBar;
    private boolean mInvisible;
    private ValueAnimator mAlphaAnimator;
    private ValueAnimator.AnimatorUpdateListener mAlphaUpdateListener
            = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mBitmapAlpha = (float) animation.getAnimatedValue();
            updateFilter();
        }
    };

    public TaskViewThumbnail(Context context) {
        this(context, null);
    }

    public TaskViewThumbnail(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskViewThumbnail(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskViewThumbnail(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        mCornerRadius = mConfig.taskViewRoundedCornerRadiusPx;
        mDrawPaint.setColorFilter(mLightingColorFilter);
        mDrawPaint.setFilterBitmap(true);
        mDrawPaint.setAntiAlias(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mInvisible) {
            return;
        }
        canvas.drawRoundRect(0,
                0,
                getWidth(),
                getHeight(),
                mCornerRadius,
                mCornerRadius,
                mDrawPaint);
    }

    @Override
    protected void onFinishInflate() {
        mBitmapAlpha = 0.9f;
        updateFilter();
    }

    private void updateFilter() {
        if (mInvisible) {
            return;
        }
        int mul = (int) ((1.0f - mDimAlpha) * mBitmapAlpha * 255);
        int add = (int) ((1.0f - mDimAlpha) * (1 - mBitmapAlpha) * 255);
        if (mBitmapShader != null) {
            mLightingColorFilter.setColorMultiply(Color.argb(255, mul, mul, mul));
            mLightingColorFilter.setColorAdd(Color.argb(0, add, add, add));
            mDrawPaint.setColorFilter(mLightingColorFilter);
            mDrawPaint.setColor(0xffffffff);
        } else {
            mDrawPaint.setColorFilter(null);
            int grey = mul + add;
            mDrawPaint.setColor(Color.argb(255, grey, grey, grey));
        }
        invalidate();
    }

    /** Updates the clip rect based on the given task bar. */
    void enableTaskBarClip(View taskBar) {
        mTaskBar = taskBar;
        int top = (int) Math.max(0, taskBar.getTranslationY() +
                taskBar.getMeasuredHeight() - 1);
        mClipRect.set(0, top, getMeasuredWidth(), getMeasuredHeight());
        setClipBounds(mClipRect);
    }

    void updateVisibility(int clipBottom) {
        boolean invisible = mTaskBar != null && getHeight() - clipBottom < mTaskBar.getHeight();
        if (invisible != mInvisible) {
            mInvisible = invisible;
            if (!mInvisible) {
                updateFilter();
            }
            invalidate();
        }
    }

    /** Binds the thumbnail view to the screenshot. */
    boolean bindToScreenshot(Bitmap ss) {
        setImageBitmap(ss);
        return ss != null;
    }

    /** Unbinds the thumbnail view from the screenshot. */
    void unbindFromScreenshot() {
        setImageBitmap(null);
    }

    /** Binds the thumbnail view to the task */
    void rebindToTask(Task t) {
        if (t.thumbnail != null) {
            setImageBitmap(t.thumbnail);
        } else {
            setImageBitmap(null);
        }
    }

    public void setImageBitmap(Bitmap bm) {
        if (bm != null) {
            mBitmapShader = new BitmapShader(bm, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP);
            mDrawPaint.setShader(mBitmapShader);
            mBitmapRect.set(0, 0, bm.getWidth(), bm.getHeight());
            updateBitmapScale();
        } else {
            mBitmapShader = null;
            mDrawPaint.setShader(null);
        }
        updateFilter();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            mLayoutRect.set(0, 0, getWidth(), getHeight());
            updateBitmapScale();
        }
    }

    private void updateBitmapScale() {
        if (mBitmapShader != null) {
            mScaleMatrix.setRectToRect(mBitmapRect, mLayoutRect, Matrix.ScaleToFit.FILL);
            mBitmapShader.setLocalMatrix(mScaleMatrix);
        }
    }

    public void setDimAlpha(float dimAlpha) {
        mDimAlpha = dimAlpha;
        updateFilter();
    }

    /** Unbinds the thumbnail view from the task */
    void unbindFromTask() {
        setImageBitmap(null);
    }

    /** Handles focus changes. */
    void onFocusChanged(boolean focused) {
        if (focused) {
            if (Float.compare(getAlpha(), 1f) != 0) {
                startFadeAnimation(1f, 0, 150, null);
            }
        } else {
            if (Float.compare(getAlpha(), mConfig.taskViewThumbnailAlpha) != 0) {
                startFadeAnimation(mConfig.taskViewThumbnailAlpha, 0, 150, null);
            }
        }
    }

    /** Prepares for the enter recents animation. */
    void prepareEnterRecentsAnimation(boolean isTaskViewLaunchTargetTask) {
        if (isTaskViewLaunchTargetTask) {
            mBitmapAlpha = 1f;
        } else {
            mBitmapAlpha = mConfig.taskViewThumbnailAlpha;
        }
        updateFilter();
    }

    /** Animates this task thumbnail as it enters recents */
    void startEnterRecentsAnimation(int delay, Runnable postAnimRunnable) {
        startFadeAnimation(mConfig.taskViewThumbnailAlpha, delay,
                mConfig.taskBarEnterAnimDuration, postAnimRunnable);
    }

    /** Animates this task thumbnail as it exits recents */
    void startLaunchTaskAnimation(Runnable postAnimRunnable) {
        startFadeAnimation(1f, 0, mConfig.taskBarExitAnimDuration, postAnimRunnable);
    }

    /** Animates the thumbnail alpha. */
    void startFadeAnimation(float finalAlpha, int delay, int duration, final Runnable postAnimRunnable) {
        if (mAlphaAnimator != null) {
            mAlphaAnimator.cancel();
        }
        mAlphaAnimator = ValueAnimator.ofFloat(mBitmapAlpha, finalAlpha);
        mAlphaAnimator.addUpdateListener(mAlphaUpdateListener);
        mAlphaAnimator.setStartDelay(delay);
        mAlphaAnimator.setInterpolator(mConfig.fastOutSlowInInterpolator);
        mAlphaAnimator.setDuration(duration);
        mAlphaAnimator.start();
        if (postAnimRunnable != null) {
            mAlphaAnimator.addListener(new AnimatorListenerAdapter() {
                public boolean mCancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCancelled) {
                        postAnimRunnable.run();
                    }
                }
            });
        }
    }
}
