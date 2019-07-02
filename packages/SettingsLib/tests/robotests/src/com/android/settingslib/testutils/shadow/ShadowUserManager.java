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

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.List;

@Implements(value = UserManager.class)
public class ShadowUserManager extends org.robolectric.shadows.ShadowUserManager {
    private List<UserInfo> mUserInfos = addProfile(0, "Owner");

    @Implementation
    protected static UserManager get(Context context) {
        return (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    @Implementation
    protected int[] getProfileIdsWithDisabled(int userId) {
        return mUserInfos.stream().mapToInt(s -> s.id).toArray();
    }

    @Implementation
    protected List<UserInfo> getProfiles() {
        return mUserInfos;
    }

    public List<UserInfo> addProfile(int id, String name) {
        List<UserInfo> userInfoList = mUserInfos;
        if (userInfoList == null) {
            userInfoList = new ArrayList<>();
        }
        final UserInfo userInfo = new UserInfo();
        userInfo.id = id;
        userInfo.name = name;
        userInfoList.add(userInfo);
        return userInfoList;
    }

    @Implementation
    protected List<UserInfo> getProfiles(@UserIdInt int userHandle) {
        return getProfiles();
    }
}
