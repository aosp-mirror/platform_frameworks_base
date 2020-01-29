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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for all user info.
 */
public class UserInfoHelper {

    /**
     * Listener for current user changes.
     */
    public interface UserChangedListener {
        /**
         * Called when the current user changes.
         */
        void onUserChanged(@UserIdInt int oldUserId, @UserIdInt int newUserId);
    }

    private final Context mContext;
    private final CopyOnWriteArrayList<UserChangedListener> mListeners;

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
        intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                switch (action) {
                    case Intent.ACTION_USER_SWITCHED:
                        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                                UserHandle.USER_NULL);
                        if (userId != UserHandle.USER_NULL) {
                            onUserChanged(userId);
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
    public void addListener(UserChangedListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for user changed events.
     */
    public void removeListener(UserChangedListener listener) {
        mListeners.remove(listener);
    }

    private void onUserChanged(@UserIdInt int newUserId) {
        if (newUserId == mCurrentUserId) {
            return;
        }

        int oldUserId = mCurrentUserId;
        mCurrentUserId = newUserId;

        for (UserChangedListener listener : mListeners) {
            listener.onUserChanged(oldUserId, newUserId);
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
     * Returns the user id of the current user.
     */
    @UserIdInt
    public int getCurrentUserId() {
        return mCurrentUserId;
    }

    /**
     * Returns true if the given user id is either the current user or a profile of the current
     * user.
     */
    public boolean isCurrentUserOrProfile(@UserIdInt int userId) {
        int currentUserId = mCurrentUserId;
        return userId == currentUserId || ArrayUtils.contains(
                getProfileUserIdsForParentUser(currentUserId), userId);
    }

    /**
     * Returns the parent user id of the given user id, or the user id itself if the user id either
     * is a parent or has no profiles.
     */
    @UserIdInt
    public int getParentUserId(@UserIdInt int userId) {
        synchronized (this) {
            if (userId == mCachedParentUserId || ArrayUtils.contains(mCachedProfileUserIds,
                    userId)) {
                return mCachedParentUserId;
            }

            Preconditions.checkState(mUserManager != null);
        }

        int parentUserId;

        long identity = Binder.clearCallingIdentity();
        try {
            UserInfo userInfo = mUserManager.getProfileParent(userId);
            parentUserId = userInfo != null ? userInfo.id : userId;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        // force profiles into cache
        getProfileUserIdsForParentUser(parentUserId);
        return parentUserId;
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
        int currentUserId = mCurrentUserId;
        pw.println("Current User: " + currentUserId + " " + Arrays.toString(
                getProfileUserIdsForParentUser(currentUserId)));
    }
}
