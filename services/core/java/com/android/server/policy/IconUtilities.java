/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.policy;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PaintDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.TableMaskFilter;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.content.res.Resources;
import android.content.Context;

/**
 * Various utilities shared amongst the Launcher's classes.
 */
final class IconUtilities {
    private static final String TAG = "IconUtilities";

    private static final int sColors[] = { 0xffff0000, 0xff00ff00, 0xff0000ff };

    private int mIconWidth = -1;
    private int mIconHeight = -1;
    private int mIconTextureWidth = -1;
    private int mIconTextureHeight = -1;

    private final Paint mPaint = new Paint();
    private final Paint mBlurPaint = new Paint();
    private final Paint mGlowColorPressedPaint = new Paint();
    private final Paint mGlowColorFocusedPaint = new Paint();
    private final Rect mOldBounds = new Rect();
    private final Canvas mCanvas = new Canvas();
    private final DisplayMetrics mDisplayMetrics;

    private int mColorIndex = 0;

    public IconUtilities(Context context) {
        final Resources resources = context.getResources();
        DisplayMetrics metrics = mDisplayMetrics = resources.getDisplayMetrics();
        final float density = metrics.density;
        final float blurPx = 5 * density;

        mIconWidth = mIconHeight = (int) resources.getDimension(android.R.dimen.app_icon_size);
        mIconTextureWidth = mIconTextureHeight = mIconWidth + (int)(blurPx*2);

        mBlurPaint.setMaskFilter(new BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL));

        TypedValue value = new TypedValue();
        mGlowColorPressedPaint.setColor(context.getTheme().resolveAttribute(
                android.R.attr.colorPressedHighlight, value, true) ? value.data : 0xffffc300);
        mGlowColorPressedPaint.setMaskFilter(TableMaskFilter.CreateClipTable(0, 30));
        mGlowColorFocusedPaint.setColor(context.getTheme().resolveAttribute(
                android.R.attr.colorFocusedHighlight, value, true) ? value.data : 0xffff8e00);
        mGlowColorFocusedPaint.setMaskFilter(TableMaskFilter.CreateClipTable(0, 30));

        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.2f);

        mCanvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.DITHER_FLAG,
                Paint.FILTER_BITMAP_FLAG));
    }

    public Drawable createIconDrawable(Drawable src) {
        Bitmap scaled = createIconBitmap(src);

        StateListDrawable result = new StateListDrawable();

        result.addState(new int[] { android.R.attr.state_focused },
                new BitmapDrawable(createSelectedBitmap(scaled, false)));
        result.addState(new int[] { android.R.attr.state_pressed },
                new BitmapDrawable(createSelectedBitmap(scaled, true)));
        result.addState(new int[0], new BitmapDrawable(scaled));

        result.setBounds(0, 0, mIconTextureWidth, mIconTextureHeight);
        return result;
    }

    /**
     * Returns a bitmap suitable for the all apps view.  The bitmap will be a power
     * of two sized ARGB_8888 bitmap that can be used as a gl texture.
     */
    private Bitmap createIconBitmap(Drawable icon) {
        int width = mIconWidth;
        int height = mIconHeight;

        if (icon instanceof PaintDrawable) {
            PaintDrawable painter = (PaintDrawable) icon;
            painter.setIntrinsicWidth(width);
            painter.setIntrinsicHeight(height);
        } else if (icon instanceof BitmapDrawable) {
            // Ensure the bitmap has a density.
            BitmapDrawable bitmapDrawable = (BitmapDrawable) icon;
            Bitmap bitmap = bitmapDrawable.getBitmap();
            if (bitmap.getDensity() == Bitmap.DENSITY_NONE) {
                bitmapDrawable.setTargetDensity(mDisplayMetrics);
            }
        }
        int sourceWidth = icon.getIntrinsicWidth();
        int sourceHeight = icon.getIntrinsicHeight();

        if (sourceWidth > 0 && sourceHeight > 0) {
            // There are intrinsic sizes.
            if (width < sourceWidth || height < sourceHeight) {
                // It's too big, scale it down.
                final float ratio = (float) sourceWidth / sourceHeight;
                if (sourceWidth > sourceHeight) {
                    height = (int) (width / ratio);
                } else if (sourceHeight > sourceWidth) {
                    width = (int) (height * ratio);
                }
            } else if (sourceWidth < width && sourceHeight < height) {
                // It's small, use the size they gave us.
                width = sourceWidth;
                height = sourceHeight;
            }
        }

        // no intrinsic size --> use default size
        int textureWidth = mIconTextureWidth;
        int textureHeight = mIconTextureHeight;

        final Bitmap bitmap = Bitmap.createBitmap(textureWidth, textureHeight,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = mCanvas;
        canvas.setBitmap(bitmap);

        final int left = (textureWidth-width) / 2;
        final int top = (textureHeight-height) / 2;

        if (false) {
            // draw a big box for the icon for debugging
            canvas.drawColor(sColors[mColorIndex]);
            if (++mColorIndex >= sColors.length) mColorIndex = 0;
            Paint debugPaint = new Paint();
            debugPaint.setColor(0xffcccc00);
            canvas.drawRect(left, top, left+width, top+height, debugPaint);
        }

        mOldBounds.set(icon.getBounds());
        icon.setBounds(left, top, left+width, top+height);
        icon.draw(canvas);
        icon.setBounds(mOldBounds);

        return bitmap;
    }

    private Bitmap createSelectedBitmap(Bitmap src, boolean pressed) {
        final Bitmap result = Bitmap.createBitmap(mIconTextureWidth, mIconTextureHeight,
                Bitmap.Config.ARGB_8888);
        final Canvas dest = new Canvas(result);

        dest.drawColor(0, PorterDuff.Mode.CLEAR);

        int[] xy = new int[2];
        Bitmap mask = src.extractAlpha(mBlurPaint, xy);

        dest.drawBitmap(mask, xy[0], xy[1],
                pressed ? mGlowColorPressedPaint : mGlowColorFocusedPaint);

        mask.recycle();

        dest.drawBitmap(src, 0, 0, mPaint);
        dest.setBitmap(null);

        return result;
    }
}
