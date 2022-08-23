/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.complication;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.TextClock;

import com.android.systemui.R;

/**
 * Extension of {@link TextClock} which draws two shadows on the text (ambient and key shadows)
 */
public class DoubleShadowTextClock extends TextClock {
    private final float mAmbientShadowBlur;
    private final int mAmbientShadowColor;
    private final float mKeyShadowBlur;
    private final float mKeyShadowOffsetX;
    private final float mKeyShadowOffsetY;
    private final int mKeyShadowColor;
    private final float mAmbientShadowOffsetX;
    private final float mAmbientShadowOffsetY;

    public DoubleShadowTextClock(Context context) {
        this(context, null);
    }

    public DoubleShadowTextClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DoubleShadowTextClock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mKeyShadowBlur = context.getResources()
                .getDimensionPixelSize(R.dimen.dream_overlay_clock_key_text_shadow_radius);
        mKeyShadowOffsetX = context.getResources()
                .getDimensionPixelSize(R.dimen.dream_overlay_clock_key_text_shadow_dx);
        mKeyShadowOffsetY = context.getResources()
                .getDimensionPixelSize(R.dimen.dream_overlay_clock_key_text_shadow_dy);
        mKeyShadowColor = context.getResources().getColor(
                R.color.dream_overlay_clock_key_text_shadow_color);
        mAmbientShadowBlur = context.getResources()
                .getDimensionPixelSize(R.dimen.dream_overlay_clock_ambient_text_shadow_radius);
        mAmbientShadowColor = context.getResources().getColor(
                R.color.dream_overlay_clock_ambient_text_shadow_color);
        mAmbientShadowOffsetX = context.getResources()
                .getDimensionPixelSize(R.dimen.dream_overlay_clock_ambient_text_shadow_dx);
        mAmbientShadowOffsetY = context.getResources()
                .getDimensionPixelSize(R.dimen.dream_overlay_clock_ambient_text_shadow_dy);
    }

    @Override
    public void onDraw(Canvas canvas) {
        // We enhance the shadow by drawing the shadow twice
        getPaint().setShadowLayer(mAmbientShadowBlur, mAmbientShadowOffsetX, mAmbientShadowOffsetY,
                mAmbientShadowColor);
        super.onDraw(canvas);
        canvas.save();
        canvas.clipRect(getScrollX(), getScrollY() + getExtendedPaddingTop(),
                getScrollX() + getWidth(),
                getScrollY() + getHeight());

        getPaint().setShadowLayer(
                mKeyShadowBlur, mKeyShadowOffsetX, mKeyShadowOffsetY, mKeyShadowColor);
        super.onDraw(canvas);
        canvas.restore();
    }
}
