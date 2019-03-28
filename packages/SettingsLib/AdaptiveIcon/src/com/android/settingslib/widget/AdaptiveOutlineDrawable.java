/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.PathParser;

import androidx.annotation.VisibleForTesting;

/**
 * Adaptive outline drawable with white plain background color and black outline
 */
public class AdaptiveOutlineDrawable extends DrawableWrapper {
    @VisibleForTesting
    final Paint mOutlinePaint;
    private Path mPath;
    private final int mInsetPx;
    private final Bitmap mBitmap;

    public AdaptiveOutlineDrawable(Resources resources, Bitmap bitmap) {
        super(new AdaptiveIconShapeDrawable(resources));

        getDrawable().setTint(Color.WHITE);
        mPath = new Path(PathParser.createPathFromPathData(
                resources.getString(com.android.internal.R.string.config_icon_mask)));
        mOutlinePaint = new Paint();
        mOutlinePaint.setColor(resources.getColor(R.color.bt_outline_color, null));
        mOutlinePaint.setStyle(Paint.Style.STROKE);
        mOutlinePaint.setStrokeWidth(resources.getDimension(R.dimen.adaptive_outline_stroke));
        mOutlinePaint.setAntiAlias(true);

        mInsetPx = resources
                .getDimensionPixelSize(R.dimen.dashboard_tile_foreground_image_inset);
        mBitmap = bitmap;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        final Rect bounds = getBounds();
        final float pathSize = AdaptiveIconDrawable.MASK_SIZE;

        final float scaleX = (bounds.right - bounds.left) / pathSize;
        final float scaleY = (bounds.bottom - bounds.top) / pathSize;

        final int count = canvas.save();
        canvas.scale(scaleX, scaleY);
        // Draw outline
        canvas.drawPath(mPath, mOutlinePaint);
        canvas.restoreToCount(count);

        // Draw the foreground icon
        canvas.drawBitmap(mBitmap, bounds.left + mInsetPx, bounds.top + mInsetPx, null);
    }

    @Override
    public int getIntrinsicHeight() {
        return mBitmap.getHeight() + 2 * mInsetPx;
    }

    @Override
    public int getIntrinsicWidth() {
        return mBitmap.getWidth() + 2 * mInsetPx;
    }
}
