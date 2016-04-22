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

package com.android.server.wm;

import android.util.Slog;
import android.view.Display;

import java.io.PrintWriter;
import java.util.ArrayDeque;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYERS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.LAYER_OFFSET_DIM;
import static com.android.server.wm.WindowManagerService.WINDOW_LAYER_MULTIPLIER;

/**
 * Controller for assigning layers to windows on the display.
 *
 * This class encapsulates general algorithm for assigning layers and special rules that we need to
 * apply on top. The general algorithm goes through windows from bottom to the top and the higher
 * the window is, the higher layer is assigned. The final layer is equal to base layer +
 * adjustment from the order. This means that the window list is assumed to be ordered roughly by
 * the base layer (there are exceptions, e.g. due to keyguard and wallpaper and they need to be
 * handled with care, because they break the algorithm).
 *
 * On top of the general algorithm we add special rules, that govern such amazing things as:
 * <li>IME (which has higher base layer, but will be positioned above application windows)</li>
 * <li>docked/pinned windows (that need to be lifted above other application windows, including
 * animations)
 * <li>dock divider (which needs to live above applications, but below IME)</li>
 * <li>replaced windows, which need to live above their normal level, because they anticipate
 * an animation</li>.
 */
public class WindowLayersController {
    private final WindowManagerService mService;

    private int mInputMethodAnimLayerAdjustment;

    public WindowLayersController(WindowManagerService service) {
        mService = service;
    }

    private int mHighestApplicationLayer = 0;
    private ArrayDeque<WindowState> mPinnedWindows = new ArrayDeque<>();
    private ArrayDeque<WindowState> mDockedWindows = new ArrayDeque<>();
    private ArrayDeque<WindowState> mInputMethodWindows = new ArrayDeque<>();
    private WindowState mDockDivider = null;
    private ArrayDeque<WindowState> mReplacingWindows = new ArrayDeque<>();

    final void assignLayersLocked(WindowList windows) {
        if (DEBUG_LAYERS) Slog.v(TAG_WM, "Assigning layers based on windows=" + windows,
                new RuntimeException("here").fillInStackTrace());

        clear();
        int curBaseLayer = 0;
        int curLayer = 0;
        boolean anyLayerChanged = false;
        for (int i = 0, windowCount = windows.size(); i < windowCount; i++) {
            final WindowState w = windows.get(i);
            boolean layerChanged = false;

            int oldLayer = w.mLayer;
            if (w.mBaseLayer == curBaseLayer || w.mIsImWindow || (i > 0 && w.mIsWallpaper)) {
                curLayer += WINDOW_LAYER_MULTIPLIER;
            } else {
                curBaseLayer = curLayer = w.mBaseLayer;
            }
            assignAnimLayer(w, curLayer);

            // TODO: Preserved old behavior of code here but not sure comparing
            // oldLayer to mAnimLayer and mLayer makes sense...though the
            // worst case would be unintentionalp layer reassignment.
            if (w.mLayer != oldLayer || w.mWinAnimator.mAnimLayer != oldLayer) {
                layerChanged = true;
                anyLayerChanged = true;
            }

            if (w.mAppToken != null) {
                mHighestApplicationLayer = Math.max(mHighestApplicationLayer,
                        w.mWinAnimator.mAnimLayer);
            }
            collectSpecialWindows(w);

            if (layerChanged) {
                w.scheduleAnimationIfDimming();
            }
        }

        adjustSpecialWindows();

        //TODO (multidisplay): Magnification is supported only for the default display.
        if (mService.mAccessibilityController != null && anyLayerChanged
                && windows.get(windows.size() - 1).getDisplayId() == Display.DEFAULT_DISPLAY) {
            mService.mAccessibilityController.onWindowLayersChangedLocked();
        }

        if (DEBUG_LAYERS) logDebugLayers(windows);
    }

    void setInputMethodAnimLayerAdjustment(int adj) {
        if (DEBUG_LAYERS) Slog.v(TAG_WM, "Setting im layer adj to " + adj);
        mInputMethodAnimLayerAdjustment = adj;
        final WindowState imw = mService.mInputMethodWindow;
        if (imw != null) {
            imw.mWinAnimator.mAnimLayer = imw.mLayer + adj;
            if (DEBUG_LAYERS) Slog.v(TAG_WM, "IM win " + imw
                    + " anim layer: " + imw.mWinAnimator.mAnimLayer);
            for (int i = imw.mChildWindows.size() - 1; i >= 0; i--) {
                final WindowState childWindow = imw.mChildWindows.get(i);
                childWindow.mWinAnimator.mAnimLayer = childWindow.mLayer + adj;
                if (DEBUG_LAYERS) Slog.v(TAG_WM, "IM win " + childWindow
                        + " anim layer: " + childWindow.mWinAnimator.mAnimLayer);
            }
        }
        for (int i = mService.mInputMethodDialogs.size() - 1; i >= 0; i--) {
            final WindowState dialog = mService.mInputMethodDialogs.get(i);
            dialog.mWinAnimator.mAnimLayer = dialog.mLayer + adj;
            if (DEBUG_LAYERS) Slog.v(TAG_WM, "IM win " + imw
                    + " anim layer: " + dialog.mWinAnimator.mAnimLayer);
        }
    }

