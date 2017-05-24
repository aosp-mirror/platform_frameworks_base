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

import android.content.res.Resources;
import android.os.CountDownTimer;
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
    private final UserGridView mUserGridView;
    private final UserSwitcherController mUserSwitcherController;
    private final ProgressBar mProgressBar;
    private final int mLoginTimeoutMs;
    private final int mAnimUpdateIntervalMs;

    private CountDownTimer mTimer;

    public FullscreenUserSwitcher(StatusBar statusBar,
            UserSwitcherController userSwitcherController,
            ViewStub containerStub) {
        mUserSwitcherController = userSwitcherController;
        mContainer = containerStub.inflate();
        mUserGridView = mContainer.findViewById(R.id.user_grid);
        mUserGridView.init(statusBar, mUserSwitcherController);

        PageIndicator pageIndicator = mContainer.findViewById(R.id.user_switcher_page_indicator);
        pageIndicator.setupWithViewPager(mUserGridView);

        mProgressBar = mContainer.findViewById(R.id.countdown_progress);
        Resources res = mContainer.getResources();
        mLoginTimeoutMs = res.getInteger(R.integer.car_user_switcher_timeout_ms);
        mAnimUpdateIntervalMs = res.getInteger(R.integer.car_user_switcher_anim_update_ms);

        mContainer.findViewById(R.id.start_driving).setOnClickListener(v -> {
            cancelTimer();
            automaticallySelectUser();
        });
    }

    public void onUserSwitched(int newUserId) {
        mUserGridView.onUserSwitched(newUserId);
    }

    public void show() {
        mContainer.setVisibility(View.VISIBLE);
        cancelTimer();
        mTimer = new CountDownTimer(mLoginTimeoutMs, mAnimUpdateIntervalMs) {
            @Override
            public void onTick(long msUntilFinished) {
                int elapsed = mLoginTimeoutMs - (int) msUntilFinished;
                mProgressBar.setProgress((int) elapsed, true /* animate */);
            }

            @Override
            public void onFinish() {
                mProgressBar.setProgress(mLoginTimeoutMs, true /* animate */);
                automaticallySelectUser();
            }
        };
        mTimer.start();
    }

    public void hide() {
        cancelTimer();
        mContainer.setVisibility(View.GONE);
    }

    private void cancelTimer() {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
    }

    private void automaticallySelectUser() {
        // TODO: Switch according to some policy. This implementation just tries to drop the
        //       keyguard for the current user.
        mUserGridView.showOfflineAuthUi();
    }
}
