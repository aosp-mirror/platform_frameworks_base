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
import android.hardware.display.DisplayManager;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.R;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Utility methods for working with display density.
 */
public class DisplayDensityUtils {
    private static final String LOG_TAG = "DisplayDensityUtils";

    /** Summary used for "default" scale. */
    public static final int SUMMARY_DEFAULT = R.string.screen_zoom_summary_default;

    /** Summary used for "custom" scale. */
    private static final int SUMMARY_CUSTOM = R.string.screen_zoom_summary_custom;

    /**
     * Summaries for scales smaller than "default" in order of smallest to
     * largest.
     */
    private static final int[] SUMMARIES_SMALLER = new int[] {
            R.string.screen_zoom_summary_small
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

    private static final Predicate<DisplayInfo> INTERNAL_ONLY =
            (info) -> info.type == Display.TYPE_INTERNAL;

    private final Predicate<DisplayInfo> mPredicate;

    private final DisplayManager mDisplayManager;

    /**
     * The text description of the density values.
     */
    @Nullable
    private final String[] mEntries;

    /**
     * The density values.
     */
    @Nullable
    private final int[] mValues;

    private final int mDefaultDensity;
    private final int mCurrentIndex;

    public DisplayDensityUtils(@NonNull Context context) {
        this(context, INTERNAL_ONLY);
    }

    /**
     * Creates an instance that stores the density values for the smallest display that satisfies
     * the predicate. It is enough to store the values for one display because the same density
     * should be set to all the displays that satisfy the predicate.
     * @param context The context
     * @param predicate Determines what displays the density should be set for. The default display
     *                  must satisfy this predicate.
     */
    public DisplayDensityUtils(@NonNull Context context,
            @NonNull Predicate<DisplayInfo> predicate) {
        mPredicate = predicate;
        mDisplayManager = context.getSystemService(DisplayManager.class);

        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayInfo defaultDisplayInfo = new DisplayInfo();
        if (!defaultDisplay.getDisplayInfo(defaultDisplayInfo)) {
            Log.w(LOG_TAG, "Cannot fetch display info for the default display");
            mEntries = null;
            mValues = null;
            mDefaultDensity = 0;
            mCurrentIndex = -1;
            return;
        }
        if (!mPredicate.test(defaultDisplayInfo)) {
            throw new IllegalArgumentException(
                    "Predicate must not filter out the default display.");
        }

        int idOfSmallestDisplay = Display.DEFAULT_DISPLAY;
        int minDimensionPx = Math.min(defaultDisplayInfo.logicalWidth,
                defaultDisplayInfo.logicalHeight);
        for (Display display : mDisplayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)) {
            DisplayInfo info = new DisplayInfo();
            if (!display.getDisplayInfo(info)) {
                Log.w(LOG_TAG, "Cannot fetch display info for display " + display.getDisplayId());
                continue;
            }
            if (!mPredicate.test(info)) {
                continue;
            }
            int minDimension = Math.min(info.logicalWidth, info.logicalHeight);
            if (minDimension < minDimensionPx) {
                minDimensionPx = minDimension;
                idOfSmallestDisplay = display.getDisplayId();
            }
        }

        final int defaultDensity =
                DisplayDensityUtils.getDefaultDensityForDisplay(idOfSmallestDisplay);
        if (defaultDensity <= 0) {
            Log.w(LOG_TAG, "Cannot fetch default density for display " + idOfSmallestDisplay);
            mEntries = null;
            mValues = null;
            mDefaultDensity = 0;
            mCurrentIndex = -1;
            return;
        }

        final Resources res = context.getResources();

        final int currentDensity = defaultDisplayInfo.logicalDensityDpi;
        int currentDensityIndex = -1;

        // Compute number of "larger" and "smaller" scales for this display.
        final int maxDensity =
                DisplayMetrics.DENSITY_MEDIUM * minDimensionPx / MIN_DIMENSION_DP;
        final float maxScaleDimen = context.getResources().getFraction(
                R.fraction.display_density_max_scale, 1, 1);
        final float maxScale = Math.min(maxScaleDimen, maxDensity / (float) defaultDensity);
        final float minScale = context.getResources().getFraction(
                R.fraction.display_density_min_scale, 1, 1);
        final float minScaleInterval = context.getResources().getFraction(
                R.fraction.display_density_min_scale_interval, 1, 1);
        final int numLarger = (int) MathUtils.constrain((maxScale - 1) / minScaleInterval,
                0, SUMMARIES_LARGER.length);
        final int numSmaller = (int) MathUtils.constrain((1 - minScale) / minScaleInterval,
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

    @Nullable
    public String[] getEntries() {
        return mEntries;
    }

    @Nullable
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
    private static int getDefaultDensityForDisplay(int displayId) {
       try {
           final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
           return wm.getInitialDisplayDensity(displayId);
       } catch (RemoteException exc) {
           return -1;
       }
    }

    /**
     * Asynchronously applies display density changes to the displays that satisfy the predicate.
     * <p>
     * The change will be applied to the user specified by the value of
     * {@link UserHandle#myUserId()} at the time the method is called.
     */
    public void clearForcedDisplayDensity() {
        final int userId = UserHandle.myUserId();
        AsyncTask.execute(() -> {
            try {
                for (Display display : mDisplayManager.getDisplays(
                        DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)) {
                    int displayId = display.getDisplayId();
                    DisplayInfo info = new DisplayInfo();
                    if (!display.getDisplayInfo(info)) {
                        Log.w(LOG_TAG, "Unable to clear forced display density setting "
                                + "for display " + displayId);
                        continue;
                    }
                    if (!mPredicate.test(info)) {
                        continue;
                    }

                    final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                    wm.clearForcedDisplayDensityForUser(displayId, userId);
                }
            } catch (RemoteException exc) {
                Log.w(LOG_TAG, "Unable to clear forced display density setting");
            }
        });
    }

    /**
     * Asynchronously applies display density changes to the displays that satisfy the predicate.
     * <p>
     * The change will be applied to the user specified by the value of
     * {@link UserHandle#myUserId()} at the time the method is called.
     *
     * @param index The index of the density value
     */
    public void setForcedDisplayDensity(final int index) {
        final int userId = UserHandle.myUserId();
        AsyncTask.execute(() -> {
            try {
                for (Display display : mDisplayManager.getDisplays(
                        DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)) {
                    int displayId = display.getDisplayId();
                    DisplayInfo info = new DisplayInfo();
                    if (!display.getDisplayInfo(info)) {
                        Log.w(LOG_TAG, "Unable to save forced display density setting "
                                + "for display " + displayId);
                        continue;
                    }
                    if (!mPredicate.test(info)) {
                        continue;
                    }

                    final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                    wm.setForcedDisplayDensityForUser(displayId, mValues[index], userId);
                }
            } catch (RemoteException exc) {
                Log.w(LOG_TAG, "Unable to save forced display density setting");
            }
        });
    }
}
