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
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.colorextraction.types.ExtractionType;
import com.google.android.colorextraction.types.Tonal;

import java.util.ArrayList;

/**
 * Class to process wallpaper colors and generate a tonal palette based on them.
 */
public class ColorExtractor implements WallpaperManager.OnColorsChangedListener {

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_DARK = 1;
    public static final int TYPE_EXTRA_DARK = 2;
    private static final int[] sGradientTypes = new int[]{TYPE_NORMAL, TYPE_DARK, TYPE_EXTRA_DARK};

    private static final String TAG = "ColorExtractor";

    @VisibleForTesting
    static final int FALLBACK_COLOR = 0xff83888d;

    private int mMainFallbackColor = FALLBACK_COLOR;
    private int mSecondaryFallbackColor = FALLBACK_COLOR;
    private final SparseArray<GradientColors[]> mGradientColors;
    private final ArrayList<OnColorsChangedListener> mOnColorsChangedListeners;
    // Colors to return when the wallpaper isn't visible
    private final GradientColors mWpHiddenColors;
    private final Context mContext;
    private final ExtractionType mExtractionType;

    public ColorExtractor(Context context) {
        this(context, new Tonal());
    }

    @VisibleForTesting
    public ColorExtractor(Context context, ExtractionType extractionType) {
        mContext = context;
        mWpHiddenColors = new GradientColors();
        mWpHiddenColors.setMainColor(FALLBACK_COLOR);
        mWpHiddenColors.setSecondaryColor(FALLBACK_COLOR);
        mExtractionType = extractionType;

        mGradientColors = new SparseArray<>();
        for (int which : new int[] { WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM}) {
            GradientColors[] colors = new GradientColors[sGradientTypes.length];
            mGradientColors.append(which, colors);
            for (int type : sGradientTypes) {
                colors[type] = new GradientColors();
            }
        }

        mOnColorsChangedListeners = new ArrayList<>();

        WallpaperManager wallpaperManager = mContext.getSystemService(WallpaperManager.class);
        if (wallpaperManager == null) {
            Log.w(TAG, "Can't listen to color changes!");
        } else {
            wallpaperManager.addOnColorsChangedListener(this);

            // Initialize all gradients with the current colors
            GradientColors[] systemColors = mGradientColors.get(WallpaperManager.FLAG_SYSTEM);
            extractInto(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM),
                    systemColors[TYPE_NORMAL],
                    systemColors[TYPE_DARK],
                    systemColors[TYPE_EXTRA_DARK]);

            GradientColors[] lockColors = mGradientColors.get(WallpaperManager.FLAG_LOCK);
            extractInto(wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_LOCK),
                    lockColors[TYPE_NORMAL],
                    lockColors[TYPE_DARK],
                    lockColors[TYPE_EXTRA_DARK]);
        }
    }

    /**
     * Retrieve TYPE_NORMAL gradient colors considering wallpaper visibility.
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @return colors
     */
    @NonNull
    public GradientColors getColors(int which) {
        return getColors(which, TYPE_NORMAL);
    }

    /**
     * Get current gradient colors for one of the possible gradient types
     *
     * @param which FLAG_LOCK or FLAG_SYSTEM
     * @param type TYPE_NORMAL, TYPE_DARK or TYPE_EXTRA_DARK
     * @return colors
     */
    public GradientColors getColors(int which, int type) {
        if (type != TYPE_NORMAL && type != TYPE_DARK && type != TYPE_EXTRA_DARK) {
            throw new IllegalArgumentException(
                    "type should be TYPE_NORMAL, TYPE_DARK or TYPE_EXTRA_DARK");
        }
        if (which != WallpaperManager.FLAG_LOCK && which != WallpaperManager.FLAG_SYSTEM) {
            throw new IllegalArgumentException("which should be FLAG_SYSTEM or FLAG_NORMAL");
        }

        return mGradientColors.get(which)[type];
    }

    @Override
    public void onColorsChanged(WallpaperColors colors, int which) {
        boolean changed = false;
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            GradientColors[] lockColors = mGradientColors.get(WallpaperManager.FLAG_LOCK);
            extractInto(colors, lockColors[TYPE_NORMAL], lockColors[TYPE_DARK],
                    lockColors[TYPE_EXTRA_DARK]);

            changed = true;
        }
        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
            GradientColors[] systemColors = mGradientColors.get(WallpaperManager.FLAG_SYSTEM);
            extractInto(colors, systemColors[TYPE_NORMAL], systemColors[TYPE_DARK],
                    systemColors[TYPE_EXTRA_DARK]);
            changed = true;
        }

        if (changed) {
            triggerColorsChanged(which);
        }
    }

    private void triggerColorsChanged(int which) {
        for (OnColorsChangedListener listener: mOnColorsChangedListeners) {
            listener.onColorsChanged(this, which);
        }
    }

    private void extractInto(WallpaperColors inWallpaperColors,
            GradientColors outGradientColorsNormal, GradientColors outGradientColorsDark,
            GradientColors outGradientColorsExtraDark) {
        if (inWallpaperColors == null) {
            applyFallback(outGradientColorsNormal);
            applyFallback(outGradientColorsDark);
            applyFallback(outGradientColorsExtraDark);
            return;
        }
        boolean success = mExtractionType.extractInto(inWallpaperColors, outGradientColorsNormal,
                outGradientColorsDark, outGradientColorsExtraDark);
        if (!success) {
            applyFallback(outGradientColorsNormal);
            applyFallback(outGradientColorsDark);
            applyFallback(outGradientColorsExtraDark);
        }
    }

    private void applyFallback(GradientColors outGradientColors) {
        outGradientColors.setMainColor(mMainFallbackColor);
        outGradientColors.setSecondaryColor(mSecondaryFallbackColor);
        outGradientColors.setSupportsDarkText(false);
    }

    public void destroy() {
        WallpaperManager wallpaperManager = mContext.getSystemService(WallpaperManager.class);
        if (wallpaperManager != null) {
            wallpaperManager.removeOnColorsChangedListener(this);
        }
    }

    public void addOnColorsChangedListener(@NonNull OnColorsChangedListener listener) {
        mOnColorsChangedListeners.add(listener);
    }

    public void removeOnColorsChangedListener(@NonNull OnColorsChangedListener listener) {
        mOnColorsChangedListeners.remove(listener);
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

        @Override
        public String toString() {
            return "GradientColors(" + Integer.toHexString(mMainColor) + ", "
                    + Integer.toHexString(mSecondaryColor) + ")";
        }
    }

    public interface OnColorsChangedListener {
        void onColorsChanged(ColorExtractor colorExtractor, int which);
    }
}
