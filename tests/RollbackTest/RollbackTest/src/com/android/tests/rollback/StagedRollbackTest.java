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
import static org.junit.Assert.assertNull;
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
    private static final String TEST_APEX_PKG = "com.android.tests.rollback.testapex";
    private static final String TEST_APEX_V1 =
            "com.android.tests.rollback.testapex.RollbackTestApexV1.apex";
    private static final String TEST_APEX_V2 =
            "com.android.tests.rollback.testapex.RollbackTestApexV2.apex";

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
        RollbackTestUtils.processUserData(TEST_APP_A);

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
        RollbackTestUtils.processUserData(TEST_APP_A);

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
        RollbackTestUtils.processUserData(TEST_APP_A);

        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
        assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);
        assertTrue(rollback.isStaged());
        assertNotEquals(-1, rollback.getCommittedSessionId());
    }

    /**
     * Test rollbacks of staged installs involving only apex.
     * Prepare apex phase.
     */
    @Test
    public void testApexOnlyPrepareApex() throws Exception {
        // Note: We can't uninstall the apex if it is already on device,
        // because that isn't supported yet (b/123667725). As long as nothing
        // is failing, this should be fine because we don't expect the tests
        // to leave the device with v2 of the apex installed.
        RollbackTestUtils.installStaged(false, TEST_APEX_V1);

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyEnableRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apex.
     * Enable rollback phase.
     */
    @Test
    public void testApexOnlyEnableRollback() throws Exception {
        assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APEX_PKG));
        RollbackTestUtils.installStaged(true, TEST_APEX_V2);

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyCommitRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apks.
     * Commit rollback phase.
     */
    @Test
    public void testApexOnlyCommitRollback() throws Exception {
        assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APEX_PKG));

        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getAvailableRollbacks(), TEST_APEX_PKG);
        assertRollbackInfoEquals(TEST_APEX_PKG, 2, 1, rollback);
        assertTrue(rollback.isStaged());

        RollbackTestUtils.rollback(rollback.getRollbackId());

        // Note: We can't use getUniqueRollbackInfoForPackage for the apex,
        // because we can't uninstall the apex (b/123667725), which means
        // there's no way to clear info about rollbacks from previous tests
        // run on the device. Look up the info by rollback id instead.
        RollbackInfo committed = null;
        for (RollbackInfo info : rm.getRecentlyCommittedRollbacks()) {
            if (info.getRollbackId() == rollback.getRollbackId()) {
                assertNull(committed);
                committed = info;
                break;
            }
        }
        assertRollbackInfoEquals(TEST_APEX_PKG, 2, 1, committed);
        assertTrue(committed.isStaged());
        assertNotEquals(-1, committed.getCommittedSessionId());

        RollbackTestUtils.waitForSessionReady(committed.getCommittedSessionId());

        // The apex should not be rolled back until after reboot.
        assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APEX_PKG));

        // At this point, the host test driver will reboot the device and run
        // testApexOnlyConfirmRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apks.
     * Confirm rollback phase.
     */
    @Test
    public void testApexOnlyConfirmRollback() throws Exception {
        assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APEX_PKG));
    }
}
