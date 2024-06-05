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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;

import java.util.concurrent.locks.ReentrantLock;

/**
 * This is a unique class that is used as the PackageManager lock.  It can be targeted for lock
 * injection, similar to {@link ActivityManagerGlobalLock}.
 */
public class PackageManagerTracedLock implements AutoCloseable {
    private static final String TAG = "PackageManagerTracedLock";
    private static final boolean DEBUG = false;
    private @NonNull final RawLock mLock;

    public PackageManagerTracedLock(@Nullable String lockName) {
        mLock = new RawLock(lockName);
    }

    public PackageManagerTracedLock() {
        this(null);
    }

    /**
     * Use this method to acquire the lock. Use it with try-with-resources to make sure the lock is
     * released afterwards. Example usage:
     * <pre>
     * PackageManagerTracedLock myInstallLock = new PackageManagerTracedLock();
     * try (PackageManagerTracedLock installLock = myInstallLock.acquireLock()) {
     *     // do stuff under lock
     * }
     * </pre>
     */
    public PackageManagerTracedLock acquireLock() {
        mLock.lock();
        return this;
    }

    /**
     * Obtain the raw lock for fine control of lock state. Example usage:
     * <pre>
     * PackageManagerTracedLock myInstallLock = new PackageManagerTracedLock();
     * PackageManagerTracedLock.RawLock rawLock = myInstallLock.getRawLock();
     * rawLock.lock();
     * // do stuff under lock
     * rawLock.unlock();
     * </pre>
     */
    public RawLock getRawLock() {
        return mLock;
    }

    /**
     * Release the lock if it's held by the current thread.
     * If you use {@link #acquireLock()} using try-with-resources, there's no need to call this
     * method explicitly.
     */
    @Override
    public void close() {
        mLock.unlock();
    }

    public static class RawLock extends ReentrantLock {
        @Nullable private final String mLockName;
        RawLock(@Nullable String lockName) {
            mLockName = lockName;
        }
        @Override
        public void lock() {
            super.lock();
            if (DEBUG && mLockName != null) {
                Slog.i(TAG, "locked " + mLockName);
            }
        }

        @Override
        public void unlock() {
            super.unlock();
            if (DEBUG && mLockName != null) {
                Slog.i(TAG, "unlocked " + mLockName);
            }
        }
    }
}
