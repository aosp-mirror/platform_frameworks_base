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
import android.car.trust.CarTrustAgentEnrollmentManager;
import android.car.userlib.CarUserManagerHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.view.ViewStub;

import androidx.recyclerview.widget.GridLayoutManager;

import com.android.systemui.R;
import com.android.systemui.statusbar.car.CarTrustAgentUnlockDialogHelper.OnHideListener;
import com.android.systemui.statusbar.car.UserGridRecyclerView.UserRecord;

/**
 * Manages the fullscreen user switcher.
 */
public class FullscreenUserSwitcher {
    private static final String TAG = FullscreenUserSwitcher.class.getSimpleName();
    // Because user 0 is headless, user count for single user is 2
    private static final int NUMBER_OF_BACKGROUND_USERS = 1;
    private final UserGridRecyclerView mUserGridView;
    private final View mParent;
    private final int mShortAnimDuration;
    private final CarStatusBar mStatusBar;
    private final Context mContext;
    private final UserManager mUserManager;
    private final CarTrustAgentEnrollmentManager mEnrollmentManager;
    private CarTrustAgentUnlockDialogHelper mUnlockDialogHelper;
    private UserGridRecyclerView.UserRecord mSelectedUser;
    private CarUserManagerHelper mCarUserManagerHelper;
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


    public FullscreenUserSwitcher(CarStatusBar statusBar, ViewStub containerStub,
            CarTrustAgentEnrollmentManager enrollmentManager, Context context) {
        mStatusBar = statusBar;
        mParent = containerStub.inflate();
        mEnrollmentManager = enrollmentManager;
        mContext = context;

        View container = mParent.findViewById(R.id.container);

        // Initialize user grid.
        mUserGridView = container.findViewById(R.id.user_grid);
        GridLayoutManager layoutManager = new GridLayoutManager(context,
                context.getResources().getInteger(R.integer.user_fullscreen_switcher_num_col));
        mUserGridView.setLayoutManager(layoutManager);
        mUserGridView.buildAdapter();
        mUserGridView.setUserSelectionListener(this::onUserSelected);
        mCarUserManagerHelper = new CarUserManagerHelper(context);
        mUnlockDialogHelper = new CarTrustAgentUnlockDialogHelper(mContext);
        mUserManager = mContext.getSystemService(UserManager.class);

        mShortAnimDuration = container.getResources()
                .getInteger(android.R.integer.config_shortAnimTime);
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        if (mUserManager.isUserUnlocked(UserHandle.USER_SYSTEM)) {
            // User0 is unlocked, switched to the initial user
            showDialogForInitialUser();
        } else {
            // listen to USER_UNLOCKED
            mContext.registerReceiverAsUser(mUserUnlockReceiver,
                    UserHandle.getUserHandleForUid(UserHandle.USER_SYSTEM),
                    filter,
                    /* broadcastPermission= */ null,
                    /* scheduler */ null);
        }
    }

    private void showDialogForInitialUser() {
        int initialUser = mCarUserManagerHelper.getInitialUser();
        UserInfo initialUserInfo = mUserManager.getUserInfo(initialUser);
        mSelectedUser = new UserRecord(initialUserInfo,
                /* isStartGuestSession= */ false,
                /* isAddUser= */ false,
                /* isForeground= */ true);
        // For single user without trusted device, hide the user switcher.
        if (!hasMultipleUsers() && !hasTrustedDevice(initialUser)) {
            dismissUserSwitcher();
            return;
        }
        // Show unlock dialog for initial user
        if (hasTrustedDevice(initialUser)) {
            mUnlockDialogHelper.showUnlockDialogAfterDelay(initialUser,
                    mOnHideListener);
        }
    }

    /**
     * Check if there is only one possible user to login in.
     * In a Multi-User system there is always one background user (user 0)
     */
    private boolean hasMultipleUsers() {
        return mUserManager.getUserCount() > NUMBER_OF_BACKGROUND_USERS + 1;
    }

    /**
     * Makes user grid visible.
     */
    public void show() {
        mParent.setVisibility(View.VISIBLE);
    }

    /**
     * Hides the user grid.
     */
    public void hide() {
        mParent.setVisibility(View.INVISIBLE);
    }

    /**
     * @return {@code true} if user grid is visible, {@code false} otherwise.
     */
    public boolean isVisible() {
        return mParent.getVisibility() == View.VISIBLE;
    }

    /**
     * Every time user clicks on an item in the switcher, if the clicked user has no trusted device,
     * we hide the switcher, either gradually or immediately.
     *
     * If the user has trusted device, we show an unlock dialog to notify user the unlock state.
     * When the unlock dialog is dismissed by user, we hide the unlock dialog and the switcher.
     *
     * We dismiss the entire keyguard when we hide the switcher if user clicked on the foreground
     * user (user we're already logged in as).
     */
    private void onUserSelected(UserGridRecyclerView.UserRecord record) {
        mSelectedUser = record;
        if (hasTrustedDevice(record.mInfo.id)) {
            mUnlockDialogHelper.showUnlockDialog(record.mInfo.id, mOnHideListener);
            return;
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "no trusted device enrolled for uid: " + record.mInfo.id);
        }
        dismissUserSwitcher();
    }

    private void dismissUserSwitcher() {
        if (mSelectedUser == null) {
            Log.e(TAG, "Request to dismiss user switcher, but no user selected");
            return;
        }
        if (mSelectedUser.mIsForeground) {
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

    private boolean hasTrustedDevice(int uid) {
        return !mEnrollmentManager.getEnrolledDeviceInfoForUser(uid).isEmpty();
    }

    private OnHideListener mOnHideListener = new OnHideListener() {
        @Override
        public void onHide(boolean dismissUserSwitcher) {
            if (dismissUserSwitcher) {
                dismissUserSwitcher();
            } else {
                // Re-draw the parent view, otherwise the unlock dialog will not be removed from
                // the screen immediately.
                mParent.invalidate();
            }

        }
    };
}
