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

import java.util.ArrayDeque;
import java.util.function.Consumer;

import static android.app.ActivityManager.StackId;
import static android.app.ActivityManager.StackId.ASSISTANT_STACK_ID;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYERS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_OFFSET;
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
class WindowLayersController {
    private final WindowManagerService mService;

    WindowLayersController(WindowManagerService service) {
        mService = service;
    }

    private ArrayDeque<WindowState> mPinnedWindows = new ArrayDeque<>();
    private ArrayDeque<WindowState> mDockedWindows = new ArrayDeque<>();
    private ArrayDeque<WindowState> mAssistantWindows = new ArrayDeque<>();
    private ArrayDeque<WindowState> mInputMethodWindows = new ArrayDeque<>();
    private WindowState mDockDivider = null;
    private ArrayDeque<WindowState> mReplacingWindows = new ArrayDeque<>();
    private int mCurBaseLayer;
    private int mCurLayer;
    private boolean mAnyLayerChanged;
    private int mHighestApplicationLayer;
    private int mHighestDockedAffectedLayer;
    private int mHighestLayerInImeTargetBaseLayer;
    private WindowState mImeTarget;
    private boolean mAboveImeTarget;
    private ArrayDeque<WindowState> mAboveImeTargetAppWindows = new ArrayDeque();

    private final Consumer<WindowState> mAssignWindowLayersConsumer = w -> {
        boolean layerChanged = false;

        int oldLayer = w.mLayer;
        if (w.mBaseLayer == mCurBaseLayer) {
            mCurLayer += WINDOW_LAYER_MULTIPLIER;
        } else {
            mCurBaseLayer = mCurLayer = w.mBaseLayer;
        }
        assignAnimLayer(w, mCurLayer);

        // TODO: Preserved old behavior of code here but not sure comparing oldLayer to
        // mAnimLayer and mLayer makes sense...though the worst case would be unintentional
        // layer reassignment.
        if (w.mLayer != oldLayer || w.mWinAnimator.mAnimLayer != oldLayer) {
            layerChanged = true;
            mAnyLayerChanged = true;
        }

        if (w.mAppToken != null) {
            mHighestApplicationLayer = Math.max(mHighestApplicationLayer,
                    w.mWinAnimator.mAnimLayer);
        }
        if (mImeTarget != null && w.mBaseLayer == mImeTarget.mBaseLayer) {
            mHighestLayerInImeTargetBaseLayer = Math.max(mHighestLayerInImeTargetBaseLayer,
                    w.mWinAnimator.mAnimLayer);
        }
        if (w.getAppToken() != null && StackId.isResizeableByDockedStack(w.getStackId())) {
            mHighestDockedAffectedLayer = Math.max(mHighestDockedAffectedLayer,
                    w.mWinAnimator.mAnimLayer);
        }

        collectSpecialWindows(w);

        if (layerChanged) {
            w.scheduleAnimationIfDimming();
        }
    };

    final void assignWindowLayers(DisplayContent dc) {
        if (DEBUG_LAYERS) Slog.v(TAG_WM, "Assigning layers based",
                new RuntimeException("here").fillInStackTrace());

        reset();
        dc.forAllWindows(mAssignWindowLayersConsumer, false /* traverseTopToBottom */);

        adjustSpecialWindows();

        //TODO (multidisplay): Magnification is supported only for the default display.
        if (mService.mAccessibilityController != null && mAnyLayerChanged
                && dc.getDisplayId() == DEFAULT_DISPLAY) {
            mService.mAccessibilityController.onWindowLayersChangedLocked();
        }

        if (DEBUG_LAYERS) logDebugLayers(dc);
    }

    private void logDebugLayers(DisplayContent dc) {
        dc.forAllWindows((w) -> {
            final WindowStateAnimator winAnimator = w.mWinAnimator;
            Slog.v(TAG_WM, "Assign layer " + w + ": " + "mBase=" + w.mBaseLayer
                    + " mLayer=" + w.mLayer + (w.mAppToken == null
                    ? "" : " mAppLayer=" + w.mAppToken.getAnimLayerAdjustment())
                    + " =mAnimLayer=" + winAnimator.mAnimLayer);
        }, false /* traverseTopToBottom */);
    }

