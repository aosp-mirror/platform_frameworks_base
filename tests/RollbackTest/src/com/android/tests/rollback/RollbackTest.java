/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.net.Uri;
import android.support.test.InstrumentationRegistry;
import android.util.Log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.TimeUnit;

/**
 * Test system Rollback APIs.
 * TODO: Should this be a cts test instead? Where should it live?
 */
@RunWith(JUnit4.class)
public class RollbackTest {

    private static final String TAG = "RollbackTest";

    private static final String TEST_APP_PACKAGE_NAME = "com.android.tests.rollback.testapp";

    /**
     * Test basic rollbacks.
     */
    @Test
    public void testBasic() throws Exception {
        // Make sure an app can't listen to or disturb the internal
        // ACTION_PACKAGE_ENABLE_ROLLBACK broadcast.
        Context context = InstrumentationRegistry.getContext();
        IntentFilter enableRollbackFilter = new IntentFilter();
        enableRollbackFilter.addAction("android.intent.action.PACKAGE_ENABLE_ROLLBACK");
        enableRollbackFilter.addDataType("application/vnd.android.package-archive");
        enableRollbackFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        BroadcastReceiver enableRollbackReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                abortBroadcast();
            }
        };
        context.registerReceiver(enableRollbackReceiver, enableRollbackFilter);

        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            // Register a broadcast receiver for notification when the rollback is
            // done executing.
            RollbackBroadcastReceiver broadcastReceiver = new RollbackBroadcastReceiver();
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Uninstall com.android.tests.rollback.testapp
            RollbackTestUtils.uninstall("com.android.tests.rollback.testapp");
            assertEquals(-1, RollbackTestUtils.getInstalledVersion(TEST_APP_PACKAGE_NAME));

            // TODO: There is currently a race condition between when the app is
            // uninstalled and when rollback manager deletes the rollback. Fix it
            // so that's not the case!
            for (int i = 0; i < 5; ++i) {
                for (RollbackInfo info : rm.getRecentlyExecutedRollbacks()) {
                    if (TEST_APP_PACKAGE_NAME.equals(info.targetPackage.packageName)) {
                        Log.i(TAG, "Sleeping 1 second to wait for uninstall to take effect.");
                        Thread.sleep(1000);
                        break;
                    }
                }
            }

            // The app should not be available for rollback.
            assertNull(rm.getAvailableRollback(TEST_APP_PACKAGE_NAME));
            assertFalse(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_PACKAGE_NAME));

            // There should be no recently executed rollbacks for this package.
            for (RollbackInfo info : rm.getRecentlyExecutedRollbacks()) {
                assertNotEquals(TEST_APP_PACKAGE_NAME, info.targetPackage.packageName);
            }

            // Install v1 of the app (without rollbacks enabled).
            RollbackTestUtils.install("RollbackTestAppV1.apk", false);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_PACKAGE_NAME));

            // Upgrade from v1 to v2, with rollbacks enabled.
            RollbackTestUtils.install("RollbackTestAppV2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_PACKAGE_NAME));

            // The app should now be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_PACKAGE_NAME));
            RollbackInfo rollback = rm.getAvailableRollback(TEST_APP_PACKAGE_NAME);
            assertNotNull(rollback);
            assertEquals(TEST_APP_PACKAGE_NAME, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // We should not have received any rollback requests yet.
            // TODO: Possibly flaky if, by chance, some other app on device
            // happens to be rolled back at the same time?
            assertNull(broadcastReceiver.poll(0, TimeUnit.SECONDS));

            // Roll back the app.
            RollbackTestUtils.rollback(rollback);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_PACKAGE_NAME));

            // Verify we received a broadcast for the rollback.
            // TODO: Race condition between the timeout and when the broadcast is
            // received could lead to test flakiness.
            Intent broadcast = broadcastReceiver.poll(5, TimeUnit.SECONDS);
            assertNotNull(broadcast);
            assertEquals(TEST_APP_PACKAGE_NAME, broadcast.getData().getSchemeSpecificPart());
            assertNull(broadcastReceiver.poll(0, TimeUnit.SECONDS));

            // Verify the recent rollback has been recorded.
            rollback = null;
            for (RollbackInfo r : rm.getRecentlyExecutedRollbacks()) {
                if (TEST_APP_PACKAGE_NAME.equals(r.targetPackage.packageName)) {
                    assertNull(rollback);
                    rollback = r;
                }
            }
            assertNotNull(rollback);
            assertEquals(TEST_APP_PACKAGE_NAME, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            broadcastReceiver.unregister();
            context.unregisterReceiver(enableRollbackReceiver);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that rollback data is properly persisted.
     */
    @Test
    public void testRollbackDataPersistence() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Prep installation of com.android.tests.rollback.testapp
            RollbackTestUtils.uninstall("com.android.tests.rollback.testapp");
            RollbackTestUtils.install("RollbackTestAppV1.apk", false);
            RollbackTestUtils.install("RollbackTestAppV2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_PACKAGE_NAME));

            // The app should now be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_PACKAGE_NAME));
            RollbackInfo rollback = rm.getAvailableRollback(TEST_APP_PACKAGE_NAME);
            assertNotNull(rollback);
            assertEquals(TEST_APP_PACKAGE_NAME, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // Reload the persisted data.
            rm.reloadPersistedData();

            // The app should still be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_PACKAGE_NAME));
            rollback = rm.getAvailableRollback(TEST_APP_PACKAGE_NAME);
            assertNotNull(rollback);
            assertEquals(TEST_APP_PACKAGE_NAME, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // Roll back the app.
            RollbackTestUtils.rollback(rollback);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_PACKAGE_NAME));

            // Verify the recent rollback has been recorded.
            rollback = null;
            for (RollbackInfo r : rm.getRecentlyExecutedRollbacks()) {
                if (TEST_APP_PACKAGE_NAME.equals(r.targetPackage.packageName)) {
                    assertNull(rollback);
                    rollback = r;
                }
            }
            assertNotNull(rollback);
            assertEquals(TEST_APP_PACKAGE_NAME, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // Reload the persisted data.
            rm.reloadPersistedData();

            // Verify the recent rollback is still recorded.
            rollback = null;
            for (RollbackInfo r : rm.getRecentlyExecutedRollbacks()) {
                if (TEST_APP_PACKAGE_NAME.equals(r.targetPackage.packageName)) {
                    assertNull(rollback);
                    rollback = r;
                }
            }
            assertNotNull(rollback);
            assertEquals(TEST_APP_PACKAGE_NAME, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test explicit expiration of rollbacks.
     * Does not test the scheduling aspects of rollback expiration.
     */
    @Test
    public void testRollbackExpiration() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();
            RollbackTestUtils.uninstall("com.android.tests.rollback.testapp");
            RollbackTestUtils.install("RollbackTestAppV1.apk", false);
            RollbackTestUtils.install("RollbackTestAppV2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_PACKAGE_NAME));

            // The app should now be available for rollback.
            assertTrue(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_PACKAGE_NAME));
            RollbackInfo rollback = rm.getAvailableRollback(TEST_APP_PACKAGE_NAME);
            assertNotNull(rollback);
            assertEquals(TEST_APP_PACKAGE_NAME, rollback.targetPackage.packageName);
            assertEquals(2, rollback.targetPackage.higherVersion.versionCode);
            assertEquals(1, rollback.targetPackage.lowerVersion.versionCode);

            // Expire the rollback.
            rm.expireRollbackForPackage(TEST_APP_PACKAGE_NAME);

            // The rollback should no longer be available.
            assertNull(rm.getAvailableRollback(TEST_APP_PACKAGE_NAME));
            assertFalse(rm.getPackagesWithAvailableRollbacks().contains(TEST_APP_PACKAGE_NAME));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test restrictions on rollback broadcast sender.
     * A random app should not be able to send a PACKAGE_ROLLBACK_EXECUTED broadcast.
     */
    @Test
    public void testRollbackBroadcastRestrictions() throws Exception {
        RollbackBroadcastReceiver broadcastReceiver = new RollbackBroadcastReceiver();
        Intent broadcast = new Intent(Intent.ACTION_PACKAGE_ROLLBACK_EXECUTED,
                Uri.fromParts("package", "com.android.tests.rollback.bogus", null));
        try {
            InstrumentationRegistry.getContext().sendBroadcast(broadcast);
            fail("Succeeded in sending restricted broadcast from app context.");
        } catch (SecurityException se) {
            // Expected behavior.
        }

        // Confirm that we really haven't received the broadcast.
        // TODO: How long to wait for the expected timeout?
        assertNull(broadcastReceiver.poll(5, TimeUnit.SECONDS));

        // TODO: Do we need to do this? Do we need to ensure this is always
        // called, even when the test fails?
        broadcastReceiver.unregister();
    }

    /**
     * Test that the MANAGE_ROLLBACKS permission is required to call
     * RollbackManager APIs.
     */
    @Test
    public void testManageRollbacksPermission() throws Exception {
        // We shouldn't be allowed to call any of the RollbackManager APIs
        // without the MANAGE_ROLLBACKS permission.
        RollbackManager rm = RollbackTestUtils.getRollbackManager();

        try {
            rm.getAvailableRollback(TEST_APP_PACKAGE_NAME);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.getPackagesWithAvailableRollbacks();
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.getRecentlyExecutedRollbacks();
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            // TODO: What if the implementation checks arguments for non-null
            // first? Then this test isn't valid.
            rm.executeRollback(null, null);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.reloadPersistedData();
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.expireRollbackForPackage(TEST_APP_PACKAGE_NAME);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }
    }
}
