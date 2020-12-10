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

import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_BACK;

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
public class PipMenuView extends FrameLayout {
    private static final String TAG = "PipMenuView";
    private static final boolean DEBUG = PipController.DEBUG;

    private final Animator mFadeInAnimation;
    private final Animator mFadeOutAnimation;
    private final PipControlsViewController mPipControlsViewController;
    @Nullable
    private OnBackPressListener mOnBackPressListener;

    public PipMenuView(Context context, PipController pipController) {
        super(context, null, 0);
        inflate(context, R.layout.tv_pip_menu, this);

        mPipControlsViewController = new PipControlsViewController(
                findViewById(R.id.pip_controls), pipController);
        mFadeInAnimation = AnimatorInflater.loadAnimator(
                mContext, R.anim.tv_pip_menu_fade_in_animation);
        mFadeInAnimation.setTarget(mPipControlsViewController.getView());
        mFadeOutAnimation = AnimatorInflater.loadAnimator(
                mContext, R.anim.tv_pip_menu_fade_out_animation);
        mFadeOutAnimation.setTarget(mPipControlsViewController.getView());
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
        mFadeInAnimation.start();
        setAlpha(1.0f);
        grantWindowFocus(true);
    }

    void hideMenu() {
        mFadeOutAnimation.start();
        setAlpha(0.0f);
        grantWindowFocus(false);
    }

    private void grantWindowFocus(boolean grantFocus) {
        try {
            WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(null /* window */,
                    getViewRootImpl().getInputToken(), grantFocus);
        } catch (Exception e) {
            Log.e(TAG, "Unable to update focus as menu disappears", e);
        }
    }

    void setOnBackPressListener(OnBackPressListener onBackPressListener) {
        mOnBackPressListener = onBackPressListener;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KEYCODE_BACK && event.getAction() == ACTION_UP
                && mOnBackPressListener != null) {
            mOnBackPressListener.onBackPress();
            return true;
        } else {
            return super.dispatchKeyEvent(event);
        }
    }

    void setAppActions(ParceledListSlice<RemoteAction> actions) {
        if (DEBUG) Log.d(TAG, "onPipMenuActionsChanged()");

        boolean hasCustomActions = actions != null && !actions.getList().isEmpty();
        mPipControlsViewController.setCustomActions(
                hasCustomActions ? actions.getList() : Collections.emptyList());
    }

    interface OnBackPressListener {
        void onBackPress();
    }
}
