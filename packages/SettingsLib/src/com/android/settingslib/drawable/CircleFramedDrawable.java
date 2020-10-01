/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.settingslib.drawable;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import com.android.settingslib.R;

/**
 * Converts the user avatar icon to a circularly clipped one.
 * TODO: Move this to an internal framework class and share with the one in Keyguard.
 */
public class CircleFramedDrawable extends Drawable {

    private final Bitmap mBitmap;
    private final int mSize;
    private Paint mIconPaint;

    private float mScale;
    private Rect mSrcRect;
    private RectF mDstRect;

    public static CircleFramedDrawable getInstance(Context context, Bitmap icon) {
        Resources res = context.getResources();
        float iconSize = res.getDimension(R.dimen.circle_avatar_size);

        CircleFramedDrawable instance = new CircleFramedDrawable(icon, (int) iconSize);
        return instance;
    }

    public CircleFramedDrawable(Bitmap icon, int size) {
        super();
        mSize = size;

        mBitmap = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(mBitmap);

        final int width = icon.getWidth();
        final int height = icon.getHeight();
        final int square = Math.min(width, height);

        final Rect cropRect = new Rect((width - square) / 2, (height - square) / 2, square, square);
        final RectF circleRect = new RectF(0f, 0f, mSize, mSize);

        final Path fillPath = new Path();
        fillPath.addArc(circleRect, 0f, 360f);

        canvas.drawColor(0, PorterDuff.Mode.CLEAR);

        // opaque circle matte
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(fillPath, paint);

        // mask in the icon where the bitmap is opaque
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(icon, cropRect, circleRect, paint);

        // prepare paint for frame drawing
        paint.setXfermode(null);

        mScale = 1f;

        mSrcRect = new Rect(0, 0, mSize, mSize);
        mDstRect = new RectF(0, 0, mSize, mSize);
    }

    @Override
    public void draw(Canvas canvas) {
        final float inside = mScale * mSize;
        final float pad = (mSize - inside) / 2f;

        mDstRect.set(pad, pad, mSize - pad, mSize - pad);
        canvas.drawBitmap(mBitmap, mSrcRect, mDstRect, mIconPaint);
    }

    public void setScale(float scale) {
        mScale = scale;
    }

    public float getScale() {
        return mScale;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        if (mIconPaint == null) {
            mIconPaint = new Paint();
        }
        mIconPaint.setColorFilter(cf);
    }

    @Override
    public int getIntrinsicWidth() {
        return mSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }
}
