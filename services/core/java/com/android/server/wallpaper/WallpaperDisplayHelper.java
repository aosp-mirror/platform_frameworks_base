/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wallpaper;

import static android.app.WallpaperManager.ORIENTATION_UNKNOWN;
import static android.app.WallpaperManager.getRotatedOrientation;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.window.flags.Flags.multiCrop;

import android.app.WallpaperManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Debug;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.WindowManager;
import android.view.WindowMetrics;

import com.android.server.wm.WindowManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Internal class used to store all the display data relevant to the wallpapers
 */
class WallpaperDisplayHelper {

    static final class DisplayData {
        int mWidth = -1;
        int mHeight = -1;
        final Rect mPadding = new Rect(0, 0, 0, 0);
        final int mDisplayId;
        DisplayData(int displayId) {
            mDisplayId = displayId;
        }
    }

    private static final String TAG = WallpaperDisplayHelper.class.getSimpleName();
    private final SparseArray<DisplayData> mDisplayDatas = new SparseArray<>();
    private final DisplayManager mDisplayManager;
    private final WindowManagerInternal mWindowManagerInternal;
    private final SparseArray<Point> mDefaultDisplaySizes = new SparseArray<>();

    // related orientations pairs for foldable (folded orientation, unfolded orientation)
    private final List<Pair<Integer, Integer>> mFoldableOrientationPairs = new ArrayList<>();

    private boolean mIsFoldable;

    WallpaperDisplayHelper(
            DisplayManager displayManager,
            WindowManager windowManager,
            WindowManagerInternal windowManagerInternal,
            boolean isFoldable) {
        mDisplayManager = displayManager;
        mWindowManagerInternal = windowManagerInternal;
        mIsFoldable = isFoldable;
        if (!multiCrop()) return;
        Set<WindowMetrics> metrics = windowManager.getPossibleMaximumWindowMetrics(DEFAULT_DISPLAY);
        boolean populateOrientationPairs = isFoldable && metrics.size() == 2;
        float surface = 0;
        int firstOrientation = -1;
        for (WindowMetrics metric: metrics) {
            Rect bounds = metric.getBounds();
            Point displaySize = new Point(bounds.width(), bounds.height());
            Point reversedDisplaySize = new Point(displaySize.y, displaySize.x);
            for (Point point : List.of(displaySize, reversedDisplaySize)) {
                int orientation = WallpaperManager.getOrientation(point);
                // don't add an entry if there is already a larger display of the same orientation
                Point display = mDefaultDisplaySizes.get(orientation);
                if (display == null || display.x * display.y < point.x * point.y) {
                    mDefaultDisplaySizes.put(orientation, point);
                }
            }
            if (populateOrientationPairs) {
                int orientation = WallpaperManager.getOrientation(displaySize);
                float newSurface = displaySize.x * displaySize.y * metric.getDensity();
                if (surface <= 0) {
                    surface = newSurface;
                    firstOrientation = orientation;
                } else {
                    Pair<Integer, Integer> pair = (newSurface > surface)
                            ? new Pair<>(firstOrientation, orientation)
                            : new Pair<>(orientation, firstOrientation);
                    Pair<Integer, Integer> rotatedPair = new Pair<>(
                            getRotatedOrientation(pair.first), getRotatedOrientation(pair.second));
                    mFoldableOrientationPairs.add(pair);
                    mFoldableOrientationPairs.add(rotatedPair);
                }
            }
        }
    }

    DisplayData getDisplayDataOrCreate(int displayId) {
        DisplayData wpdData = mDisplayDatas.get(displayId);
        if (wpdData == null) {
            wpdData = new DisplayData(displayId);
            ensureSaneWallpaperDisplaySize(wpdData, displayId);
            mDisplayDatas.append(displayId, wpdData);
        }
        return wpdData;
    }

    int getDefaultDisplayCurrentOrientation() {
        Point displaySize = new Point();
        mDisplayManager.getDisplay(DEFAULT_DISPLAY).getSize(displaySize);
        return WallpaperManager.getOrientation(displaySize);
    }

    void removeDisplayData(int displayId) {
        mDisplayDatas.remove(displayId);
    }

    void ensureSaneWallpaperDisplaySize(DisplayData wpdData, int displayId) {
        // We always want to have some reasonable width hint.
        final int baseSize = getMaximumSizeDimension(displayId);
        if (wpdData.mWidth < baseSize) {
            wpdData.mWidth = baseSize;
        }
        if (wpdData.mHeight < baseSize) {
            wpdData.mHeight = baseSize;
        }
    }

    int getMaximumSizeDimension(int displayId) {
        Display display = mDisplayManager.getDisplay(displayId);
        if (display == null) {
            Slog.w(TAG, "Invalid displayId=" + displayId + " " + Debug.getCallers(4));
            display = mDisplayManager.getDisplay(DEFAULT_DISPLAY);
        }
        return display.getMaximumSizeDimension();
    }

    void forEachDisplayData(Consumer<DisplayData> action) {
        for (int i = mDisplayDatas.size() - 1; i >= 0; i--) {
            final DisplayData wpdData = mDisplayDatas.valueAt(i);
            action.accept(wpdData);
        }
    }

    Display[] getDisplays() {
        return mDisplayManager.getDisplays();
    }

    DisplayInfo getDisplayInfo(int displayId) {
        final DisplayInfo displayInfo = new DisplayInfo();
        mDisplayManager.getDisplay(displayId).getDisplayInfo(displayInfo);
        return displayInfo;
    }

    boolean isUsableDisplay(int displayId, int clientUid) {
        return isUsableDisplay(mDisplayManager.getDisplay(displayId), clientUid);
    }

    boolean isUsableDisplay(Display display, int clientUid) {
        if (display == null || !display.hasAccess(clientUid)) {
            return false;
        }
        final int displayId = display.getDisplayId();
        if (displayId == DEFAULT_DISPLAY) {
            return true;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            return mWindowManagerInternal.isHomeSupportedOnDisplay(displayId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    boolean isValidDisplay(int displayId) {
        return mDisplayManager.getDisplay(displayId) != null;
    }

    SparseArray<Point> getDefaultDisplaySizes() {
        return mDefaultDisplaySizes;
    }

    /** Return the number of pixel of the largest dimension of the default display */
    int getDefaultDisplayLargestDimension() {
        int result = -1;
        for (int i = 0; i < mDefaultDisplaySizes.size(); i++) {
            Point size = mDefaultDisplaySizes.valueAt(i);
            result = Math.max(result, Math.max(size.x, size.y));
        }
        return result;
    }

    boolean isFoldable() {
        return mIsFoldable;
    }

    /**
     * If a given orientation corresponds to an unfolded orientation on foldable, return the
     * corresponding folded orientation. Otherwise, return UNKNOWN. Always return UNKNOWN if the
     * device is not a foldable.
     */
    int getFoldedOrientation(int orientation) {
        for (Pair<Integer, Integer> pair : mFoldableOrientationPairs) {
            if (pair.second.equals(orientation)) return pair.first;
        }
        return ORIENTATION_UNKNOWN;
    }

    /**
     * If a given orientation corresponds to a folded orientation on foldable, return the
     * corresponding unfolded orientation. Otherwise, return UNKNOWN. Always return UNKNOWN if the
     * device is not a foldable.
     */
    int getUnfoldedOrientation(int orientation) {
        for (Pair<Integer, Integer> pair : mFoldableOrientationPairs) {
            if (pair.first.equals(orientation)) return pair.second;
        }
        return ORIENTATION_UNKNOWN;
    }
}
