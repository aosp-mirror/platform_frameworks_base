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
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * A filter that implements 70% black scrim effect.
 */
public class ScrimFilter extends ImageWallpaperFilter {
    private static final int MAX_ALPHA = (int) (255 * .7f);
    private static final int MIN_ALPHA = 0;

    private final Paint mPaint;

    public ScrimFilter() {
        mPaint = new Paint();
        mPaint.setColor(Color.BLACK);
        mPaint.setAlpha(MAX_ALPHA);
    }

    @Override
    public void apply(Canvas c, Bitmap bitmap, Rect src, RectF dest) {
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

}
