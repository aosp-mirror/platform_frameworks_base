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
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewDebug;

import com.android.systemui.R;
import com.android.systemui.recents.model.Task;


/**
 * The task thumbnail view.  It implements an image view that allows for animating the dim and
 * alpha of the thumbnail image.
 */
public class TaskViewThumbnail extends View {


    private static final ColorMatrix TMP_FILTER_COLOR_MATRIX = new ColorMatrix();
    private static final ColorMatrix TMP_BRIGHTNESS_COLOR_MATRIX = new ColorMatrix();

    private Task mTask;

    // Drawing
    @ViewDebug.ExportedProperty(category="recents")
    Rect mThumbnailRect = new Rect();
    @ViewDebug.ExportedProperty(category="recents")
    Rect mTaskViewRect = new Rect();
    int mCornerRadius;
    @ViewDebug.ExportedProperty(category="recents")
    float mDimAlpha;
    Matrix mScaleMatrix = new Matrix();
    Paint mDrawPaint = new Paint();
    Paint mBgFillPaint = new Paint();
    BitmapShader mBitmapShader;
    LightingColorFilter mLightingColorFilter = new LightingColorFilter(0xffffffff, 0);

    // Task bar clipping, the top of this thumbnail can be clipped against the opaque header
    // bar that overlaps this thumbnail
    View mTaskBar;
    @ViewDebug.ExportedProperty(category="recents")
    Rect mClipRect = new Rect();

    // Visibility optimization, if the thumbnail height is less than the height of the header
    // bar for the task view, then just mark this thumbnail view as invisible
    @ViewDebug.ExportedProperty(category="recents")
    boolean mInvisible;

    @ViewDebug.ExportedProperty(category="recents")
    boolean mDisabledInSafeMode;

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
        mBgFillPaint.setColor(Color.WHITE);
    }

    /**
     * Called when the task view frame changes, allowing us to move the contents of the header
     * to match the frame changes.
     */
    public void onTaskViewSizeChanged(int width, int height) {
        // Return early if the bounds have not changed
        if (mTaskViewRect.width() == width && mTaskViewRect.height() == height) {
            return;
        }

        mTaskViewRect.set(0, 0, width, height);
        updateThumbnailScale();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mInvisible) {
            return;
        }

        int thumbnailHeight = (int) (((float) mTaskViewRect.width() / mThumbnailRect.width()) *
                mThumbnailRect.height());
        if (thumbnailHeight >= mTaskViewRect.height()) {
            // The thumbnail fills the full task view bounds, so just draw it
            canvas.drawRoundRect(0, 0, mTaskViewRect.width(), mTaskViewRect.height(),
                    mCornerRadius, mCornerRadius, mDrawPaint);
        } else {
            int count = 0;
            if (thumbnailHeight > 0) {
                // The thumbnail only covers part of the task view bounds, so fill in the
                // non-thumbnail space with the default background color.  This is the equivalent of
                // the GL border texture mode.
                count = canvas.save();

                // Since we only want the top corners to be rounded, draw slightly beyond the
                // thumbnail height, but clip to the thumbnail height
                canvas.clipRect(0, 0, mTaskViewRect.width(), thumbnailHeight, Region.Op.REPLACE);
                canvas.drawRoundRect(0, 0, mTaskViewRect.width(), thumbnailHeight + mCornerRadius,
                        mCornerRadius, mCornerRadius, mDrawPaint);
            }

            // In the remaining space, draw the background color
            canvas.clipRect(0, thumbnailHeight, mTaskViewRect.width(), mTaskViewRect.height(),
                    Region.Op.REPLACE);
            canvas.drawRoundRect(0, Math.max(0, thumbnailHeight - mCornerRadius),
                    mTaskViewRect.width(), mTaskViewRect.height(), mCornerRadius, mCornerRadius,
                    mBgFillPaint);

            if (thumbnailHeight > 0) {
                canvas.restoreToCount(count);
            }
        }
    }

    /** Sets the thumbnail to a given bitmap. */
    void setThumbnail(Bitmap bm) {
        if (bm != null) {
            mBitmapShader = new BitmapShader(bm, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP);
            mDrawPaint.setShader(mBitmapShader);
            mThumbnailRect.set(0, 0, bm.getWidth(), bm.getHeight());
            updateThumbnailScale();
        } else {
            mBitmapShader = null;
            mDrawPaint.setShader(null);
            mThumbnailRect.setEmpty();
        }
    }

    /** Updates the paint to draw the thumbnail. */
    void updateThumbnailPaintFilter() {
        if (mInvisible) {
            return;
        }
        int mul = (int) ((1.0f - mDimAlpha) * 255);
        if (mBitmapShader != null) {
            if (mDisabledInSafeMode) {
                // Brightness: C-new = C-old*(1-amount) + amount
                TMP_FILTER_COLOR_MATRIX.setSaturation(0);
                float scale = 1f - mDimAlpha;
                float[] mat = TMP_BRIGHTNESS_COLOR_MATRIX.getArray();
                mat[0] = scale;
                mat[6] = scale;
                mat[12] = scale;
                mat[4] = mDimAlpha * 255f;
                mat[9] = mDimAlpha * 255f;
                mat[14] = mDimAlpha * 255f;
                TMP_FILTER_COLOR_MATRIX.preConcat(TMP_BRIGHTNESS_COLOR_MATRIX);
                ColorMatrixColorFilter filter = new ColorMatrixColorFilter(TMP_FILTER_COLOR_MATRIX);
                mDrawPaint.setColorFilter(filter);
                mBgFillPaint.setColorFilter(filter);
            } else {
                mLightingColorFilter.setColorMultiply(Color.argb(255, mul, mul, mul));
                mDrawPaint.setColorFilter(mLightingColorFilter);
                mDrawPaint.setColor(0xFFffffff);
                mBgFillPaint.setColorFilter(mLightingColorFilter);
            }
        } else {
            int grey = mul;
            mDrawPaint.setColorFilter(null);
            mDrawPaint.setColor(Color.argb(255, grey, grey, grey));
        }
        if (!mInvisible) {
            invalidate();
        }
    }

    /**
     * Updates the scale of the bitmap relative to this view.
     */
    public void updateThumbnailScale() {
        if (mBitmapShader != null) {
            float thumbnailScale;
            if (!mTask.isFreeformTask() || mTask.bounds == null) {
                // If this is a stack task, or a stack task moved into the freeform workspace, then
                // just scale this thumbnail to fit the width of the view
                thumbnailScale = (float) mTaskViewRect.width() / mThumbnailRect.width();
            } else {
                // Otherwise, if this is a freeform task with task bounds, then scale the thumbnail
                // to fit the entire bitmap into the task bounds
                thumbnailScale = Math.min(
                        (float) mTaskViewRect.width() / mThumbnailRect.width(),
                        (float) mTaskViewRect.height() / mThumbnailRect.height());
            }
            mScaleMatrix.setScale(thumbnailScale, thumbnailScale);
            mBitmapShader.setLocalMatrix(mScaleMatrix);
        }
        if (!mInvisible) {
            invalidate();
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
    void rebindToTask(Task t, boolean disabledInSafeMode) {
        mTask = t;
        mDisabledInSafeMode = disabledInSafeMode;
        if (t.thumbnail != null) {
            setThumbnail(t.thumbnail);
            if (t.colorBackground != 0) {
                mBgFillPaint.setColor(t.colorBackground);
            }
        } else {
            setThumbnail(null);
        }
    }

    /** Unbinds the thumbnail view from the task */
    void unbindFromTask() {
        mTask = null;
        setThumbnail(null);
    }
}
