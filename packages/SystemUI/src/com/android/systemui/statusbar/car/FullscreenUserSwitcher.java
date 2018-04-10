/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.car;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.View;
import android.view.ViewStub;
import android.widget.ProgressBar;

import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBar;

/**
 * Manages the fullscreen user switcher.
 */
public class FullscreenUserSwitcher {
    private final View mContainer;
    private final View mParent;
    private final UserGridRecyclerView mUserGridView;
    private final ProgressBar mSwitchingUsers;
    private final int mShortAnimDuration;
    private final StatusBar mStatusBar;

    private boolean mShowing;

    public FullscreenUserSwitcher(StatusBar statusBar, ViewStub containerStub, Context context) {
        mStatusBar = statusBar;
        mParent = containerStub.inflate();
        mContainer = mParent.findViewById(R.id.container);
        mUserGridView = mContainer.findViewById(R.id.user_grid);
        GridLayoutManager layoutManager = new GridLayoutManager(context,
                context.getResources().getInteger(R.integer.user_fullscreen_switcher_num_col));
        mUserGridView.setLayoutManager(layoutManager);
        mUserGridView.buildAdapter();
        mUserGridView.setUserSelectionListener(this::onUserSelected);

        mShortAnimDuration = mContainer.getResources()
            .getInteger(android.R.integer.config_shortAnimTime);

        mSwitchingUsers = mParent.findViewById(R.id.switching_users);
    }

    public void show() {
        if (mShowing) {
            return;
        }
        mShowing = true;
        mParent.setVisibility(View.VISIBLE);
    }

    public void hide() {
        mShowing = false;
        toggleSwitchInProgress(false);
        mParent.setVisibility(View.GONE);
    }

    public void onUserSwitched(int newUserId) {
        mParent.post(this::showOfflineAuthUi);
    }

    private void onUserSelected(UserGridRecyclerView.UserRecord record) {
        if (record.mIsForeground) {
            showOfflineAuthUi();
            return;
        }
        toggleSwitchInProgress(true);
    }

    private void showOfflineAuthUi() {
        mStatusBar.executeRunnableDismissingKeyguard(null/* runnable */, null /* cancelAction */,
                true /* dismissShade */, true /* afterKeyguardGone */, true /* deferred */);
    }

    private void toggleSwitchInProgress(boolean inProgress) {
        if (inProgress) {
            crossFade(mSwitchingUsers, mContainer);
        } else {
            crossFade(mContainer, mSwitchingUsers);
        }
    }

    private void crossFade(View incoming, View outgoing) {
        incoming.animate()
            .alpha(1.0f)
            .setDuration(mShortAnimDuration)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animator) {
                    incoming.setAlpha(0.0f);
                    incoming.setVisibility(View.VISIBLE);
                }
            });

        outgoing.animate()
            .alpha(0.0f)
            .setDuration(mShortAnimDuration)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    outgoing.setVisibility(View.GONE);
                }
            });
    }
}
