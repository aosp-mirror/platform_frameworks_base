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

import static androidx.annotation.VisibleForTesting.NONE;

import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_BACKGROUND_ARGB;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_BACKGROUND_HINT;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.drawer.Tile;

/**
 * Adaptive icon that can set background color
 */
public class AdaptiveIcon extends LayerDrawable {

    private static final String TAG = "AdaptiveHomepageIcon";

    @VisibleForTesting(otherwise = NONE)
    int mBackgroundColor = -1;
    private AdaptiveConstantState mAdaptiveConstantState;

    public AdaptiveIcon(Context context, Drawable foreground) {
        this(context, foreground, R.dimen.dashboard_tile_foreground_image_inset);
    }

    public AdaptiveIcon(Context context, Drawable foreground, int insetResId) {
        super(new Drawable[]{
                new AdaptiveIconShapeDrawable(context.getResources()),
                foreground
        });
        final int insetPx = context.getResources().getDimensionPixelSize(insetResId);
        setLayerInset(1 /* index */, insetPx, insetPx, insetPx, insetPx);
        mAdaptiveConstantState = new AdaptiveConstantState(context, foreground);
    }

    /**
     *  According {@code tile} metaData to set background color
     */
    public void setBackgroundColor(Context context, Tile tile) {
        final Bundle metaData = tile.getMetaData();
        try {
            if (metaData != null) {
                // Load from bg.argb first
                int bgColor = metaData.getInt(META_DATA_PREFERENCE_ICON_BACKGROUND_ARGB,
                        0 /* default */);
                // Not found, load from bg.hint
                if (bgColor == 0) {
                    final int colorRes = metaData.getInt(META_DATA_PREFERENCE_ICON_BACKGROUND_HINT,
                            0 /* default */);
                    if (colorRes != 0) {
                        bgColor = context.getPackageManager()
                                .getResourcesForApplication(tile.getPackageName())
                                .getColor(colorRes, null /* theme */);
                    }
                }
                // If found anything, use it.
                if (bgColor != 0) {
                    setBackgroundColor(bgColor);
                    return;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to set background color for " + tile.getPackageName());
        }
        setBackgroundColor(context.getColor(R.color.homepage_generic_icon_background));
    }

    /**
     * Set background color by {@code color}
     */
    public void setBackgroundColor(int color) {
        mBackgroundColor = color;
        getDrawable(0).setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
        Log.d(TAG, "Setting background color " + mBackgroundColor);
        mAdaptiveConstantState.mColor = color;
    }

    @Override
    public ConstantState getConstantState() {
        return mAdaptiveConstantState;
    }

    @VisibleForTesting
    static class AdaptiveConstantState extends ConstantState {
        Context mContext;
        Drawable mDrawable;
        int mColor;

        AdaptiveConstantState(Context context, Drawable drawable) {
            this.mContext = context;
            this.mDrawable = drawable;
        }

        @Override
        public Drawable newDrawable() {
            final AdaptiveIcon
                    icon = new AdaptiveIcon(mContext, mDrawable);
            icon.setBackgroundColor(mColor);

            return icon;
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }
}
