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

package com.android.server.utils;

import android.annotation.UserIdInt;
import android.os.Handler;
import android.os.IBinder;
import android.os.TokenWatcher;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;

/**
 * Multi-user aware {@link TokenWatcher}.
 *
 * {@link UserTokenWatcher} is thread-safe.
 */
public final class UserTokenWatcher {

    private final Callback mCallback;
    private final Handler mHandler;
    private final String mTag;

    @GuardedBy("mWatchers")
    private final SparseArray<TokenWatcher> mWatchers = new SparseArray<>(1);

    public UserTokenWatcher(Callback callback, Handler handler, String tag) {
        mCallback = callback;
        mHandler = handler;
        mTag = tag;
    }

    /**
     * Record that this token has been acquired for the given user.  When acquire is called, and
     * the user's count goes from 0 to 1, the acquired callback is called on the given
     * handler.
     *
     * Note that the same {@code token} can only be acquired once per user. If this
     * {@code token} has already been acquired for the given user, no action is taken. The first
     * subsequent call to {@link #release} will release this {@code token}
     * immediately.
     *
     * @param token  An IBinder object.
     * @param tag    A string used by the {@link #dump} method for debugging,
     *               to see who has references.
     * @param userId A user id
     */
    public void acquire(IBinder token, String tag, @UserIdInt int userId) {
        synchronized (mWatchers) {
            TokenWatcher watcher = mWatchers.get(userId);
            if (watcher == null) {
                watcher = new InnerTokenWatcher(userId, mHandler, mTag);
                mWatchers.put(userId, watcher);
            }
            watcher.acquire(token, tag);
        }
    }

    /**
     * Record that this token has been released for the given user.  When release is called, and
     * the user's count goes from 1 to 0, the released callback is called on the given
     * handler.
     *
     * @param token  An IBinder object.
     * @param userId A user id
     */
    public void release(IBinder token, @UserIdInt int userId) {
        synchronized (mWatchers) {
            TokenWatcher watcher = mWatchers.get(userId);
            if (watcher != null) {
                watcher.release(token);
            }
        }
    }

    /**
     * Returns whether the given user has any registered tokens that have not been cleaned up.
     *
     * @return true, if the given user has registered tokens.
     */
    public boolean isAcquired(@UserIdInt int userId) {
        synchronized (mWatchers) {
            TokenWatcher watcher = mWatchers.get(userId);
            return watcher != null && watcher.isAcquired();
        }
    }

    /**
     * Dumps the current state.
     */
    public void dump(PrintWriter pw) {
        synchronized (mWatchers) {
            for (int i = 0; i < mWatchers.size(); i++) {
                int userId = mWatchers.keyAt(i);
                TokenWatcher watcher = mWatchers.valueAt(i);
                if (watcher.isAcquired()) {
                    pw.print("User ");
                    pw.print(userId);
                    pw.println(":");
                    watcher.dump(new IndentingPrintWriter(pw, " "));
                }
            }
        }
    }

    /**
     * Callback for {@link UserTokenWatcher}.
     */
    public interface Callback {

        /**
         * Reports that the first token has been acquired for the given user.
         */
        void acquired(@UserIdInt int userId);

        /**
         * Reports that the last token has been release for the given user.
         */
        void released(@UserIdInt int userId);
    }

    private final class InnerTokenWatcher extends TokenWatcher {
        private final int mUserId;

        private InnerTokenWatcher(int userId, Handler handler, String tag) {
            super(handler, tag);
            this.mUserId = userId;
        }

        @Override
        public void acquired() {
            // We MUST NOT hold any locks while invoking the callbacks.
            mCallback.acquired(mUserId);
        }

        @Override
        public void released() {
            // We MUST NOT hold any locks while invoking the callbacks.
            mCallback.released(mUserId);

            synchronized (mWatchers) {
                final TokenWatcher watcher = mWatchers.get(mUserId);
                if (watcher != null && !watcher.isAcquired()) {
                    mWatchers.remove(mUserId);
                }
            }
        }
    }
}
