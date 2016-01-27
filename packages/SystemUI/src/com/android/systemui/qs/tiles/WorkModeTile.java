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
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

import java.util.LinkedList;
import java.util.List;

/** Quick settings tile: Work profile on/off */
public class WorkModeTile extends QSTile<QSTile.BooleanState> {
    private final AnimationIcon mEnable =
            new AnimationIcon(R.drawable.ic_signal_workmode_enable_animation);
    private final AnimationIcon mDisable =
            new AnimationIcon(R.drawable.ic_signal_workmode_disable_animation);

    private boolean mListening;

    private UserManager mUserManager;
    private List<UserInfo> mProfiles;

    public WorkModeTile(Host host) {
        super(host);
        mUserManager = UserManager.get(mContext);
        mProfiles = new LinkedList<UserInfo>();
        reloadManagedProfiles(UserHandle.USER_CURRENT);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        MetricsLogger.action(mContext, getMetricsCategory(), !mState.value);
        setWorkModeEnabled(!mState.value);
    }

    private void reloadManagedProfiles(int userHandle) {
        synchronized (mProfiles) {
            mProfiles.clear();

            if (userHandle == UserHandle.USER_CURRENT) {
                userHandle = ActivityManager.getCurrentUser();
            }
            for (UserInfo ui : mUserManager.getEnabledProfiles(userHandle)) {
                if (ui.isManagedProfile()) {
                    mProfiles.add(ui);
                }
            }
        }
    }

    private boolean hasActiveProfile() {
        synchronized (mProfiles) {
            return mProfiles.size() > 0;
        }
    }

    private boolean isWorkModeEnabled() {
        synchronized (mProfiles) {
            for (UserInfo ui : mProfiles) {
                if (ui.isQuietModeEnabled()) {
                    return false;
                }
            }
            return true;
        }
    }

    private void refreshQuietModeState(boolean backgroundRefresh) {
        refreshState(isWorkModeEnabled());
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (!hasActiveProfile()) {
            mHost.removeTile(getTileSpec());
            return;
        }

        if (arg instanceof Boolean) {
            state.value = (Boolean) arg;
        } else {
            state.value = isWorkModeEnabled();
        }

        state.label = mContext.getString(R.string.quick_settings_work_mode_label);
        if (state.value) {
            state.icon = mEnable;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_work_mode_on);
        } else {
            state.icon = mDisable;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_work_mode_off);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_WORKMODE;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_work_mode_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_work_mode_changed_off);
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) {
            return;
        }
        mListening = listening;
        if (listening) {
            reloadManagedProfiles(UserHandle.USER_CURRENT);

            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
            filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABILITY_CHANGED);
            mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, null);
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    private void setWorkModeEnabled(boolean enabled) {
        synchronized (mProfiles) {
            for (UserInfo ui : mProfiles) {
                mUserManager.setQuietModeEnabled(ui.id, !enabled);
            }
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int targetUser;
            final boolean isBackgroundRefresh;
            switch (action) {
                case Intent.ACTION_USER_SWITCHED:
                    targetUser = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            UserHandle.USER_CURRENT);
                    isBackgroundRefresh = true;
                    break;
                case Intent.ACTION_MANAGED_PROFILE_ADDED:
                case Intent.ACTION_MANAGED_PROFILE_REMOVED:
                    targetUser = UserHandle.USER_CURRENT;
                    isBackgroundRefresh = true;
                    break;
                case Intent.ACTION_MANAGED_PROFILE_AVAILABILITY_CHANGED:
                    targetUser = UserHandle.USER_CURRENT;
                    isBackgroundRefresh = false;
                    break;
               default:
                   targetUser = UserHandle.USER_NULL;
                   isBackgroundRefresh = false;
            }
            if (targetUser != UserHandle.USER_NULL) {
                reloadManagedProfiles(targetUser);
                refreshQuietModeState(isBackgroundRefresh);
            }
        }
    };
}
