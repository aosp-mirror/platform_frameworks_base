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

import android.annotation.Nullable;
import android.util.Slog;

import java.util.concurrent.locks.ReentrantLock;

/**
 * This is a unique class that is used as the PackageManager lock.  It can be targeted for lock
 * injection, similar to {@link ActivityManagerGlobalLock}.
 */
public class PackageManagerTracedLock extends ReentrantLock {
    private static final String TAG = "PackageManagerTracedLock";
    private static final boolean DEBUG = false;
    @Nullable private final String mLockName;

    public PackageManagerTracedLock(@Nullable String lockName) {
        mLockName = lockName;
    }

    public PackageManagerTracedLock() {
        this(null);
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
