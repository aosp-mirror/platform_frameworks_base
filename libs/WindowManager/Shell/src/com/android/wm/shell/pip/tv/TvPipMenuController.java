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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceControl;

import androidx.annotation.Nullable;

import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipMenuController;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the visibility of the PiP Menu as user interacts with PiP.
 */
public class TvPipMenuController implements PipMenuController, TvPipMenuView.Listener {
    private static final String TAG = "TvPipMenuController";
    private static final boolean DEBUG = TvPipController.DEBUG;

    private final Context mContext;
    private final SystemWindows mSystemWindows;
    private final PipBoundsState mPipBoundsState;
    private final Handler mMainHandler;

    private Delegate mDelegate;
    private SurfaceControl mLeash;
    private TvPipMenuView mMenuView;

    private final List<RemoteAction> mMediaActions = new ArrayList<>();
    private final List<RemoteAction> mAppActions = new ArrayList<>();

    public TvPipMenuController(Context context, PipBoundsState pipBoundsState,
            SystemWindows systemWindows, PipMediaController pipMediaController,
            Handler mainHandler) {
        mContext = context;
        mPipBoundsState = pipBoundsState;
        mSystemWindows = systemWindows;
        mMainHandler = mainHandler;

        // We need to "close" the menu the platform call for all the system dialogs to close (for
        // example, on the Home button press).
        final BroadcastReceiver closeSystemDialogsBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                hideMenu();
            }
        };
        context.registerReceiverForAllUsers(closeSystemDialogsBroadcastReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), null /* permission */,
                mainHandler);

        pipMediaController.addActionListener(this::onMediaActionsChanged);
    }

    void setDelegate(Delegate delegate) {
        if (DEBUG) Log.d(TAG, "setDelegate(), delegate=" + delegate);
        if (mDelegate != null) {
            throw new IllegalStateException(
                    "The delegate has already been set and should not change.");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("The delegate must not be null.");
        }

        mDelegate = delegate;
    }

    @Override
    public void attach(SurfaceControl leash) {
        if (mDelegate == null) {
            throw new IllegalStateException("Delegate is not set.");
        }

        mLeash = leash;
        attachPipMenuView();
    }

    private void attachPipMenuView() {
        if (DEBUG) Log.d(TAG, "attachPipMenuView()");

        if (mMenuView != null) {
            detachPipMenuView();
        }

        mMenuView = new TvPipMenuView(mContext);
        mMenuView.setListener(this);
        mSystemWindows.addView(mMenuView,
                getPipMenuLayoutParams(MENU_WINDOW_TITLE, 0 /* width */, 0 /* height */),
                0, SHELL_ROOT_LAYER_PIP);
    }

    @Override
    public void showMenu() {
        if (DEBUG) Log.d(TAG, "showMenu()");

        if (mMenuView != null) {
            mSystemWindows.updateViewLayout(mMenuView, getPipMenuLayoutParams(MENU_WINDOW_TITLE,
                    mPipBoundsState.getDisplayBounds().width(),
                    mPipBoundsState.getDisplayBounds().height()));
            maybeUpdateMenuViewActions();
            mMenuView.show();

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
        hideMenu(true);
    }

    void hideMenu(boolean movePipWindow) {
        if (DEBUG) Log.d(TAG, "hideMenu(), movePipWindow=" + movePipWindow);

        if (!isMenuVisible()) {
            return;
        }

        mMenuView.hide();
        if (movePipWindow) {
            mDelegate.movePipToNormalPosition();
        }
    }

    @Override
    public void detach() {
        hideMenu();
        detachPipMenuView();
        mLeash = null;
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
    public void setAppActions(ParceledListSlice<RemoteAction> actions) {
        if (DEBUG) Log.d(TAG, "setAppActions()");
        updateAdditionalActionsList(mAppActions, actions.getList());
    }

    private void onMediaActionsChanged(List<RemoteAction> actions) {
        if (DEBUG) Log.d(TAG, "onMediaActionsChanged()");
        updateAdditionalActionsList(mMediaActions, actions);
    }

    private void updateAdditionalActionsList(
            List<RemoteAction> destination, @Nullable List<RemoteAction> source) {
        final int number = source != null ? source.size() : 0;
        if (number == 0 && destination.isEmpty()) {
            // Nothing changed.
            return;
        }

        destination.clear();
        if (number > 0) {
            destination.addAll(source);
        }
        maybeUpdateMenuViewActions();
    }

    private void maybeUpdateMenuViewActions() {
        if (mMenuView == null) {
            return;
        }
        if (!mAppActions.isEmpty()) {
            mMenuView.setAdditionalActions(mAppActions, mMainHandler);
        } else {
            mMenuView.setAdditionalActions(mMediaActions, mMainHandler);
        }
    }

    @Override
    public boolean isMenuVisible() {
        return mMenuView != null && mMenuView.isVisible();
    }

    @Override
    public void onBackPress() {
        hideMenu();
    }

    @Override
    public void onCloseButtonClick() {
        mDelegate.closePip();
    }

    @Override
    public void onFullscreenButtonClick() {
        mDelegate.movePipToFullscreen();
    }

    interface Delegate {
        void movePipToNormalPosition();
        void movePipToFullscreen();
        void closePip();
    }
}
