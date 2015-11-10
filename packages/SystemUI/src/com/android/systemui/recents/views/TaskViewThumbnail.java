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
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
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
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import com.android.systemui.R;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;


/**
 * The task thumbnail view.  It implements an image view that allows for animating the dim and
 * alpha of the thumbnail image.
 */
public class TaskViewThumbnail extends View {

    RecentsConfiguration mConfig;

    // Drawing
    float mDimAlpha;
    Matrix mScaleMatrix = new Matrix();
    Paint mDrawPaint = new Paint();
    RectF mBitmapRect = new RectF();
    RectF mLayoutRect = new RectF();
    BitmapShader mBitmapShader;
    LightingColorFilter mLightingColorFilter = new LightingColorFilter(0xffffffff, 0);

    // Thumbnail alpha
    float mThumbnailAlpha;
    ValueAnimator mThumbnailAlphaAnimator;
    ValueAnimator.AnimatorUpdateListener mThumbnailAlphaUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mThumbnailAlpha = (float) animation.getAnimatedValue();
            updateThumbnailPaintFilter();
        }
    };

    // Task bar clipping, the top of this thumbnail can be clipped against the opaque header
    // bar that overlaps this thumbnail
    View mTaskBar;
    Rect mClipRect = new Rect();

    // Visibility optimization, if the thumbnail height is less than the height of the header
    // bar for the task view, then just mark this thumbnail view as invisible
    boolean mInvisible;

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
        mDrawPaint.setColorFilter(mLightingColorFilter);
        mDrawPaint.setFilterBitmap(true);
        mDrawPaint.setAntiAlias(true);
    }

    @Override
    protected void onFinishInflate() {
        mThumbnailAlpha = mConfig.taskViewThumbnailAlpha;
        updateThumbnailPaintFilter();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            mLayoutRect.set(0, 0, getWidth(), getHeight());
            updateThumbnailScale();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mInvisible) {
            return;
        }
        // Draw the thumbnail with the rounded corners
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(),
                mConfig.taskViewRoundedCornerRadiusPx,
                mConfig.taskViewRoundedCornerRadiusPx, mDrawPaint);
    }

    /** Sets the thumbnail to a given bitmap. */
    void setThumbnail(Bitmap bm) {
        if (bm != null) {
            mBitmapShader = new BitmapShader(bm, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP);
            mDrawPaint.setShader(mBitmapShader);
            mBitmapRect.set(0, 0, bm.getWidth(), bm.getHeight());
            updateThumbnailScale();
        } else {
            mBitmapShader = null;
            mDrawPaint.setShader(null);
        }
        updateThumbnailPaintFilter();
    }

    /** Updates the paint to draw the thumbnail. */
    void updateThumbnailPaintFilter() {
        if (mInvisible) {
            return;
        }
        int mul = (int) ((1.0f - mDimAlpha) * mThumbnailAlpha * 255);
        int add = (int) ((1.0f - mDimAlpha) * (1 - mThumbnailAlpha) * 255);
        if (mBitmapShader != null) {
            mLightingColorFilter.setColorMultiply(Color.argb(255, mul, mul, mul));
            mLightingColorFilter.setColorAdd(Color.argb(0, add, add, add));
            mDrawPaint.setColorFilter(mLightingColorFilter);
            mDrawPaint.setColor(0xffffffff);
        } else {
            int grey = mul + add;
            mDrawPaint.setColorFilter(null);
            mDrawPaint.setColor(Color.argb(255, grey, grey, grey));
        }
        invalidate();
    }

    /** Updates the thumbnail shader's scale transform. */
    void updateThumbnailScale() {
        if (mBitmapShader != null) {
            mScaleMatrix.setRectToRect(mBitmapRect, mLayoutRect, Matrix.ScaleToFit.FILL);
            mBitmapShader.setLocalMatrix(mScaleMatrix);
        }
    }

    /** Updates the clip rect based on the given task bar. */
    void updateClipToTaskBar(View taskBar) {
        mTaskBar = taskBar;
        int top = (int) Math.max(0, taskBar.getTranslationY() +
                taskBar.getMeasuredHeight() - 1);
        mClipRect.set(0, top, getMeasuredWidth(), getMeasuredHeight());
        setClipBounds(mClipRect);
    }

    /** Updates the visibility of the the thumbnail. */
    void updateThumbnailVisibility(int clipBottom) {
        boolean invisible = mTaskBar != null && (getHeight() - clipBottom) <= mTaskBar.getHeight();
        if (invisible != mInvisible) {
            mInvisible = invisible;
            if (!mInvisible) {
                updateThumbnailPaintFilter();
            }
            invalidate();
        }
    }

    /**
     * Sets the dim alpha, only used when we are not using hardware layers.
     * (see RecentsConfiguration.useHardwareLayers)
     */
    public void setDimAlpha(float dimAlpha) {
        mDimAlpha = dimAlpha;
        updateThumbnailPaintFilter();
    }

    /** Binds the thumbnail view to the task */
    void rebindToTask(Task t) {
        if (t.thumbnail != null) {
            setThumbnail(t.thumbnail);
        } else {
            setThumbnail(null);
        }
    }

    /** Unbinds the thumbnail view from the task */
    void unbindFromTask() {
        setThumbnail(null);
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

    /**
     * Prepares for the enter recents animation, this gets called before the the view
     * is first visible and will be followed by a startEnterRecentsAnimation() call.
     */
    void prepareEnterRecentsAnimation(boolean isTaskViewLaunchTargetTask) {
        if (isTaskViewLaunchTargetTask) {
            mThumbnailAlpha = 1f;
        } else {
            mThumbnailAlpha = mConfig.taskViewThumbnailAlpha;
        }
        updateThumbnailPaintFilter();
    }

    /** Animates this task thumbnail as it enters Recents. */
    void startEnterRecentsAnimation(int delay, Runnable postAnimRunnable) {
        startFadeAnimation(mConfig.taskViewThumbnailAlpha, delay,
                mConfig.taskViewEnterFromAppDuration, postAnimRunnable);
    }

    /** Animates this task thumbnail as it exits Recents. */
    void startLaunchTaskAnimation(Runnable postAnimRunnable) {
        startFadeAnimation(1f, 0, mConfig.taskViewExitToAppDuration, postAnimRunnable);
    }

    /** Starts a new thumbnail alpha animation. */
    void startFadeAnimation(float finalAlpha, int delay, int duration, final Runnable postAnimRunnable) {
        Utilities.cancelAnimationWithoutCallbacks(mThumbnailAlphaAnimator);
        mThumbnailAlphaAnimator = ValueAnimator.ofFloat(mThumbnailAlpha, finalAlpha);
        mThumbnailAlphaAnimator.setStartDelay(delay);
        mThumbnailAlphaAnimator.setDuration(duration);
        mThumbnailAlphaAnimator.setInterpolator(mConfig.fastOutSlowInInterpolator);
        mThumbnailAlphaAnimator.addUpdateListener(mThumbnailAlphaUpdateListener);
        if (postAnimRunnable != null) {
            mThumbnailAlphaAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    postAnimRunnable.run();
                }
            });
        }
        mThumbnailAlphaAnimator.start();
    }
}
