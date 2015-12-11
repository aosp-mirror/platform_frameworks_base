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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Property;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.systemui.R;
import com.android.systemui.recents.model.Task;


/**
 * The task thumbnail view.  It implements an image view that allows for animating the dim and
 * alpha of the thumbnail image.
 */
public class TaskViewThumbnail extends View {

    public static final Property<TaskViewThumbnail, Float> BITMAP_SCALE =
            new FloatProperty<TaskViewThumbnail>("bitmapScale") {
                @Override
                public void setValue(TaskViewThumbnail object, float scale) {
                    object.setBitmapScale(scale);
                }

                @Override
                public Float get(TaskViewThumbnail object) {
                    return object.getBitmapScale();
                }
            };

    // Drawing
    Rect mTaskViewRect = new Rect();
    int mCornerRadius;
    float mDimAlpha;
    Matrix mScaleMatrix = new Matrix();
    Paint mDrawPaint = new Paint();
    float mBitmapScale = 1f;
    BitmapShader mBitmapShader;
    LightingColorFilter mLightingColorFilter = new LightingColorFilter(0xffffffff, 0);

    Interpolator mFastOutSlowInInterpolator;

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
        mDrawPaint.setColorFilter(mLightingColorFilter);
        mDrawPaint.setFilterBitmap(true);
        mDrawPaint.setAntiAlias(true);
        mCornerRadius = getResources().getDimensionPixelSize(
                R.dimen.recents_task_view_rounded_corners_radius);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
    }

    /**
     * Called when the task view frame changes, allowing us to move the contents of the header
     * to match the frame changes.
     */
    public void onTaskViewSizeChanged(int width, int height) {
        mTaskViewRect.set(0, 0, width, height);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mInvisible) {
            return;
        }
        // Draw the thumbnail with the rounded corners
        canvas.drawRoundRect(0, 0, mTaskViewRect.width(), mTaskViewRect.height(),
                mCornerRadius,
                mCornerRadius, mDrawPaint);
    }

    /** Sets the thumbnail to a given bitmap. */
    void setThumbnail(Bitmap bm) {
        if (bm != null) {
            mBitmapShader = new BitmapShader(bm, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP);
            mDrawPaint.setShader(mBitmapShader);
        } else {
            mBitmapShader = null;
            mDrawPaint.setShader(null);
        }
    }

    /** Updates the paint to draw the thumbnail. */
    void updateThumbnailPaintFilter() {
        if (mInvisible) {
            return;
        }
        int mul = (int) ((1.0f - mDimAlpha) * 255);
        if (mBitmapShader != null) {
            mLightingColorFilter.setColorMultiply(Color.argb(255, mul, mul, mul));
            mDrawPaint.setColorFilter(mLightingColorFilter);
            mDrawPaint.setColor(0xffffffff);
        } else {
            int grey = mul;
            mDrawPaint.setColorFilter(null);
            mDrawPaint.setColor(Color.argb(255, grey, grey, grey));
        }
        invalidate();
    }

    /**
     * Sets the scale of the bitmap relative to this view.
     */
    public void setBitmapScale(float scale) {
        if (mBitmapShader != null) {
            mBitmapScale = scale;
            mScaleMatrix.setScale(mBitmapScale, mBitmapScale);
            mBitmapShader.setLocalMatrix(mScaleMatrix);
        }
        if (!mInvisible) {
            invalidate();
        }
    }

    public float getBitmapScale() {
        return mBitmapScale;
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
}
