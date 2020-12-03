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

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.annotation.Nullable;
import android.app.RemoteAction;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;

import com.android.wm.shell.R;

import java.util.Collections;

/**
 * The Menu View that shows controls of the PiP. Always fullscreen.
 */
public class PipMenuView extends FrameLayout implements PipController.Listener {
    private static final String TAG = "PipMenuView";
    private static final boolean DEBUG = PipController.DEBUG;

    private final PipController mPipController;
    private final Animator mFadeInAnimation;
    private final Animator mFadeOutAnimation;
    private final PipControlsViewController mPipControlsViewController;
    private boolean mRestorePipSizeWhenClose;

    public PipMenuView(Context context, PipController pipController) {
        super(context, null, 0);
        mPipController = pipController;

        inflate(context, R.layout.tv_pip_menu, this);

        mPipControlsViewController = new PipControlsViewController(
                findViewById(R.id.pip_controls), mPipController);
        mRestorePipSizeWhenClose = true;
        mFadeInAnimation = AnimatorInflater.loadAnimator(
                mContext, R.anim.tv_pip_menu_fade_in_animation);
        mFadeInAnimation.setTarget(mPipControlsViewController.getView());
        mFadeOutAnimation = AnimatorInflater.loadAnimator(
                mContext, R.anim.tv_pip_menu_fade_out_animation);
        mFadeOutAnimation.setTarget(mPipControlsViewController.getView());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            restorePipAndFinish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Nullable
    SurfaceControl getWindowSurfaceControl() {
        final ViewRootImpl root = getViewRootImpl();
        if (root == null) {
            return null;
        }
        final SurfaceControl out = root.getSurfaceControl();
        if (out != null && out.isValid()) {
            return out;
        }
        return null;
    }

    void showMenu() {
        mPipController.addListener(this);
        mFadeInAnimation.start();
        setAlpha(1.0f);
        try {
            WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(null /* window */,
                    getViewRootImpl().getInputToken(), true /* grantFocus */);
        } catch (Exception e) {
            Log.e(TAG, "Unable to update focus as menu appears", e);
        }
    }

    void hideMenu() {
        mPipController.removeListener(this);
        mPipController.resumePipResizing(
                PipController.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
        mFadeOutAnimation.start();
        setAlpha(0.0f);
        try {
            WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(null /* window */,
                    getViewRootImpl().getInputToken(), false /* grantFocus */);
        } catch (Exception e) {
            Log.e(TAG, "Unable to update focus as menu disappears", e);
        }
    }

    private void restorePipAndFinish() {
        if (DEBUG) Log.d(TAG, "restorePipAndFinish()");

        if (mRestorePipSizeWhenClose) {
            if (DEBUG) Log.d(TAG, "   > restoring to the default position");

            // When PIP menu activity is closed, restore to the default position.
            mPipController.resizePinnedStack(PipController.STATE_PIP);
        }
        hideMenu();
    }

    @Override
    public void onPipEntered(String packageName) {
        if (DEBUG) Log.d(TAG, "onPipEntered(), packageName=" + packageName);
    }

    @Override
    public void onPipActivityClosed() {
        if (DEBUG) Log.d(TAG, "onPipActivityClosed()");

        hideMenu();
    }

    void setAppActions(ParceledListSlice<RemoteAction> actions) {
        if (DEBUG) Log.d(TAG, "onPipMenuActionsChanged()");

        boolean hasCustomActions = actions != null && !actions.getList().isEmpty();
        mPipControlsViewController.setCustomActions(
                hasCustomActions ? actions.getList() : Collections.emptyList());
    }

    @Override
    public void onShowPipMenu() {
        if (DEBUG) Log.d(TAG, "onShowPipMenu()");
    }

    @Override
    public void onMoveToFullscreen() {
        if (DEBUG) Log.d(TAG, "onMoveToFullscreen()");

        // Moving PIP to fullscreen is implemented by resizing PINNED_STACK with null bounds.
        // This conflicts with restoring PIP position, so disable it.
        mRestorePipSizeWhenClose = false;
        hideMenu();
    }

    @Override
    public void onPipResizeAboutToStart() {
        if (DEBUG) Log.d(TAG, "onPipResizeAboutToStart()");

        hideMenu();
        mPipController.suspendPipResizing(
                PipController.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }
}
