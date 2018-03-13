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
import android.content.res.Resources;
import android.view.View;
import android.view.ViewStub;
import android.widget.ProgressBar;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * Manages the fullscreen user switcher.
 */
public class FullscreenUserSwitcher {
    private final View mContainer;
    private final View mParent;
    private final UserGridView mUserGridView;
    private final UserSwitcherController mUserSwitcherController;
    private final ProgressBar mSwitchingUsers;
    private final int mShortAnimDuration;

    private boolean mShowing;

    public FullscreenUserSwitcher(StatusBar statusBar,
            UserSwitcherController userSwitcherController,
            ViewStub containerStub) {
        mUserSwitcherController = userSwitcherController;
        mParent = containerStub.inflate();
        mContainer = mParent.findViewById(R.id.container);
        mUserGridView = mContainer.findViewById(R.id.user_grid);
        mUserGridView.init(statusBar, mUserSwitcherController, true /* overrideAlpha */);
        mUserGridView.setUserSelectionListener(record -> {
            if (!record.isCurrent) {
                toggleSwitchInProgress(true);
            }
        });

        PageIndicator pageIndicator = mContainer.findViewById(R.id.user_switcher_page_indicator);
        pageIndicator.setupWithViewPager(mUserGridView);

        Resources res = mContainer.getResources();
        mShortAnimDuration = res.getInteger(android.R.integer.config_shortAnimTime);

        mContainer.findViewById(R.id.start_driving).setOnClickListener(v -> {
            automaticallySelectUser();
        });

        mSwitchingUsers = mParent.findViewById(R.id.switching_users);
    }

    public void onUserSwitched(int newUserId) {
        mUserGridView.onUserSwitched(newUserId);
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

    private void automaticallySelectUser() {
        // TODO: Switch according to some policy. This implementation just tries to drop the
        //       keyguard for the current user.
        mUserGridView.showOfflineAuthUi();
    }
}
