/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.app.StatusBarManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public class ManagedProfileControllerImpl implements ManagedProfileController {

    private final List<Callback> mCallbacks = new ArrayList<>();
    private final UserTrackerCallback mUserTrackerCallback = new UserTrackerCallback();
    private final Context mContext;
    private final Executor mMainExecutor;
    private final UserManager mUserManager;
    private final UserTracker mUserTracker;
    private final LinkedList<UserInfo> mProfiles;

    private boolean mListening;
    private int mCurrentUser;

    @Inject
    public ManagedProfileControllerImpl(Context context, @Main Executor mainExecutor,
            UserTracker userTracker, UserManager userManager) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mUserManager = userManager;
        mUserTracker = userTracker;
        mProfiles = new LinkedList<>();
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        mCallbacks.add(callback);
        if (mCallbacks.size() == 1) {
            setListening(true);
        }
        callback.onManagedProfileChanged();
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        if (mCallbacks.remove(callback) && mCallbacks.size() == 0) {
            setListening(false);
        }
    }

    public void setWorkModeEnabled(boolean enableWorkMode) {
        synchronized (mProfiles) {
            for (UserInfo ui : mProfiles) {
                if (!mUserManager.requestQuietModeEnabled(!enableWorkMode, UserHandle.of(ui.id))) {
                    StatusBarManager statusBarManager = (StatusBarManager) mContext
                            .getSystemService(android.app.Service.STATUS_BAR_SERVICE);
                    statusBarManager.collapsePanels();
                }
            }
        }
    }

    private void reloadManagedProfiles() {
        synchronized (mProfiles) {
            boolean hadProfile = mProfiles.size() > 0;
            int user = mUserTracker.getUserId();
            mProfiles.clear();

            for (UserInfo ui : mUserManager.getEnabledProfiles(user)) {
                if (ui.isManagedProfile()) {
                    mProfiles.add(ui);
                }
            }
            if (mProfiles.size() == 0 && hadProfile && (user == mCurrentUser)) {
                mMainExecutor.execute(this::notifyManagedProfileRemoved);
            }
            mCurrentUser = user;
        }
    }

    private void notifyManagedProfileRemoved() {
        for (Callback callback : mCallbacks) {
            callback.onManagedProfileRemoved();
        }
    }

    public boolean hasActiveProfile() {
        if (!mListening || mUserTracker.getUserId() != mCurrentUser) {
            reloadManagedProfiles();
        }
        synchronized (mProfiles) {
            return mProfiles.size() > 0;
        }
    }

    public boolean isWorkModeEnabled() {
        if (!mListening) reloadManagedProfiles();
        synchronized (mProfiles) {
            for (UserInfo ui : mProfiles) {
                if (ui.isQuietModeEnabled()) {
                    return false;
                }
            }
            return true;
        }
    }

    private void setListening(boolean listening) {
        if (mListening == listening) {
            return;
        }
        mListening = listening;
        if (listening) {
            reloadManagedProfiles();
            mUserTracker.addCallback(mUserTrackerCallback, mMainExecutor);
        } else {
            mUserTracker.removeCallback(mUserTrackerCallback);
        }
    }

    private final class UserTrackerCallback implements UserTracker.Callback {

        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            reloadManagedProfiles();
            for (Callback callback : mCallbacks) {
                callback.onManagedProfileChanged();
            }
        }

        @Override
        public void onProfilesChanged(@NonNull List<UserInfo> profiles) {
            reloadManagedProfiles();
            for (Callback callback : mCallbacks) {
                callback.onManagedProfileChanged();
            }
        }
    }
}
