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
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.app.UserSwitchObserver;
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

/**
 * Perf tests for user life cycle events.
 *
 * Running the tests:
 * make MultiUserPerfTests &&
 * adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/MultiUserPerfTests/MultiUserPerfTests.apk &&
 * adb shell am instrument -e class android.multiuser.UserLifecycleTest \
 *     -w com.android.perftests.multiuser/android.support.test.runner.AndroidJUnitRunner
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class UserLifecycleTest {
    private final int TIMEOUT_IN_SECOND = 10;
    private final int CHECK_USER_REMOVED_INTERVAL_MS = 200;

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
        mIam = ActivityManager.getService();
        mState = mPerfStatusReporter.getBenchmarkState();
        mUsersToRemove = new ArrayList<>();
    }

    @After
    public void tearDown() {
        for (int userId : mUsersToRemove) {
            try {
                mUm.removeUser(userId);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Test
    public void createAndStartUserPerf() throws Exception {
        while (mState.keepRunning()) {
            final UserInfo userInfo = mUm.createUser("TestUser", 0);

            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_STARTED, latch, userInfo.id);
            mIam.startUserInBackground(userInfo.id);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);

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

    @Test
    public void stopUserPerf() throws Exception {
        while (mState.keepRunning()) {
            mState.pauseTiming();
            final UserInfo userInfo = mUm.createUser("TestUser", 0);
            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_STARTED, latch, userInfo.id);
            mIam.startUserInBackground(userInfo.id);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
            mState.resumeTiming();

            stopUser(userInfo.id, false);

            mState.pauseTiming();
            removeUser(userInfo.id);
            mState.resumeTiming();
        }
    }

    @Test
    public void lockedBootCompletedPerf() throws Exception {
        while (mState.keepRunning()) {
            mState.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final UserInfo userInfo = mUm.createUser("TestUser", 0);
            final CountDownLatch latch = new CountDownLatch(1);
            registerUserSwitchObserver(null, latch, userInfo.id);
            mState.resumeTiming();

            mAm.switchUser(userInfo.id);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);

            mState.pauseTiming();
            switchUser(startUser);
            removeUser(userInfo.id);
            mState.resumeTiming();
        }
    }

    @Test
    public void managedProfileUnlockPerf() throws Exception {
        while (mState.keepRunning()) {
            mState.pauseTiming();
            final UserInfo userInfo = mUm.createProfileForUser("TestUser",
                    UserInfo.FLAG_MANAGED_PROFILE, mAm.getCurrentUser());
            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_UNLOCKED, latch, userInfo.id);
            mState.resumeTiming();

            mIam.startUserInBackground(userInfo.id);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);

            mState.pauseTiming();
            removeUser(userInfo.id);
            mState.resumeTiming();
        }
    }

    @Test
    public void ephemeralUserStoppedPerf() throws Exception {
        while (mState.keepRunning()) {
            mState.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final UserInfo userInfo = mUm.createUser("TestUser",
                    UserInfo.FLAG_EPHEMERAL | UserInfo.FLAG_DEMO);
            switchUser(userInfo.id);
            final CountDownLatch latch = new CountDownLatch(1);
            InstrumentationRegistry.getContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_USER_STOPPED.equals(intent.getAction()) && intent.getIntExtra(
                            Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL) == userInfo.id) {
                        latch.countDown();
                    }
                }
            }, new IntentFilter(Intent.ACTION_USER_STOPPED));
            final CountDownLatch switchLatch = new CountDownLatch(1);
            registerUserSwitchObserver(switchLatch, null, startUser);
            mState.resumeTiming();

            mAm.switchUser(startUser);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);

            mState.pauseTiming();
            switchLatch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
            removeUser(userInfo.id);
            mState.resumeTiming();
        }
    }

    @Test
    public void managedProfileStoppedPerf() throws Exception {
        while (mState.keepRunning()) {
            mState.pauseTiming();
            final UserInfo userInfo = mUm.createProfileForUser("TestUser",
                    UserInfo.FLAG_MANAGED_PROFILE, mAm.getCurrentUser());
            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_UNLOCKED, latch, userInfo.id);
            mIam.startUserInBackground(userInfo.id);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
            mState.resumeTiming();

            stopUser(userInfo.id, true);

            mState.pauseTiming();
            removeUser(userInfo.id);
            mState.resumeTiming();
        }
    }

    private void switchUser(int userId) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        registerUserSwitchObserver(latch, null, userId);
        mAm.switchUser(userId);
        latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
    }

    private void stopUser(int userId, boolean force) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mIam.stopUser(userId, force /* force */, new IStopUserCallback.Stub() {
            @Override
            public void userStopped(int userId) throws RemoteException {
                latch.countDown();
            }

            @Override
            public void userStopAborted(int userId) throws RemoteException {
            }
        });
        latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
    }

    private void registerUserSwitchObserver(final CountDownLatch switchLatch,
            final CountDownLatch bootCompleteLatch, final int userId) throws Exception {
        ActivityManager.getService().registerUserSwitchObserver(
                new UserSwitchObserver() {
                    @Override
                    public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        if (switchLatch != null && userId == newUserId) {
                            switchLatch.countDown();
                        }
                    }

                    @Override
                    public void onLockedBootComplete(int newUserId) {
                        if (bootCompleteLatch != null && userId == newUserId) {
                            bootCompleteLatch.countDown();
                        }
                    }
                }, "UserLifecycleTest");
    }

    private void registerBroadcastReceiver(final String action, final CountDownLatch latch,
            final int userId) {
        InstrumentationRegistry.getContext().registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (action.equals(intent.getAction()) && intent.getIntExtra(
                        Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL) == userId) {
                    latch.countDown();
                }
            }
        }, UserHandle.of(userId), new IntentFilter(action), null, null);
    }

    private void removeUser(int userId) {
        try {
            mUm.removeUser(userId);
            final long startTime = System.currentTimeMillis();
            final long timeoutInMs = TIMEOUT_IN_SECOND * 1000;
            while (mUm.getUserInfo(userId) != null &&
                    System.currentTimeMillis() - startTime < timeoutInMs) {
                TimeUnit.MILLISECONDS.sleep(CHECK_USER_REMOVED_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Ignore
        }
        if (mUm.getUserInfo(userId) != null) {
            mUsersToRemove.add(userId);
        }
    }
}
