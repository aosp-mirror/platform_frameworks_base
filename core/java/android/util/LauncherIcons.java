/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Utility class to handle icon treatments (e.g., shadow generation) for the Launcher icons.
 * @hide
 */
public final class LauncherIcons {

    private final Paint mPaint = new Paint();
    private final Canvas mCanvas = new Canvas();

    private static final int KEY_SHADOW_ALPHA = 61;
    private static final int AMBIENT_SHADOW_ALPHA = 30;
    private static final float BLUR_FACTOR = 0.5f / 48;
    private int mShadowInset;
    private Bitmap mShadowBitmap;
    private int mIconSize;
    private Resources mRes;

    public LauncherIcons(Context context) {
        mRes = context.getResources();
        DisplayMetrics metrics = mRes.getDisplayMetrics();
        mShadowInset = (int) metrics.density / DisplayMetrics.DENSITY_DEFAULT;
        mCanvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.DITHER_FLAG,
            Paint.FILTER_BITMAP_FLAG));
        mIconSize = (int) mRes.getDimensionPixelSize(android.R.dimen.app_icon_size);
    }

    /**
     * Draw the drawable into a bitmap.
     */
    public Bitmap createIconBitmap(Drawable icon) {
        final Bitmap bitmap = Bitmap.createBitmap(mIconSize, mIconSize, Bitmap.Config.ARGB_8888);
        mPaint.setAlpha(255);
        mCanvas.setBitmap(bitmap);
        int iconInset = 0;
        if (mShadowBitmap != null) {
            mCanvas.drawBitmap(mShadowBitmap, 0, 0, mPaint);
            iconInset = mShadowInset;
        }

        icon.setBounds(iconInset, iconInset, mIconSize - iconInset,
            mIconSize - iconInset);
        icon.draw(mCanvas);
        mCanvas.setBitmap(null);
        return bitmap;
    }

    public Drawable wrapIconDrawableWithShadow(Drawable drawable) {
        if (!(drawable instanceof AdaptiveIconDrawable)) {
            return drawable;
        }
        AdaptiveIconDrawable d =
            (AdaptiveIconDrawable) drawable.getConstantState().newDrawable().mutate();
        getShadowBitmap(d);
        Bitmap iconbitmap = createIconBitmap(d);
        return new BitmapDrawable(mRes, iconbitmap);
    }

    private Bitmap getShadowBitmap(AdaptiveIconDrawable d) {
        if (mShadowBitmap != null) {
            return mShadowBitmap;
        }

        int shadowSize = mIconSize - mShadowInset;
        mShadowBitmap = Bitmap.createBitmap(mIconSize, mIconSize, Bitmap.Config.ALPHA_8);
        mCanvas.setBitmap(mShadowBitmap);

        // Draw key shadow
        mPaint.setColor(Color.TRANSPARENT);
        float blur = BLUR_FACTOR * mIconSize;
        mPaint.setShadowLayer(blur, 0, mShadowInset, KEY_SHADOW_ALPHA << 24);
        d.setBounds(mShadowInset, mShadowInset, mIconSize - mShadowInset, mIconSize - mShadowInset);
        mCanvas.drawPath(d.getIconMask(), mPaint);

        // Draw ambient shadow
        mPaint.setShadowLayer(blur, 0, 0, AMBIENT_SHADOW_ALPHA << 24);
        d.setBounds(mShadowInset, 2 * mShadowInset, mIconSize - mShadowInset, mIconSize);
        mCanvas.drawPath(d.getIconMask(), mPaint);
        mPaint.clearShadowLayer();

        return mShadowBitmap;
    }
}
