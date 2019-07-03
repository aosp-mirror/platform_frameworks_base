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
 * Runs rollback tests from a secondary user.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class SecondaryUserRollbackTest extends BaseHostJUnit4Test {
    private static final int SYSTEM_USER_ID = 0;
    // The user that was running originally when the test starts.
    private int mOriginalUser = SYSTEM_USER_ID;
    private int mSecondaryUserId = -1;
    private static final long SWITCH_USER_COMPLETED_NUMBER_OF_POLLS = 60;
    private static final long SWITCH_USER_COMPLETED_POLL_INTERVAL_IN_MILLIS = 1000;


    @After
    public void tearDown() throws Exception {
        getDevice().switchUser(mOriginalUser);
        getDevice().executeShellCommand("pm uninstall com.android.cts.install.lib.testapp.A");
        getDevice().executeShellCommand("pm uninstall com.android.cts.install.lib.testapp.B");
        removeSecondaryUserIfNecessary();
    }

    @Before
    public void setup() throws Exception {
        createAndSwitchToSecondaryUserIfNecessary();
        installPackageAsUser("RollbackTest.apk", true, mSecondaryUserId, "--user current");
    }

    @Test
    public void testBasic() throws Exception {
        assertTrue(runDeviceTests("com.android.tests.rollback",
                "com.android.tests.rollback.RollbackTest",
                "testBasic"));
    }

    private void removeSecondaryUserIfNecessary() throws Exception {
        if (mSecondaryUserId != -1) {
            getDevice().removeUser(mSecondaryUserId);
            mSecondaryUserId = -1;
        }
    }

    private void createAndSwitchToSecondaryUserIfNecessary() throws Exception {
        if (mSecondaryUserId == -1) {
            mOriginalUser = getDevice().getCurrentUser();
            mSecondaryUserId = getDevice().createUser("SecondaryUserRollbackTest_User");
            assertTrue(getDevice().switchUser(mSecondaryUserId));
            // give time for user to be switched
            waitForSwitchUserCompleted(mSecondaryUserId);
        }
    }

    private void waitForSwitchUserCompleted(int userId) throws Exception {
        for (int i = 0; i < SWITCH_USER_COMPLETED_NUMBER_OF_POLLS; ++i) {
            String logs = getDevice().executeAdbCommand("logcat", "-v", "brief", "-d",
                    "ActivityManager:D");
            if (logs.contains("Posting BOOT_COMPLETED user #" + userId)) {
                return;
            }
            Thread.sleep(SWITCH_USER_COMPLETED_POLL_INTERVAL_IN_MILLIS);
        }
        fail("User switch to user " + userId + " timed out");
    }
}
