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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.VersionedPackage;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;

import androidx.test.InstrumentationRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    private static final String TEST_APP_A_V1 = "RollbackTestAppAv1.apk";
    private static final String TEST_APP_A_CRASHING_V2 = "RollbackTestAppACrashingV2.apk";
    private static final String NETWORK_STACK_CONNECTOR_CLASS =
            "android.net.INetworkStackConnector";

    /**
     * Adopts common shell permissions needed for rollback tests.
     */
    @Before
    public void adoptShellPermissions() {
        RollbackTestUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.DELETE_PACKAGES,
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                Manifest.permission.KILL_BACKGROUND_PROCESSES);
    }

    /**
     * Drops shell permissions needed for rollback tests.
     */
    @After
    public void dropShellPermissions() {
        RollbackTestUtils.dropShellPermissionIdentity();
    }

    /**
     * Test rollbacks of staged installs involving only apks with bad update.
     * Enable rollback phase.
     */
    @Test
    public void testBadApkOnlyEnableRollback() throws Exception {
        RollbackTestUtils.uninstall(TEST_APP_A);
        assertEquals(-1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

        RollbackTestUtils.install(TEST_APP_A_V1, false);
        assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
        RollbackTestUtils.processUserData(TEST_APP_A);

        RollbackTestUtils.installStaged(true, TEST_APP_A_CRASHING_V2);

        // At this point, the host test driver will reboot the device and run
        // testBadApkOnlyConfirmEnableRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apks with bad update.
     * Confirm that rollback was successfully enabled.
     */
    @Test
    public void testBadApkOnlyConfirmEnableRollback() throws Exception {
        assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
        RollbackTestUtils.processUserData(TEST_APP_A);

        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getAvailableRollbacks(), TEST_APP_A);
        assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);
        assertTrue(rollback.isStaged());

        // At this point, the host test driver will run
        // testBadApkOnlyTriggerRollback().
    }

    /**
     * Test rollbacks of staged installs involving only apks with bad update.
     * Trigger rollback phase. This is expected to fail due to watchdog
     * rebooting the test out from under it.
     */
    @Test
    public void testBadApkOnlyTriggerRollback() throws Exception {
        BroadcastReceiver crashCountReceiver = null;
        Context context = InstrumentationRegistry.getContext();
        RollbackManager rm = RollbackTestUtils.getRollbackManager();

        try {
            // Crash TEST_APP_A PackageWatchdog#TRIGGER_FAILURE_COUNT times to trigger rollback
            crashCountReceiver = RollbackTestUtils.sendCrashBroadcast(context, TEST_APP_A, 5);
        } finally {
            if (crashCountReceiver != null) {
                context.unregisterReceiver(crashCountReceiver);
            }
        }

        // We expect the device to be rebooted automatically. Wait for that to
        // happen. At that point, the host test driver will wait for the
        // device to come back up and run testApkOnlyConfirmRollback().
        Thread.sleep(30 * 1000);

        fail("watchdog did not trigger reboot");
    }

    /**
     * Test rollbacks of staged installs involving only apks.
     * Confirm rollback phase.
     */
    @Test
    public void testBadApkOnlyConfirmRollback() throws Exception {
        assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
        RollbackTestUtils.processUserData(TEST_APP_A);

        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
        assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback, new VersionedPackage(TEST_APP_A, 2));
        assertTrue(rollback.isStaged());
        assertNotEquals(-1, rollback.getCommittedSessionId());
    }

    @Test
    public void resetNetworkStack() throws Exception {
        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        String networkStack = getNetworkStackPackageName();

        rm.expireRollbackForPackage(networkStack);
        RollbackTestUtils.uninstall(networkStack);

        assertNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        networkStack));
    }

    @Test
    public void assertNetworkStackRollbackAvailable() throws Exception {
        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        assertNotNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        getNetworkStackPackageName()));
    }

    @Test
    public void assertNetworkStackRollbackCommitted() throws Exception {
        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        assertNotNull(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                        getNetworkStackPackageName()));
    }

    @Test
    public void assertNoNetworkStackRollbackCommitted() throws Exception {
        RollbackManager rm = RollbackTestUtils.getRollbackManager();
        assertNull(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                        getNetworkStackPackageName()));
    }

    private String getNetworkStackPackageName() {
        Intent intent = new Intent(NETWORK_STACK_CONNECTOR_CLASS);
        ComponentName comp = intent.resolveSystemService(
                InstrumentationRegistry.getContext().getPackageManager(), 0);
        return comp.getPackageName();
    }
}
