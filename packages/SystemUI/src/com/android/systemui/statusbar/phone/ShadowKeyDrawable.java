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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;


import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.BlurMaskFilter.Blur;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import com.android.systemui.R;

/**
 * A drawable which adds shadow around a child drawable.
 */
public class ShadowKeyDrawable extends Drawable {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private final ShadowDrawableState mState;

    public ShadowKeyDrawable(Drawable d) {
        this(d, new ShadowDrawableState());
    }

    private ShadowKeyDrawable(Drawable d, ShadowDrawableState state) {
        mState = state;
        if (d != null) {
            mState.mBaseHeight = d.getIntrinsicHeight();
            mState.mBaseWidth = d.getIntrinsicWidth();
            mState.mChangingConfigurations = d.getChangingConfigurations();
            mState.mChildState = d.getConstantState();
        }
    }

    public void setRotation(float degrees) {
        if (mState.mRotateDegrees != degrees) {
            mState.mRotateDegrees = degrees;
            mState.mLastDrawnBitmap = null;
            invalidateSelf();
        }
    }

    public void setShadowProperties(int x, int y, int size, int color) {
        if (mState.mShadowOffsetX != x || mState.mShadowOffsetY != y
                || mState.mShadowSize != size || mState.mShadowColor != color) {
            mState.mShadowOffsetX = x;
            mState.mShadowOffsetY = y;
            mState.mShadowSize = size;
            mState.mShadowColor = color;
            mState.mLastDrawnBitmap = null;
            invalidateSelf();
        }
    }

    public float getRotation() {
        return mState.mRotateDegrees;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        if (mState.mLastDrawnBitmap == null) {
            regenerateBitmapCache();
        }
        canvas.drawBitmap(mState.mLastDrawnBitmap, null, bounds, mPaint);
    }

    @Override
    public void setTint(int tintColor) {
        super.setTint(tintColor);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public ConstantState getConstantState() {
        return mState;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicHeight() {
        return mState.mBaseHeight;
    }

    @Override
    public int getIntrinsicWidth() {
        return mState.mBaseWidth;
    }

    @Override
    public boolean canApplyTheme() {
        return mState.canApplyTheme();
    }

    private void regenerateBitmapCache() {
        final int width = getIntrinsicWidth();
        final int height = getIntrinsicHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        final float radians = (float) (mState.mRotateDegrees * Math.PI / 180);

        // Rotate canvas before drawing original drawable if no shadow
        if (mState.mShadowSize == 0) {
            canvas.rotate(mState.mRotateDegrees, width / 2, height / 2);
        }

        // Call mutate, so that the pixel allocation by the underlying vector drawable is cleared.
        final Drawable d = mState.mChildState.newDrawable().mutate();
        d.setBounds(0, 0, mState.mBaseWidth, mState.mBaseHeight);
        d.draw(canvas);

        if (mState.mShadowSize > 0) {
            // Draws the shadow
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setMaskFilter(new BlurMaskFilter(mState.mShadowSize, Blur.NORMAL));
            int[] offset = new int[2];

            final Bitmap shadow = bitmap.extractAlpha(paint, offset);

            paint.setMaskFilter(null);
            paint.setColor(mState.mShadowColor);
            bitmap.eraseColor(Color.TRANSPARENT);

            canvas.rotate(mState.mRotateDegrees, width / 2, height / 2);

            final float shadowOffsetX = (float) (Math.sin(radians) * mState.mShadowOffsetY
                    + Math.cos(radians) * mState.mShadowOffsetX);
            final float shadowOffsetY = (float) (Math.cos(radians) * mState.mShadowOffsetY
                    - Math.sin(radians) * mState.mShadowOffsetX);

            canvas.drawBitmap(shadow, offset[0] + shadowOffsetX, offset[1] + shadowOffsetY, paint);
            d.draw(canvas);
        }

        bitmap = bitmap.copy(Bitmap.Config.HARDWARE, false);
        mState.mLastDrawnBitmap = bitmap;
        canvas.restore();
    }

    private static class ShadowDrawableState extends ConstantState {
        int mChangingConfigurations;
        int mBaseWidth;
        int mBaseHeight;
        float mRotateDegrees;
        int mShadowOffsetX;
        int mShadowOffsetY;
        int mShadowSize;
        int mShadowColor;

        Bitmap mLastDrawnBitmap;
        ConstantState mChildState;

        @Override
        public Drawable newDrawable() {
            return new ShadowKeyDrawable(null, this);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        @Override
        public boolean canApplyTheme() {
            return true;
        }
    }
}
