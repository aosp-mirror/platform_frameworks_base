/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Encapsulates all logic for the status bar window state management.
 */
@Singleton
public class StatusBarWindowController {
    private static final String TAG = "StatusBarWindowController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final SuperStatusBarViewFactory mSuperStatusBarViewFactory;
    private final Resources mResources;
    private int mBarHeight = -1;
    private final State mCurrentState = new State();

    private ViewGroup mStatusBarView;
    private WindowManager.LayoutParams mLp;
    private final WindowManager.LayoutParams mLpChanged;

    @Inject
    public StatusBarWindowController(Context context, WindowManager windowManager,
            SuperStatusBarViewFactory superStatusBarViewFactory,
            @Main Resources resources) {
        mContext = context;
        mWindowManager = windowManager;
        mSuperStatusBarViewFactory = superStatusBarViewFactory;
        mStatusBarView = mSuperStatusBarViewFactory.getStatusBarWindowView();
        mLpChanged = new WindowManager.LayoutParams();
        mResources = resources;

        if (mBarHeight < 0) {
            mBarHeight = mResources.getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
        }
    }

    public int getStatusBarHeight() {
        return mBarHeight;
    }

    /**
     * Rereads the status_bar_height from configuration and reapplys the current state if the height
     * is different.
     */
    public void refreshStatusBarHeight() {
        int heightFromConfig = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        if (mBarHeight != heightFromConfig) {
            mBarHeight = heightFromConfig;
            apply(mCurrentState);
        }

        if (DEBUG) Log.v(TAG, "defineSlots");
    }

    /**
     * Adds the status bar view to the window manager.
     */
    public void attach() {
        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        mLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mBarHeight,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        mLp.privateFlags |= PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        mLp.token = new Binder();
        mLp.gravity = Gravity.TOP;
        mLp.setFitInsetsTypes(0 /* types */);
        mLp.setTitle("StatusBar");
        mLp.packageName = mContext.getPackageName();
        mLp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        mWindowManager.addView(mStatusBarView, mLp);
        mLpChanged.copyFrom(mLp);
    }

    /** Set force status bar visible. */
    public void setForceStatusBarVisible(boolean forceStatusBarVisible) {
        mCurrentState.mForceStatusBarVisible = forceStatusBarVisible;
        apply(mCurrentState);
    }

    private void applyHeight() {
        mLpChanged.height = mBarHeight;
    }

    private void apply(State state) {
        applyForceStatusBarVisibleFlag(state);
        applyHeight();
        if (mLp != null && mLp.copyFrom(mLpChanged) != 0) {
            mWindowManager.updateViewLayout(mStatusBarView, mLp);
        }
    }

    private static class State {
        boolean mForceStatusBarVisible;
    }

    private void applyForceStatusBarVisibleFlag(State state) {
        if (state.mForceStatusBarVisible) {
            mLpChanged.privateFlags |= PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
        } else {
            mLpChanged.privateFlags &= ~PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
        }
    }
}
