/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.tv;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.view.View;

import com.android.systemui.R;

import java.util.Collections;
/**
 * Activity to show the PIP menu to control PIP.
 */
public class PipMenuActivity extends Activity implements PipManager.Listener {
    private static final String TAG = "PipMenuActivity";

    static final String EXTRA_CUSTOM_ACTIONS = "custom_actions";

    private final PipManager mPipManager = PipManager.getInstance();

    private Animator mFadeInAnimation;
    private Animator mFadeOutAnimation;
    private PipControlsView mPipControlsView;
    private boolean mRestorePipSizeWhenClose;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (!mPipManager.isPipShown()) {
            finish();
        }
        setContentView(R.layout.tv_pip_menu);
        mPipManager.addListener(this);

        mRestorePipSizeWhenClose = true;
        mPipControlsView = findViewById(R.id.pip_controls);
        mFadeInAnimation = AnimatorInflater.loadAnimator(
                this, R.anim.tv_pip_menu_fade_in_animation);
        mFadeInAnimation.setTarget(mPipControlsView);
        mFadeOutAnimation = AnimatorInflater.loadAnimator(
                this, R.anim.tv_pip_menu_fade_out_animation);
        mFadeOutAnimation.setTarget(mPipControlsView);

        onPipMenuActionsChanged(getIntent().getParcelableExtra(EXTRA_CUSTOM_ACTIONS));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        onPipMenuActionsChanged(getIntent().getParcelableExtra(EXTRA_CUSTOM_ACTIONS));
    }

    private void restorePipAndFinish() {
        if (mRestorePipSizeWhenClose) {
            // When PIP menu activity is closed, restore to the default position.
            mPipManager.resizePinnedStack(PipManager.STATE_PIP);
        }
        finish();
    }

    @Override
    public void onResume() {
        super.onResume();
        mFadeInAnimation.start();
    }

    @Override
    public void onPause() {
        super.onPause();
        mFadeOutAnimation.start();
        restorePipAndFinish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPipManager.removeListener(this);
        mPipManager.resumePipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }

    @Override
    public void onBackPressed() {
        restorePipAndFinish();
    }

    @Override
    public void onPipEntered() { }

    @Override
    public void onPipActivityClosed() {
        finish();
    }

    @Override
    public void onPipMenuActionsChanged(ParceledListSlice actions) {
        boolean hasCustomActions = actions != null && !actions.getList().isEmpty();
        mPipControlsView.setActions(hasCustomActions ? actions.getList() : Collections.EMPTY_LIST);
    }

    @Override
    public void onShowPipMenu() { }

    @Override
    public void onMoveToFullscreen() {
        // Moving PIP to fullscreen is implemented by resizing PINNED_STACK with null bounds.
        // This conflicts with restoring PIP position, so disable it.
        mRestorePipSizeWhenClose = false;
        finish();
    }

    @Override
    public void onPipResizeAboutToStart() {
        finish();
        mPipManager.suspendPipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }
}
