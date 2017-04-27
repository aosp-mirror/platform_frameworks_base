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
 * limitations under the License
 */

package com.google.android.colorextraction;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import com.google.android.colorextraction.types.ExtractionType;
import com.google.android.colorextraction.types.Tonal;

/**
 * Class to process wallpaper colors and generate a tonal palette based on them.
 */
public class ColorExtractor implements WallpaperManager.OnColorsChangedListener {
    private static final String TAG = "ColorExtractor";
    private static final int FALLBACK_COLOR = Color.BLACK;

    private int mMainFallbackColor = FALLBACK_COLOR;
    private int mSecondaryFallbackColor = FALLBACK_COLOR;
    private final GradientColors mSystemColors;
    private final GradientColors mLockColors;
    private final Context mContext;
    private final ExtractionType mExtractionType;
    private OnColorsChangedListener mListener;

    public ColorExtractor(Context context) {
        mContext = context;
        mSystemColors = new GradientColors();
        mLockColors = new GradientColors();
        mExtractionType = new Tonal();

        WallpaperManager wallpaperManager = mContext.getSystemService(WallpaperManager.class);

        if (wallpaperManager == null) {
            Log.w(TAG, "Can't listen to color changes!");
        } else {
            wallpaperManager.addOnColorsChangedListener(this);
            extractInto(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM),
                    mSystemColors);
            extractInto(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_LOCK),
                    mLockColors);
        }
    }

    public GradientColors getColors(int which) {
        if (which == WallpaperManager.FLAG_LOCK) {
            return mLockColors;
        } else if (which == WallpaperManager.FLAG_SYSTEM) {
            return mSystemColors;
        } else {
            throw new IllegalArgumentException("which should be either FLAG_SYSTEM or FLAG_LOCK");
        }
    }

    public void setListener(OnColorsChangedListener listener) {
        mListener = listener;
    }

    @Override
    public void onColorsChanged(WallpaperColors colors, int which) {
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            extractInto(colors, mLockColors);
            if (mListener != null) {
                mListener.onColorsChanged(mLockColors, WallpaperManager.FLAG_LOCK);
            }
        }
        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
            extractInto(colors, mSystemColors);
            if (mListener != null) {
                mListener.onColorsChanged(mSystemColors, WallpaperManager.FLAG_SYSTEM);
            }
        }
    }

    private void extractInto(WallpaperColors inWallpaperColors, GradientColors outGradientColors) {
        applyFallback(outGradientColors);
        if (inWallpaperColors == null) {
            return;
        }
        mExtractionType.extractInto(inWallpaperColors, outGradientColors);
    }

    private void applyFallback(GradientColors outGradientColors) {
        outGradientColors.setMainColor(mMainFallbackColor);
        outGradientColors.setSecondaryColor(mSecondaryFallbackColor);
    }

    public void destroy() {
        WallpaperManager wallpaperManager = mContext.getSystemService(WallpaperManager.class);
        if (wallpaperManager != null) {
            wallpaperManager.removeOnColorsChangedListener(this);
        }
    }

    public static class GradientColors {
        private int mMainColor = FALLBACK_COLOR;
        private int mSecondaryColor = FALLBACK_COLOR;
        private boolean mSupportsDarkText;

        public void setMainColor(int mainColor) {
            mMainColor = mainColor;
        }

        public void setSecondaryColor(int secondaryColor) {
            mSecondaryColor = secondaryColor;
        }

        public void setSupportsDarkText(boolean supportsDarkText) {
            mSupportsDarkText = supportsDarkText;
        }

        public void set(GradientColors other) {
            mMainColor = other.mMainColor;
            mSecondaryColor = other.mSecondaryColor;
            mSupportsDarkText = other.mSupportsDarkText;
        }

        public int getMainColor() {
            return mMainColor;
        }

        public int getSecondaryColor() {
            return mSecondaryColor;
        }

        public boolean supportsDarkText() {
            return mSupportsDarkText;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || o.getClass() != getClass()) {
                return false;
            }
            GradientColors other = (GradientColors) o;
            return other.mMainColor == mMainColor &&
                    other.mSecondaryColor == mSecondaryColor &&
                    other.mSupportsDarkText == mSupportsDarkText;
        }

        @Override
        public int hashCode() {
            int code = mMainColor;
            code = 31 * code + mSecondaryColor;
            code = 31 * code + (mSupportsDarkText ? 0 : 1);
            return code;
        }
    }

    public interface OnColorsChangedListener {
        void onColorsChanged(GradientColors colors, int which);
    }
}
