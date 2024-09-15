/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input;

import static android.view.PointerIcon.DEFAULT_POINTER_SCALE;
import static android.view.PointerIcon.POINTER_ICON_VECTOR_STYLE_FILL_BLACK;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.PointerIcon;

import com.android.internal.annotations.GuardedBy;
import com.android.server.UiThread;

import java.util.Objects;

/**
 * A thread-safe component of {@link InputManagerService} responsible for caching loaded
 * {@link PointerIcon}s and triggering reloading of the icons.
 */
final class PointerIconCache {
    private static final String TAG = PointerIconCache.class.getSimpleName();

    private final Context mContext;

    // Do not hold the lock when calling into native code.
    private final NativeInputManagerService mNative;

    // We use the UI thread for loading pointer icons.
    private final Handler mUiThreadHandler = UiThread.getHandler();

    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    private final SparseArray<SparseArray<PointerIcon>> mLoadedPointerIconsByDisplayAndType =
            new SparseArray<>();
    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    private boolean mUseLargePointerIcons = false;
    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    private final SparseArray<Context> mDisplayContexts = new SparseArray<>();
    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    private final SparseIntArray mDisplayDensities = new SparseIntArray();
    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    private @PointerIcon.PointerIconVectorStyleFill int mPointerIconFillStyle =
            POINTER_ICON_VECTOR_STYLE_FILL_BLACK;
    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    private float mPointerIconScale = DEFAULT_POINTER_SCALE;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    synchronized (mLoadedPointerIconsByDisplayAndType) {
                        updateDisplayDensityLocked(displayId);
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    synchronized (mLoadedPointerIconsByDisplayAndType) {
                        mLoadedPointerIconsByDisplayAndType.remove(displayId);
                        mDisplayContexts.remove(displayId);
                        mDisplayDensities.delete(displayId);
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    handleDisplayChanged(displayId);
                }
            };

    /* package */ PointerIconCache(Context context, NativeInputManagerService nativeService) {
        mContext = context;
        mNative = nativeService;
    }

    public void systemRunning() {
        final DisplayManager displayManager = Objects.requireNonNull(
                mContext.getSystemService(DisplayManager.class));
        displayManager.registerDisplayListener(mDisplayListener, mUiThreadHandler);
        final Display[] displays = displayManager.getDisplays();
        for (int i = 0; i < displays.length; i++) {
            mDisplayListener.onDisplayAdded(displays[i].getDisplayId());
        }
    }

    public void monitor() {
        synchronized (mLoadedPointerIconsByDisplayAndType) { /* Test if blocked by lock */}
    }

    /** Set whether the large pointer icons should be used for accessibility. */
    public void setUseLargePointerIcons(boolean useLargeIcons) {
        mUiThreadHandler.post(() -> handleSetUseLargePointerIcons(useLargeIcons));
    }

    /** Set the fill style for vector pointer icons. */
    public void setPointerFillStyle(@PointerIcon.PointerIconVectorStyleFill int fillStyle) {
        mUiThreadHandler.post(() -> handleSetPointerFillStyle(fillStyle));
    }

    /** Set the scale for vector pointer icons. */
    public void setPointerScale(float scale) {
        mUiThreadHandler.post(() -> handleSetPointerScale(scale));
    }

