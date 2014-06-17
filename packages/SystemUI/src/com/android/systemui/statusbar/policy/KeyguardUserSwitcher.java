/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarHeaderView;
import com.android.systemui.statusbar.phone.UserAvatarView;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.WindowManagerGlobal;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the user switcher on the Keyguard.
 */
public class KeyguardUserSwitcher implements View.OnClickListener {

    private static final String TAG = "KeyguardUserSwitcher";

    private final Context mContext;
    private final ViewGroup mUserSwitcher;
    private final UserManager mUserManager;
    private final StatusBarHeaderView mHeader;

    public KeyguardUserSwitcher(Context context, ViewStub userSwitcher,
            StatusBarHeaderView header) {
        mContext = context;
        if (context.getResources().getBoolean(R.bool.config_keyguardUserSwitcher)) {
            mUserSwitcher = (ViewGroup) userSwitcher.inflate();
            mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            mHeader = header;
            refresh();
        } else {
            mUserSwitcher = null;
            mUserManager = null;
            mHeader = null;
        }
    }

    public void setKeyguard(boolean keyguard) {
        if (mUserSwitcher != null) {
            // TODO: Cache showUserSwitcherOnKeyguard().
            if (keyguard && showUserSwitcherOnKeyguard()) {
                show();
                refresh();
            } else {
                hide();
            }
        }
    }

    /**
     * @return true if the user switcher should be shown on the lock screen.
     * @see android.os.UserManager#isUserSwitcherEnabled()
     */
    private boolean showUserSwitcherOnKeyguard() {
        // TODO: Set isEdu. The edu provisioning process can add settings to Settings.Global.
        boolean isEdu = false;
        if (isEdu) {
            return true;
        }
        List<UserInfo> users = mUserManager.getUsers(true /* excludeDying */);
        int N = users.size();
        int switchableUsers = 0;
        for (int i = 0; i < N; i++) {
            if (users.get(i).supportsSwitchTo()) {
                switchableUsers++;
            }
        }
        return switchableUsers > 1;
    }

    public void show() {
        if (mUserSwitcher != null) {
            // TODO: animate
            mUserSwitcher.setVisibility(View.VISIBLE);
            mHeader.setKeyguardUserSwitcherShowing(true);
        }
    }

    private void hide() {
        if (mUserSwitcher != null) {
            // TODO: animate
            mUserSwitcher.setVisibility(View.GONE);
            mHeader.setKeyguardUserSwitcherShowing(false);
        }
    }

    private void refresh() {
        if (mUserSwitcher != null) {
            new AsyncTask<Void, Void, ArrayList<UserData>>() {
                @Override
                protected ArrayList<UserData> doInBackground(Void... params) {
                    return loadUsers();
                }

                @Override
                protected void onPostExecute(ArrayList<UserData> userInfos) {
                    bind(userInfos);
                }
            }.execute((Void[]) null);
        }
    }

    private void bind(ArrayList<UserData> userList) {
        mUserSwitcher.removeAllViews();
        int N = userList.size();
        for (int i = 0; i < N; i++) {
            mUserSwitcher.addView(inflateUser(userList.get(i)));
        }
        // TODO: add Guest
        // TODO: add (+) button
    }

    private View inflateUser(UserData user) {
        View v = LayoutInflater.from(mUserSwitcher.getContext()).inflate(
                R.layout.keyguard_user_switcher_item, mUserSwitcher, false);
        TextView name = (TextView) v.findViewById(R.id.name);
        UserAvatarView picture = (UserAvatarView) v.findViewById(R.id.picture);
        name.setText(user.userInfo.name);
        picture.setActivated(user.isCurrent);
        if (user.userInfo.isGuest()) {
            picture.setDrawable(mContext.getResources().getDrawable(R.drawable.ic_account_circle));
        } else {
            picture.setBitmap(user.userIcon);
        }
        v.setOnClickListener(this);
        v.setTag(user.userInfo);
        // TODO: mark which user is current for accessibility.
        return v;
    }

    @Override
    public void onClick(View v) {
        switchUser(((UserInfo)v.getTag()).id);
    }

    // TODO: Factor out logic below and share with QS implementation.

    private ArrayList<UserData> loadUsers() {
        ArrayList<UserInfo> users = (ArrayList<UserInfo>) mUserManager
                .getUsers(true /* excludeDying */);
        int N = users.size();
        ArrayList<UserData> result = new ArrayList<>(N);
        int currentUser = -1;
        try {
            currentUser = ActivityManagerNative.getDefault().getCurrentUser().id;
        } catch (RemoteException e) {
            Log.e(TAG, "Couln't get current user.", e);
        }
        for (int i = 0; i < N; i++) {
            UserInfo user = users.get(i);
            if (user.supportsSwitchTo()) {
                boolean isCurrent = user.id == currentUser;
                result.add(new UserData(user, mUserManager.getUserIcon(user.id), isCurrent));
            }
        }
        return result;
    }

    private void switchUser(int userId) {
        try {
            WindowManagerGlobal.getWindowManagerService().lockNow(null);
            ActivityManagerNative.getDefault().switchUser(userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    private static class UserData {
        final UserInfo userInfo;
        final Bitmap userIcon;
        final boolean isCurrent;

        UserData(UserInfo userInfo, Bitmap userIcon, boolean isCurrent) {
            this.userInfo = userInfo;
            this.userIcon = userIcon;
            this.isCurrent = isCurrent;
        }
    }
}
