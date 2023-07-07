/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.IUserSwitchObserver;
import android.app.UserSwitchObserver;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.FunctionalUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class UserSwitchWaiter implements Closeable {

    private final String mTag;
    private final int mTimeoutInSecond;
    private final IActivityManager mActivityManager;
    private final IUserSwitchObserver mUserSwitchObserver = new UserSwitchObserver() {
        @Override
        public void onUserSwitchComplete(int newUserId) {
            getSemaphoreSwitchComplete(newUserId).release();
        }

        @Override
        public void onLockedBootComplete(int newUserId) {
            getSemaphoreBootComplete(newUserId).release();
        }
    };

    private final Map<Integer, Semaphore> mSemaphoresMapSwitchComplete = new ConcurrentHashMap<>();
    private Semaphore getSemaphoreSwitchComplete(final int userId) {
        return mSemaphoresMapSwitchComplete.computeIfAbsent(userId,
                (Integer absentKey) -> new Semaphore(0));
    }

    private final Map<Integer, Semaphore> mSemaphoresMapBootComplete = new ConcurrentHashMap<>();
    private Semaphore getSemaphoreBootComplete(final int userId) {
        return mSemaphoresMapBootComplete.computeIfAbsent(userId,
                (Integer absentKey) -> new Semaphore(0));
    }

    public UserSwitchWaiter(String tag, int timeoutInSecond) throws RemoteException {
        mTag = tag;
        mTimeoutInSecond = timeoutInSecond;
        mActivityManager = ActivityManager.getService();

        mActivityManager.registerUserSwitchObserver(mUserSwitchObserver, mTag);
    }

    @Override
    public void close() throws IOException {
        try {
            mActivityManager.unregisterUserSwitchObserver(mUserSwitchObserver);
        } catch (RemoteException e) {
            Log.e(mTag, "Failed to unregister user switch observer", e);
        }
    }

    public void runThenWaitUntilSwitchCompleted(int userId,
            FunctionalUtils.ThrowingRunnable runnable, Runnable onFail) {
        final Semaphore semaphore = getSemaphoreSwitchComplete(userId);
        semaphore.drainPermits();
        runnable.run();
        waitForSemaphore(semaphore, onFail);
    }

    public void runThenWaitUntilBootCompleted(int userId,
            FunctionalUtils.ThrowingRunnable runnable, Runnable onFail) {
        final Semaphore semaphore = getSemaphoreBootComplete(userId);
        semaphore.drainPermits();
        runnable.run();
        waitForSemaphore(semaphore, onFail);
    }

    private void waitForSemaphore(Semaphore semaphore, Runnable onFail) {
        boolean success = false;
        try {
            success = semaphore.tryAcquire(mTimeoutInSecond, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(mTag, "Thread interrupted unexpectedly.", e);
        }
        if (!success && onFail != null) {
            onFail.run();
        }
    }
}