    /**
     * Get a loaded system pointer icon. This will fetch the icon from the cache, or load it if
     * it isn't already cached.
     */
    public @NonNull PointerIcon getLoadedPointerIcon(int displayId, int type) {
        synchronized (mLoadedPointerIconsByDisplayAndType) {
            SparseArray<PointerIcon> iconsByType = mLoadedPointerIconsByDisplayAndType.get(
                    displayId);
            if (iconsByType == null) {
                iconsByType = new SparseArray<>();
                mLoadedPointerIconsByDisplayAndType.put(displayId, iconsByType);
            }
            PointerIcon icon = iconsByType.get(type);
            if (icon == null) {
                Context context = getContextForDisplayLocked(displayId);
                Resources.Theme theme = context.getResources().newTheme();
                theme.setTo(context.getTheme());
                theme.applyStyle(PointerIcon.vectorFillStyleToResource(mPointerIconFillStyle),
                        /* force= */ true);
                icon = PointerIcon.getLoadedSystemIcon(new ContextThemeWrapper(context, theme),
                        type, mUseLargePointerIcons, mPointerIconScale);
                iconsByType.put(type, icon);
            }
            return Objects.requireNonNull(icon);
        }
    }

    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    private @NonNull Context getContextForDisplayLocked(int displayId) {
        if (displayId == Display.INVALID_DISPLAY) {
            // Fallback to using the default context.
            return mContext;
        }
        if (displayId == mContext.getDisplay().getDisplayId()) {
            return mContext;
        }

        Context displayContext = mDisplayContexts.get(displayId);
        if (displayContext == null) {
            final DisplayManager displayManager = Objects.requireNonNull(
                    mContext.getSystemService(DisplayManager.class));
            final Display display = displayManager.getDisplay(displayId);
            if (display == null) {
                // Fallback to using the default context.
                return mContext;
            }

            displayContext = mContext.createDisplayContext(display);
            mDisplayContexts.put(displayId, displayContext);
        }
        return displayContext;
    }

    @android.annotation.UiThread
    private void handleDisplayChanged(int displayId) {
        synchronized (mLoadedPointerIconsByDisplayAndType) {
            if (!updateDisplayDensityLocked(displayId)) {
                return;
            }
            // The display density changed, so force all cached pointer icons to be
            // reloaded for the display.
            Slog.i(TAG, "Reloading pointer icons due to density change on display: " + displayId);
            var iconsByType = mLoadedPointerIconsByDisplayAndType.get(displayId);
            if (iconsByType == null) {
                return;
            }
            iconsByType.clear();
            mDisplayContexts.remove(displayId);
        }
        mNative.reloadPointerIcons();
    }

    @android.annotation.UiThread
    private void handleSetUseLargePointerIcons(boolean useLargeIcons) {
        synchronized (mLoadedPointerIconsByDisplayAndType) {
            if (mUseLargePointerIcons == useLargeIcons) {
                return;
            }
            mUseLargePointerIcons = useLargeIcons;
            // Clear all cached icons on all displays.
            mLoadedPointerIconsByDisplayAndType.clear();
        }
        mNative.reloadPointerIcons();
    }

    @android.annotation.UiThread
    private void handleSetPointerFillStyle(@PointerIcon.PointerIconVectorStyleFill int fillStyle) {
        synchronized (mLoadedPointerIconsByDisplayAndType) {
            if (mPointerIconFillStyle == fillStyle) {
                return;
            }
            mPointerIconFillStyle = fillStyle;
            // Clear all cached icons on all displays.
            mLoadedPointerIconsByDisplayAndType.clear();
        }
        mNative.reloadPointerIcons();
    }

    @android.annotation.UiThread
    private void handleSetPointerScale(float scale) {
        synchronized (mLoadedPointerIconsByDisplayAndType) {
            if (mPointerIconScale == scale) {
                return;
            }
            mPointerIconScale = scale;
            // Clear all cached icons on all displays.
            mLoadedPointerIconsByDisplayAndType.clear();
        }
        mNative.reloadPointerIcons();
    }

    // Updates the cached display density for the given displayId, and returns true if
    // the cached density changed.
    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    private boolean updateDisplayDensityLocked(int displayId) {
        final DisplayManager displayManager = Objects.requireNonNull(
                mContext.getSystemService(DisplayManager.class));
        final Display display = displayManager.getDisplay(displayId);
        if (display == null) {
            return false;
        }
        DisplayInfo info = new DisplayInfo();
        display.getDisplayInfo(info);
        final int oldDensity = mDisplayDensities.get(displayId, 0 /* default */);
        if (oldDensity == info.logicalDensityDpi) {
            return false;
        }
        mDisplayDensities.put(displayId, info.logicalDensityDpi);
        return true;
    }
}
