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

package com.android.server.location;

import static android.os.UserManager.DISALLOW_SHARE_LOCATION;

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;
import static com.android.server.location.UserInfoHelper.UserListener.CURRENT_USER_CHANGED;
import static com.android.server.location.UserInfoHelper.UserListener.USER_STARTED;
import static com.android.server.location.UserInfoHelper.UserListener.USER_STOPPED;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for all user info.
 */
public abstract class UserInfoHelper {

    /**
     * Listener for current user changes.
     */
    public interface UserListener {

        int CURRENT_USER_CHANGED = 1;
        int USER_STARTED = 2;
        int USER_STOPPED = 3;

        @IntDef({CURRENT_USER_CHANGED, USER_STARTED, USER_STOPPED})
        @Retention(RetentionPolicy.SOURCE)
        @interface UserChange {}

        /**
         * Called when something has changed about the given user.
         */
        void onUserChanged(@UserIdInt int userId, @UserChange int change);
    }

    private final Context mContext;
    private final CopyOnWriteArrayList<UserListener> mListeners;

    @GuardedBy("this")
    @Nullable private ActivityManagerInternal mActivityManagerInternal;
    @GuardedBy("this")
    @Nullable private UserManager mUserManager;

    public UserInfoHelper(Context context) {
        mContext = context;
        mListeners = new CopyOnWriteArrayList<>();
    }

    /** Called when system is ready. */
    @CallSuper
    public synchronized void onSystemReady() {
        if (mActivityManagerInternal != null) {
            return;
        }

        mActivityManagerInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mUserManager = mContext.getSystemService(UserManager.class);
    }

    /**
     * Adds a listener for user changed events. Callbacks occur on an unspecified thread.
     */
    public final void addListener(UserListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for user changed events.
     */
    public final void removeListener(UserListener listener) {
        mListeners.remove(listener);
    }

    protected void dispatchOnUserStarted(@UserIdInt int userId) {
        if (D) {
            Log.d(TAG, "u" + userId + " started");
        }

        for (UserListener listener : mListeners) {
            listener.onUserChanged(userId, USER_STARTED);
        }
    }

    protected void dispatchOnUserStopped(@UserIdInt int userId) {
        if (D) {
            Log.d(TAG, "u" + userId + " stopped");
        }

        for (UserListener listener : mListeners) {
            listener.onUserChanged(userId, USER_STOPPED);
        }
    }

    protected void dispatchOnCurrentUserChanged(@UserIdInt int fromUserId,
            @UserIdInt int toUserId) {
        int[] fromUserIds = getProfileIds(fromUserId);
        int[] toUserIds = getProfileIds(toUserId);
        if (D) {
            Log.d(TAG, "current user changed from u" + Arrays.toString(fromUserIds) + " to u"
                    + Arrays.toString(toUserIds));
        }

        for (UserListener listener : mListeners) {
            for (int userId : fromUserIds) {
                listener.onUserChanged(userId, CURRENT_USER_CHANGED);
            }
        }

        for (UserListener listener : mListeners) {
            for (int userId : toUserIds) {
                listener.onUserChanged(userId, CURRENT_USER_CHANGED);
            }
        }
    }

    /**
     * Returns an array of current user ids. This will always include the current user, and will
     * also include any profiles of the current user. The caller must never mutate the returned
     * array.
     */
    public int[] getCurrentUserIds() {
        synchronized (this) {
            if (mActivityManagerInternal == null) {
                return new int[] {};
            }
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return mActivityManagerInternal.getCurrentProfileIds();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns true if the given user id is either the current user or a profile of the current
     * user.
     */
    public boolean isCurrentUserId(@UserIdInt int userId) {
        synchronized (this) {
            if (mActivityManagerInternal == null) {
                return false;
            }
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return mActivityManagerInternal.isCurrentProfile(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private int[] getProfileIds(@UserIdInt int userId) {
        synchronized (this) {
            Preconditions.checkState(mUserManager != null);
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return mUserManager.getEnabledProfileIds(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Dump info for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        int[] currentUserProfiles = getCurrentUserIds();
        pw.println("current users: " + Arrays.toString(currentUserProfiles));
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager != null) {
            for (int userId : currentUserProfiles) {
                if (userManager.hasUserRestrictionForUser(DISALLOW_SHARE_LOCATION,
                        UserHandle.of(userId))) {
                    pw.println("  u" + userId + " restricted");
                }
            }
        }
    }
}
