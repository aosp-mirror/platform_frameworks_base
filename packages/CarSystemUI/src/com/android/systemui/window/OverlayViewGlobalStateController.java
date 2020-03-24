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

package com.android.systemui.window;

import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.navigationbar.car.CarNavigationBarController;

import java.util.HashSet;
import java.util.Set;

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
    private static final String TAG = OverlayViewGlobalStateController.class.getSimpleName();
    private final SystemUIOverlayWindowController mSystemUIOverlayWindowController;
    private final CarNavigationBarController mCarNavigationBarController;
    @VisibleForTesting
    Set<String> mShownSet;

    @Inject
    public OverlayViewGlobalStateController(
            CarNavigationBarController carNavigationBarController,
            SystemUIOverlayWindowController systemUIOverlayWindowController) {
        mSystemUIOverlayWindowController = systemUIOverlayWindowController;
        mSystemUIOverlayWindowController.attach();
        mCarNavigationBarController = carNavigationBarController;
        mShownSet = new HashSet<>();
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
     * Show content in Overlay Window.
     */
    public void showView(OverlayViewController viewController, Runnable show) {
        if (mShownSet.isEmpty()) {
            mCarNavigationBarController.hideBars();
            setWindowVisible(true);
        }

        inflateView(viewController);

        show.run();
        mShownSet.add(viewController.getClass().getName());

        Log.d(TAG, "Content shown: " + viewController.getClass().getName());
    }

    /**
     * Hide content in Overlay Window.
     */
    public void hideView(OverlayViewController viewController, Runnable hide) {
        if (!viewController.isInflated()) {
            Log.d(TAG, "Content cannot be hidden since it isn't inflated: "
                    + viewController.getClass().getName());
            return;
        }
        if (!mShownSet.contains(viewController.getClass().getName())) {
            Log.d(TAG, "Content cannot be hidden since it isn't shown: "
                    + viewController.getClass().getName());
            return;
        }

        hide.run();
        mShownSet.remove(viewController.getClass().getName());

        if (mShownSet.isEmpty()) {
            mCarNavigationBarController.showBars();
            setWindowVisible(false);
        }

        Log.d(TAG, "Content hidden: " + viewController.getClass().getName());
    }

    /** Sets the window visibility state. */
    public void setWindowVisible(boolean expanded) {
        mSystemUIOverlayWindowController.setWindowVisible(expanded);
    }

    /** Returns {@code true} is the window is visible. */
    public boolean isWindowVisible() {
        return mSystemUIOverlayWindowController.isWindowVisible();
    }

    /** Sets the focusable flag of the sysui overlawy window. */
    public void setWindowFocusable(boolean focusable) {
        mSystemUIOverlayWindowController.setWindowFocusable(focusable);
    }

    /** Returns {@code true} if the window is focusable. */
    public boolean isWindowFocusable() {
        return mSystemUIOverlayWindowController.isWindowFocusable();
    }

    /** Inflates the view controlled by the given view controller. */
    public void inflateView(OverlayViewController viewController) {
        if (!viewController.isInflated()) {
            viewController.inflate(mSystemUIOverlayWindowController.getBaseLayout());
        }
    }
}
