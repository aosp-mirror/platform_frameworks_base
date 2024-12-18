/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;

public class HardwareBgDrawable extends LayerDrawable {

    private final Paint mPaint = new Paint();
    private final Drawable[] mLayers;
    private final boolean mRoundTop;
    private int mPoint;
    private boolean mRotatedBackground;

    public HardwareBgDrawable(boolean roundTop, boolean roundEnd, Context context) {
        this(roundTop, getLayers(context, roundTop, roundEnd));
    }

    public HardwareBgDrawable(boolean roundTop, Drawable[] layers) {
        super(layers);
        if (layers.length != 2) {
            throw new IllegalArgumentException("Need 2 layers");
        }
        mRoundTop = roundTop;
        mLayers = layers;
    }

    private static Drawable[] getLayers(Context context, boolean roundTop, boolean roundEnd) {
        int drawable = roundEnd ? R.drawable.rounded_bg_full : R.drawable.rounded_bg;
        final Drawable[] layers;
        if (roundTop) {
            layers = new Drawable[]{
                    context.getDrawable(drawable).mutate(),
                    context.getDrawable(drawable).mutate(),
            };
        } else {
            layers = new Drawable[]{
                    context.getDrawable(drawable).mutate(),
                    context.getDrawable(roundEnd ? R.drawable.rounded_full_bg_bottom
                            : R.drawable.rounded_bg_bottom).mutate(),
            };
        }
        layers[1].setTintList(Utils.getColorAttr(context, android.R.attr.colorPrimary));
        return layers;
    }

    public void setCutPoint(int point) {
        mPoint = point;
        invalidateSelf();
    }

    public int getCutPoint() {
        return mPoint;
    }

    @Override
    public void draw(Canvas canvas) {
        if (mPoint >= 0 && !mRotatedBackground) {
            Rect bounds = getBounds();
            int top = bounds.top + mPoint;
            if (top > bounds.bottom) top = bounds.bottom;
            if (mRoundTop) {
                mLayers[0].setBounds(bounds.left, bounds.top, bounds.right, top);
            } else {
                mLayers[1].setBounds(bounds.left, top, bounds.right, bounds.bottom);
            }
            if (mRoundTop) {
                mLayers[1].draw(canvas);
                mLayers[0].draw(canvas);
            } else {
                mLayers[0].draw(canvas);
                mLayers[1].draw(canvas);
            }
        } else {
            mLayers[0].draw(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    public void setRotatedBackground(boolean rotatedBackground) {
        mRotatedBackground = rotatedBackground;
    }
}