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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Handler;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.car.CarStatusBar;
import com.android.systemui.statusbar.car.CarStatusBarKeyguardViewManager;
import com.android.systemui.window.OverlayViewMediator;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages the fullscreen user switcher and it's interactions with the keyguard.
 */
@Singleton
public class FullscreenUserSwitcherViewMediator implements OverlayViewMediator {
    private static final String TAG = FullscreenUserSwitcherViewMediator.class.getSimpleName();

    private final Context mContext;
    private final CarStatusBarKeyguardViewManager mCarStatusBarKeyguardViewManager;
    private final Handler mMainHandler;
    private final StatusBarStateController mStatusBarStateController;
    private final FullScreenUserSwitcherViewController mFullScreenUserSwitcherViewController;
    private final ScreenLifecycle mScreenLifecycle;
    private final CarStatusBar mCarStatusBar;
    private final boolean mIsUserSwitcherEnabled;

    @Inject
    public FullscreenUserSwitcherViewMediator(
            Context context,
            @Main Resources resources,
            @Main Handler mainHandler,
            CarStatusBarKeyguardViewManager carStatusBarKeyguardViewManager,
            CarStatusBar carStatusBar,
            StatusBarStateController statusBarStateController,
            FullScreenUserSwitcherViewController fullScreenUserSwitcherViewController,
            ScreenLifecycle screenLifecycle) {
        mContext = context;

        mIsUserSwitcherEnabled = resources.getBoolean(R.bool.config_enableFullscreenUserSwitcher);

        mMainHandler = mainHandler;

        mCarStatusBarKeyguardViewManager = carStatusBarKeyguardViewManager;
        mCarStatusBar = carStatusBar;
        mStatusBarStateController = statusBarStateController;
        mFullScreenUserSwitcherViewController = fullScreenUserSwitcherViewController;
        mScreenLifecycle = screenLifecycle;
    }

    @Override
    public void registerListeners() {
        registerUserSwitcherShowListeners();
        registerUserSwitcherHideListeners();
        registerHideKeyguardListeners();
    }

    private void registerUserSwitcherShowListeners() {
        mCarStatusBarKeyguardViewManager.addOnKeyguardCancelClickedListener(this::show);
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

    private void registerHideKeyguardListeners() {
        mStatusBarStateController.addCallback(new StatusBarStateController.StateListener() {
            @Override
            public void onStateChanged(int newState) {
                if (newState != StatusBarState.FULLSCREEN_USER_SWITCHER) {
                    return;
                }
                dismissKeyguardWhenUserSwitcherNotDisplayed(newState);
            }
        });

        mScreenLifecycle.addObserver(new ScreenLifecycle.Observer() {
            @Override
            public void onScreenTurnedOn() {
                dismissKeyguardWhenUserSwitcherNotDisplayed(mStatusBarStateController.getState());
            }
        });

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!intent.getAction().equals(Intent.ACTION_USER_SWITCHED)) {
                    return;
                }

                // Try to dismiss the keyguard after every user switch.
                dismissKeyguardWhenUserSwitcherNotDisplayed(mStatusBarStateController.getState());
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED));
    }

    @Override
    public void setupOverlayContentViewControllers() {
        mFullScreenUserSwitcherViewController.setUserGridSelectionListener(this::onUserSelected);
    }

    /**
     * Every time user clicks on an item in the switcher, we hide the switcher.
     *
     * We dismiss the entire keyguard if user clicked on the foreground user (user we're already
     * logged in as).
     */
    private void onUserSelected(UserGridRecyclerView.UserRecord record) {
        hide();
        if (record.mType == UserGridRecyclerView.UserRecord.FOREGROUND_USER) {
            mCarStatusBar.dismissKeyguard();
        }
    }

    // We automatically dismiss keyguard unless user switcher is being shown above the keyguard.
    private void dismissKeyguardWhenUserSwitcherNotDisplayed(int state) {
        if (!mIsUserSwitcherEnabled) {
            return; // Not using the full screen user switcher.
        }

        if (state == StatusBarState.FULLSCREEN_USER_SWITCHER
                && !mFullScreenUserSwitcherViewController.isVisible()) {
            // Current execution path continues to set state after this, thus we deffer the
            // dismissal to the next execution cycle.

            // Dismiss the keyguard if switcher is not visible.
            // TODO(b/150402329): Remove once keyguard is implemented using Overlay Window
            //  abstractions.
            mMainHandler.post(mCarStatusBar::dismissKeyguard);
        }
    }

    private void hide() {
        mFullScreenUserSwitcherViewController.stop();
    }

    private void show() {
        mFullScreenUserSwitcherViewController.start();
    }
}
