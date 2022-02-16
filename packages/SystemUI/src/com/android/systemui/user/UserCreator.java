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

package com.android.systemui.user;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.UserManager;

import com.android.internal.util.UserIcons;
import com.android.settingslib.users.UserCreatingDialog;
import com.android.settingslib.utils.ThreadUtils;

import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * A class to do the user creation process. It shows a progress dialog, and manages the user
 * creation
 */
public class UserCreator {

    private final Context mContext;
    private final UserManager mUserManager;

    @Inject
    public UserCreator(Context context, UserManager userManager) {
        mContext = context;
        mUserManager = userManager;
    }

    /**
     * Shows a progress dialog then starts the user creation process on the main thread.
     *
     * @param successCallback is called when the user creation is successful.
     * @param errorCallback   is called when userManager.createUser returns null.
     *                        (Exceptions are not handled by this class)
     */
    public void createUser(String userName, Drawable userIcon, Consumer<UserInfo> successCallback,
            Runnable errorCallback) {

        Dialog userCreationProgressDialog = new UserCreatingDialog(mContext);
        userCreationProgressDialog.show();

        // userManager.createUser will block the thread so post is needed for the dialog to show
        ThreadUtils.postOnMainThread(() -> {
            UserInfo user =
                    mUserManager.createUser(userName, UserManager.USER_TYPE_FULL_SECONDARY, 0);
            if (user == null) {
                // Couldn't create user for some reason
                userCreationProgressDialog.dismiss();
                errorCallback.run();
                return;
            }

            Drawable newUserIcon = userIcon;
            Resources res = mContext.getResources();
            if (newUserIcon == null) {
                newUserIcon = UserIcons.getDefaultUserIcon(res, user.id, false);
            }
            mUserManager.setUserIcon(
                    user.id, UserIcons.convertToBitmapAtUserIconSize(res, newUserIcon));

            userCreationProgressDialog.dismiss();
            successCallback.accept(user);
        });
    }
}
