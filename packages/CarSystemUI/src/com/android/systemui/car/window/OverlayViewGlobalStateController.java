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

package com.android.systemui.car.window;

import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;

import android.annotation.Nullable;
import android.util.Log;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController;

import androidx.annotation.VisibleForTesting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This controller is responsible for the following:
 * <p><ul>
 * <li>Holds the global state for SystemUIOverlayWindow.
 * <li>Allows {@link SystemUIOverlayWindowManager} to register {@link OverlayViewMediator}(s).
 * <li>Enables {@link OverlayViewController)(s) to reveal/conceal themselves while respecting the
 * global state of SystemUIOverlayWindow.
 * </ul>
 */
@Singleton
public class OverlayViewGlobalStateController {
    private static final boolean DEBUG = false;
    private static final String TAG = OverlayViewGlobalStateController.class.getSimpleName();
    private static final int UNKNOWN_Z_ORDER = -1;
    private final SystemUIOverlayWindowController mSystemUIOverlayWindowController;
    private final WindowInsetsController mWindowInsetsController;
    @VisibleForTesting
    Map<OverlayViewController, Integer> mZOrderMap;
    @VisibleForTesting
    SortedMap<Integer, OverlayViewController> mZOrderVisibleSortedMap;
    @VisibleForTesting
    Set<OverlayViewController> mViewsHiddenForOcclusion;
    @VisibleForTesting
    OverlayViewController mHighestZOrder;
    private boolean mIsOccluded;

    @Inject
    public OverlayViewGlobalStateController(
            SystemUIOverlayWindowController systemUIOverlayWindowController) {
        mSystemUIOverlayWindowController = systemUIOverlayWindowController;
        mSystemUIOverlayWindowController.attach();
        mWindowInsetsController =
                mSystemUIOverlayWindowController.getBaseLayout().getWindowInsetsController();
        mZOrderMap = new HashMap<>();
        mZOrderVisibleSortedMap = new TreeMap<>();
        mViewsHiddenForOcclusion = new HashSet<>();
    }

    /**
     * Register {@link OverlayViewMediator} to use in SystemUIOverlayWindow.
     */
    public void registerMediator(OverlayViewMediator overlayViewMediator) {
        Log.d(TAG, "Registering content mediator: " + overlayViewMediator.getClass().getName());

        overlayViewMediator.registerListeners();
        overlayViewMediator.setupOverlayContentViewControllers();
    }

    /**
     * Show content in Overlay Window using {@link OverlayPanelViewController}.
     *
     * This calls {@link OverlayViewGlobalStateController#showView(OverlayViewController, Runnable)}
     * where the runnable is nullified since the actual showing of the panel is handled by the
     * controller itself.
     */
    public void showView(OverlayPanelViewController panelViewController) {
        showView(panelViewController, /* show= */ null);
    }

    /**
     * Show content in Overlay Window using {@link OverlayViewController}.
     */
    public void showView(OverlayViewController viewController, @Nullable Runnable show) {
        debugLog();
        if (mIsOccluded && !viewController.shouldShowWhenOccluded()) {
            mViewsHiddenForOcclusion.add(viewController);
            return;
        }
        if (mZOrderVisibleSortedMap.isEmpty()) {
            setWindowVisible(true);
        }
        if (!(viewController instanceof OverlayPanelViewController)) {
            inflateView(viewController);
        }

        if (show != null) {
            show.run();
        }

        updateInternalsWhenShowingView(viewController);
        refreshInsetTypesToFit();
        refreshWindowFocus();
        refreshNavigationBarVisibility();
        refreshStatusBarVisibility();
        refreshRotaryFocusIfNeeded();

        Log.d(TAG, "Content shown: " + viewController.getClass().getName());
        debugLog();
    }

    private void updateInternalsWhenShowingView(OverlayViewController viewController) {
        int zOrder;
        if (mZOrderMap.containsKey(viewController)) {
            zOrder = mZOrderMap.get(viewController);
        } else {
            zOrder = mSystemUIOverlayWindowController.getBaseLayout().indexOfChild(
                    viewController.getLayout());
            mZOrderMap.put(viewController, zOrder);
        }

        mZOrderVisibleSortedMap.put(zOrder, viewController);

        refreshHighestZOrderWhenShowingView(viewController);
    }

