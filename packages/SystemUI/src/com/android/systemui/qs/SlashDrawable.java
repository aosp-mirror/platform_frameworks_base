/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.annotation.ColorInt;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;

public class SlashDrawable extends Drawable {

    private final Path mPath = new Path();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static float[] OFFSET = {
        1.3f / 24f,-1.3f / 24f
    };
    private static float[][] PATH = {
            {21.9f / 24f, 21.9f / 24f},
            {2.1f / 24f, 2.1f / 24f},
            {0.8f / 24f, 3.4f / 24f},
            {20.6f / 24f, 23.2f / 24f},
    };
    private Drawable mDrawable;
    private float mRotation;
    private boolean mSlashed;
    private Mode mTintMode;
    private ColorStateList mTintList;

    public SlashDrawable(Drawable d) {
        mDrawable = d;
    }

    @Override
    public int getIntrinsicHeight() {
        return mDrawable != null ? mDrawable.getIntrinsicHeight(): 0;
    }

    @Override
    public int getIntrinsicWidth() {
        return mDrawable != null ? mDrawable.getIntrinsicWidth(): 0;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mDrawable.setBounds(bounds);
    }

    public void setDrawable(Drawable d) {
        mDrawable = d;
        mDrawable.setCallback(getCallback());
        mDrawable.setBounds(getBounds());
        if (mTintMode != null) mDrawable.setTintMode(mTintMode);
        if (mTintList != null) mDrawable.setTintList(mTintList);
        invalidateSelf();
    }

    public void setRotation(float rotation) {
        if (mRotation == rotation) return;
        mRotation = rotation;
        invalidateSelf();
    }

    public void setSlashed(boolean slashed) {
        Log.d("TestTest", "setSlashed " + slashed);
        if (mSlashed == slashed) return;
        // TODO: Animate.
        mSlashed = slashed;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.save();
        Log.d("TestTest", "draw " + mSlashed);
        if (mSlashed) {
            Matrix m = new Matrix();
            int width = getBounds().width();
            int height = getBounds().height();
            mPath.reset();
            mPath.moveTo(scale(PATH[0][0], width), scale(PATH[0][1], height));
            mPath.lineTo(scale(PATH[1][0], width), scale(PATH[1][1], height));
            mPath.lineTo(scale(PATH[2][0], width), scale(PATH[2][1], height));
            mPath.lineTo(scale(PATH[3][0], width), scale(PATH[3][1], height));
            mPath.close();
            m = new Matrix();
            m.setRotate(mRotation, width / 2, height / 2);
            mPath.transform(m);
            canvas.drawPath(mPath, mPaint);
            m = new Matrix();
            m.setRotate(-mRotation, width / 2, height / 2);
            mPath.transform(m);

            m = new Matrix();
            m.setTranslate(scale(OFFSET[0], width), scale(OFFSET[1], height));
            mPath.transform(m);
            mPath.moveTo(scale(PATH[0][0], width), scale(PATH[0][1], height));
            mPath.lineTo(scale(PATH[1][0], width), scale(PATH[1][1], height));
            mPath.lineTo(scale(PATH[2][0], width), scale(PATH[2][1], height));
            mPath.lineTo(scale(PATH[3][0], width), scale(PATH[3][1], height));
            mPath.close();
            m = new Matrix();
            m.setRotate(mRotation, width / 2, height / 2);
            mPath.transform(m);
            canvas.clipOutPath(mPath);
        }

        mDrawable.draw(canvas);
        canvas.restore();
    }

    private float scale(float frac, int width) {
        return frac * width;
    }

    @Override
    public void setTint(@ColorInt int tintColor) {
        super.setTint(tintColor);
        mDrawable.setTint(tintColor);
        mPaint.setColor(tintColor);
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        mTintList = tint;
        super.setTintList(tint);
        mDrawable.setTintList(tint);
        mPaint.setColor(tint.getDefaultColor());
        invalidateSelf();
    }

    @Override
    public void setTintMode(@NonNull Mode tintMode) {
        mTintMode = tintMode;
        super.setTintMode(tintMode);
        mDrawable.setTintMode(tintMode);
    }

    @Override
    public void setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        mDrawable.setAlpha(alpha);
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mDrawable.setColorFilter(colorFilter);
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return 255;
    }
}
