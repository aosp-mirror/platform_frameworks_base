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

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.LayoutDirection;
import android.view.View;

import com.android.systemui.R;

/**
 * Gradient background for the user switcher on Keyguard.
 */
public class KeyguardUserSwitcherScrim extends Drawable
        implements View.OnLayoutChangeListener {

    private static final float OUTER_EXTENT = 2.5f;
    private static final float INNER_EXTENT = 0.75f;

    private int mDarkColor;
    private int mTop;
    private int mAlpha;
    private Paint mRadialGradientPaint = new Paint();
    private int mLayoutWidth;

    public KeyguardUserSwitcherScrim(View host) {
        host.addOnLayoutChangeListener(this);
        mDarkColor = host.getResources().getColor(
                R.color.keyguard_user_switcher_background_gradient_color);
    }

    @Override
    public void draw(Canvas canvas) {
        boolean isLtr = getLayoutDirection() == LayoutDirection.LTR;
        Rect bounds = getBounds();
        float width = bounds.width() * OUTER_EXTENT;
        float height = (mTop + bounds.height()) * OUTER_EXTENT;
        canvas.translate(0, -mTop);
        canvas.scale(1, height / width);
        canvas.drawRect(isLtr ? bounds.right - width : 0, 0,
                isLtr ? bounds.right : bounds.left + width, width, mRadialGradientPaint);
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
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            mLayoutWidth = right - left;
            mTop = top;
            updatePaint();
        }
    }

    private void updatePaint() {
        if (mLayoutWidth == 0) {
            return;
        }
        float radius = mLayoutWidth * OUTER_EXTENT;
        boolean isLtr = getLayoutDirection() == LayoutDirection.LTR;
        mRadialGradientPaint.setShader(
                new RadialGradient(isLtr ? mLayoutWidth : 0, 0, radius,
                        new int[] { Color.argb(
                                        (int) (Color.alpha(mDarkColor) * mAlpha / 255f), 0, 0, 0),
                                Color.TRANSPARENT },
                        new float[] { Math.max(0f, mLayoutWidth * INNER_EXTENT / radius), 1f },
                        Shader.TileMode.CLAMP));
    }
}
