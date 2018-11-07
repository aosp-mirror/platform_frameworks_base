/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Handler;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;
import android.view.WindowManagerGlobal;

import com.android.systemui.statusbar.phone.NavigationBarFragment;

/**
 * A controller to handle external navigation bars
 */
public class DisplayNavigationBarController implements DisplayListener {

    private final Context mContext;
    private final Handler mHandler;
    private final DisplayManager mDisplayManager;

    /** A displayId - nav bar mapping */
    private SparseArray<NavigationBarFragment> mExternalNavigationBarMap = new SparseArray<>();

    public DisplayNavigationBarController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);

        registerListener();
    }

    @Override
    public void onDisplayAdded(int displayId) {
        final Display display = mDisplayManager.getDisplay(displayId);
        addExternalNavigationBar(display);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        final NavigationBarFragment navBar = mExternalNavigationBarMap.get(displayId);
        if (navBar != null) {
            final View navigationView = navBar.getView().getRootView();
            WindowManagerGlobal.getInstance().removeView(navigationView, true);
            mExternalNavigationBarMap.remove(displayId);
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
    }

    /** Create external navigation bars when car/status bar initializes */
    public void createNavigationBars() {
        // Add external navigation bars if more than one displays exist.
        final Display[] displays = mDisplayManager.getDisplays();
        for (Display display : displays) {
            addExternalNavigationBar(display);
        }
    }

    /** remove external navigation bars and unset everything related to external navigation bars */
    public void destroy() {
        unregisterListener();
        if (mExternalNavigationBarMap.size() > 0) {
            for (int i = 0; i < mExternalNavigationBarMap.size(); i++) {
                final View navigationWindow = mExternalNavigationBarMap.valueAt(i)
                        .getView().getRootView();
                WindowManagerGlobal.getInstance()
                        .removeView(navigationWindow, true /* immediate */);
            }
            mExternalNavigationBarMap.clear();
        }
    }

    private void registerListener() {
        mDisplayManager.registerDisplayListener(this, mHandler);
    }

    private void unregisterListener() {
        mDisplayManager.unregisterDisplayListener(this);
    }

    /**
     * Add a phone navigation bar on an external display if the display supports system decorations.
     *
     * @param display the display to add navigation bar on
     */
    private void addExternalNavigationBar(Display display) {
        if (display == null || display.getDisplayId() == DEFAULT_DISPLAY
                || !display.supportsSystemDecorations()) {
            return;
        }

        final int displayId = display.getDisplayId();
        final Context externalDisplayContext = mContext.createDisplayContext(display);
        NavigationBarFragment.create(externalDisplayContext,
                (tag, fragment) -> {
                    final NavigationBarFragment navBar = (NavigationBarFragment) fragment;
                    // TODO(b/115978725): handle external nav bars sysuiVisibility
                    navBar.setCurrentSysuiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                    mExternalNavigationBarMap.append(displayId, navBar);
                }
        );
    }
}
