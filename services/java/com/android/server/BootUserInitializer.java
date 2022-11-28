/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import com.android.server.am.ActivityManagerService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.Slogf;
import com.android.server.utils.TimingsTraceAndSlog;

/**
 * Class responsible for booting the device in the proper user on headless system user mode.
 *
 */
// TODO(b/204091126): STOPSHIP - provide proper APIs
final class BootUserInitializer {

    private static final String TAG = BootUserInitializer.class.getSimpleName();

     // TODO(b/204091126): STOPSHIP - set to false or dynamic value
    private static final boolean DEBUG = true;

    private final ActivityManagerService mAms;
    private final ContentResolver mContentResolver;

    BootUserInitializer(ActivityManagerService am, ContentResolver contentResolver) {
        mAms = am;
        mContentResolver = contentResolver;
    }

    public void init(TimingsTraceAndSlog t) {
        Slogf.i(TAG, "init())");

        // TODO(b/204091126): in the long term, we need to decide who's reponsible for that,
        // this class or the setup wizard app
        provisionHeadlessSystemUser();

        unlockSystemUser(t);

        try {
            t.traceBegin("getBootUser");
            int bootUser = LocalServices.getService(UserManagerInternal.class).getBootUser();
            t.traceEnd();
            t.traceBegin("switchToBootUser-" + bootUser);
            switchToBootUser(bootUser);
            t.traceEnd();
        } catch (UserManager.CheckedUserOperationException e) {
            Slogf.wtf(TAG, "Failed to created boot user", e);
        }
    }

    /* TODO(b/261791491): STOPSHIP - SUW should be responsible for this. */
    private void provisionHeadlessSystemUser() {
        if (isDeviceProvisioned()) {
            Slogf.d(TAG, "provisionHeadlessSystemUser(): already provisioned");
            return;
        }

        Slogf.i(TAG, "Marking USER_SETUP_COMPLETE for system user");
        Settings.Secure.putInt(mContentResolver, Settings.Secure.USER_SETUP_COMPLETE, 1);
        Slogf.i(TAG, "Marking DEVICE_PROVISIONED for system user");
        Settings.Global.putInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED, 1);
    }

    private boolean isDeviceProvisioned() {
        try {
            return Settings.Global.getInt(mContentResolver,
                    Settings.Global.DEVICE_PROVISIONED) == 1;
        } catch (Exception e) {
            Slogf.wtf(TAG, "DEVICE_PROVISIONED setting not found.", e);
            return false;
        }
    }

    // NOTE: Mostly copied from Automotive's InitialUserSetter
    private void unlockSystemUser(TimingsTraceAndSlog t) {
        Slogf.i(TAG, "Unlocking system user");
        t.traceBegin("unlock-system-user");
        try {
            // This is for force changing state into RUNNING_LOCKED. Otherwise unlock does not
            // update the state and USER_SYSTEM unlock happens twice.
            t.traceBegin("am.startUser");
            boolean started = mAms.startUserInBackgroundWithListener(UserHandle.USER_SYSTEM,
                            /* listener= */ null);
            t.traceEnd();
            if (!started) {
                Slogf.w(TAG, "could not restart system user in background; trying unlock instead");
                t.traceBegin("am.unlockUser");
                boolean unlocked = mAms.unlockUser(UserHandle.USER_SYSTEM, /* token= */ null,
                        /* secret= */ null, /* listener= */ null);
                t.traceEnd();
                if (!unlocked) {
                    Slogf.w(TAG, "could not unlock system user either");
                    return;
                }
            }
        } finally {
            t.traceEnd();
        }
    }

    private void switchToBootUser(@UserIdInt int bootUserId) {
        Slogf.i(TAG, "Switching to boot user %d", bootUserId);
        boolean started = mAms.startUserInForegroundWithListener(bootUserId,
                /* unlockListener= */ null);
        if (!started) {
            Slogf.wtf(TAG, "Failed to start user %d in foreground", bootUserId);
        }
    }
}
