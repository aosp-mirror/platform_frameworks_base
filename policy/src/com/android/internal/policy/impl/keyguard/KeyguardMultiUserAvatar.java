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
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

class KeyguardMultiUserAvatar extends FrameLayout {
    private static final String TAG = "KeyguardViewHost";

    private ImageView mUserImage;
    private TextView mUserName;
    private UserInfo mUserInfo;
    private KeyguardMultiUserSelectorView mUserSelector;

    public static KeyguardMultiUserAvatar fromXml(int resId, Context context,
            KeyguardMultiUserSelectorView userSelector, UserInfo info) {
        KeyguardMultiUserAvatar icon = (KeyguardMultiUserAvatar)
                LayoutInflater.from(context).inflate(resId, userSelector, false);

        icon.setup(info, userSelector);
        return icon;
    }

    public KeyguardMultiUserAvatar(Context context) {
        super(context, null, 0);
    }

    public KeyguardMultiUserAvatar(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public KeyguardMultiUserAvatar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setup(UserInfo user, KeyguardMultiUserSelectorView userSelector) {
        mUserInfo = user;
        mUserSelector = userSelector;
        init();
    }

    private void init() {
        mUserImage = (ImageView) findViewById(R.id.keyguard_user_avatar);
        mUserName = (TextView) findViewById(R.id.keyguard_user_name);

        mUserImage.setImageDrawable(Drawable.createFromPath(mUserInfo.iconPath));
        mUserName.setText(mUserInfo.name);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ActivityManagerNative.getDefault().switchUser(mUserInfo.id);
                    WindowManagerGlobal.getWindowManagerService().lockNow();
                    mUserSelector.init();
                } catch (RemoteException re) {
                    Log.e(TAG, "Couldn't switch user " + re);
                }
            }
        });
    }
}
