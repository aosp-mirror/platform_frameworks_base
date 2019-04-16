/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.server.pm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.IStopUserCallback;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * To run the test:
 * bit FrameworksServicesTests:com.android.server.pm.UserLifecycleStressTest
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class UserLifecycleStressTest {
    private static final String TAG = "UserLifecycleStressTest";
    // TODO: Make this smaller once we have improved it.
    private static final int MAX_TIME_STOP_USER_IN_SECOND = 30;
    private static final int NUM_ITERATIONS_STOP_USER = 10;
    private static final int WAIT_BEFORE_STOP_USER_IN_SECOND = 3;

    private Context mContext;
    private UserManager mUserManager;
    private ActivityManager mActivityManager;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mUserManager = mContext.getSystemService(UserManager.class);
        mActivityManager = mContext.getSystemService(ActivityManager.class);
    }

    /**
     * Create and stop user 10 times in a row. Check stop user can be finished in a reasonable
     * amount of time.
     */
    @Test
    public void stopManagedProfileStressTest()
            throws IOException, RemoteException, InterruptedException {
        for (int i = 0; i < NUM_ITERATIONS_STOP_USER; i++) {
            final UserInfo userInfo = mUserManager.createProfileForUser("TestUser",
                    UserInfo.FLAG_MANAGED_PROFILE, mActivityManager.getCurrentUser());
            assertNotNull(userInfo);
            try {
                assertTrue(
                        "Failed to start the profile",
                        ActivityManager.getService().startUserInBackground(userInfo.id));
                // Seems the broadcast queue is getting more busy if we wait a few seconds before
                // stopping the user.
                TimeUnit.SECONDS.sleep(WAIT_BEFORE_STOP_USER_IN_SECOND);
                stopUser(userInfo.id);
            } finally {
                mUserManager.removeUser(userInfo.id);
            }
        }
    }

    private void stopUser(int userId) throws RemoteException, InterruptedException {
        final long startTime = System.currentTimeMillis();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        ActivityManager.getService().
                stopUser(userId, true,
                        new IStopUserCallback.Stub() {
                            @Override
                            public void userStopped(int userId) throws RemoteException {
                                countDownLatch.countDown();
                            }

                            @Override
                            public void userStopAborted(int userId) throws RemoteException {

                            }
                        });
        boolean stoppedBeforeTimeout =
                countDownLatch.await(MAX_TIME_STOP_USER_IN_SECOND, TimeUnit.SECONDS);
        assertTrue(
                "Take more than " + MAX_TIME_STOP_USER_IN_SECOND + "s to stop user",
                stoppedBeforeTimeout);
        Log.d(TAG, "stopUser takes " + (System.currentTimeMillis() - startTime) + " ms");
    }
}

