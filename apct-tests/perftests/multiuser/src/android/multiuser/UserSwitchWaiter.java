/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.multiuser;

import android.app.ActivityManager;
import android.app.UserSwitchObserver;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.FunctionalUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UserSwitchWaiter {

    private final String mTag;
    private final int mTimeoutInSecond;

    public UserSwitchWaiter(String tag, int timeoutInSecond) {
        mTag = tag;
        mTimeoutInSecond = timeoutInSecond;
    }

    public void runThenWaitUntilSwitchCompleted(int userId,
            FunctionalUtils.ThrowingRunnable runnable, Runnable onFail) throws RemoteException {
        final CountDownLatch latch = new CountDownLatch(1);
        ActivityManager.getService().registerUserSwitchObserver(
                new UserSwitchObserver() {
                    @Override
                    public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        if (userId == newUserId) {
                            latch.countDown();
                        }
                    }
                }, mTag);
        runnable.run();
        waitForLatch(latch, onFail);
    }

    public void runThenWaitUntilBootCompleted(int userId,
            FunctionalUtils.ThrowingRunnable runnable, Runnable onFail) throws RemoteException {
        final CountDownLatch latch = new CountDownLatch(1);
        ActivityManager.getService().registerUserSwitchObserver(
                new UserSwitchObserver() {
                    @Override
                    public void onLockedBootComplete(int newUserId) {
                        if (userId == newUserId) {
                            latch.countDown();
                        }
                    }
                }, mTag);
        runnable.run();
        waitForLatch(latch, onFail);
    }

    private void waitForLatch(CountDownLatch latch, Runnable onFail) {
        boolean success = false;
        try {
            success = latch.await(mTimeoutInSecond, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(mTag, "Thread interrupted unexpectedly.", e);
        }
        if (!success && onFail != null) {
            onFail.run();
        }
    }
}
