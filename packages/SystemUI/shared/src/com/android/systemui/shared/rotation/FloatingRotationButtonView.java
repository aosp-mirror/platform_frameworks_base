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

package com.android.systemui.shared.rotation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.navigationbar.buttons.KeyButtonRipple;

public class FloatingRotationButtonView extends ImageView {

    private static final float BACKGROUND_ALPHA = 0.92f;

    private final KeyButtonRipple mRipple;
    private final Paint mOvalBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public FloatingRotationButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FloatingRotationButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setClickable(true);

        mRipple = new KeyButtonRipple(context, this);
        setBackground(mRipple);
        setWillNotDraw(false);
        forceHasOverlappingRendering(false);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) {
            jumpDrawablesToCurrentState();
        }
    }

    public void setColors(int lightColor, int darkColor) {
        getDrawable().setColorFilter(new PorterDuffColorFilter(lightColor, PorterDuff.Mode.SRC_IN));

        final int ovalBackgroundColor = Color.valueOf(Color.red(darkColor),
                Color.green(darkColor), Color.blue(darkColor), BACKGROUND_ALPHA).toArgb();

        mOvalBgPaint.setColor(ovalBackgroundColor);
        mRipple.setType(KeyButtonRipple.Type.OVAL);
    }

    public void setDarkIntensity(float darkIntensity) {
        mRipple.setDarkIntensity(darkIntensity);
    }

    @Override
    public void draw(Canvas canvas) {
        int d = Math.min(getWidth(), getHeight());
        canvas.drawOval(0, 0, d, d, mOvalBgPaint);
        super.draw(canvas);
    }
}