    private void refreshHighestZOrderWhenShowingView(OverlayViewController viewController) {
        if (mZOrderMap.getOrDefault(mHighestZOrder, UNKNOWN_Z_ORDER) < mZOrderMap.get(
                viewController)) {
            mHighestZOrder = viewController;
        }
    }

    /**
     * Hide content in Overlay Window using {@link OverlayPanelViewController}.
     *
     * This calls {@link OverlayViewGlobalStateController#hideView(OverlayViewController, Runnable)}
     * where the runnable is nullified since the actual hiding of the panel is handled by the
     * controller itself.
     */
    public void hideView(OverlayPanelViewController panelViewController) {
        hideView(panelViewController, /* hide= */ null);
    }

    /**
     * Hide content in Overlay Window using {@link OverlayViewController}.
     */
    public void hideView(OverlayViewController viewController, @Nullable Runnable hide) {
        debugLog();
        if (mIsOccluded && mViewsHiddenForOcclusion.contains(viewController)) {
            mViewsHiddenForOcclusion.remove(viewController);
            return;
        }
        if (!viewController.isInflated()) {
            Log.d(TAG, "Content cannot be hidden since it isn't inflated: "
                    + viewController.getClass().getName());
            return;
        }
        if (!mZOrderMap.containsKey(viewController)) {
            Log.d(TAG, "Content cannot be hidden since it has never been shown: "
                    + viewController.getClass().getName());
            return;
        }
        if (!mZOrderVisibleSortedMap.containsKey(mZOrderMap.get(viewController))) {
            Log.d(TAG, "Content cannot be hidden since it isn't currently shown: "
                    + viewController.getClass().getName());
            return;
        }

        if (hide != null) {
            hide.run();
        }

        mZOrderVisibleSortedMap.remove(mZOrderMap.get(viewController));
        refreshHighestZOrderWhenHidingView(viewController);
        refreshInsetTypesToFit();
        refreshWindowFocus();
        refreshNavigationBarVisibility();
        refreshStatusBarVisibility();
        refreshRotaryFocusIfNeeded();

        if (mZOrderVisibleSortedMap.isEmpty()) {
            setWindowVisible(false);
        }

        Log.d(TAG, "Content hidden: " + viewController.getClass().getName());
        debugLog();
    }

    private void refreshHighestZOrderWhenHidingView(OverlayViewController viewController) {
        if (mZOrderVisibleSortedMap.isEmpty()) {
            mHighestZOrder = null;
            return;
        }
        if (!mHighestZOrder.equals(viewController)) {
            return;
        }

        mHighestZOrder = mZOrderVisibleSortedMap.get(mZOrderVisibleSortedMap.lastKey());
    }

    private void refreshNavigationBarVisibility() {
        if (mZOrderVisibleSortedMap.isEmpty()) {
            mWindowInsetsController.show(navigationBars());
            return;
        }

        // Do not hide navigation bar insets if the window is not focusable.
        if (mHighestZOrder.shouldFocusWindow() && !mHighestZOrder.shouldShowNavigationBarInsets()) {
            mWindowInsetsController.hide(navigationBars());
        } else {
            mWindowInsetsController.show(navigationBars());
        }
    }

    private void refreshStatusBarVisibility() {
        if (mZOrderVisibleSortedMap.isEmpty()) {
            mWindowInsetsController.show(statusBars());
            return;
        }

        // Do not hide status bar insets if the window is not focusable.
        if (mHighestZOrder.shouldFocusWindow() && !mHighestZOrder.shouldShowStatusBarInsets()) {
            mWindowInsetsController.hide(statusBars());
        } else {
            mWindowInsetsController.show(statusBars());
        }
    }

    private void refreshWindowFocus() {
        setWindowFocusable(mHighestZOrder == null ? false : mHighestZOrder.shouldFocusWindow());
    }

    private void refreshInsetTypesToFit() {
        if (mZOrderVisibleSortedMap.isEmpty()) {
            setFitInsetsTypes(statusBars());
        } else {
            setFitInsetsTypes(mHighestZOrder.getInsetTypesToFit());
        }
    }

