/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.settingslib.display;

import android.content.Context;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.settingslib.R;

import java.util.Arrays;

/**
 * Utility methods for working with display density.
 */
public class DisplayDensityUtils {
    private static final String LOG_TAG = "DisplayDensityUtils";

    /** Minimum increment between density scales. */
    private static final float MIN_SCALE_INTERVAL = 0.09f;

    /** Minimum density scale. This is available on all devices. */
    private static final float MIN_SCALE = 0.70f;

    /** Maximum density scale. The actual scale used depends on the device. */
    private static final float MAX_SCALE = 1.50f;

    /** Summary used for "default" scale. */
    public static final int SUMMARY_DEFAULT = R.string.screen_zoom_summary_default;

    /** Summary used for "custom" scale. */
    private static final int SUMMARY_CUSTOM = R.string.screen_zoom_summary_custom;

    /**
     * Summaries for scales smaller than "default" in order of smallest to
     * largest.
     */
    private static final int[] SUMMARIES_SMALLER = new int[] {
            R.string.screen_zoom_summary_small,
            R.string.screen_zoom_summary_smaller,
            R.string.screen_zoom_summary_smallest
    };

    /**
     * Summaries for scales larger than "default" in order of smallest to
     * largest.
     */
    private static final int[] SUMMARIES_LARGER = new int[] {
            R.string.screen_zoom_summary_large,
            R.string.screen_zoom_summary_very_large,
            R.string.screen_zoom_summary_extremely_large,
    };

    /**
     * Minimum allowed screen dimension, corresponds to resource qualifiers
     * "small" or "sw320dp". This value must be at least the minimum screen
     * size required by the CDD so that we meet developer expectations.
     */
    private static final int MIN_DIMENSION_DP = 320;

    private final String[] mEntries;
    private final int[] mValues;

    private final int mDefaultDensity;
    private final int mCurrentIndex;

    public DisplayDensityUtils(Context context) {
        final int defaultDensity = DisplayDensityUtils.getDefaultDisplayDensity(
                Display.DEFAULT_DISPLAY);
        if (defaultDensity <= 0) {
            mEntries = null;
            mValues = null;
            mDefaultDensity = 0;
            mCurrentIndex = -1;
            return;
        }

        final Resources res = context.getResources();
        final DisplayMetrics metrics = new DisplayMetrics();
        context.getDisplay().getRealMetrics(metrics);

        final int currentDensity = metrics.densityDpi;
        int currentDensityIndex = -1;

        // Compute number of "larger" and "smaller" scales for this display.
        final int minDimensionPx = Math.min(metrics.widthPixels, metrics.heightPixels);
        final int maxDensity = DisplayMetrics.DENSITY_MEDIUM * minDimensionPx / MIN_DIMENSION_DP;
        final float maxScale = Math.min(MAX_SCALE, maxDensity / (float) defaultDensity);
        final float minScale = MIN_SCALE;
        final int numLarger = (int) MathUtils.constrain((maxScale - 1) / MIN_SCALE_INTERVAL,
                0, SUMMARIES_LARGER.length);
        final int numSmaller = (int) MathUtils.constrain((1 - minScale) / MIN_SCALE_INTERVAL,
                0, SUMMARIES_SMALLER.length);

        String[] entries = new String[1 + numSmaller + numLarger];
        int[] values = new int[entries.length];
        int curIndex = 0;

        if (numSmaller > 0) {
            final float interval = (1 - minScale) / numSmaller;
            for (int i = numSmaller - 1; i >= 0; i--) {
                // Round down to a multiple of 2 by truncating the low bit.
                final int density = ((int) (defaultDensity * (1 - (i + 1) * interval))) & ~1;
                if (currentDensity == density) {
                    currentDensityIndex = curIndex;
                }
                entries[curIndex] = res.getString(SUMMARIES_SMALLER[i]);
                values[curIndex] = density;
                curIndex++;
            }
        }

        if (currentDensity == defaultDensity) {
            currentDensityIndex = curIndex;
        }
        values[curIndex] = defaultDensity;
        entries[curIndex] = res.getString(SUMMARY_DEFAULT);
        curIndex++;

        if (numLarger > 0) {
            final float interval = (maxScale - 1) / numLarger;
            for (int i = 0; i < numLarger; i++) {
                // Round down to a multiple of 2 by truncating the low bit.
                final int density = ((int) (defaultDensity * (1 + (i + 1) * interval))) & ~1;
                if (currentDensity == density) {
                    currentDensityIndex = curIndex;
                }
                values[curIndex] = density;
                entries[curIndex] = res.getString(SUMMARIES_LARGER[i]);
                curIndex++;
            }
        }

        final int displayIndex;
        if (currentDensityIndex >= 0) {
            displayIndex = currentDensityIndex;
        } else {
            // We don't understand the current density. Must have been set by
            // someone else. Make room for another entry...
            int newLength = values.length + 1;
            values = Arrays.copyOf(values, newLength);
            values[curIndex] = currentDensity;

            entries = Arrays.copyOf(entries, newLength);
            entries[curIndex] = res.getString(SUMMARY_CUSTOM, currentDensity);

            displayIndex = curIndex;
        }

        mDefaultDensity = defaultDensity;
        mCurrentIndex = displayIndex;
        mEntries = entries;
        mValues = values;
    }

    public String[] getEntries() {
        return mEntries;
    }

    public int[] getValues() {
        return mValues;
    }

    public int getCurrentIndex() {
        return mCurrentIndex;
    }

    public int getDefaultDensity() {
        return mDefaultDensity;
    }

    /**
     * Returns the default density for the specified display.
     *
     * @param displayId the identifier of the display
     * @return the default density of the specified display, or {@code -1} if
     *         the display does not exist or the density could not be obtained
     */
    private static int getDefaultDisplayDensity(int displayId) {
       try {
           final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
           return wm.getInitialDisplayDensity(displayId);
       } catch (RemoteException exc) {
           return -1;
       }
    }

    /**
     * Asynchronously applies display density changes to the specified display.
     * <p>
     * The change will be applied to the user specified by the value of
     * {@link UserHandle#myUserId()} at the time the method is called.
     *
     * @param displayId the identifier of the display to modify
     */
    public static void clearForcedDisplayDensity(final int displayId) {
        final int userId = UserHandle.myUserId();
        AsyncTask.execute(() -> {
            try {
                final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                wm.clearForcedDisplayDensityForUser(displayId, userId);
            } catch (RemoteException exc) {
                Log.w(LOG_TAG, "Unable to clear forced display density setting");
            }
        });
    }

    /**
     * Asynchronously applies display density changes to the specified display.
     * <p>
     * The change will be applied to the user specified by the value of
     * {@link UserHandle#myUserId()} at the time the method is called.
     *
     * @param displayId the identifier of the display to modify
     * @param density the density to force for the specified display
     */
    public static void setForcedDisplayDensity(final int displayId, final int density) {
        final int userId = UserHandle.myUserId();
        AsyncTask.execute(() -> {
            try {
                final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                wm.setForcedDisplayDensityForUser(displayId, density, userId);
            } catch (RemoteException exc) {
                Log.w(LOG_TAG, "Unable to save forced display density setting");
            }
        });
    }
}
