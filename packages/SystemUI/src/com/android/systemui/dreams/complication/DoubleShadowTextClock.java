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
import android.content.res.Resources;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.TextClock;

import com.android.systemui.R;
import com.android.systemui.dreams.complication.DoubleShadowTextHelper.ShadowInfo;

import kotlin.Unit;

/**
 * Extension of {@link TextClock} which draws two shadows on the text (ambient and key shadows)
 */
public class DoubleShadowTextClock extends TextClock {
    private final DoubleShadowTextHelper mShadowHelper;

    public DoubleShadowTextClock(Context context) {
        this(context, null);
    }

    public DoubleShadowTextClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DoubleShadowTextClock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources resources = context.getResources();
        final ShadowInfo keyShadowInfo = new ShadowInfo(
                resources.getDimensionPixelSize(R.dimen.dream_overlay_clock_key_text_shadow_radius),
                resources.getDimensionPixelSize(R.dimen.dream_overlay_clock_key_text_shadow_dx),
                resources.getDimensionPixelSize(R.dimen.dream_overlay_clock_key_text_shadow_dy),
                resources.getColor(R.color.dream_overlay_clock_key_text_shadow_color));

        final ShadowInfo ambientShadowInfo = new ShadowInfo(
                resources.getDimensionPixelSize(
                        R.dimen.dream_overlay_clock_ambient_text_shadow_radius),
                resources.getDimensionPixelSize(R.dimen.dream_overlay_clock_ambient_text_shadow_dx),
                resources.getDimensionPixelSize(R.dimen.dream_overlay_clock_ambient_text_shadow_dy),
                resources.getColor(R.color.dream_overlay_clock_ambient_text_shadow_color));
        mShadowHelper = new DoubleShadowTextHelper(keyShadowInfo, ambientShadowInfo);
    }

    @Override
    public void onDraw(Canvas canvas) {
        mShadowHelper.applyShadows(this, canvas, () -> {
            super.onDraw(canvas);
            return Unit.INSTANCE;
        });
    }
}