    private void refreshRotaryFocusIfNeeded() {
        for (OverlayViewController controller : mZOrderVisibleSortedMap.values()) {
            boolean isTop = Objects.equals(controller, mHighestZOrder);
            controller.setAllowRotaryFocus(isTop);
        }

        if (!mZOrderVisibleSortedMap.isEmpty()) {
            mHighestZOrder.refreshRotaryFocusIfNeeded();
        }
    }

    /** Returns {@code true} is the window is visible. */
    public boolean isWindowVisible() {
        return mSystemUIOverlayWindowController.isWindowVisible();
    }

    private void setWindowVisible(boolean visible) {
        mSystemUIOverlayWindowController.setWindowVisible(visible);
    }

    private void setFitInsetsTypes(@InsetsType int types) {
        mSystemUIOverlayWindowController.setFitInsetsTypes(types);
    }

    /**
     * Sets the {@link android.view.WindowManager.LayoutParams#FLAG_ALT_FOCUSABLE_IM} flag of the
     * sysui overlay window.
     */
    public void setWindowNeedsInput(boolean needsInput) {
        mSystemUIOverlayWindowController.setWindowNeedsInput(needsInput);
    }

    /** Returns {@code true} if the window is focusable. */
    public boolean isWindowFocusable() {
        return mSystemUIOverlayWindowController.isWindowFocusable();
    }

    /** Sets the focusable flag of the sysui overlawy window. */
    public void setWindowFocusable(boolean focusable) {
        mSystemUIOverlayWindowController.setWindowFocusable(focusable);
    }

    /** Inflates the view controlled by the given view controller. */
    public void inflateView(OverlayViewController viewController) {
        if (!viewController.isInflated()) {
            viewController.inflate(mSystemUIOverlayWindowController.getBaseLayout());
        }
    }

    /**
     * Return {@code true} if OverlayWindow is in a state where HUNs should be displayed above it.
     */
    public boolean shouldShowHUN() {
        return mZOrderVisibleSortedMap.isEmpty() || mHighestZOrder.shouldShowHUN();
    }

    /**
     * Set the OverlayViewWindow to be in occluded or unoccluded state. When OverlayViewWindow is
     * occluded, all views mounted to it that are not configured to be shown during occlusion will
     * be hidden.
     */
    public void setOccluded(boolean occluded) {
        if (occluded) {
            // Hide views before setting mIsOccluded to true so the regular hideView logic is used,
            // not the one used during occlusion.
            hideViewsForOcclusion();
            mIsOccluded = true;
        } else {
            mIsOccluded = false;
            // show views after setting mIsOccluded to false so the regular showView logic is used,
            // not the one used during occlusion.
            showViewsHiddenForOcclusion();
        }
    }

    private void hideViewsForOcclusion() {
        HashSet<OverlayViewController> viewsCurrentlyShowing = new HashSet<>(
                mZOrderVisibleSortedMap.values());
        viewsCurrentlyShowing.forEach(overlayController -> {
            if (!overlayController.shouldShowWhenOccluded()) {
                hideView(overlayController, overlayController::hideInternal);
                mViewsHiddenForOcclusion.add(overlayController);
            }
        });
    }

    private void showViewsHiddenForOcclusion() {
        mViewsHiddenForOcclusion.forEach(overlayViewController -> {
            showView(overlayViewController, overlayViewController::showInternal);
        });
        mViewsHiddenForOcclusion.clear();
    }

    private void debugLog() {
        if (!DEBUG) {
            return;
        }

        Log.d(TAG, "mHighestZOrder: " + mHighestZOrder);
        Log.d(TAG, "mZOrderVisibleSortedMap.size(): " + mZOrderVisibleSortedMap.size());
        Log.d(TAG, "mZOrderVisibleSortedMap: " + mZOrderVisibleSortedMap);
        Log.d(TAG, "mZOrderMap.size(): " + mZOrderMap.size());
        Log.d(TAG, "mZOrderMap: " + mZOrderMap);
        Log.d(TAG, "mIsOccluded: " + mIsOccluded);
        Log.d(TAG, "mViewsHiddenForOcclusion: " + mViewsHiddenForOcclusion);
        Log.d(TAG, "mViewsHiddenForOcclusion.size(): " + mViewsHiddenForOcclusion.size());
    }
}
