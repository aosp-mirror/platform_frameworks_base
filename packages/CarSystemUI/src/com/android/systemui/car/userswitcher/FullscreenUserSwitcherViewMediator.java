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

package com.android.systemui.car.userswitcher;

import com.android.systemui.car.keyguard.CarKeyguardViewController;
import com.android.systemui.car.window.OverlayViewMediator;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages the fullscreen user switcher and it's interactions with the keyguard.
 */
@Singleton
public class FullscreenUserSwitcherViewMediator implements OverlayViewMediator {
    private static final String TAG = FullscreenUserSwitcherViewMediator.class.getSimpleName();

    private final StatusBarStateController mStatusBarStateController;
    private final FullScreenUserSwitcherViewController mFullScreenUserSwitcherViewController;
    private final CarKeyguardViewController mCarKeyguardViewController;
    private final UserSwitchTransitionViewController mUserSwitchTransitionViewController;

    @Inject
    public FullscreenUserSwitcherViewMediator(
            StatusBarStateController statusBarStateController,
            CarKeyguardViewController carKeyguardViewController,
            UserSwitchTransitionViewController userSwitchTransitionViewController,
            FullScreenUserSwitcherViewController fullScreenUserSwitcherViewController) {

        mStatusBarStateController = statusBarStateController;
        mCarKeyguardViewController = carKeyguardViewController;
        mUserSwitchTransitionViewController = userSwitchTransitionViewController;
        mFullScreenUserSwitcherViewController = fullScreenUserSwitcherViewController;
    }

    @Override
    public void registerListeners() {
        registerUserSwitcherHideListeners();
    }

    private void registerUserSwitcherHideListeners() {
        mStatusBarStateController.addCallback(new StatusBarStateController.StateListener() {
            @Override
            public void onStateChanged(int newState) {
                if (newState == StatusBarState.FULLSCREEN_USER_SWITCHER) {
                    return;
                }
                hide();
            }
        });
    }

    @Override
    public void setupOverlayContentViewControllers() {
        mFullScreenUserSwitcherViewController.setUserGridSelectionListener(this::onUserSelected);
    }

    /**
     * Every time user clicks on an item in the switcher, we hide the switcher.
     */
    private void onUserSelected(UserGridRecyclerView.UserRecord record) {
        if (record.mType != UserGridRecyclerView.UserRecord.FOREGROUND_USER) {
            mCarKeyguardViewController.hideKeyguardToPrepareBouncer();
            // If guest user, we cannot use record.mInfo.id and should listen to the User lifecycle
            // event instead.
            if (record.mType != UserGridRecyclerView.UserRecord.START_GUEST) {
                mUserSwitchTransitionViewController.handleShow(record.mInfo.id);
            }
        }

        hide();
    }

    private void hide() {
        mFullScreenUserSwitcherViewController.stop();
    }

    private void show() {
        mFullScreenUserSwitcherViewController.start();
    }
}
