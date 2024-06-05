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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import com.android.server.am.ActivityManagerService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.Slogf;
import com.android.server.utils.TimingsTraceAndSlog;

/**
 * Class responsible for booting the device in the proper user on headless system user mode.
 *
 */
final class HsumBootUserInitializer {

    private static final String TAG = HsumBootUserInitializer.class.getSimpleName();

    private final UserManagerInternal mUmi;
    private final ActivityManagerService mAms;
    private final PackageManagerService mPms;
    private final ContentResolver mContentResolver;

    private final ContentObserver mDeviceProvisionedObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    // Set USER_SETUP_COMPLETE for the (headless) system user only when the device
                    // has been set up at least once.
                    if (isDeviceProvisioned()) {
                        Slogf.i(TAG, "Marking USER_SETUP_COMPLETE for system user");
                        Settings.Secure.putInt(mContentResolver,
                                Settings.Secure.USER_SETUP_COMPLETE, 1);
                        mContentResolver.unregisterContentObserver(mDeviceProvisionedObserver);
                    }
                }
            };

    /** Whether this device should always have a non-removable MainUser, including at first boot. */
    private final boolean mShouldAlwaysHaveMainUser;

    /** Static factory method for creating a {@link HsumBootUserInitializer} instance. */
    public static @Nullable HsumBootUserInitializer createInstance(ActivityManagerService am,
            PackageManagerService pms, ContentResolver contentResolver,
            boolean shouldAlwaysHaveMainUser) {

        if (!UserManager.isHeadlessSystemUserMode()) {
            return null;
        }
        return new HsumBootUserInitializer(
                LocalServices.getService(UserManagerInternal.class),
                am, pms, contentResolver,
                shouldAlwaysHaveMainUser);
    }

    private HsumBootUserInitializer(UserManagerInternal umi, ActivityManagerService am,
            PackageManagerService pms, ContentResolver contentResolver,
            boolean shouldAlwaysHaveMainUser) {
        mUmi = umi;
        mAms = am;
        mPms = pms;
        mContentResolver = contentResolver;
        mShouldAlwaysHaveMainUser = shouldAlwaysHaveMainUser;
    }

    /**
     * Initialize this object, and create MainUser if needed.
     *
     * <p>Should be called before PHASE_SYSTEM_SERVICES_READY as services' setups may require
     * MainUser, but probably after PHASE_LOCK_SETTINGS_READY since that may be needed for user
     * creation.
     */
    public void init(TimingsTraceAndSlog t) {
        Slogf.i(TAG, "init())");

        if (mShouldAlwaysHaveMainUser) {
            t.traceBegin("createMainUserIfNeeded");
            createMainUserIfNeeded();
            t.traceEnd();
        }
    }

    private void createMainUserIfNeeded() {
        final int mainUser = mUmi.getMainUserId();
        if (mainUser != UserHandle.USER_NULL) {
            Slogf.d(TAG, "Found existing MainUser, userId=%d", mainUser);
            return;
        }

        Slogf.d(TAG, "Creating a new MainUser");
        try {
            final UserInfo newInitialUser = mUmi.createUserEvenWhenDisallowed(
                    /* name= */ null, // null will appear as "Owner" in on-demand localisation
                    UserManager.USER_TYPE_FULL_SECONDARY,
                    UserInfo.FLAG_ADMIN | UserInfo.FLAG_MAIN,
                    /* disallowedPackages= */ null,
                    /* token= */ null);
            Slogf.i(TAG, "Successfully created MainUser, userId=%d", newInitialUser.id);
        } catch (UserManager.CheckedUserOperationException e) {
            Slogf.wtf(TAG, "Initial bootable MainUser creation failed", e);
        }
    }

    /**
     * Put the device into the correct user state: unlock the system and switch to the boot user.
     *
     * <p>Should only call once PHASE_THIRD_PARTY_APPS_CAN_START is reached to ensure that
     * privileged apps have had the chance to set the boot user, if applicable.
     */
    public void systemRunning(TimingsTraceAndSlog t) {
        observeDeviceProvisioning();
        unlockSystemUser(t);

        try {
            t.traceBegin("getBootUser");
            final int bootUser = mUmi.getBootUser(/* waitUntilSet= */ mPms
                    .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, /* version= */0));
            t.traceEnd();
            t.traceBegin("switchToBootUser-" + bootUser);
            switchToBootUser(bootUser);
            t.traceEnd();
        } catch (UserManager.CheckedUserOperationException e) {
            Slogf.wtf(TAG, "Failed to switch to boot user since there isn't one.");
        }
    }

    private void observeDeviceProvisioning() {
        if (isDeviceProvisioned()) {
            return;
        }

        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false,
                mDeviceProvisionedObserver
        );
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
    // TODO(b/266158156): Refactor how starting/unlocking works for the System.
    private void unlockSystemUser(TimingsTraceAndSlog t) {
        Slogf.i(TAG, "Unlocking system user");
        t.traceBegin("unlock-system-user");
        try {
            // This is for force changing state into RUNNING_LOCKED. Otherwise unlock does not
            // update the state and USER_SYSTEM unlock happens twice.
            t.traceBegin("am.startUser");
            final boolean started = mAms.startUserInBackgroundWithListener(UserHandle.USER_SYSTEM,
                            /* listener= */ null);
            t.traceEnd();
            if (!started) {
                Slogf.w(TAG, "could not restart system user in background; trying unlock instead");
                t.traceBegin("am.unlockUser");
                final boolean unlocked = mAms.unlockUser(UserHandle.USER_SYSTEM, /* token= */ null,
                        /* secret= */ null, /* listener= */ null);
                t.traceEnd();
                if (!unlocked) {
                    Slogf.w(TAG, "could not unlock system user either");
                }
            }
        } finally {
            t.traceEnd();
        }
    }

    private void switchToBootUser(@UserIdInt int bootUserId) {
        Slogf.i(TAG, "Switching to boot user %d", bootUserId);
        final boolean started = mAms.startUserInForegroundWithListener(bootUserId,
                /* unlockListener= */ null);
        if (!started) {
            Slogf.wtf(TAG, "Failed to start user %d in foreground", bootUserId);
        }
    }
}
