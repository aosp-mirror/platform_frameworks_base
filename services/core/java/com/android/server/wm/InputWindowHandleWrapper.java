/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Region;
import android.os.IBinder;
import android.view.IWindow;
import android.view.InputApplicationHandle;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;

import java.util.Objects;

/**
 * The wrapper of {@link InputWindowHandle} with field change detection to reduce unnecessary
 * updates to surface, e.g. if there are no changes, then skip invocation of
 * {@link SurfaceControl.Transaction#setInputWindowInfo(SurfaceControl, InputWindowHandle)}.
 */
class InputWindowHandleWrapper {
    /** The wrapped handle should not be directly exposed to avoid untracked changes. */
    private final @NonNull InputWindowHandle mHandle;

    /** Whether the {@link #mHandle} is changed. */
    private boolean mChanged = true;

    InputWindowHandleWrapper(@NonNull InputWindowHandle handle) {
        mHandle = handle;
    }

    /**
     * Returns {@code true} if the input window handle has changed since the last invocation of
     * {@link #applyChangesToSurface(SurfaceControl.Transaction, SurfaceControl)}}
     */
    boolean isChanged() {
        return mChanged;
    }

    void forceChange() {
        mChanged = true;
    }

    void applyChangesToSurface(@NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl sc) {
        t.setInputWindowInfo(sc, mHandle);
        mChanged = false;
    }

    int getDisplayId() {
        return mHandle.displayId;
    }

    boolean isFocusable() {
        return mHandle.focusable;
    }

    InputApplicationHandle getInputApplicationHandle() {
        return mHandle.inputApplicationHandle;
    }

    void setInputApplicationHandle(InputApplicationHandle handle) {
        if (mHandle.inputApplicationHandle == handle) {
            return;
        }
        mHandle.inputApplicationHandle = handle;
        mChanged = true;
    }

    void setToken(IBinder token) {
        if (mHandle.token == token) {
            return;
        }
        mHandle.token = token;
        mChanged = true;
    }

    void setName(String name) {
        if (Objects.equals(mHandle.name, name)) {
            return;
        }
        mHandle.name = name;
        mChanged = true;
    }

    void setLayoutParamsFlags(int flags) {
        if (mHandle.layoutParamsFlags == flags) {
            return;
        }
        mHandle.layoutParamsFlags = flags;
        mChanged = true;
    }

    void setLayoutParamsType(int type) {
        if (mHandle.layoutParamsType == type) {
            return;
        }
        mHandle.layoutParamsType = type;
        mChanged = true;
    }

    void setDispatchingTimeoutMillis(long timeout) {
        if (mHandle.dispatchingTimeoutMillis == timeout) {
            return;
        }
        mHandle.dispatchingTimeoutMillis = timeout;
        mChanged = true;
    }

    void setTouchableRegion(Region region) {
        if (mHandle.touchableRegion.equals(region)) {
            return;
        }
        mHandle.touchableRegion.set(region);
        mChanged = true;
    }

    void clearTouchableRegion() {
        if (mHandle.touchableRegion.isEmpty()) {
            return;
        }
        mHandle.touchableRegion.setEmpty();
        mChanged = true;
    }

    void setVisible(boolean visible) {
        if (mHandle.visible == visible) {
            return;
        }
        mHandle.visible = visible;
        mChanged = true;
    }

    void setFocusable(boolean focusable) {
        if (mHandle.focusable == focusable) {
            return;
        }
        mHandle.focusable = focusable;
        mChanged = true;
    }

    void setTouchOcclusionMode(int mode) {
        if (mHandle.touchOcclusionMode == mode) {
            return;
        }
        mHandle.touchOcclusionMode = mode;
        mChanged = true;
    }

    void setHasWallpaper(boolean hasWallpaper) {
        if (mHandle.hasWallpaper == hasWallpaper) {
            return;
        }
        mHandle.hasWallpaper = hasWallpaper;
        mChanged = true;
    }

    void setPaused(boolean paused) {
        if (mHandle.paused == paused) {
            return;
        }
        mHandle.paused = paused;
        mChanged = true;
    }

    void setTrustedOverlay(boolean trustedOverlay) {
        if (mHandle.trustedOverlay == trustedOverlay) {
            return;
        }
        mHandle.trustedOverlay = trustedOverlay;
        mChanged = true;
    }

    void setOwnerPid(int pid) {
        if (mHandle.ownerPid == pid) {
            return;
        }
        mHandle.ownerPid = pid;
        mChanged = true;
    }

    void setOwnerUid(int uid) {
        if (mHandle.ownerUid == uid) {
            return;
        }
        mHandle.ownerUid = uid;
        mChanged = true;
    }

    void setPackageName(String packageName) {
        if (Objects.equals(mHandle.packageName, packageName)) {
            return;
        }
        mHandle.packageName = packageName;
        mChanged = true;
    }

    void setInputFeatures(int features) {
        if (mHandle.inputFeatures == features) {
            return;
        }
        mHandle.inputFeatures = features;
        mChanged = true;
    }

    void setDisplayId(int displayId) {
        if (mHandle.displayId == displayId) {
            return;
        }
        mHandle.displayId = displayId;
        mChanged = true;
    }

    void setFrame(int left, int top, int right, int bottom) {
        if (mHandle.frameLeft == left && mHandle.frameTop == top && mHandle.frameRight == right
                && mHandle.frameBottom == bottom) {
            return;
        }
        mHandle.frameLeft = left;
        mHandle.frameTop = top;
        mHandle.frameRight = right;
        mHandle.frameBottom = bottom;
        mChanged = true;
    }

    void setSurfaceInset(int inset) {
        if (mHandle.surfaceInset == inset) {
            return;
        }
        mHandle.surfaceInset = inset;
        mChanged = true;
    }

    void setScaleFactor(float scale) {
        if (mHandle.scaleFactor == scale) {
            return;
        }
        mHandle.scaleFactor = scale;
        mChanged = true;
    }

    void setTouchableRegionCrop(@Nullable SurfaceControl bounds) {
        if (mHandle.touchableRegionSurfaceControl.get() == bounds) {
            return;
        }
        mHandle.setTouchableRegionCrop(bounds);
        mChanged = true;
    }

    void setReplaceTouchableRegionWithCrop(boolean replace) {
        if (mHandle.replaceTouchableRegionWithCrop == replace) {
            return;
        }
        mHandle.replaceTouchableRegionWithCrop = replace;
        mChanged = true;
    }

    void setWindowToken(IWindow windowToken) {
        if (mHandle.getWindow() == windowToken) {
            return;
        }
        mHandle.setWindowToken(windowToken);
        mChanged = true;
    }

    @Override
    public String toString() {
        return mHandle + ", changed=" + mChanged;
    }
}
