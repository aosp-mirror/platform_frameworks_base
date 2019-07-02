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

package com.android.settingslib;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.style.ImageSpan;

/**
 * An extension of ImageSpan which adds a padding before the image.
 */
public class RestrictedLockImageSpan extends ImageSpan {
    private Context mContext;
    private final float mExtraPadding;
    private final Drawable mRestrictedPadlock;

    public RestrictedLockImageSpan(Context context) {
        // we are overriding getDrawable, so passing null to super class here.
        super((Drawable) null);

        mContext = context;
        mExtraPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.restricted_icon_padding);
        mRestrictedPadlock = RestrictedLockUtilsInternal.getRestrictedPadlock(mContext);
    }

    @Override
    public Drawable getDrawable() {
        return mRestrictedPadlock;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
            int bottom, Paint paint) {
        Drawable drawable = getDrawable();
        canvas.save();

        // Add extra padding before the padlock.
        float transX = x + mExtraPadding;
        float transY = (bottom - drawable.getBounds().bottom) / 2.0f;

        canvas.translate(transX, transY);
        drawable.draw(canvas);
        canvas.restore();
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
            Paint.FontMetricsInt fontMetrics) {
        int size = super.getSize(paint, text, start, end, fontMetrics);
        size += 2 * mExtraPadding;
        return size;
    }
}