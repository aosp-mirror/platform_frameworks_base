/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.stagesplit;

import static android.view.RoundedCorner.POSITION_BOTTOM_LEFT;
import static android.view.RoundedCorner.POSITION_BOTTOM_RIGHT;
import static android.view.RoundedCorner.POSITION_TOP_LEFT;
import static android.view.RoundedCorner.POSITION_TOP_RIGHT;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.RoundedCorner;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;

/** View for drawing split outline. */
public class OutlineView extends View {
    private final Paint mPaint = new Paint();
    private final Path mPath = new Path();
    private final float[] mRadii = new float[8];

    public OutlineView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(
                getResources().getDimension(R.dimen.accessibility_focus_highlight_stroke_width));
        mPaint.setColor(getResources().getColor(R.color.system_accent1_100, null));
    }

    @Override
    protected void onAttachedToWindow() {
        // TODO(b/200850654): match the screen corners with the actual display decor.
        mRadii[0] = mRadii[1] = getCornerRadius(POSITION_TOP_LEFT);
        mRadii[2] = mRadii[3] = getCornerRadius(POSITION_TOP_RIGHT);
        mRadii[4] = mRadii[5] = getCornerRadius(POSITION_BOTTOM_RIGHT);
        mRadii[6] = mRadii[7] = getCornerRadius(POSITION_BOTTOM_LEFT);
    }

    private int getCornerRadius(@RoundedCorner.Position int position) {
        final RoundedCorner roundedCorner = getDisplay().getRoundedCorner(position);
        return roundedCorner == null ? 0 : roundedCorner.getRadius();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (changed) {
            mPath.reset();
            mPath.addRoundRect(0, 0, getWidth(), getHeight(), mRadii, Path.Direction.CW);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(mPath, mPaint);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
