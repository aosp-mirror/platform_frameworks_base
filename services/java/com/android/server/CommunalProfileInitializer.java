/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server;

import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.server.am.ActivityManagerService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.Slogf;
import com.android.server.utils.TimingsTraceAndSlog;

/**
 * Responsible for creating the communal profile at first boot, if required.
 */
public class CommunalProfileInitializer {

    private static final String TAG = CommunalProfileInitializer.class.getSimpleName();

    private UserManagerInternal mUmi;
    private final ActivityManagerService mAms;

    public CommunalProfileInitializer(ActivityManagerService ams) {
        mUmi = LocalServices.getService(UserManagerInternal.class);
        mAms = ams;
    }

    /**
     * Initialize this object and create the Communal Profile if needed.
     */
    public void init(TimingsTraceAndSlog t) {
        Slogf.i(TAG, "init())");

        t.traceBegin("createCommunalProfileIfNeeded");
        createCommunalProfileIfNeeded();
        t.traceEnd();
    }

    private void createCommunalProfileIfNeeded() {
        final int communalProfile = mUmi.getCommunalProfileId();
        if (communalProfile != UserHandle.USER_NULL) {
            Slogf.d(TAG, "Found existing Communal Profile, userId=%d", communalProfile);
            return;
        }

        Slogf.d(TAG, "Creating a new Communal Profile");
        try {
            // TODO: b/293860614 - Create Communal Profile string name
            final UserInfo newProfile = mUmi.createUserEvenWhenDisallowed(
                    /* name= */ null,
                    UserManager.USER_TYPE_PROFILE_COMMUNAL,
                    /* flags= */ 0, /* disallowedPackages= */ null, /* token= */ null);
            Slogf.i(TAG, "Successfully created Communal Profile, userId=%d", newProfile.id);
        } catch (UserManager.CheckedUserOperationException e) {
            Slogf.wtf(TAG, "Communal Profile creation failed", e);
        }
    }

    static void removeCommunalProfileIfPresent() {
        final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        final int communalProfile = umi.getCommunalProfileId();
        if (communalProfile == UserHandle.USER_NULL) {
            return;
        }
        Slogf.d(TAG, "Removing existing Communal Profile, userId=%d", communalProfile);
        final boolean removeSucceeded = umi.removeUserEvenWhenDisallowed(communalProfile);
        if (!removeSucceeded) {
            Slogf.e(TAG, "Failed to remove Communal Profile, userId=%d", communalProfile);
        }
    }

}
