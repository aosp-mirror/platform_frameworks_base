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

package com.android.server.location.injector;

import static android.os.UserManager.DISALLOW_SHARE_LOCATION;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.util.Arrays;

/**
 * Provides accessors and listeners for all user info.
 */
public class SystemUserInfoHelper extends UserInfoHelper {

    private final Context mContext;

    @GuardedBy("this")
    @Nullable private ActivityManagerInternal mActivityManagerInternal;
    @GuardedBy("this")
    @Nullable private IActivityManager mActivityManager;
    @GuardedBy("this")
    @Nullable private UserManager mUserManager;

    public SystemUserInfoHelper(Context context) {
        mContext = context;
    }

    @Nullable
    protected final ActivityManagerInternal getActivityManagerInternal() {
        synchronized (this) {
            if (mActivityManagerInternal == null) {
                mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            }
        }

        return mActivityManagerInternal;
    }

    @Nullable
    protected final IActivityManager getActivityManager() {
        synchronized (this) {
            if (mActivityManager == null) {
                mActivityManager = ActivityManager.getService();
            }
        }

        return mActivityManager;
    }

    @Nullable
    protected final UserManager getUserManager() {
        synchronized (this) {
            if (mUserManager == null) {
                mUserManager = mContext.getSystemService(UserManager.class);
            }
        }

        return mUserManager;
    }

    @Override
    public int[] getRunningUserIds() {
        IActivityManager activityManager = getActivityManager();
        if (activityManager != null) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return activityManager.getRunningUserIds();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } else {
            return new int[]{};
        }
    }

    @Override
    public boolean isCurrentUserId(@UserIdInt int userId) {
        ActivityManagerInternal activityManagerInternal = getActivityManagerInternal();
        if (activityManagerInternal != null) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return activityManagerInternal.isCurrentProfile(userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } else {
            return false;
        }
    }

    @Override
    public @UserIdInt int getCurrentUserId() {
        ActivityManagerInternal activityManagerInternal = getActivityManagerInternal();
        if (activityManagerInternal != null) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return activityManagerInternal.getCurrentUserId();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } else {
            return UserHandle.USER_NULL;
        }
    }

    @Override
    protected int[] getProfileIds(@UserIdInt int userId) {
        UserManager userManager = getUserManager();

        // if you're hitting this precondition then you are invoking this before the system is ready
        Preconditions.checkState(userManager != null);

        final long identity = Binder.clearCallingIdentity();
        try {
            return userManager.getEnabledProfileIds(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Dump info for debugging.
     */
    @Override
    public void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
        int[] runningUserIds = getRunningUserIds();
        if (runningUserIds.length > 1) {
            pw.println("running users: u" + Arrays.toString(runningUserIds));
        }

        ActivityManagerInternal activityManagerInternal = getActivityManagerInternal();
        if (activityManagerInternal == null) {
            return;
        }

        int[] currentProfileIds = activityManagerInternal.getCurrentProfileIds();
        pw.println("current users: u" + Arrays.toString(currentProfileIds));

        UserManager userManager = getUserManager();
        if (userManager != null) {
            for (int userId : currentProfileIds) {
                if (userManager.hasUserRestrictionForUser(DISALLOW_SHARE_LOCATION,
                        UserHandle.of(userId))) {
                    pw.increaseIndent();
                    pw.println("u" + userId + " restricted");
                    pw.decreaseIndent();
                }
            }
        }
    }
}
