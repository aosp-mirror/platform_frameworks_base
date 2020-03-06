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

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Binder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.ConfigurationController;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controls the expansion state of the primary window which will contain all of the fullscreen sysui
 * behavior. This window still has a collapsed state in order to watch for swipe events to expand
 * this window for the notification panel.
 */
@Singleton
public class SystemUIOverlayWindowController implements
        ConfigurationController.ConfigurationListener {

    private final Context mContext;
    private final Resources mResources;
    private final WindowManager mWindowManager;

    private final int mStatusBarHeight;
    private final int mNavBarHeight;
    private final int mDisplayHeight;
    private ViewGroup mBaseLayout;
    private WindowManager.LayoutParams mLp;
    private WindowManager.LayoutParams mLpChanged;
    private boolean mIsAttached = false;

    @Inject
    public SystemUIOverlayWindowController(
            Context context,
            @Main Resources resources,
            WindowManager windowManager,
            ConfigurationController configurationController
    ) {
        mContext = context;
        mResources = resources;
        mWindowManager = windowManager;

        Point display = new Point();
        mWindowManager.getDefaultDisplay().getSize(display);
        mDisplayHeight = display.y;

        mStatusBarHeight = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mNavBarHeight = mResources.getDimensionPixelSize(R.dimen.navigation_bar_height);

        mLpChanged = new WindowManager.LayoutParams();
        mBaseLayout = (ViewGroup) LayoutInflater.from(context)
                .inflate(R.layout.sysui_overlay_window, /* root= */ null, false);

        configurationController.addCallback(this);
    }

    /** Returns the base view of the primary window. */
    public ViewGroup getBaseLayout() {
        return mBaseLayout;
    }

    /** Returns {@code true} if the window is already attached. */
    public boolean isAttached() {
        return mIsAttached;
    }

    /** Attaches the window to the window manager. */
    public void attach() {
        if (mIsAttached) {
            return;
        }
        mIsAttached = true;
        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        mLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mStatusBarHeight,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        mLp.token = new Binder();
        mLp.gravity = Gravity.TOP;
        mLp.setFitInsetsTypes(/* types= */ 0);
        mLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        mLp.setTitle("SystemUIOverlayWindow");
        mLp.packageName = mContext.getPackageName();
        mLp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        mWindowManager.addView(mBaseLayout, mLp);
        mLpChanged.copyFrom(mLp);
    }

    /** Sets the window to the expanded state. */
    public void setWindowExpanded(boolean expanded) {
        if (expanded) {
            // TODO: Update this so that the windowing type gets the full height of the display
            //  when we use MATCH_PARENT.
            mLpChanged.height = mDisplayHeight + mNavBarHeight;
            mLpChanged.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mLpChanged.height = mStatusBarHeight;
            // TODO: Allow touches to go through to the status bar to handle notification panel.
            mLpChanged.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        updateWindow();
    }

    /** Returns {@code true} if the window is expanded */
    public boolean isWindowExpanded() {
        return mLp.height != mStatusBarHeight;
    }

    private void updateWindow() {
        if (mLp != null && mLp.copyFrom(mLpChanged) != 0) {
            if (isAttached()) {
                mWindowManager.updateViewLayout(mBaseLayout, mLp);
            }
        }
    }
}
