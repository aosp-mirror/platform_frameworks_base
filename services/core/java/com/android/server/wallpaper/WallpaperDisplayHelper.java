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

import static android.view.Display.DEFAULT_DISPLAY;

import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Debug;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.WindowManagerInternal;

import java.util.function.Consumer;
/**
 * Internal class used to store all the display data relevant to the wallpapers
 */
class WallpaperDisplayHelper {

    @VisibleForTesting
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

    WallpaperDisplayHelper(
            DisplayManager displayManager,
            WindowManagerInternal windowManagerInternal) {
        mDisplayManager = displayManager;
        mWindowManagerInternal = windowManagerInternal;
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
            return mWindowManagerInternal.shouldShowSystemDecorOnDisplay(displayId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    boolean isValidDisplay(int displayId) {
        return mDisplayManager.getDisplay(displayId) != null;
    }
}
