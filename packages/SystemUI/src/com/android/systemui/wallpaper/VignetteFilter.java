/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.wallpaper;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A filter that implements vignette effect.
 */
public class VignetteFilter extends ImageWallpaperFilter {
    private static final String TAG = VignetteFilter.class.getSimpleName();
    private static final int MAX_ALPHA = 255;
    private static final int MIN_ALPHA = 0;

    private final Paint mPaint;
    private final Matrix mMatrix;
    private final Shader mShader;

    private float mXOffset;
    private float mYOffset;
    private float mCenterX;
    private float mCenterY;
    private float mStretchX;
    private float mStretchY;
    private boolean mCalculateOffsetNeeded;

    public VignetteFilter() {
        mPaint = new Paint();
        mMatrix = new Matrix();
        mShader = new RadialGradient(0, 0, 1,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
    }

    @Override
    public void apply(Canvas c, Bitmap bitmap, Rect src, RectF dest) {
        DisplayInfo info = getTransformer().getDisplayInfo();

        if (mCalculateOffsetNeeded) {
            int lw = info.logicalWidth;
            int lh = info.logicalHeight;
            mCenterX = lw / 2 + (dest.width() - lw) * mXOffset;
            mCenterY = lh / 2 + (dest.height() - lh) * mYOffset;
            mStretchX = info.logicalWidth / 2;
            mStretchY = info.logicalHeight / 2;
            mCalculateOffsetNeeded = false;
        }

        if (DEBUG) {
            Log.d(TAG, "apply: lw=" + info.logicalWidth + ", lh=" + info.logicalHeight
                    + ", center=(" + mCenterX + "," + mCenterY + ")"
                    + ", stretch=(" + mStretchX + "," + mStretchY + ")");
        }

        mMatrix.reset();
        mMatrix.postTranslate(mCenterX, mCenterY);
        mMatrix.postScale(mStretchX, mStretchY, mCenterX, mCenterY);
        mShader.setLocalMatrix(mMatrix);
        mPaint.setShader(mShader);

        ImageWallpaperTransformer transformer = getTransformer();

        // If it is not in the transition, we need to set the property according to aod state.
        if (!transformer.isTransiting()) {
            mPaint.setAlpha(transformer.isInAmbientMode() ? MAX_ALPHA : MIN_ALPHA);
        }

        c.drawRect(dest, mPaint);
    }

    @Override
    public void onAnimatorUpdate(ValueAnimator animator) {
        ImageWallpaperTransformer transformer = getTransformer();
        float fraction = animator.getAnimatedFraction();
        float factor = transformer.isInAmbientMode() ? fraction : 1f - fraction;
        mPaint.setAlpha((int) (factor * MAX_ALPHA));
    }

    @Override
    public void onTransitionAmountUpdate(float amount) {
        mPaint.setAlpha((int) (amount * MAX_ALPHA));
    }

    @Override
    public void onOffsetsUpdate(boolean force, float xOffset, float yOffset) {
        if (force || mXOffset != xOffset || mYOffset != yOffset) {
            mXOffset = xOffset;
            mYOffset = yOffset;
            mCalculateOffsetNeeded = true;
        }
    }

    @VisibleForTesting
    public PointF getCenterPoint() {
        return new PointF(mCenterX, mCenterY);
    }
}
