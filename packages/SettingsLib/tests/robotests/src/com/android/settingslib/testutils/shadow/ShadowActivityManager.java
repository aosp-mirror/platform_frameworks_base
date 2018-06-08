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

import android.app.ActivityManager;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;

@Implements(ActivityManager.class)
public class ShadowActivityManager {
    private static int sCurrentUserId = 0;
    private int mUserSwitchedTo = -1;

    @Resetter
    public void reset() {
        sCurrentUserId = 0;
        mUserSwitchedTo = 0;
    }

    @Implementation
    public static int getCurrentUser() {
        return sCurrentUserId;
    }

    @Implementation
    public boolean switchUser(int userId) {
        mUserSwitchedTo = userId;
        return true;
    }

    public boolean getSwitchUserCalled() {
        return mUserSwitchedTo != -1;
    }

    public int getUserSwitchedTo() {
        return mUserSwitchedTo;
    }

    public static void setCurrentUser(int userId) {
        sCurrentUserId = userId;
    }

    public static ShadowActivityManager getShadow() {
        return (ShadowActivityManager) Shadow.extract(
                RuntimeEnvironment.application.getSystemService(ActivityManager.class));
    }
}
