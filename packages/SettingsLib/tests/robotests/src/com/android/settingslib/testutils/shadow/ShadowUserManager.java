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

package com.android.settingslib.testutils.shadow;

import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.List;

@Implements(value = UserManager.class, inheritImplementationMethods = true)
public class ShadowUserManager extends org.robolectric.shadows.ShadowUserManager {

    private boolean mAdminUser;

    public void setIsAdminUser(boolean isAdminUser) {
        mAdminUser = isAdminUser;
    }

    @Resetter
    public void reset() {
        mAdminUser = false;
    }

    @Implementation
    public boolean isAdminUser() {
        return mAdminUser;
    }

    @Implementation
    public static UserManager get(Context context) {
        return (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    @Implementation
    public int[] getProfileIdsWithDisabled(int userId) {
        return new int[] { 0 };
    }

    @Implementation
    public List<UserInfo> getProfiles() {
        UserInfo userInfo = new UserInfo();
        userInfo.id = 0;
        List<UserInfo> userInfos = new ArrayList<>();
        userInfos.add(userInfo);
        return userInfos;
    }

    public static ShadowUserManager getShadow() {
        return (ShadowUserManager) Shadow.extract(
                RuntimeEnvironment.application.getSystemService(UserManager.class));
    }
}