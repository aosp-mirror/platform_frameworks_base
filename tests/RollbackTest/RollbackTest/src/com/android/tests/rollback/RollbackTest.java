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

import static com.android.tests.rollback.RollbackTestUtils.assertPackageRollbackInfoEquals;
import static com.android.tests.rollback.RollbackTestUtils.assertRollbackInfoEquals;
import static com.android.tests.rollback.RollbackTestUtils.getUniqueRollbackInfoForPackage;
import static com.android.tests.rollback.RollbackTestUtils.processUserData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.VersionedPackage;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Test system Rollback APIs.
 * TODO: Should this be a cts test instead? Where should it live?
 */
@RunWith(JUnit4.class)
public class RollbackTest {

    private static final String TAG = "RollbackTest";

    private static final String TEST_APP_A = "com.android.tests.rollback.testapp.A";
    private static final String TEST_APP_B = "com.android.tests.rollback.testapp.B";
    private static final String INSTRUMENTED_APP = "com.android.tests.rollback";

    // copied from PackageManagerService#PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS
    // TODO: find a better place for the property so that it can be imported in tests
    // maybe android.content.pm.PackageManager?
    private static final String PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS =
            "enable_rollback_timeout";

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
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.MANAGE_ROLLBACKS);

            // Register a broadcast receiver for notification when the
            // rollback has been committed.
            RollbackBroadcastReceiver broadcastReceiver = new RollbackBroadcastReceiver();
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Uninstall TEST_APP_A
            RollbackTestUtils.uninstall(TEST_APP_A);
            assertEquals(-1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // TODO: There is currently a race condition between when the app is
            // uninstalled and when rollback manager deletes the rollback. Fix it
            // so that's not the case!
            for (int i = 0; i < 5; ++i) {
                RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                        rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
                if (rollback != null) {
                    Log.i(TAG, "Sleeping 1 second to wait for uninstall to take effect.");
                    Thread.sleep(1000);
                }
            }

            // The app should not be available for rollback.
            assertNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_A));

            // There should be no recently committed rollbacks for this package.
            assertNull(getUniqueRollbackInfoForPackage(
                        rm.getRecentlyCommittedRollbacks(), TEST_APP_A));

            // Install v1 of the app (without rollbacks enabled).
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Upgrade from v1 to v2, with rollbacks enabled.
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // The app should now be available for rollback.
            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);

            // We should not have received any rollback requests yet.
            // TODO: Possibly flaky if, by chance, some other app on device
            // happens to be rolled back at the same time?
            assertNull(broadcastReceiver.poll(0, TimeUnit.SECONDS));

            // Roll back the app.
            RollbackTestUtils.rollback(rollback.getRollbackId());
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Verify we received a broadcast for the rollback.
            // TODO: Race condition between the timeout and when the broadcast is
            // received could lead to test flakiness.
            Intent broadcast = broadcastReceiver.poll(5, TimeUnit.SECONDS);
            assertNotNull(broadcast);
            assertNull(broadcastReceiver.poll(0, TimeUnit.SECONDS));

            // Verify the recent rollback has been recorded.
            rollback = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);

            broadcastReceiver.unregister();
            context.unregisterReceiver(enableRollbackReceiver);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that multiple available rollbacks are properly persisted.
     */
    @Test
    public void testAvailableRollbackPersistence() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.install("RollbackTestAppBv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppBv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // Both test apps should now be available for rollback.
            RollbackInfo rollbackA = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollbackA);

            RollbackInfo rollbackB = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_B);
            assertRollbackInfoEquals(TEST_APP_B, 2, 1, rollbackB);

            // Reload the persisted data.
            rm.reloadPersistedData();

            // The apps should still be available for rollback.
            rollbackA = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollbackA);

            rollbackB = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_B);
            assertRollbackInfoEquals(TEST_APP_B, 2, 1, rollbackB);

            // Rollback of B should not rollback A
            RollbackTestUtils.rollback(rollbackB.getRollbackId());
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_B));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that available multi-package rollbacks are properly persisted.
     */
    @Test
    public void testAvailableMultiPackageRollbackPersistence() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.installMultiPackage(false,
                    "RollbackTestAppAv1.apk",
                    "RollbackTestAppBv1.apk");
            RollbackTestUtils.installMultiPackage(true,
                    "RollbackTestAppAv2.apk",
                    "RollbackTestAppBv2.apk");
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // The app should now be available for rollback.
            RollbackInfo rollbackA = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoForAandB(rollbackA);

            RollbackInfo rollbackB = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_B);
            assertRollbackInfoForAandB(rollbackB);

            // Reload the persisted data.
            rm.reloadPersistedData();

            // The apps should still be available for rollback.
            rollbackA = getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoForAandB(rollbackA);

            rollbackB = getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_B);
            assertRollbackInfoForAandB(rollbackB);

            // Rollback of B should rollback A as well
            RollbackTestUtils.rollback(rollbackB.getRollbackId());
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_B));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that recently committed rollback data is properly persisted.
     */
    @Test
    public void testRecentlyCommittedRollbackPersistence() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // The app should now be available for rollback.
            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);

            // Roll back the app.
            VersionedPackage cause = new VersionedPackage(
                    "com.android.tests.rollback.testapp.Foo", 42);
            RollbackTestUtils.rollback(rollback.getRollbackId(), cause);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Verify the recent rollback has been recorded.
            rollback = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback, cause);

            // Reload the persisted data.
            rm.reloadPersistedData();

            // Verify the recent rollback is still recorded.
            rollback = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback, cause);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test the scheduling aspect of rollback expiration.
     */
    @Test
    public void testRollbackExpiresAfterLifetime() throws Exception {
        long expirationTime = TimeUnit.SECONDS.toMillis(30);
        long defaultExpirationTime = TimeUnit.HOURS.toMillis(48);
        RollbackManager rm = RollbackTestUtils.getRollbackManager();

        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.WRITE_DEVICE_CONFIG);

            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(expirationTime), false /* makeDefault*/);

            // Pull the new expiration time from DeviceConfig
            rm.reloadPersistedData();

            // Uninstall TEST_APP_A
            RollbackTestUtils.uninstall(TEST_APP_A);
            assertEquals(-1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Install v1 of the app (without rollbacks enabled).
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Upgrade from v1 to v2, with rollbacks enabled.
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // Check that the rollback data has not expired
            Thread.sleep(1000);
            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);

            // Give it a little more time, but still not the long enough to expire
            Thread.sleep(expirationTime / 2);
            rollback = getUniqueRollbackInfoForPackage(
                rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);

            // Check that the data has expired after the expiration time (with a buffer of 1 second)
            Thread.sleep(expirationTime / 2);
            assertNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_A));

        } finally {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(defaultExpirationTime), false /* makeDefault*/);
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that changing time on device does not affect the duration of time that we keep
     * rollback available
     */
    @Test
    public void testTimeChangeDoesNotAffectLifetime() throws Exception {
        long expirationTime = TimeUnit.SECONDS.toMillis(30);
        long defaultExpirationTime = TimeUnit.HOURS.toMillis(48);
        RollbackManager rm = RollbackTestUtils.getRollbackManager();

        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.WRITE_DEVICE_CONFIG,
                    Manifest.permission.SET_TIME);

            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(expirationTime), false /* makeDefault*/);

            // Pull the new expiration time from DeviceConfig
            rm.reloadPersistedData();

            // Install app A with rollback enabled
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            Thread.sleep(expirationTime / 2);

            // Install app B with rollback enabled
            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.install("RollbackTestAppBv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppBv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));
            // 1 second buffer
            Thread.sleep(1000);

            try {
                // Change the time
                RollbackTestUtils.forwardTimeBy(expirationTime);

                // 1 second buffer to allow Rollback Manager to handle time change before loading
                // persisted data
                Thread.sleep(1000);

                // Load timestamps from storage
                rm.reloadPersistedData();

                // Wait until rollback for app A has expired
                // This will trigger an expiration run that should expire app A but not B
                Thread.sleep(expirationTime / 2);
                assertNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_A));

                // Rollback for app B should not be expired
                RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                        rm.getAvailableRollbacks(), TEST_APP_B);
                assertRollbackInfoEquals(TEST_APP_B, 2, 1, rollback);

                // Wait until rollback for app B has expired
                Thread.sleep(expirationTime / 2);
                assertNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_B));
            } finally {
                RollbackTestUtils.forwardTimeBy(-expirationTime);
            }
        } finally {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(defaultExpirationTime), false /* makeDefault*/);
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
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            // The app should now be available for rollback.
            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);

            // Expire the rollback.
            rm.expireRollbackForPackage(TEST_APP_A);

            // The rollback should no longer be available.
            assertNull(getUniqueRollbackInfoForPackage(
                        rm.getAvailableRollbacks(), TEST_APP_A));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that app user data is rolled back.
     */
    @Test
    public void testUserDataRollback() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            processUserData(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            processUserData(TEST_APP_A);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();
            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            RollbackTestUtils.rollback(rollback.getRollbackId());
            processUserData(TEST_APP_A);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test rollback of apks involving splits.
     */
    @Test
    public void testRollbackWithSplits() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.installSplit(false,
                    "RollbackTestAppASplitV1.apk",
                    "RollbackTestAppASplitV1_anydpi.apk");
            processUserData(TEST_APP_A);

            RollbackTestUtils.installSplit(true,
                    "RollbackTestAppASplitV2.apk",
                    "RollbackTestAppASplitV2_anydpi.apk");
            processUserData(TEST_APP_A);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();
            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertNotNull(rollback);
            RollbackTestUtils.rollback(rollback.getRollbackId());
            processUserData(TEST_APP_A);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test restrictions on rollback broadcast sender.
     * A random app should not be able to send a ROLLBACK_COMMITTED broadcast.
     */
    @Test
    public void testRollbackBroadcastRestrictions() throws Exception {
        RollbackBroadcastReceiver broadcastReceiver = new RollbackBroadcastReceiver();
        Intent broadcast = new Intent(Intent.ACTION_ROLLBACK_COMMITTED);
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
     * Regression test for rollback in the case when multiple apps are
     * available for rollback at the same time.
     */
    @Test
    public void testMultipleRollbackAvailable() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Prep installation of the test apps.
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.install("RollbackTestAppBv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppBv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // Both test apps should now be available for rollback, and the
            // RollbackInfo returned for the rollbacks should be correct.
            RollbackInfo rollbackA = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollbackA);

            RollbackInfo rollbackB = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_B);
            assertRollbackInfoEquals(TEST_APP_B, 2, 1, rollbackB);

            // Executing rollback should roll back the correct package.
            RollbackTestUtils.rollback(rollbackA.getRollbackId());
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.rollback(rollbackB.getRollbackId());
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_B));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
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
            rm.getAvailableRollbacks();
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            rm.getRecentlyCommittedRollbacks();
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }

        try {
            // TODO: What if the implementation checks arguments for non-null
            // first? Then this test isn't valid.
            rm.commitRollback(0, Collections.emptyList(), null);
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
            rm.expireRollbackForPackage(TEST_APP_A);
            fail("expected SecurityException");
        } catch (SecurityException e) {
            // Expected.
        }
    }

    /**
     * Test that you cannot enable rollback for a package without the
     * MANAGE_ROLLBACKS permission.
     */
    @Test
    public void testEnableRollbackPermission() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES);

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", /* enableRollback */ false);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.install("RollbackTestAppAv2.apk", /* enableRollback */ true);

            // We expect v2 of the app was installed, but rollback has not
            // been enabled.
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();
            assertNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_A));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that you cannot enable rollback for a non-module package when
     * holding the MANAGE_ROLLBACKS permission.
     */
    @Test
    public void testNonModuleEnableRollback() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", /* enableRollback */ false);
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.install("RollbackTestAppAv2.apk", /* enableRollback */ true);

            // We expect v2 of the app was installed, but rollback has not
            // been enabled because the test app is not a module.
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackManager rm = RollbackTestUtils.getRollbackManager();
            assertNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_A));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test rollback of multi-package installs is implemented.
     */
    @Test
    public void testMultiPackage() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Prep installation of the test apps.
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.installMultiPackage(false,
                    "RollbackTestAppAv1.apk",
                    "RollbackTestAppBv1.apk");
            processUserData(TEST_APP_A);
            processUserData(TEST_APP_B);
            RollbackTestUtils.installMultiPackage(true,
                    "RollbackTestAppAv2.apk",
                    "RollbackTestAppBv2.apk");
            processUserData(TEST_APP_A);
            processUserData(TEST_APP_B);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // TEST_APP_A should now be available for rollback.
            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoForAandB(rollback);

            // Rollback the app. It should cause both test apps to be rolled
            // back.
            RollbackTestUtils.rollback(rollback.getRollbackId());
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // We should see recent rollbacks listed for both A and B.
            Thread.sleep(1000);
            RollbackInfo rollbackA = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TEST_APP_A);

            RollbackInfo rollbackB = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TEST_APP_B);
            assertRollbackInfoForAandB(rollbackB);

            assertEquals(rollbackA.getRollbackId(), rollbackB.getRollbackId());

            processUserData(TEST_APP_A);
            processUserData(TEST_APP_B);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test failure to enable rollback for multi-package installs.
     * If any one of the packages fail to enable rollback, we shouldn't enable
     * rollback for any package.
     */
    @Test
    public void testMultiPackageEnableFail() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);

            // We should fail to enable rollback here because TestApp B is not
            // already installed.
            RollbackTestUtils.installMultiPackage(true,
                    "RollbackTestAppAv2.apk",
                    "RollbackTestAppBv2.apk");

            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            assertNull(getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A));
            assertNull(getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_B));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    @Test
    @Ignore("b/120200473")
    /**
     * Test rollback when app is updated to its same version.
     */
    public void testSameVersionUpdate() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            RollbackTestUtils.install("RollbackTestAppACrashingV2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 2, rollback);

            RollbackTestUtils.rollback(rollback.getRollbackId());
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            rollback = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 2, rollback);
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test bad update automatic rollback.
     */
    @Test
    public void testBadUpdateRollback() throws Exception {
        BroadcastReceiver crashCountReceiver = null;
        Context context = InstrumentationRegistry.getContext();
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.KILL_BACKGROUND_PROCESSES,
                    Manifest.permission.RESTART_PACKAGES);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            // Prep installation of the test apps.
            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppACrashingV2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackTestUtils.uninstall(TEST_APP_B);
            RollbackTestUtils.install("RollbackTestAppBv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppBv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));

            // Both test apps should now be available for rollback, and the
            // targetPackage returned for rollback should be correct.
            RollbackInfo rollbackA = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollbackA);

            RollbackInfo rollbackB = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_B);
            assertRollbackInfoEquals(TEST_APP_B, 2, 1, rollbackB);

            // Register rollback committed receiver
            RollbackBroadcastReceiver rollbackReceiver = new RollbackBroadcastReceiver();

            // Crash TEST_APP_A PackageWatchdog#TRIGGER_FAILURE_COUNT times to trigger rollback
            crashCountReceiver = RollbackTestUtils.sendCrashBroadcast(context, TEST_APP_A, 5);

            // Verify we received a broadcast for the rollback.
            rollbackReceiver.take();

            // TEST_APP_A is automatically rolled back by the RollbackPackageHealthObserver
            assertEquals(1, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
            // Instrumented app is still the package installer
            String installer = context.getPackageManager().getInstallerPackageName(TEST_APP_A);
            assertEquals(INSTRUMENTED_APP, installer);
            // TEST_APP_B is untouched
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_B));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
            if (crashCountReceiver != null) {
                context.unregisterReceiver(crashCountReceiver);
            }
        }
    }

    /**
     * Test race between roll back and roll forward.
     */
    @Test
    public void testRollForwardRace() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);
            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TEST_APP_A);
            assertRollbackInfoEquals(TEST_APP_A, 2, 1, rollback);

            // Install a new version of package A, then immediately rollback
            // the previous version. We expect the rollback to fail, because
            // it is no longer available.
            // There are a couple different ways this could fail depending on
            // thread interleaving, so don't ignore flaky failures.
            RollbackTestUtils.install("RollbackTestAppAv3.apk", false);
            try {
                RollbackTestUtils.rollback(rollback.getRollbackId());
                // Note: Don't ignore flaky failures here.
                fail("Expected rollback to fail, but it did not.");
            } catch (AssertionError e) {
                Log.i(TAG, "Note expected failure: ", e);
                // Expected
            }

            // Note: Don't ignore flaky failures here.
            assertEquals(3, RollbackTestUtils.getInstalledVersion(TEST_APP_A));
        } finally {
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    @Test
    @Ignore("b/136605788")
    public void testEnableRollbackTimeoutFailsRollback() throws Exception {
        try {
            RollbackTestUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.MANAGE_ROLLBACKS,
                    Manifest.permission.WRITE_DEVICE_CONFIG);

            //setting the timeout to a very short amount that will definitely be triggered
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS,
                    Long.toString(1), false /* makeDefault*/);
            RollbackManager rm = RollbackTestUtils.getRollbackManager();

            RollbackTestUtils.uninstall(TEST_APP_A);
            RollbackTestUtils.install("RollbackTestAppAv1.apk", false);
            RollbackTestUtils.install("RollbackTestAppAv2.apk", true);

            assertEquals(2, RollbackTestUtils.getInstalledVersion(TEST_APP_A));

            assertNull(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TEST_APP_A));
        } finally {
            //setting the timeout back to default
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS,
                    null, false /* makeDefault*/);
            RollbackTestUtils.dropShellPermissionIdentity();
        }
    }

    // Helper function to test that the given rollback info is a rollback for
    // the atomic set {A2, B2} -> {A1, B1}.
    private void assertRollbackInfoForAandB(RollbackInfo rollback) {
        assertNotNull(rollback);
        assertEquals(2, rollback.getPackages().size());
        if (TEST_APP_A.equals(rollback.getPackages().get(0).getPackageName())) {
            assertPackageRollbackInfoEquals(TEST_APP_A, 2, 1, rollback.getPackages().get(0));
            assertPackageRollbackInfoEquals(TEST_APP_B, 2, 1, rollback.getPackages().get(1));
        } else {
            assertPackageRollbackInfoEquals(TEST_APP_B, 2, 1, rollback.getPackages().get(0));
            assertPackageRollbackInfoEquals(TEST_APP_A, 2, 1, rollback.getPackages().get(1));
        }
    }
}
