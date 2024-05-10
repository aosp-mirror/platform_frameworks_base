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
package com.android.systemui.accessibility.accessibilitymenu.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;

import com.android.systemui.accessibility.accessibilitymenu.R;

/** Creates background drawable for a11y menu shortcut. */
public class ShortcutDrawableUtils {

    /**
     * To make the circular background of shortcut icons have higher resolution. The higher value of
     * LENGTH is, the higher resolution of the circular background are.
     */
    private static final int LENGTH = 480;

    private static final int RADIUS = LENGTH / 2;
    private static final int COORDINATE = LENGTH / 2;
    private static final int RIPPLE_COLOR_ID = R.color.ripple_material_color;

    private final Context mContext;
    private final ColorStateList mRippleColorStateList;

    // Placeholder of drawable to prevent NullPointerException
    private final ColorDrawable mTransparentDrawable = new ColorDrawable(Color.TRANSPARENT);

    public ShortcutDrawableUtils(Context context) {
        this.mContext = context;

        int rippleColor = context.getColor(RIPPLE_COLOR_ID);
        mRippleColorStateList = ColorStateList.valueOf(rippleColor);
    }

    /**
     * Creates a circular drawable in specific color for shortcut.
     *
     * @param colorResId color resource ID
     * @return drawable circular drawable
     */
    public Drawable createCircularDrawable(int colorResId) {
        Bitmap output = Bitmap.createBitmap(LENGTH, LENGTH, Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        int color = mContext.getColor(colorResId);
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Style.FILL);
        canvas.drawCircle(COORDINATE, COORDINATE, RADIUS, paint);

        BitmapDrawable drawable = new BitmapDrawable(mContext.getResources(), output);
        return drawable;
    }

    /**
     * Creates an adaptive icon drawable in specific color for shortcut.
     *
     * @param colorResId color resource ID
     * @return drawable for adaptive icon
     */
    public Drawable createAdaptiveIconDrawable(int colorResId) {
        Drawable circleLayer = createCircularDrawable(colorResId);
        RippleDrawable rippleLayer = new RippleDrawable(mRippleColorStateList, null, null);

        AdaptiveIconDrawable adaptiveIconDrawable =
                new AdaptiveIconDrawable(circleLayer, mTransparentDrawable);

        Drawable[] layers = {adaptiveIconDrawable, rippleLayer};
        LayerDrawable drawable = new LayerDrawable(layers);
        return drawable;
    }
}
