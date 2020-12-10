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

package com.android.wm.shell.pip.tv;

import static android.view.WindowManager.SHELL_ROOT_LAYER_PIP;

import android.app.RemoteAction;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.util.Log;
import android.view.SurfaceControl;

import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMenuController;

/**
 * Manages the visibility of the PiP Menu as user interacts with PiP.
 */
public class TvPipMenuController implements PipMenuController {
    private static final String TAG = "TvPipMenuController";
    private static final boolean DEBUG = PipController.DEBUG;

    private final Context mContext;
    private final SystemWindows mSystemWindows;
    private final PipBoundsState mPipBoundsState;
    private PipMenuView mMenuView;
    private PipController mPipController;
    private SurfaceControl mLeash;

    public TvPipMenuController(Context context, PipBoundsState pipBoundsState,
            SystemWindows systemWindows) {
        mContext = context;
        mPipBoundsState = pipBoundsState;
        mSystemWindows = systemWindows;
    }

    void attachPipController(PipController pipController) {
        mPipController = pipController;
    }

    @Override
    public void showMenu() {
        if (DEBUG) Log.d(TAG, "showMenu()");

        if (mMenuView != null) {
            mSystemWindows.updateViewLayout(mMenuView, getPipMenuLayoutParams(MENU_WINDOW_TITLE,
                    mPipBoundsState.getDisplayBounds().width(),
                    mPipBoundsState.getDisplayBounds().height()));
            mMenuView.showMenu();

            // By default, SystemWindows views are above everything else.
            // Set the relative z-order so the menu is below PiP.
            if (mMenuView.getWindowSurfaceControl() != null && mLeash != null) {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                t.setRelativeLayer(mMenuView.getWindowSurfaceControl(), mLeash, -1);
                t.apply();
            }
        }
    }

    void hideMenu() {
        if (DEBUG) Log.d(TAG, "hideMenu()");

        if (isMenuVisible()) {
            mMenuView.hideMenu();
            mPipController.resizePinnedStack(PipController.STATE_PIP);
        }
    }

    @Override
    public void attach(SurfaceControl leash) {
        mLeash = leash;
        attachPipMenuView();
    }

    @Override
    public void detach() {
        hideMenu();
        detachPipMenuView();
        mLeash = null;
    }

    private void attachPipMenuView() {
        if (DEBUG) Log.d(TAG, "attachPipMenuView()");

        if (mMenuView != null) {
            detachPipMenuView();
        }

        mMenuView = new PipMenuView(mContext, mPipController);
        mMenuView.setOnBackPressListener(this::hideMenu);
        mSystemWindows.addView(mMenuView,
                getPipMenuLayoutParams(MENU_WINDOW_TITLE, 0 /* width */, 0 /* height */),
                0, SHELL_ROOT_LAYER_PIP);
    }

    private void detachPipMenuView() {
        if (DEBUG) Log.d(TAG, "detachPipMenuView()");

        if (mMenuView == null) {
            return;
        }

        mSystemWindows.removeView(mMenuView);
        mMenuView = null;
    }

    @Override
    public void setAppActions(ParceledListSlice<RemoteAction> appActions) {
        if (DEBUG) Log.d(TAG, "setAppActions(), actions=" + appActions);

        if (mMenuView != null) {
            mMenuView.setAppActions(appActions);
        } else {
            Log.w(TAG, "Cannot set remote actions, there is no View");
        }
    }

    @Override
    public boolean isMenuVisible() {
        return mMenuView != null && mMenuView.getAlpha() == 1.0f;
    }
}
