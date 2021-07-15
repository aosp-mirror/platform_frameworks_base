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

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;
import static com.android.server.location.eventlog.LocationEventLog.EVENT_LOG;
import static com.android.server.location.injector.UserInfoHelper.UserListener.CURRENT_USER_CHANGED;
import static com.android.server.location.injector.UserInfoHelper.UserListener.USER_STARTED;
import static com.android.server.location.injector.UserInfoHelper.UserListener.USER_STOPPED;

import android.annotation.IntDef;
import android.annotation.UserIdInt;
import android.util.IndentingPrintWriter;
import android.util.Log;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
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

    private final CopyOnWriteArrayList<UserListener> mListeners;

    public UserInfoHelper() {
        mListeners = new CopyOnWriteArrayList<>();
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

    protected final void dispatchOnUserStarted(@UserIdInt int userId) {
        if (D) {
            Log.d(TAG, "u" + userId + " started");
        }

        for (UserListener listener : mListeners) {
            listener.onUserChanged(userId, USER_STARTED);
        }
    }

    protected final void dispatchOnUserStopped(@UserIdInt int userId) {
        if (D) {
            Log.d(TAG, "u" + userId + " stopped");
        }

        for (UserListener listener : mListeners) {
            listener.onUserChanged(userId, USER_STOPPED);
        }
    }

    protected final void dispatchOnCurrentUserChanged(@UserIdInt int fromUserId,
            @UserIdInt int toUserId) {
        int[] fromUserIds = getProfileIds(fromUserId);
        int[] toUserIds = getProfileIds(toUserId);
        if (D) {
            Log.d(TAG, "current user changed from u" + Arrays.toString(fromUserIds) + " to u"
                    + Arrays.toString(toUserIds));
        }
        EVENT_LOG.logUserSwitched(fromUserId, toUserId);

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
     * Returns an array of running user ids. This will include all running users, and will also
     * include any profiles of the running users. The caller must never mutate the returned
     * array.
     */
    public abstract int[] getRunningUserIds();

    /**
     * Returns true if the given user id is either the current user or a profile of the current
     * user.
     */
    public abstract boolean isCurrentUserId(@UserIdInt int userId);

    /**
     * Returns the current user id. Where possible, prefer to use {@link #isCurrentUserId(int)}
     * instead, as that method has more flexibility.
     */
    public abstract @UserIdInt int getCurrentUserId();

    protected abstract int[] getProfileIds(@UserIdInt int userId);

    /**
     * Dump info for debugging.
     */
    public abstract void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args);
}
