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

import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 */
@Singleton
public class ManagedProfileControllerImpl implements ManagedProfileController {

    private final List<Callback> mCallbacks = new ArrayList<>();

    private final Context mContext;
    private final UserManager mUserManager;
    private final LinkedList<UserInfo> mProfiles;
    private boolean mListening;
    private int mCurrentUser;

    /**
     */
    @Inject
    public ManagedProfileControllerImpl(Context context) {
        mContext = context;
        mUserManager = UserManager.get(mContext);
        mProfiles = new LinkedList<UserInfo>();
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
        if (mCallbacks.size() == 1) {
            setListening(true);
        }
        callback.onManagedProfileChanged();
    }

    public void removeCallback(Callback callback) {
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
            int user = ActivityManager.getCurrentUser();
            mProfiles.clear();

            for (UserInfo ui : mUserManager.getEnabledProfiles(user)) {
                if (ui.isManagedProfile()) {
                    mProfiles.add(ui);
                }
            }
            if (mProfiles.size() == 0 && hadProfile && (user == mCurrentUser)) {
                for (Callback callback : mCallbacks) {
                    callback.onManagedProfileRemoved();
                }
            }
            mCurrentUser = user;
        }
    }

    public boolean hasActiveProfile() {
        if (!mListening) reloadManagedProfiles();
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
        mListening = listening;
        if (listening) {
            reloadManagedProfiles();

            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
            mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, null);
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadManagedProfiles();
            for (Callback callback : mCallbacks) {
                callback.onManagedProfileChanged();
            }
        }
    };
}
