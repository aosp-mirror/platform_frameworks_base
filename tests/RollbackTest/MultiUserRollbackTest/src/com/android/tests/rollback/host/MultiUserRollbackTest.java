/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tests.rollback.host;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Runs rollback tests for multiple users.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class MultiUserRollbackTest extends BaseHostJUnit4Test {
    // The user that was running originally when the test starts.
    private int mOriginalUserId;
    private int mSecondaryUserId = -1;
    private static final long SWITCH_USER_COMPLETED_NUMBER_OF_POLLS = 60;
    private static final long SWITCH_USER_COMPLETED_POLL_INTERVAL_IN_MILLIS = 1000;

    @Rule
    public AbandonSessionsRule mHostTestRule = new AbandonSessionsRule(this);

    @After
    public void tearDown() throws Exception {
        removeSecondaryUserIfNecessary();
        runPhaseForUsers("cleanUp", mOriginalUserId);
        uninstallPackage("com.android.cts.install.lib.testapp.A");
        uninstallPackage("com.android.cts.install.lib.testapp.B");
    }

    @Before
    public void setup() throws Exception {
        mOriginalUserId = getDevice().getCurrentUser();
        createAndStartSecondaryUser();
        installPackage("RollbackTest.apk", "--user all");
        runPhaseForUsers("cleanUp", mOriginalUserId);
    }

    @Test
    public void testBasicForSecondaryUser() throws Exception {
        runPhaseForUsers("testBasic", mSecondaryUserId);
    }

    /**
     * Tests staged install/rollback works correctly on the 2nd user.
     */
    @Test
    public void testStagedRollback() throws Exception {
        runPhaseForUsers("testStagedRollback_Phase1", mSecondaryUserId);
        getDevice().reboot();

        // Need to unlock the user for device tests to run successfully
        getDevice().startUser(mSecondaryUserId);
        awaitUserUnlocked(mSecondaryUserId);
        runPhaseForUsers("testStagedRollback_Phase2", mSecondaryUserId);
        getDevice().reboot();

        getDevice().startUser(mSecondaryUserId);
        awaitUserUnlocked(mSecondaryUserId);
        runPhaseForUsers("testStagedRollback_Phase3", mSecondaryUserId);
        getDevice().reboot();

        getDevice().startUser(mSecondaryUserId);
        awaitUserUnlocked(mSecondaryUserId);
        runPhaseForUsers("testStagedRollback_Phase4", mSecondaryUserId);
    }

    @Test
    public void testBadUpdateRollback() throws Exception {
        // Need to switch user in order to send broadcasts in device tests
        assertTrue(getDevice().switchUser(mSecondaryUserId));
        runPhaseForUsers("testBadUpdateRollback", mSecondaryUserId);
    }

    @Test
    public void testMultipleUsers() throws Exception {
        runPhaseForUsers("testMultipleUsersInstallV1", mOriginalUserId, mSecondaryUserId);
        runPhaseForUsers("testMultipleUsersUpgradeToV2", mOriginalUserId);
        runPhaseForUsers("testMultipleUsersUpdateUserData", mOriginalUserId, mSecondaryUserId);
        getDevice().executeShellCommand("pm rollback-app com.android.cts.install.lib.testapp.A");
        runPhaseForUsers("testMultipleUsersVerifyUserdataRollback", mOriginalUserId,
                mSecondaryUserId);
    }

    /**
     * Run the phase for the given user ids, in the order they are given.
     */
    private void runPhaseForUsers(String phase, int... userIds) throws Exception {
        final long timeout = TimeUnit.MINUTES.toMillis(10);
        for (int userId: userIds) {
            assertTrue(runDeviceTests(getDevice(), "com.android.tests.rollback",
                    "com.android.tests.rollback.MultiUserRollbackTest",
                    phase, userId, timeout));
        }
    }

    private void removeSecondaryUserIfNecessary() throws Exception {
        if (mSecondaryUserId != -1) {
            // Can't remove the 2nd user without switching out of it
            assertTrue(getDevice().switchUser(mOriginalUserId));
            getDevice().removeUser(mSecondaryUserId);
            mSecondaryUserId = -1;
        }
    }

    private void awaitUserUnlocked(int userId) throws Exception {
        for (int i = 0; i < SWITCH_USER_COMPLETED_NUMBER_OF_POLLS; ++i) {
            String userState = getDevice().executeShellCommand("am get-started-user-state "
                    + userId);
            if (userState.contains("RUNNING_UNLOCKED")) {
                return;
            }
            Thread.sleep(SWITCH_USER_COMPLETED_POLL_INTERVAL_IN_MILLIS);
        }
        fail("Timed out in unlocking user: " + userId);
    }

    private void createAndStartSecondaryUser() throws Exception {
        String name = "MultiUserRollbackTest_User" + System.currentTimeMillis();
        mSecondaryUserId = getDevice().createUser(name);
        getDevice().startUser(mSecondaryUserId);
        // Note we can't install apps on a locked user
        awaitUserUnlocked(mSecondaryUserId);
    }
}
