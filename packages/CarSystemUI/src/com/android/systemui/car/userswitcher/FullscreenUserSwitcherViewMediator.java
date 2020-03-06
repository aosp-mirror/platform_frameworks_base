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

import android.car.Car;
import android.car.trust.CarTrustAgentEnrollmentManager;
import android.car.userlib.CarUserManagerHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;
import com.android.systemui.car.CarServiceProvider;
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
    private final UserManager mUserManager;
    private final CarServiceProvider mCarServiceProvider;
    private final CarTrustAgentUnlockDialogHelper mUnlockDialogHelper;
    private final CarStatusBarKeyguardViewManager mCarStatusBarKeyguardViewManager;
    private final Handler mMainHandler;
    private final StatusBarStateController mStatusBarStateController;
    private final FullScreenUserSwitcherViewController mFullScreenUserSwitcherViewController;
    private final ScreenLifecycle mScreenLifecycle;
    private final CarStatusBar mCarStatusBar;
    private final boolean mIsUserSwitcherEnabled;
    private final CarUserManagerHelper mCarUserManagerHelper;

    private CarTrustAgentEnrollmentManager mEnrollmentManager;
    private UserGridRecyclerView.UserRecord mSelectedUser;
    private final CarTrustAgentUnlockDialogHelper.OnHideListener mOnHideListener =
            dismissUserSwitcher -> {
                if (dismissUserSwitcher) {
                    dismissUserSwitcher();
                } else {
                    // Re-draw the parent view, otherwise the unlock dialog will not be removed
                    // from the screen immediately.
                    invalidateFullscreenUserSwitcherView();
                }
            };
    private final BroadcastReceiver mUserUnlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "user 0 is unlocked, SharedPreference is accessible.");
            }
            showDialogForInitialUser();
            mContext.unregisterReceiver(mUserUnlockReceiver);
        }
    };

    @Inject
    public FullscreenUserSwitcherViewMediator(
            Context context,
            @Main Resources resources,
            @Main Handler mainHandler,
            UserManager userManager,
            CarServiceProvider carServiceProvider,
            CarTrustAgentUnlockDialogHelper carTrustAgentUnlockDialogHelper,
            CarStatusBarKeyguardViewManager carStatusBarKeyguardViewManager,
            CarStatusBar carStatusBar,
            StatusBarStateController statusBarStateController,
            FullScreenUserSwitcherViewController fullScreenUserSwitcherViewController,
            ScreenLifecycle screenLifecycle) {
        mContext = context;

        mIsUserSwitcherEnabled = resources.getBoolean(R.bool.config_enableFullscreenUserSwitcher);

        mMainHandler = mainHandler;
        mUserManager = userManager;

        mCarServiceProvider = carServiceProvider;
        mCarServiceProvider.addListener(
                car -> mEnrollmentManager = (CarTrustAgentEnrollmentManager) car.getCarManager(
                        Car.CAR_TRUST_AGENT_ENROLLMENT_SERVICE));

        mUnlockDialogHelper = carTrustAgentUnlockDialogHelper;
        mCarStatusBarKeyguardViewManager = carStatusBarKeyguardViewManager;
        mCarStatusBar = carStatusBar;
        mStatusBarStateController = statusBarStateController;
        mFullScreenUserSwitcherViewController = fullScreenUserSwitcherViewController;
        mScreenLifecycle = screenLifecycle;

        mCarUserManagerHelper = new CarUserManagerHelper(mContext);
    }

    @Override
    public void registerListeners() {
        registerUserSwitcherShowListeners();
        registerUserSwitcherHideListeners();
        registerHideKeyguardListeners();

        if (mUserManager.isUserUnlocked(UserHandle.USER_SYSTEM)) {
            // User0 is unlocked, switched to the initial user
            showDialogForInitialUser();
        } else {
            // listen to USER_UNLOCKED
            mContext.registerReceiverAsUser(mUserUnlockReceiver,
                    UserHandle.getUserHandleForUid(UserHandle.USER_SYSTEM),
                    new IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    /* broadcastPermission= */ null,
                    /* scheduler= */ null);
        }
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
     * Every time user clicks on an item in the switcher, if the clicked user has no trusted
     * device, we hide the switcher, either gradually or immediately.
     * If the user has trusted device, we show an unlock dialog to notify user the unlock
     * state.
     * When the unlock dialog is dismissed by user, we hide the unlock dialog and the switcher.
     * We dismiss the entire keyguard when we hide the switcher if user clicked on the
     * foreground user (user we're already logged in as).
     */
    private void onUserSelected(UserGridRecyclerView.UserRecord record) {
        mSelectedUser = record;
        if (record.mInfo != null) {
            if (hasScreenLock(record.mInfo.id) && hasTrustedDevice(record.mInfo.id)) {
                mUnlockDialogHelper.showUnlockDialog(record.mInfo.id, mOnHideListener);
                return;
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "no trusted device enrolled for uid: " + record.mInfo.id);
            }
        }
        dismissUserSwitcher();
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

    private boolean hasScreenLock(int uid) {
        LockPatternUtils lockPatternUtils = new LockPatternUtils(mContext);
        return lockPatternUtils.getCredentialTypeForUser(uid)
                != LockPatternUtils.CREDENTIAL_TYPE_NONE;
    }

    private boolean hasTrustedDevice(int uid) {
        if (mEnrollmentManager == null) { // car service not ready, so it cannot be available.
            return false;
        }
        return !mEnrollmentManager.getEnrolledDeviceInfoForUser(uid).isEmpty();
    }

    private void dismissUserSwitcher() {
        if (mSelectedUser == null) {
            Log.e(TAG, "Request to dismiss user switcher, but no user selected");
            return;
        }
        if (mSelectedUser.mType == UserGridRecyclerView.UserRecord.FOREGROUND_USER) {
            hide();
            mCarStatusBar.dismissKeyguard();
            return;
        }
        hide();
    }

    private void showDialogForInitialUser() {
        int initialUser = mCarUserManagerHelper.getInitialUser();
        UserInfo initialUserInfo = mUserManager.getUserInfo(initialUser);
        mSelectedUser = new UserGridRecyclerView.UserRecord(initialUserInfo,
                UserGridRecyclerView.UserRecord.FOREGROUND_USER);

        // If the initial user has screen lock and trusted device, display the unlock dialog on the
        // keyguard.
        if (hasScreenLock(initialUser) && hasTrustedDevice(initialUser)) {
            mUnlockDialogHelper.showUnlockDialogAfterDelay(initialUser,
                    mOnHideListener);
        } else {
            // If no trusted device, dismiss the keyguard.
            dismissUserSwitcher();
        }
    }

    private void invalidateFullscreenUserSwitcherView() {
        mFullScreenUserSwitcherViewController.invalidate();
    }

    private void hide() {
        mFullScreenUserSwitcherViewController.stop();
    }

    private void show() {
        mFullScreenUserSwitcherViewController.start();
    }
}
