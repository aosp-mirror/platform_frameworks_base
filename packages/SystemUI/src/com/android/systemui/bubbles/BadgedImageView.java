/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.systemui.bubbles;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.DotRenderer;
import com.android.systemui.R;

/**
 * View that circle crops its contents and supports displaying a coloured dot on a top corner.
 */
public class BadgedImageView extends ImageView {

    private Rect mTempBounds = new Rect();

    private DotRenderer mDotRenderer;
    private DotRenderer.DrawParams mDrawParams;
    private int mIconBitmapSize;
    private int mDotColor;
    private float mDotScale = 0f;
    private boolean mShowDot;
    private boolean mOnLeft;

    /** Same as value in Launcher3 IconShape */
    static final int DEFAULT_PATH_SIZE = 100;

    public BadgedImageView(Context context) {
        this(context, null);
    }

    public BadgedImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BadgedImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BadgedImageView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mIconBitmapSize = getResources().getDimensionPixelSize(R.dimen.bubble_icon_bitmap_size);
        mDrawParams = new DotRenderer.DrawParams();

        TypedArray ta = context.obtainStyledAttributes(
                new int[]{android.R.attr.colorBackgroundFloating});
        ta.recycle();
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mShowDot) {
            return;
        }
        getDrawingRect(mTempBounds);

        mDrawParams.color = mDotColor;
        mDrawParams.iconBounds = mTempBounds;
        mDrawParams.leftAlign = mOnLeft;
        mDrawParams.scale = mDotScale;

        if (mDotRenderer == null) {
            Path circlePath = new Path();
            float radius = DEFAULT_PATH_SIZE * 0.5f;
            circlePath.addCircle(radius /* x */, radius /* y */, radius, Path.Direction.CW);
            mDotRenderer = new DotRenderer(mIconBitmapSize, circlePath, DEFAULT_PATH_SIZE);
        }
        mDotRenderer.draw(canvas, mDrawParams);
    }

    /**
     * Set whether the dot should appear on left or right side of the view.
     */
    void setDotOnLeft(boolean onLeft) {
        mOnLeft = onLeft;
        invalidate();
    }

    boolean getDotOnLeft() {
        return mOnLeft;
    }

    /**
     * Set whether the dot should show or not.
     */
    void setShowDot(boolean showDot) {
        mShowDot = showDot;
        invalidate();
    }

    /**
     * @return whether the dot is being displayed.
     */
    boolean isShowingDot() {
        return mShowDot;
    }

    /**
     * The colour to use for the dot.
     */
    public void setDotColor(int color) {
        mDotColor = ColorUtils.setAlphaComponent(color, 255 /* alpha */);
        invalidate();
    }

    /**
     * @param iconPath The new icon path to use when calculating dot position.
     */
    public void drawDot(Path iconPath) {
        mDotRenderer = new DotRenderer(mIconBitmapSize, iconPath, DEFAULT_PATH_SIZE);
        invalidate();
    }

    /**
     * How big the dot should be, fraction from 0 to 1.
     */
    void setDotScale(float fraction) {
        mDotScale = fraction;
        invalidate();
    }

    /**
     * Return dot position relative to bubble view container bounds.
     */
    float[] getDotCenter() {
        float[] dotPosition;
        if (mOnLeft) {
            dotPosition = mDotRenderer.getLeftDotPosition();
        } else {
            dotPosition =  mDotRenderer.getRightDotPosition();
        }
        getDrawingRect(mTempBounds);
        float dotCenterX = mTempBounds.width() * dotPosition[0];
        float dotCenterY = mTempBounds.height() * dotPosition[1];
        return new float[]{dotCenterX, dotCenterY};
    }
}
