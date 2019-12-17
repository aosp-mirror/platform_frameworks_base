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
import org.junit.Test;
import org.junit.runner.RunWith;

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


    @After
    public void tearDown() throws Exception {
        getDevice().switchUser(mOriginalUserId);
        getDevice().executeShellCommand("pm uninstall com.android.cts.install.lib.testapp.A");
        removeSecondaryUserIfNecessary();
    }

    @Before
    public void setup() throws Exception {
        mOriginalUserId = getDevice().getCurrentUser();
        installPackageAsUser("RollbackTest.apk", true, mOriginalUserId);
        createAndSwitchToSecondaryUserIfNecessary();
        installPackageAsUser("RollbackTest.apk", true, mSecondaryUserId);
    }

    @Test
    public void testBasicForSecondaryUser() throws Exception {
        runPhaseForUsers("testBasic", mSecondaryUserId);
    }

    @Test
    public void testMultipleUsers() throws Exception {
        runPhaseForUsers("testMultipleUsersInstallV1", mOriginalUserId, mSecondaryUserId);
        runPhaseForUsers("testMultipleUsersUpgradeToV2", mOriginalUserId);
        runPhaseForUsers("testMultipleUsersUpdateUserData", mOriginalUserId, mSecondaryUserId);
        switchToUser(mOriginalUserId);
        getDevice().executeShellCommand("pm rollback-app com.android.cts.install.lib.testapp.A");
        runPhaseForUsers("testMultipleUsersVerifyUserdataRollback", mOriginalUserId,
                mSecondaryUserId);
    }

    /**
     * Run the phase for the given user ids, in the order they are given.
     */
    private void runPhaseForUsers(String phase, int... userIds) throws Exception {
        for (int userId: userIds) {
            switchToUser(userId);
            assertTrue(runDeviceTests("com.android.tests.rollback",
                    "com.android.tests.rollback.MultiUserRollbackTest",
                    phase));
        }
    }

    private void removeSecondaryUserIfNecessary() throws Exception {
        if (mSecondaryUserId != -1) {
            getDevice().removeUser(mSecondaryUserId);
            mSecondaryUserId = -1;
        }
    }

    private void createAndSwitchToSecondaryUserIfNecessary() throws Exception {
        if (mSecondaryUserId == -1) {
            mOriginalUserId = getDevice().getCurrentUser();
            mSecondaryUserId = getDevice().createUser("MultiUserRollbackTest_User"
                    + System.currentTimeMillis());
            switchToUser(mSecondaryUserId);
        }
    }

    private void switchToUser(int userId) throws Exception {
        if (getDevice().getCurrentUser() == userId) {
            return;
        }

        assertTrue(getDevice().switchUser(userId));
        for (int i = 0; i < SWITCH_USER_COMPLETED_NUMBER_OF_POLLS; ++i) {
            String userState = getDevice().executeShellCommand("am get-started-user-state "
                    + userId);
            if (userState.contains("RUNNING_UNLOCKED")) {
                return;
            }
            Thread.sleep(SWITCH_USER_COMPLETED_POLL_INTERVAL_IN_MILLIS);
        }
        fail("User switch to user " + userId + " timed out");
    }
}
