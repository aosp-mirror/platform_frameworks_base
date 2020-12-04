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
import android.view.SurfaceControl;

import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMenuController;

/**
 * Manages the visibility of the PiP Menu as user interacts with PiP.
 */
public class TvPipMenuController implements PipMenuController {

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

    @Override
    public void attach(SurfaceControl leash) {
        if (mMenuView == null) {
            mMenuView = new PipMenuView(mContext, mPipController);
            mSystemWindows.addView(mMenuView,
                    getPipMenuLayoutParams(MENU_WINDOW_TITLE, 0 /* width */, 0 /* height */),
                    0, SHELL_ROOT_LAYER_PIP);
            mLeash = leash;
        }
    }

    @Override
    public void detach() {
        mSystemWindows.removeView(mMenuView);
        mMenuView = null;
        mLeash = null;
    }

    @Override
    public void setAppActions(ParceledListSlice<RemoteAction> appActions) {
        mMenuView.setAppActions(appActions);
    }

    @Override
    public boolean isMenuVisible() {
        return mMenuView != null && mMenuView.getAlpha() == 1.0f;
    }
}
