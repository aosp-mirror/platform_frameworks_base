/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.android.internal.R;

import java.util.ArrayList;

public class KeyguardMultiUserSelectorView extends LinearLayout{
    private KeyguardMultiUserAvatar mActiveUser;
    private LinearLayout mInactiveUsers;

    public KeyguardMultiUserSelectorView(Context context) {
        this(context, null, 0);
    }

    public KeyguardMultiUserSelectorView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardMultiUserSelectorView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    protected void onFinishInflate () {
        init();
    }

    public void init() {
        mActiveUser = (KeyguardMultiUserAvatar) findViewById(R.id.keyguard_active_user);
        mInactiveUsers = (LinearLayout) findViewById(R.id.keyguard_inactive_users);

        mInactiveUsers.removeAllViews();

        UserInfo currentUser;
        try {
            currentUser = ActivityManagerNative.getDefault().getCurrentUser();
        } catch (RemoteException re) {
            currentUser = null;
        }

        UserManager mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUm.getUsers());
        for (UserInfo user: users) {
            if (user.id == currentUser.id) {
                setActiveUser(user);
            } else {
                createAndAddInactiveUser(user);
            }
        }
    }

    private void setActiveUser(UserInfo user) {
        mActiveUser.setup(user, this);
    }

    private void createAndAddInactiveUser(UserInfo user) {
        KeyguardMultiUserAvatar uv = KeyguardMultiUserAvatar.fromXml(
                R.layout.keyguard_multi_user_avatar, mContext, this, user);
        mInactiveUsers.addView(uv);
    }
}
