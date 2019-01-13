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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.DisplayInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is used to manage the filters that will be applied.
 */
public class ImageWallpaperTransformer {
    private static final String TAG = ImageWallpaperTransformer.class.getSimpleName();

    private DisplayInfo mDisplayInfo;
    private final List<ImageWallpaperFilter> mFilters;
    private final TransformationListener mListener;
    private boolean mIsInAmbientMode;
    private boolean mIsTransiting;

    /**
     * Constructor.
     * @param listener A listener to inform you the transformation has updated.
     */
    public ImageWallpaperTransformer(TransformationListener listener) {
        mFilters = new ArrayList<>();
        mListener = listener;
    }

    /**
     * Claim that we want to use the specified filter.
     * @param filter The filter will be used.
     */
    public void addFilter(ImageWallpaperFilter filter) {
        if (filter != null) {
            filter.setTransformer(this);
            mFilters.add(filter);
        }
    }

    /**
     * Check if any transition is running.
     * @return True if the transition is running, false otherwise.
     */
    boolean isTransiting() {
        return mIsTransiting;
    }

    /**
     * Indicate if any transition is running. <br/>
     * @param isTransiting True if the transition is running.
     */
    void setIsTransiting(boolean isTransiting) {
        mIsTransiting = isTransiting;
    }

    /**
     * Check if the device is in ambient mode.
     * @return True if the device is in ambient mode, false otherwise.
     */
    public boolean isInAmbientMode() {
        return mIsInAmbientMode;
    }

    /**
     * Update current state of ambient mode.
     * @param isInAmbientMode Current ambient mode state.
     */
    public void updateAmbientModeState(boolean isInAmbientMode) {
        mIsInAmbientMode = isInAmbientMode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (ImageWallpaperFilter filter : mFilters) {
            sb.append(idx++).append(": ").append(filter.getClass().getSimpleName()).append("\n");
        }
        if (sb.length() == 0) {
            sb.append("No filters applied");
        }
        return sb.toString();
    }

    /**
     * Set a new display info.
     * @param displayInfo New display info.
     */
    public void updateDisplayInfo(DisplayInfo displayInfo) {
        mDisplayInfo = displayInfo;
    }

    /**
     * To get current display info.
     * @return Current display info.
     */
    public DisplayInfo getDisplayInfo() {
        return mDisplayInfo;
    }

    /**
     * Update the offsets with default value.
     */
    public void updateOffsets() {
        this.updateOffsets(true, 0f, .5f);
    }

    /**
     * To notify the filters that the offset of the ImageWallpaper changes.
     * @param force True to force re-evaluate offsets.
     * @param offsetX X offset of the ImageWallpaper in percentage.
     * @param offsetY Y offset of the ImageWallpaper in percentage.
     */
    public void updateOffsets(boolean force, float offsetX, float offsetY) {
        mFilters.forEach(filter -> filter.onOffsetsUpdate(force, offsetX, offsetY));
    }

    /**
     * Apply all specified filters to the bitmap then draw to the canvas.
     * @param c      The canvas that will draw to.
     * @param target The bitmap to apply filters.
     * @param src    The subset of the bitmap to be drawn
     * @param dest   The rectangle that the bitmap will be scaled/translated to fit into.
     */
    void drawTransformedImage(@NonNull Canvas c, @Nullable Bitmap target,
            @Nullable Rect src, @NonNull RectF dest) {
        mFilters.forEach(filter -> filter.apply(c, target, src, dest));
    }

    /**
     * Update the transition amount. <br/>
     * Must invoke this to update transition amount if not running built-in transition.
     * @param amount The transition amount.
     */
    void updateTransitionAmount(float amount) {
        mFilters.forEach(filter -> filter.onTransitionAmountUpdate(amount));
        if (mListener != null) {
            mListener.onTransformationUpdated();
        }
    }

    /**
     * An interface that informs the transformation status.
     */
    public interface TransformationListener {
        /**
         * Notifies the update of the transformation.
         */
        void onTransformationUpdated();
    }
}
