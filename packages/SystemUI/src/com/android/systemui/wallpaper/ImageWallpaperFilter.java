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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Abstract filter used by static image wallpaper.
 */
abstract class ImageWallpaperFilter {
    protected static final boolean DEBUG = false;

    private ImageWallpaperTransformer mTransformer;

    /**
     * Apply this filter to the bitmap before drawing on canvas.
     * @param c      The canvas that will draw to.
     * @param bitmap The bitmap to apply this filter.
     * @param src    The subset of the bitmap to be drawn.
     * @param dest   The rectangle that the bitmap will be scaled/translated to fit into.
     */
    public abstract void apply(@NonNull Canvas c, @Nullable Bitmap bitmap,
            @Nullable Rect src, @NonNull RectF dest);

    /**
     * Notifies the occurrence of built-in transition of the animation.
     * @param animator The animator which was animated.
     */
    public abstract void onAnimatorUpdate(ValueAnimator animator);

    /**
     * Notifies the occurrence of another transition of the animation.
     * @param amount The transition amount.
     */
    public abstract void onTransitionAmountUpdate(float amount);

    /**
     * To set the associated transformer.
     * @param transformer The transformer that is associated with this filter.
     */
    public void setTransformer(ImageWallpaperTransformer transformer) {
        if (transformer != null) {
            mTransformer = transformer;
        }
    }

    protected ImageWallpaperTransformer getTransformer() {
        return mTransformer;
    }

    /**
     * Notifies the changing of the offset value of the ImageWallpaper.
     * @param force True to force re-evaluate offsets.
     * @param xOffset X offset of the ImageWallpaper in percentage.
     * @param yOffset Y offset of the ImageWallpaper in percentage.
     */
    public void onOffsetsUpdate(boolean force, float xOffset, float yOffset) {
        // No-op
    }

}
