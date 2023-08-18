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

import static android.os.Build.VERSION_CODES.O;

import android.app.ActivityManager;
import android.app.IActivityManager;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

@Implements(ActivityManager.class)
public class ShadowActivityManager {
    private static int sCurrentUserId = 0;
    private static int sUserSwitchedTo = -1;

    @Resetter
    public static void reset() {
        sCurrentUserId = 0;
        sUserSwitchedTo = 0;
    }

    @Implementation
    protected static int getCurrentUser() {
        return sCurrentUserId;
    }

    @Implementation
    protected boolean switchUser(int userId) {
        sUserSwitchedTo = userId;
        return true;
    }

    @Implementation(minSdk = O)
    protected static IActivityManager getService() {
        return ReflectionHelpers.createNullProxy(IActivityManager.class);
    }

    public boolean getSwitchUserCalled() {
        return sUserSwitchedTo != -1;
    }

    public int getUserSwitchedTo() {
        return sUserSwitchedTo;
    }

    public static void setCurrentUser(int userId) {
        sCurrentUserId = userId;
    }

    public static ShadowActivityManager getShadow() {
        return (ShadowActivityManager) Shadow.extract(
                RuntimeEnvironment.application.getSystemService(ActivityManager.class));
    }
}