    int getSpecialWindowAnimLayerAdjustment(WindowState win) {
        if (win.mIsImWindow) {
            return mInputMethodAnimLayerAdjustment;
        } else if (win.mIsWallpaper) {
            return mService.mWallpaperControllerLocked.getAnimLayerAdjustment();
        }
        return 0;
    }

    /**
     * @return The layer used for dimming the apps when dismissing docked/fullscreen stack. Just
     *         above all application surfaces.
     */
    int getResizeDimLayer() {
        return (mDockDivider != null) ? mDockDivider.mLayer - 1 : LAYER_OFFSET_DIM;
    }

    private void logDebugLayers(WindowList windows) {
        for (int i = 0, n = windows.size(); i < n; i++) {
            final WindowState w = windows.get(i);
            final WindowStateAnimator winAnimator = w.mWinAnimator;
            Slog.v(TAG_WM, "Assign layer " + w + ": " + "mBase=" + w.mBaseLayer
                    + " mLayer=" + w.mLayer + (w.mAppToken == null
                    ? "" : " mAppLayer=" + w.mAppToken.mAppAnimator.animLayerAdjustment)
                    + " =mAnimLayer=" + winAnimator.mAnimLayer);
        }
    }

    private void clear() {
        mHighestApplicationLayer = 0;
        mPinnedWindows.clear();
        mInputMethodWindows.clear();
        mDockedWindows.clear();
        mReplacingWindows.clear();
        mDockDivider = null;
    }

    private void collectSpecialWindows(WindowState w) {
        if (w.mAttrs.type == TYPE_DOCK_DIVIDER) {
            mDockDivider = w;
            return;
        }
        if (w.mWillReplaceWindow) {
            mReplacingWindows.add(w);
        }
        if (w.mIsImWindow) {
            mInputMethodWindows.add(w);
            return;
        }
        final TaskStack stack = w.getStack();
        if (stack == null) {
            return;
        }
        if (stack.mStackId == PINNED_STACK_ID) {
            mPinnedWindows.add(w);
        } else if (stack.mStackId == DOCKED_STACK_ID) {
            mDockedWindows.add(w);
        }
    }

    private void adjustSpecialWindows() {
        int layer = mHighestApplicationLayer + WINDOW_LAYER_MULTIPLIER;
        // For pinned and docked stack window, we want to make them above other windows also when
        // these windows are animating.
        while (!mDockedWindows.isEmpty()) {
            layer = assignAndIncreaseLayerIfNeeded(mDockedWindows.remove(), layer);
        }

        layer = assignAndIncreaseLayerIfNeeded(mDockDivider, layer);

        if (mDockDivider != null && mDockDivider.isVisibleLw()) {
            while (!mInputMethodWindows.isEmpty()) {
                final WindowState w = mInputMethodWindows.remove();
                // Only ever move IME windows up, else we brake IME for windows above the divider.
                if (layer > w.mLayer) {
                    layer = assignAndIncreaseLayerIfNeeded(w, layer);
                }
            }
        }

        // We know that we will be animating a relaunching window in the near future, which will
        // receive a z-order increase. We want the replaced window to immediately receive the same
        // treatment, e.g. to be above the dock divider.
        while (!mReplacingWindows.isEmpty()) {
            layer = assignAndIncreaseLayerIfNeeded(mReplacingWindows.remove(), layer);
        }

        while (!mPinnedWindows.isEmpty()) {
            layer = assignAndIncreaseLayerIfNeeded(mPinnedWindows.remove(), layer);
        }
    }

    private int assignAndIncreaseLayerIfNeeded(WindowState win, int layer) {
        if (win != null) {
            assignAnimLayer(win, layer);
            // Make sure we leave space inbetween normal windows for dims and such.
            layer += WINDOW_LAYER_MULTIPLIER;
        }
        return layer;
    }

    private void assignAnimLayer(WindowState w, int layer) {
        w.mLayer = layer;
        w.mWinAnimator.mAnimLayer = w.mLayer + w.getAnimLayerAdjustment() +
                    getSpecialWindowAnimLayerAdjustment(w);
        if (w.mAppToken != null && w.mAppToken.mAppAnimator.thumbnailForceAboveLayer > 0
                && w.mWinAnimator.mAnimLayer > w.mAppToken.mAppAnimator.thumbnailForceAboveLayer) {
            w.mAppToken.mAppAnimator.thumbnailForceAboveLayer = w.mWinAnimator.mAnimLayer;
        }
    }

    void dump(PrintWriter pw, String s) {
        if (mInputMethodAnimLayerAdjustment != 0 ||
                mService.mWallpaperControllerLocked.getAnimLayerAdjustment() != 0) {
            pw.print("  mInputMethodAnimLayerAdjustment=");
            pw.print(mInputMethodAnimLayerAdjustment);
            pw.print("  mWallpaperAnimLayerAdjustment=");
            pw.println(mService.mWallpaperControllerLocked.getAnimLayerAdjustment());
        }
    }
}
