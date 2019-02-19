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

package com.android.tests.rollback;

import static com.android.tests.rollback.RollbackTestUtils.assertRollbackInfoEquals;
import static com.android.tests.rollback.RollbackTestUtils.getUniqueRollbackInfoForPackage;

import android.Manifest;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for rollback of staged installs.
 * <p>
 * Note: These tests require reboot in between test phases. They are run
 * specially so that the testFooEnableRollback, testFooCommitRollback, and
 * testFooConfirmRollback phases of each test are run in order with reboots in
 * between them.
 */
@RunWith(JUnit4.class)
public class StagedRollbackTest {

    private static final String TAG = "RollbackTest";
    private static final String TEST_APP_A = "com.android.tests.rollback.testapp.A";

    /**
     * Adopts common shell permissions needed for rollback tests.
     */
    @Before
    public void adoptShellPermissions() {
        RollbackTestUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.DELETE_PACKAGES,
                Manifest.permission.MANAGE_ROLLBACKS);
    }

    /**
     * Drops shell permissions needed for rollback tests.
     */
    @After
    public void dropShellPermissions() {
        RollbackTestUtils.dropShellPermissionIdentity();
    }


    /**
     * Test rollbacks of staged installs involving only apks.
     * Enable rollback phase.
     */
    @Test
    public void testApkOnlyEnableRollback() throws Exception {
        RollbackTestUtils.uninstall(TEST_APP_A);
        assertEquals(-1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

        RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
        assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

        RollbackTestUtils.installStaged(true, "RollbackTestAppAv2.apk");

        // At this point, the host test driver will reboot the device and run
        // testApkOnlyCommitRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apks.
     * Commit rollback phase.
     */
    @Test
    public void testApkOnlyCommitRollback() throws Exception {
        assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getAvailableRollbacks(), TEST_APP_A);
        assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);
        assertTrue(rollback.isStaged());

        RollbackTestUtils.rollback(rollback.getRollbackId());

        rollback = getUniqueRollbackInfoForPackage(
                rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
        assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);
        assertTrue(rollback.isStaged());
        assertNotEquals(-1, rollback.getCommittedSessionId());

        RollbackTestUtils.waitForSessionReady(rollback.getCommittedSessionId());

        // The app should not be rolled back until after reboot.
        assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

        // At this point, the host test driver will reboot the device and run
        // testApkOnlyConfirmRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apks.
     * Confirm rollback phase.
     */
    @Test
    public void testApkOnlyConfirmRollback() throws Exception {
        assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
        assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);
        assertTrue(rollback.isStaged());
        assertNotEquals(-1, rollback.getCommittedSessionId());
    }
}
