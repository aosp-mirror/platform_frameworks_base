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
import android.app.Activity;
import android.app.RemoteAction;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.util.Log;

import com.android.wm.shell.R;

import java.util.Collections;

/**
 * Activity to show the PIP menu to control PIP.
 * TODO(b/169395392) Refactor PipMenuActivity to PipMenuView
 */
public class PipMenuActivity extends Activity implements PipController.Listener {
    private static final String TAG = "PipMenuActivity";
    private static final boolean DEBUG = PipController.DEBUG;

    static final String EXTRA_CUSTOM_ACTIONS = "custom_actions";

    private static PipController sPipController;

    private Animator mFadeInAnimation;
    private Animator mFadeOutAnimation;
    private boolean mRestorePipSizeWhenClose;
    private PipControlsViewController mPipControlsViewController;

    @Override
    protected void onCreate(Bundle bundle) {
        if (DEBUG) Log.d(TAG, "onCreate()");

        super.onCreate(bundle);
        if (sPipController == null) {
            finish();
        }
        setContentView(R.layout.tv_pip_menu);
        mPipControlsViewController = new PipControlsViewController(
                findViewById(R.id.pip_controls), sPipController);
        sPipController.addListener(this);
        mRestorePipSizeWhenClose = true;
        mFadeInAnimation = AnimatorInflater.loadAnimator(
                this, R.anim.tv_pip_menu_fade_in_animation);
        mFadeInAnimation.setTarget(mPipControlsViewController.getView());
        mFadeOutAnimation = AnimatorInflater.loadAnimator(
                this, R.anim.tv_pip_menu_fade_out_animation);
        mFadeOutAnimation.setTarget(mPipControlsViewController.getView());

        onPipMenuActionsChanged(getIntent().getParcelableExtra(EXTRA_CUSTOM_ACTIONS));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (DEBUG) Log.d(TAG, "onNewIntent(), intent=" + intent);
        super.onNewIntent(intent);

        onPipMenuActionsChanged(getIntent().getParcelableExtra(EXTRA_CUSTOM_ACTIONS));
    }

    private void restorePipAndFinish() {
        if (DEBUG) Log.d(TAG, "restorePipAndFinish()");

        if (mRestorePipSizeWhenClose) {
            if (DEBUG) Log.d(TAG, "   > restoring to the default position");

            // When PIP menu activity is closed, restore to the default position.
            sPipController.resizePinnedStack(PipController.STATE_PIP);
        }
        finish();
    }

    @Override
    public void onResume() {
        if (DEBUG) Log.d(TAG, "onResume()");

        super.onResume();
        mFadeInAnimation.start();
    }

    @Override
    public void onPause() {
        if (DEBUG) Log.d(TAG, "onPause()");

        super.onPause();
        mFadeOutAnimation.start();
        restorePipAndFinish();
    }

    @Override
    protected void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy()");

        super.onDestroy();
        sPipController.removeListener(this);
        sPipController.resumePipResizing(
                PipController.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }

    @Override
    public void onBackPressed() {
        if (DEBUG) Log.d(TAG, "onBackPressed()");

        restorePipAndFinish();
    }

    @Override
    public void onPipEntered(String packageName) {
        if (DEBUG) Log.d(TAG, "onPipEntered(), packageName=" + packageName);
    }

    @Override
    public void onPipActivityClosed() {
        if (DEBUG) Log.d(TAG, "onPipActivityClosed()");

        finish();
    }

    @Override
    public void onPipMenuActionsChanged(ParceledListSlice<RemoteAction> actions) {
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
        finish();
    }

    @Override
    public void onPipResizeAboutToStart() {
        if (DEBUG) Log.d(TAG, "onPipResizeAboutToStart()");

        finish();
        sPipController.suspendPipResizing(
                PipController.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }

    @Override
    public void finish() {
        if (DEBUG) Log.d(TAG, "finish()", new RuntimeException());

        super.finish();
    }

    /**
     * TODO(b/169395392) Refactor PipMenuActivity to PipMenuView
     *
     * @param pipController The singleton pipController instance for TV
     */
    public static void setPipController(PipController pipController) {
        if (sPipController != null) {
            return;
        }
        sPipController = pipController;
    }
}