    private void reset() {
        mPinnedWindows.clear();
        mInputMethodWindows.clear();
        mDockedWindows.clear();
        mAssistantWindows.clear();
        mReplacingWindows.clear();
        mDockDivider = null;

        mCurBaseLayer = 0;
        mCurLayer = 0;
        mAnyLayerChanged = false;

        mHighestApplicationLayer = 0;
        mHighestDockedAffectedLayer = 0;
        mHighestLayerInImeTargetBaseLayer = (mImeTarget != null) ? mImeTarget.mBaseLayer : 0;
        mImeTarget = mService.mInputMethodTarget;
        mAboveImeTarget = false;
        mAboveImeTargetAppWindows.clear();
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
        if (mImeTarget != null) {
            if (w.getParentWindow() == mImeTarget && w.mSubLayer > 0) {
                // Child windows of the ime target with a positive sub-layer should be placed above
                // the IME.
                mAboveImeTargetAppWindows.add(w);
            } else if (mAboveImeTarget && w.mAppToken != null) {
                // windows of apps above the IME target should be placed above the IME.
                mAboveImeTargetAppWindows.add(w);
            }
            if (w == mImeTarget) {
                mAboveImeTarget = true;
            }
        }

        final int stackId = w.getAppToken() != null ? w.getStackId() : INVALID_STACK_ID;
        if (stackId == PINNED_STACK_ID) {
            mPinnedWindows.add(w);
        } else if (stackId == DOCKED_STACK_ID) {
            mDockedWindows.add(w);
        } else if (stackId == ASSISTANT_STACK_ID) {
            mAssistantWindows.add(w);
        }
    }

    private void adjustSpecialWindows() {
        // The following adjustments are beyond the highest docked-affected layer
        int layer = mHighestDockedAffectedLayer +  TYPE_LAYER_OFFSET;

        // Adjust the docked stack windows and dock divider above only the windows that are affected
        // by the docked stack. When this happens, also boost the assistant window layers, otherwise
        // the docked stack windows & divider would be promoted above the assistant.
        if (!mDockedWindows.isEmpty() && mHighestDockedAffectedLayer > 0) {
            while (!mDockedWindows.isEmpty()) {
                final WindowState window = mDockedWindows.remove();
                layer = assignAndIncreaseLayerIfNeeded(window, layer);
            }

            layer = assignAndIncreaseLayerIfNeeded(mDockDivider, layer);

            while (!mAssistantWindows.isEmpty()) {
                final WindowState window = mAssistantWindows.remove();
                if (window.mLayer > mHighestDockedAffectedLayer) {
                    layer = assignAndIncreaseLayerIfNeeded(window, layer);
                }
            }
        }

        // The following adjustments are beyond the highest app layer or boosted layer
        layer = Math.max(layer, mHighestApplicationLayer + WINDOW_LAYER_MULTIPLIER);

        // We know that we will be animating a relaunching window in the near future, which will
        // receive a z-order increase. We want the replaced window to immediately receive the same
        // treatment, e.g. to be above the dock divider.
        while (!mReplacingWindows.isEmpty()) {
            layer = assignAndIncreaseLayerIfNeeded(mReplacingWindows.remove(), layer);
        }

        while (!mPinnedWindows.isEmpty()) {
            layer = assignAndIncreaseLayerIfNeeded(mPinnedWindows.remove(), layer);
        }

        // Make sure IME is the highest window in the base layer of it's target.
        if (mImeTarget != null) {
            if (mImeTarget.mAppToken == null) {
                // For non-app ime targets adjust the layer we start from to match what we found
                // when assigning layers. Otherwise, just use the highest app layer we have some far.
                layer = mHighestLayerInImeTargetBaseLayer + WINDOW_LAYER_MULTIPLIER;
            }

            while (!mInputMethodWindows.isEmpty()) {
                layer = assignAndIncreaseLayerIfNeeded(mInputMethodWindows.remove(), layer);
            }

            // Adjust app windows the should be displayed above the IME since they are above the IME
            // target.
            while (!mAboveImeTargetAppWindows.isEmpty()) {
                layer = assignAndIncreaseLayerIfNeeded(mAboveImeTargetAppWindows.remove(), layer);
            }
        }

    }

    private int assignAndIncreaseLayerIfNeeded(WindowState win, int layer) {
        if (win != null) {
            assignAnimLayer(win, layer);
            // Make sure we leave space in-between normal windows for dims and such.
            layer += WINDOW_LAYER_MULTIPLIER;
        }
        return layer;
    }

    private void assignAnimLayer(WindowState w, int layer) {
        w.mLayer = layer;
        w.mWinAnimator.mAnimLayer = w.getAnimLayerAdjustment()
                + w.getSpecialWindowAnimLayerAdjustment();
        if (w.mAppToken != null && w.mAppToken.mAppAnimator.thumbnailForceAboveLayer > 0) {
            if (w.mWinAnimator.mAnimLayer > w.mAppToken.mAppAnimator.thumbnailForceAboveLayer) {
                w.mAppToken.mAppAnimator.thumbnailForceAboveLayer = w.mWinAnimator.mAnimLayer;
            }
            // TODO(b/62029108): the entire contents of the if statement should call the refactored
            // function to set the thumbnail layer for w.AppToken
            int highestLayer = w.mAppToken.getHighestAnimLayer();
            if (highestLayer > 0) {
                if (w.mAppToken.mAppAnimator.thumbnail != null
                        && w.mAppToken.mAppAnimator.thumbnailForceAboveLayer != highestLayer) {
                    w.mAppToken.mAppAnimator.thumbnailForceAboveLayer = highestLayer;
                    w.mAppToken.mAppAnimator.thumbnail.setLayer(highestLayer + 1);
                }
            }
        }
    }
}
