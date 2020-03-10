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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for all user info.
 */
public class UserInfoHelper {

    /**
     * Listener for current user changes.
     */
    public interface UserListener {

        int USER_SWITCHED = 1;
        int USER_STARTED = 2;
        int USER_STOPPED = 3;

        @IntDef({USER_SWITCHED, USER_STARTED, USER_STOPPED})
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
    @Nullable private UserManager mUserManager;

    @UserIdInt private volatile int mCurrentUserId;

    @GuardedBy("this")
    @UserIdInt private int mCachedParentUserId;
    @GuardedBy("this")
    private int[] mCachedProfileUserIds;

    public UserInfoHelper(Context context) {
        mContext = context;
        mListeners = new CopyOnWriteArrayList<>();

        mCurrentUserId = UserHandle.USER_NULL;
        mCachedParentUserId = UserHandle.USER_NULL;
        mCachedProfileUserIds = new int[]{UserHandle.USER_NULL};
    }

    /** Called when system is ready. */
    public synchronized void onSystemReady() {
        if (mUserManager != null) {
            return;
        }

        mUserManager = mContext.getSystemService(UserManager.class);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_STARTED);
        intentFilter.addAction(Intent.ACTION_USER_STOPPED);
        intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                int userId;
                switch (action) {
                    case Intent.ACTION_USER_SWITCHED:
                        userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                        if (userId != UserHandle.USER_NULL) {
                            onCurrentUserChanged(userId);
                        }
                        break;
                    case Intent.ACTION_USER_STARTED:
                        userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                        if (userId != UserHandle.USER_NULL) {
                            onUserStarted(userId);
                        }
                        break;
                    case Intent.ACTION_USER_STOPPED:
                        userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                        if (userId != UserHandle.USER_NULL) {
                            onUserStopped(userId);
                        }
                        break;
                    case Intent.ACTION_MANAGED_PROFILE_ADDED:
                    case Intent.ACTION_MANAGED_PROFILE_REMOVED:
                        onUserProfilesChanged();
                        break;
                }
            }
        }, UserHandle.ALL, intentFilter, null, FgThread.getHandler());

        mCurrentUserId = ActivityManager.getCurrentUser();
    }

    /**
     * Adds a listener for user changed events. Callbacks occur on an unspecified thread.
     */
    public void addListener(UserListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for user changed events.
     */
    public void removeListener(UserListener listener) {
        mListeners.remove(listener);
    }

    private void onCurrentUserChanged(@UserIdInt int newUserId) {
        if (newUserId == mCurrentUserId) {
            return;
        }

        if (D) {
            Log.d(TAG, "current user switched from u" + mCurrentUserId + " to u" + newUserId);
        }

        int oldUserId = mCurrentUserId;
        mCurrentUserId = newUserId;

        onUserChanged(oldUserId, UserListener.USER_SWITCHED);
        onUserChanged(newUserId, UserListener.USER_SWITCHED);
    }

    private void onUserStarted(@UserIdInt int userId) {
        if (D) {
            Log.d(TAG, "u" + userId + " started");
        }

        onUserChanged(userId, UserListener.USER_STARTED);
    }

    private void onUserStopped(@UserIdInt int userId) {
        if (D) {
            Log.d(TAG, "u" + userId + " stopped");
        }

        onUserChanged(userId, UserListener.USER_STOPPED);
    }

    private void onUserChanged(@UserIdInt int userId, @UserListener.UserChange int change) {
        for (UserListener listener : mListeners) {
            listener.onUserChanged(userId, change);
        }
    }

    private synchronized void onUserProfilesChanged() {
        // this intent is only sent to the current user
        if (mCachedParentUserId == mCurrentUserId) {
            mCachedParentUserId = UserHandle.USER_NULL;
            mCachedProfileUserIds = new int[]{UserHandle.USER_NULL};
        }
    }

    /**
     * Returns an array of current user ids. This will always include the current user, and will
     * also include any profiles of the current user.
     */
    public int[] getCurrentUserIds() {
        return getProfileUserIdsForParentUser(mCurrentUserId);
    }

    /**
     * Returns true if the given user id is either the current user or a profile of the current
     * user.
     */
    public boolean isCurrentUserId(@UserIdInt int userId) {
        int currentUserId = mCurrentUserId;
        return userId == currentUserId || ArrayUtils.contains(
                getProfileUserIdsForParentUser(currentUserId), userId);
    }

    @GuardedBy("this")
    private synchronized int[] getProfileUserIdsForParentUser(@UserIdInt int parentUserId) {
        if (parentUserId != mCachedParentUserId) {
            long identity = Binder.clearCallingIdentity();
            try {
                Preconditions.checkState(mUserManager != null);

                // more expensive check - check that argument really is a parent user id
                if (Build.IS_DEBUGGABLE) {
                    Preconditions.checkArgument(
                            mUserManager.getProfileParent(parentUserId) == null);
                }

                mCachedParentUserId = parentUserId;
                mCachedProfileUserIds = mUserManager.getProfileIdsWithDisabled(parentUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        return mCachedProfileUserIds;
    }

    /**
     * Dump info for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        boolean systemRunning;
        synchronized (this) {
            systemRunning = mUserManager != null;
        }

        if (systemRunning) {
            int[] currentUserIds = getProfileUserIdsForParentUser(mCurrentUserId);
            pw.println("current users: " + Arrays.toString(currentUserIds));
            for (int userId : currentUserIds) {
                if (mUserManager.hasUserRestrictionForUser(DISALLOW_SHARE_LOCATION,
                        UserHandle.of(userId))) {
                    pw.println("  u" + userId + " restricted");
                }
            }
        } else {
            pw.println("current user: " + mCurrentUserId);
        }
    }
}
