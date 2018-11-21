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
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.android.systemui.R;

/**
 * View that circle crops its contents and supports displaying a coloured dot on a top corner.
 */
public class BadgedImageView extends ImageView {

    private BadgeRenderer mDotRenderer;
    private int mIconSize;
    private Rect mTempBounds = new Rect();
    private Point mTempPoint = new Point();
    private Path mClipPath = new Path();

    private float mDotScale = 0f;
    private int mUpdateDotColor;
    private boolean mShowUpdateDot;
    private boolean mOnLeft;

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
        setScaleType(ScaleType.CENTER_CROP);
        mIconSize = getResources().getDimensionPixelSize(R.dimen.bubble_size);
        mDotRenderer = new BadgeRenderer(mIconSize);
    }

    // TODO: Clipping oval path isn't great: rerender image into a separate, rounded bitmap and
    // then draw would be better
    @Override
    public void onDraw(Canvas canvas) {
        canvas.save();
        // Circle crop
        mClipPath.addOval(getPaddingStart(), getPaddingTop(),
                getWidth() - getPaddingEnd(), getHeight() - getPaddingBottom(), Path.Direction.CW);
        canvas.clipPath(mClipPath);
        super.onDraw(canvas);

        // After we've circle cropped what we're showing, restore so we don't clip the badge
        canvas.restore();

        // Draw the badge
        if (mShowUpdateDot) {
            getDrawingRect(mTempBounds);
            mTempPoint.set((getWidth() - mIconSize) / 2, getPaddingTop());
            mDotRenderer.draw(canvas, mUpdateDotColor, mTempBounds, mDotScale, mTempPoint,
                    mOnLeft);
        }
    }

    /**
     * Set whether the dot should appear on left or right side of the view.
     */
    public void setDotPosition(boolean onLeft) {
        mOnLeft = onLeft;
        invalidate();
    }

    /**
     * Set whether the dot should show or not.
     */
    public void setShowDot(boolean showBadge) {
        mShowUpdateDot = showBadge;
        invalidate();
    }

    /**
     * @return whether the dot is being displayed.
     */
    public boolean isShowingDot() {
        return mShowUpdateDot;
    }

    /**
     * The colour to use for the dot.
     */
    public void setDotColor(int color) {
        mUpdateDotColor = color;
        invalidate();
    }

    /**
     * How big the dot should be, fraction from 0 to 1.
     */
    public void setDotScale(float fraction) {
        mDotScale = fraction;
        invalidate();
    }

    public float getDotScale() {
        return mDotScale;
    }
}
