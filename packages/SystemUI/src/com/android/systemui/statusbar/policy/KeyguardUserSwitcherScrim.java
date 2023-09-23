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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.android.systemui.res.R;

/**
 * Gradient background for the user switcher on Keyguard.
 */
public class KeyguardUserSwitcherScrim extends Drawable
        implements View.OnLayoutChangeListener {

    private static final float OUTER_EXTENT = 2.5f;
    private static final float INNER_EXTENT = 0.25f;

    private int mDarkColor;
    private int mAlpha = 255;
    private Paint mRadialGradientPaint = new Paint();
    private int mCircleX;
    private int mCircleY;
    private int mSize;

    public KeyguardUserSwitcherScrim(Context context) {
        mDarkColor = context.getColor(
                R.color.keyguard_user_switcher_background_gradient_color);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mAlpha == 0) {
            return;
        }
        Rect bounds = getBounds();
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, mRadialGradientPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        updatePaint();
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            int width = right - left;
            int height = bottom - top;
            mSize = Math.max(width, height);
            updatePaint();
        }
    }

    private void updatePaint() {
        if (mSize == 0) {
            return;
        }
        float outerRadius = mSize * OUTER_EXTENT;
        mRadialGradientPaint.setShader(
                new RadialGradient(mCircleX, mCircleY, outerRadius,
                        new int[] { Color.argb(
                                        (int) (Color.alpha(mDarkColor) * mAlpha / 255f), 0, 0, 0),
                                Color.TRANSPARENT },
                        new float[] { Math.max(0f, INNER_EXTENT / OUTER_EXTENT), 1f },
                        Shader.TileMode.CLAMP));
    }

    /**
     * Sets the center of the radial gradient used as a background
     *
     * @param x
     * @param y
     */
    public void setGradientCenter(int x, int y) {
        mCircleX = x;
        mCircleY = y;
        updatePaint();
    }
}
