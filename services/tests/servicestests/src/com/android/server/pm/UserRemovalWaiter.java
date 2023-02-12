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

import static org.junit.Assert.fail;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UserRemovalWaiter extends BroadcastReceiver implements Closeable {

    private final Context mContext;
    private final UserManager mUserManager;
    private final String mTag;
    private final long mTimeoutMillis;
    private final Map<Integer, CountDownLatch> mMap = new ConcurrentHashMap<>();

    private CountDownLatch getLatch(final int userId) {
        return mMap.computeIfAbsent(userId, absentKey -> new CountDownLatch(1));
    }

    public UserRemovalWaiter(Context context, String tag, int timeoutInSeconds) {
        mContext = context;
        mUserManager = UserManager.get(mContext);
        mTag = tag;
        mTimeoutMillis = timeoutInSeconds * 1000L;

        mContext.registerReceiver(this, new IntentFilter(Intent.ACTION_USER_REMOVED));
    }

    @Override
    public void close() throws IOException {
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            Log.i(mTag, "ACTION_USER_REMOVED received for user " + userId);
            getLatch(userId).countDown();
        }
    }

    /**
     * Waits for the removal of the given user, or fails if it times out.
     */
    public void waitFor(int userId) {
        Log.i(mTag, "Waiting for user " + userId + " to be removed");
        CountDownLatch latch = getLatch(userId);
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < mTimeoutMillis) {
            if (hasUserGone(userId) || waitLatchForOneSecond(latch)) {
                Log.i(mTag, "User " + userId + " is removed in "
                        + (System.currentTimeMillis() - startTime) + " ms");
                return;
            }
        }
        fail("Timeout waiting for user removal. userId = " + userId);
    }

    private boolean hasUserGone(int userId) {
        return mUserManager.getAliveUsers().stream().noneMatch(x -> x.id == userId);
    }

    private boolean waitLatchForOneSecond(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(mTag, "Thread interrupted unexpectedly.", e);
            return false;
        }
    }
}
