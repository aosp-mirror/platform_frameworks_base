/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.wm.shell.legacysplitscreen;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowManager;

import com.android.wm.shell.R;

/**
 * Shadow for the minimized dock state on homescreen.
 */
public class MinimizedDockShadow extends View {

    private final Paint mShadowPaint = new Paint();

    private int mDockSide = WindowManager.DOCKED_INVALID;

    public MinimizedDockShadow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    void setDockSide(int dockSide) {
        if (dockSide != mDockSide) {
            mDockSide = dockSide;
            updatePaint(getLeft(), getTop(), getRight(), getBottom());
            invalidate();
        }
    }

    private void updatePaint(int left, int top, int right, int bottom) {
        int startColor = mContext.getResources().getColor(
                R.color.minimize_dock_shadow_start, null);
        int endColor = mContext.getResources().getColor(
                R.color.minimize_dock_shadow_end, null);
        final int middleColor = Color.argb(
                (Color.alpha(startColor) + Color.alpha(endColor)) / 2, 0, 0, 0);
        final int quarter = Color.argb(
                (int) (Color.alpha(startColor) * 0.25f + Color.alpha(endColor) * 0.75f),
                0, 0, 0);
        if (mDockSide == WindowManager.DOCKED_TOP) {
            mShadowPaint.setShader(new LinearGradient(
                    0, 0, 0, bottom - top,
                    new int[] { startColor, middleColor, quarter, endColor },
                    new float[] { 0f, 0.35f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        } else if (mDockSide == WindowManager.DOCKED_LEFT) {
            mShadowPaint.setShader(new LinearGradient(
                    0, 0, right - left, 0,
                    new int[] { startColor, middleColor, quarter, endColor },
                    new float[] { 0f, 0.35f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        } else if (mDockSide == WindowManager.DOCKED_RIGHT) {
            mShadowPaint.setShader(new LinearGradient(
                    right - left, 0, 0, 0,
                    new int[] { startColor, middleColor, quarter, endColor },
                    new float[] { 0f, 0.35f, 0.6f, 1f }, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            updatePaint(left, top, right, bottom);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), mShadowPaint);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
