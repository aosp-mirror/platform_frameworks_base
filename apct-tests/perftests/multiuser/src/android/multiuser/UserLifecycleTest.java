/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class UserLifecycleTest {
    private final int MIN_REPEAT_TIMES = 4;

    private final int TIMEOUT_REMOVE_USER_SEC = 4;
    private final int CHECK_USER_REMOVED_INTERVAL_MS = 200; // 0.2 sec

    private final int TIMEOUT_USER_START_SEC = 4; // 4 sec

    private final int TIMEOUT_USER_SWITCH_SEC = 8; // 8 sec

    private UserManager mUm;
    private ActivityManager mAm;
    private IActivityManager mIam;
    private BenchmarkState mState;
    private ArrayList<Integer> mUsersToRemove;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getContext();
        mUm = UserManager.get(context);
        mAm = context.getSystemService(ActivityManager.class);
        mIam = ActivityManagerNative.getDefault();
        mState = mPerfStatusReporter.getBenchmarkState();
        mState.setMinRepeatTimes(MIN_REPEAT_TIMES);
        mUsersToRemove = new ArrayList<>();
    }

    @After
    public void tearDown() {
        for (int userId : mUsersToRemove) {
            mUm.removeUser(userId);
        }
    }

    @Test
    public void createAndStartUserPerf() throws Exception {
        while (mState.keepRunning()) {
            final UserInfo userInfo = mUm.createUser("TestUser", 0);

            final CountDownLatch latch = new CountDownLatch(1);
            InstrumentationRegistry.getContext().registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_USER_STARTED.equals(intent.getAction())) {
                        latch.countDown();
                    }
                }
            }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_STARTED), null, null);
            mIam.startUserInBackground(userInfo.id);
            latch.await(TIMEOUT_USER_START_SEC, TimeUnit.SECONDS);

            mState.pauseTiming();
            removeUser(userInfo.id);
            mState.resumeTiming();
        }
    }

    @Test
    public void switchUserPerf() throws Exception {
        while (mState.keepRunning()) {
            mState.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final UserInfo userInfo = mUm.createUser("TestUser", 0);
            mState.resumeTiming();

            switchUser(userInfo.id);

            mState.pauseTiming();
            switchUser(startUser);
            removeUser(userInfo.id);
            mState.resumeTiming();
        }
    }

    private void switchUser(int userId) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        registerUserSwitchObserver(latch);
        mAm.switchUser(userId);
        latch.await(TIMEOUT_USER_SWITCH_SEC, TimeUnit.SECONDS);
    }

    private void registerUserSwitchObserver(final CountDownLatch latch) throws Exception {
        ActivityManagerNative.getDefault().registerUserSwitchObserver(
                new SynchronousUserSwitchObserver() {
                    @Override
                    public void onUserSwitching(int newUserId) throws RemoteException {
                    }

                    @Override
                    public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        latch.countDown();
                    }

                    @Override
                    public void onForegroundProfileSwitch(int newProfileId) throws RemoteException {
                    }
                }, "UserLifecycleTest");
    }

    private void removeUser(int userId) throws Exception {
        mUm.removeUser(userId);
        final long startTime = System.currentTimeMillis();
        while (mUm.getUserInfo(userId) != null &&
                System.currentTimeMillis() - startTime < TIMEOUT_REMOVE_USER_SEC) {
            Thread.sleep(CHECK_USER_REMOVED_INTERVAL_MS);
        }
        if (mUm.getUserInfo(userId) != null) {
            mUsersToRemove.add(userId);
        }
    }
}