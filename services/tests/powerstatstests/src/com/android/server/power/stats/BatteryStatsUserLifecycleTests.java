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
 * limitations under the License.
 */

package com.android.server.power.stats;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@LargeTest
@RunWith(AndroidJUnit4.class)
@android.platform.test.annotations.DisabledOnRavenwood(reason = "Integration test")
public class BatteryStatsUserLifecycleTests {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private static final long POLL_INTERVAL_MS = 500;
    private static final long USER_REMOVE_TIMEOUT_MS = 5_000;
    private static final long STOP_USER_TIMEOUT_MS = 20_000;
    private static final long USER_UIDS_REMOVE_TIMEOUT_MS = 20_000;
    private static final long BATTERYSTATS_POLLING_TIMEOUT_MS = 5_000;

    private static final String CPU_DATA_TAG = "cpu";
    private static final String CPU_FREQ_DATA_TAG = "ctf";

    private int mTestUserId = UserHandle.USER_NULL;
    private Context mContext;
    private UserManager mUm;
    private IActivityManager mIam;

    @BeforeClass
    public static void setUpOnce() {
        if (RavenwoodRule.isOnRavenwood()) {
            return;
        }

        assumeTrue(UserManager.getMaxSupportedUsers() > 1);
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mUm = UserManager.get(mContext);
        mIam = ActivityManager.getService();
        final UserInfo user = mUm.createUser("Test_user_" + System.currentTimeMillis() / 1000, 0);
        assertNotNull("Unable to create test user", user);
        mTestUserId = user.id;
        batteryOnScreenOff();
    }

    @Test
    public void testNoCpuDataForRemovedUser() throws Exception {
        mIam.startUserInBackground(mTestUserId);
        waitUntilTrue("No uids for started user " + mTestUserId,
                () -> getNumberOfUidsInBatteryStats() > 0, BATTERYSTATS_POLLING_TIMEOUT_MS);

        final boolean[] userStopped = new boolean[1];
        CountDownLatch stopUserLatch = new CountDownLatch(1);
        mIam.stopUserWithCallback(mTestUserId, new IStopUserCallback.Stub() {
            @Override
            public void userStopped(int userId) throws RemoteException {
                userStopped[0] = true;
                stopUserLatch.countDown();
            }

            @Override
            public void userStopAborted(int userId) throws RemoteException {
                stopUserLatch.countDown();
            }
        });
        assertTrue("User " + mTestUserId + " could not be stopped in " + STOP_USER_TIMEOUT_MS,
                stopUserLatch.await(STOP_USER_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue("User " + mTestUserId + " could not be stopped", userStopped[0]);

        mUm.removeUser(mTestUserId);
        waitUntilTrue("Unable to remove user " + mTestUserId, () -> {
            for (UserInfo user : mUm.getUsers()) {
                if (user.id == mTestUserId) {
                    return false;
                }
            }
            return true;
        }, USER_REMOVE_TIMEOUT_MS);
        waitUntilTrue("Uids still found for removed user " + mTestUserId,
                () -> getNumberOfUidsInBatteryStats() == 0, USER_UIDS_REMOVE_TIMEOUT_MS);
    }

    @After
    public void tearDown() throws Exception {
        batteryOffScreenOn();
        if (mTestUserId != UserHandle.USER_NULL) {
            mUm.removeUser(mTestUserId);
        }
    }

    private int getNumberOfUidsInBatteryStats() throws Exception {
        ArraySet<Integer> uids = new ArraySet<>();
        final String dumpsys = executeShellCommand("dumpsys batterystats -c");
        for (String line : dumpsys.split("\n")) {
            final String[] parts = line.trim().split(",");
            if (parts.length < 5 ||
                    (!parts[3].equals(CPU_DATA_TAG) && !parts[3].equals(CPU_FREQ_DATA_TAG))) {
                continue;
            }
            try {
                final int uid = Integer.parseInt(parts[1]);
                if (UserHandle.getUserId(uid) == mTestUserId) {
                    uids.add(uid);
                }
            } catch (NumberFormatException nexc) {
                // ignore
            }
        }
        return uids.size();
    }

    protected void batteryOnScreenOff() throws Exception {
        executeShellCommand("dumpsys battery unplug");
        executeShellCommand("dumpsys batterystats enable pretend-screen-off");
    }

    protected void batteryOffScreenOn() throws Exception {
        executeShellCommand("dumpsys battery reset");
        executeShellCommand("dumpsys batterystats disable pretend-screen-off");
    }

    private String executeShellCommand(String cmd) throws Exception {
        return UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
    }

    private void waitUntilTrue(String message, Condition condition, long timeout) throws Exception {
        final long deadLine = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() <= deadLine && !condition.isTrue()) {
            Thread.sleep(POLL_INTERVAL_MS);
        }
        assertTrue(message, condition.isTrue());
    }

    private interface Condition {
        boolean isTrue() throws Exception;
    }
}
