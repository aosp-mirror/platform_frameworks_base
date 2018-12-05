/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.View;
import android.view.ViewStub;

import androidx.recyclerview.widget.GridLayoutManager;

import com.android.systemui.R;

/**
 * Manages the fullscreen user switcher.
 */
public class FullscreenUserSwitcher {
    private final UserGridRecyclerView mUserGridView;
    private final View mParent;
    private final int mShortAnimDuration;
    private final CarStatusBar mStatusBar;

    public FullscreenUserSwitcher(CarStatusBar statusBar, ViewStub containerStub, Context context) {
        mStatusBar = statusBar;
        mParent = containerStub.inflate();
        mParent.setVisibility(View.VISIBLE);
        View container = mParent.findViewById(R.id.container);

        // Initialize user grid.
        mUserGridView = container.findViewById(R.id.user_grid);
        GridLayoutManager layoutManager = new GridLayoutManager(context,
                context.getResources().getInteger(R.integer.user_fullscreen_switcher_num_col));
        mUserGridView.getRecyclerView().setLayoutManager(layoutManager);
        mUserGridView.buildAdapter();
        mUserGridView.setUserSelectionListener(this::onUserSelected);

        // Hide the user grid by default. It will only be made visible by clicking on a cancel
        // button in a bouncer.
        hide();

        mShortAnimDuration = container.getResources()
                .getInteger(android.R.integer.config_shortAnimTime);
    }

    /**
     * Makes user grid visible.
     */
    public void show() {
        mUserGridView.setVisibility(View.VISIBLE);
    }

    /**
     * Hides the user grid.
     */
    public void hide() {
        mUserGridView.setVisibility(View.INVISIBLE);
    }

    /**
     * @return {@code true} if user grid is visible, {@code false} otherwise.
     */
    public boolean isVisible() {
        return mUserGridView.getVisibility() == View.VISIBLE;
    }

    /**
     * Every time user clicks on an item in the switcher, we hide the switcher, either
     * gradually or immediately.
     *
     * We dismiss the entire keyguard if user clicked on the foreground user (user we're already
     * logged in as).
     */
    private void onUserSelected(UserGridRecyclerView.UserRecord record) {
        if (record.mIsForeground) {
            hide();
            mStatusBar.dismissKeyguard();
            return;
        }
        // Switching is about to happen, since it takes time, fade out the switcher gradually.
        fadeOut();
    }

    private void fadeOut() {
        mUserGridView.animate()
                .alpha(0.0f)
                .setDuration(mShortAnimDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        hide();
                        mUserGridView.setAlpha(1.0f);
                    }
                });

    }
}
