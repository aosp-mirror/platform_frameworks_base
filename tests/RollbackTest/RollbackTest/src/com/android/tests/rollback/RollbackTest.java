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

import static com.android.cts.install.lib.InstallUtils.processUserData;
import static com.android.cts.rollback.lib.RollbackInfoSubject.assertThat;
import static com.android.cts.rollback.lib.RollbackUtils.getUniqueRollbackInfoForPackage;
import static com.android.cts.rollback.lib.RollbackUtils.waitForAvailableRollback;
import static com.android.cts.rollback.lib.RollbackUtils.waitForUnavailableRollback;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;
import com.android.cts.rollback.lib.Rollback;
import com.android.cts.rollback.lib.RollbackBroadcastReceiver;
import com.android.cts.rollback.lib.RollbackUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Test system Rollback APIs.
 * TODO: Should this be a cts test instead? Where should it live?
 */
@RunWith(JUnit4.class)
public class RollbackTest {

    private static final String TAG = "RollbackTest";

    private static final String INSTRUMENTED_APP = "com.android.tests.rollback";

    // copied from PackageManagerService#PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS
    // TODO: find a better place for the property so that it can be imported in tests
    // maybe android.content.pm.PackageManager?
    private static final String PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS =
            "enable_rollback_timeout";

    private static boolean hasRollbackInclude(List<RollbackInfo> rollbacks, String packageName) {
        return rollbacks.stream().anyMatch(
                ri -> ri.getPackages().stream().anyMatch(
                        pri -> packageName.equals(pri.getPackageName())));
    }

    @Before
    @After
    public void cleanUp() {
        try {
            InstallUtils.adoptShellPermissionIdentity(Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackUtils.getRollbackManager();
            rm.getAvailableRollbacks().stream().flatMap(info -> info.getPackages().stream())
                    .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
            rm.getRecentlyCommittedRollbacks().stream().flatMap(info -> info.getPackages().stream())
                    .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

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
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.MANAGE_ROLLBACKS,
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.CREATE_USERS,
                    Manifest.permission.INTERACT_ACROSS_USERS);

            // Register a broadcast receiver for notification when the
            // rollback has been committed.
            RollbackBroadcastReceiver broadcastReceiver = new RollbackBroadcastReceiver();
            RollbackManager rm = RollbackUtils.getRollbackManager();

            // Uninstall TestApp.A
            Uninstall.packages(TestApp.A);
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);
            for (int i = 0; i < 5; ++i) {
                if (hasRollbackInclude(rm.getRecentlyCommittedRollbacks(), TestApp.A)) {
                    Log.i(TAG, "Sleeping 1 second to wait for uninstall to take effect.");
                    Thread.sleep(1000);
                }
            }

            assertThat(hasRollbackInclude(rm.getRecentlyCommittedRollbacks(), TestApp.A)).isFalse();
            // The app should not be available for rollback.
            waitForUnavailableRollback(TestApp.A);

            // There should be no recently committed rollbacks for this package.
            assertThat(getUniqueRollbackInfoForPackage(
                        rm.getRecentlyCommittedRollbacks(), TestApp.A)).isNull();

            // Install v1 of the app (without rollbacks enabled).
            Install.single(TestApp.A1).commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

            // Upgrade from v1 to v2, with rollbacks enabled.
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            // The app should now be available for rollback.
            RollbackInfo available = waitForAvailableRollback(TestApp.A);
            assertThat(available).isNotStaged();
            assertThat(available).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            // We should not have received any rollback requests yet.
            // TODO: Possibly flaky if, by chance, some other app on device
            // happens to be rolled back at the same time?
            assertThat(broadcastReceiver.poll(0, TimeUnit.SECONDS)).isNull();

            // Roll back the app.
            RollbackUtils.rollback(available.getRollbackId());
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

            UserManager um = (UserManager) context.getSystemService(context.USER_SERVICE);
            List<Integer> userIds = um.getUsers(true)
                    .stream().map(user -> user.id).collect(Collectors.toList());
            assertThat(InstallUtils.isOnlyInstalledForUser(TestApp.A,
                    context.getUserId(), userIds)).isTrue();

            // Verify we received a broadcast for the rollback.
            // TODO: Race condition between the timeout and when the broadcast is
            // received could lead to test flakiness.
            Intent broadcast = broadcastReceiver.poll(5, TimeUnit.SECONDS);
            assertThat(broadcast).isNotNull();
            assertThat(broadcastReceiver.poll(0, TimeUnit.SECONDS)).isNull();

            // Verify the recent rollback has been recorded.
            RollbackInfo committed = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TestApp.A);
            assertThat(committed).isNotNull();
            assertThat(committed).isNotStaged();
            assertThat(committed).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));
            assertThat(committed).hasRollbackId(available.getRollbackId());

            broadcastReceiver.unregister();
            context.unregisterReceiver(enableRollbackReceiver);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that multiple available rollbacks are properly persisted.
     */
    @Test
    public void testAvailableRollbackPersistence() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackUtils.getRollbackManager();

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            Uninstall.packages(TestApp.B);
            Install.single(TestApp.B1).commit();
            Install.single(TestApp.B2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            // Both test apps should now be available for rollback.
            RollbackInfo rollbackA = waitForAvailableRollback(TestApp.A);
            assertThat(rollbackA).isNotNull();
            assertThat(rollbackA).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            RollbackInfo rollbackB = waitForAvailableRollback(TestApp.B);
            assertThat(rollbackB).isNotNull();
            assertThat(rollbackB).packagesContainsExactly(
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            // Reload the persisted data.
            rm.reloadPersistedData();

            // The apps should still be available for rollback.
            rollbackA = waitForAvailableRollback(TestApp.A);
            assertThat(rollbackA).isNotNull();
            assertThat(rollbackA).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            rollbackB = waitForAvailableRollback(TestApp.B);
            assertThat(rollbackB).isNotNull();
            assertThat(rollbackB).packagesContainsExactly(
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            // Rollback of B should not rollback A
            RollbackUtils.rollback(rollbackB.getRollbackId());
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(1);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that available multi-package rollbacks are properly persisted.
     */
    @Test
    public void testAvailableMultiPackageRollbackPersistence() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackUtils.getRollbackManager();

            Uninstall.packages(TestApp.A, TestApp.B);
            Install.multi(TestApp.A1, TestApp.B1).commit();
            Install.multi(TestApp.A2, TestApp.B2).setEnableRollback().commit();

            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            // The app should now be available for rollback.
            RollbackInfo availableA = waitForAvailableRollback(TestApp.A);
            assertThat(availableA).isNotNull();
            assertThat(availableA).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1),
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            RollbackInfo availableB = waitForAvailableRollback(TestApp.B);
            assertThat(availableB).isNotNull();
            assertThat(availableB).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1),
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            // Assert they're both the same rollback
            assertThat(availableA).hasRollbackId(availableB.getRollbackId());

            // Reload the persisted data.
            rm.reloadPersistedData();

            // The apps should still be available for rollback.
            availableA = waitForAvailableRollback(TestApp.A);
            assertThat(availableA).isNotNull();
            assertThat(availableA).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1),
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            availableB = waitForAvailableRollback(TestApp.B);
            assertThat(availableB).isNotNull();
            assertThat(availableB).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1),
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            // Rollback of B should rollback A as well
            RollbackUtils.rollback(availableB.getRollbackId());
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(1);

            RollbackInfo committedA = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TestApp.A);
            assertThat(committedA).isNotNull();
            assertThat(committedA).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1),
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            RollbackInfo committedB = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TestApp.A);
            assertThat(committedB).isNotNull();
            assertThat(committedB).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1),
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            // Assert they're both the same rollback
            assertThat(committedA).hasRollbackId(committedB.getRollbackId());
            assertThat(committedA).hasRollbackId(availableA.getRollbackId());
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that recently committed rollback data is properly persisted.
     */
    @Test
    public void testRecentlyCommittedRollbackPersistence() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackUtils.getRollbackManager();

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            // The app should now be available for rollback.
            RollbackInfo available = waitForAvailableRollback(TestApp.A);
            assertThat(available).isNotNull();

            // Roll back the app.
            TestApp cause = new TestApp("Foo", "com.android.tests.rollback.testapp.Foo",
                    /*versionCode*/ 42, /*isApex*/ false);
            RollbackUtils.rollback(available.getRollbackId(), cause);
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

            // Verify the recent rollback has been recorded.
            RollbackInfo committed = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TestApp.A);
            assertThat(committed).isNotNull();
            assertThat(committed).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));
            assertThat(committed).causePackagesContainsExactly(cause);

            // Reload the persisted data.
            rm.reloadPersistedData();

            // Verify the recent rollback is still recorded.
            committed = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TestApp.A);
            assertThat(committed).isNotNull();
            assertThat(committed).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));
            assertThat(committed).causePackagesContainsExactly(cause);
            assertThat(committed).hasRollbackId(available.getRollbackId());
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test the scheduling aspect of rollback expiration.
     */
    @Test
    public void testRollbackExpiresAfterLifetime() throws Exception {
        long expirationTime = TimeUnit.SECONDS.toMillis(30);
        long defaultExpirationTime = TimeUnit.HOURS.toMillis(48);
        RollbackManager rm = RollbackUtils.getRollbackManager();

        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.WRITE_DEVICE_CONFIG);

            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(expirationTime), false /* makeDefault*/);

            // Uninstall TestApp.A
            Uninstall.packages(TestApp.A);
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);

            // Install v1 of the app (without rollbacks enabled).
            Install.single(TestApp.A1).commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

            // Upgrade from v1 to v2, with rollbacks enabled.
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            // Check that the rollback data has not expired
            Thread.sleep(1000);
            RollbackInfo rollback = waitForAvailableRollback(TestApp.A);
            assertThat(rollback).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            // Give it a little more time, but still not long enough to expire
            Thread.sleep(expirationTime / 2);
            rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TestApp.A);
            assertThat(rollback).isNotNull();
            assertThat(rollback).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            // Check that the data has expired after the expiration time (with a buffer of 1 second)
            Thread.sleep(expirationTime / 2);
            rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TestApp.A);
            assertThat(rollback).isNull();

        } finally {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(defaultExpirationTime), false /* makeDefault*/);
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that available rollbacks should expire correctly when the property
     * {@link RollbackManager#PROPERTY_ROLLBACK_LIFETIME_MILLIS} is changed
     */
    @Test
    public void testRollbackExpiresWhenLifetimeChanges() throws Exception {
        long defaultExpirationTime = TimeUnit.HOURS.toMillis(48);
        RollbackManager rm = RollbackUtils.getRollbackManager();

        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.WRITE_DEVICE_CONFIG);

            Uninstall.packages(TestApp.A);
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);
            Install.single(TestApp.A1).commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
            RollbackInfo rollback = waitForAvailableRollback(TestApp.A);
            assertThat(rollback).packagesContainsExactly(Rollback.from(TestApp.A2).to(TestApp.A1));

            // Change the lifetime to 0 which should expire rollbacks immediately
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(0), false /* makeDefault*/);

            // Keep polling until device config changes has happened (which might take more than
            // 5 sec depending how busy system_server is) and rollbacks have expired
            for (int i = 0; i < 30; ++i) {
                if (hasRollbackInclude(rm.getAvailableRollbacks(), TestApp.A)) {
                    Thread.sleep(1000);
                }
            }
            rollback = getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TestApp.A);
            assertThat(rollback).isNull();
        } finally {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(defaultExpirationTime), false /* makeDefault*/);
            InstallUtils.dropShellPermissionIdentity();
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
        RollbackManager rm = RollbackUtils.getRollbackManager();

        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.WRITE_DEVICE_CONFIG,
                    Manifest.permission.SET_TIME);

            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(expirationTime), false /* makeDefault*/);

            // Install app A with rollback enabled
            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            Thread.sleep(expirationTime / 2);

            // Install app B with rollback enabled
            Uninstall.packages(TestApp.B);
            Install.single(TestApp.B1).commit();
            Install.single(TestApp.B2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            // 1 second buffer
            Thread.sleep(1000);

            try {
                // Change the time
                RollbackUtils.forwardTimeBy(expirationTime);

                // 1 second buffer to allow Rollback Manager to handle time change before loading
                // persisted data
                Thread.sleep(1000);

                // Load timestamps from storage
                rm.reloadPersistedData();

                // Wait until rollback for app A has expired
                // This will trigger an expiration run that should expire app A but not B
                Thread.sleep(expirationTime / 2);
                RollbackInfo rollbackA =
                        getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TestApp.A);
                Log.i(TAG, "Checking if the rollback for TestApp.A is null");

                // Rollback for app B should not be expired
                RollbackInfo rollbackB1 = getUniqueRollbackInfoForPackage(
                        rm.getAvailableRollbacks(), TestApp.B);

                // Wait until rollback for app B has expired
                Thread.sleep(expirationTime / 2);
                RollbackInfo rollbackB2 = getUniqueRollbackInfoForPackage(
                        rm.getAvailableRollbacks(), TestApp.B);

                assertThat(rollbackA).isNull();
                assertThat(rollbackB1).isNotNull();
                assertThat(rollbackB1).packagesContainsExactly(
                        Rollback.from(TestApp.B2).to(TestApp.B1));
                assertThat(rollbackB2).isNull();
            } finally {
                RollbackUtils.forwardTimeBy(-expirationTime);
            }
        } finally {
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                    RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                    Long.toString(defaultExpirationTime), false /* makeDefault*/);
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test explicit expiration of rollbacks.
     * Does not test the scheduling aspects of rollback expiration.
     */
    @Test
    public void testRollbackExpiration() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackUtils.getRollbackManager();
            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            // The app should now be available for rollback.
            RollbackInfo rollback = waitForAvailableRollback(TestApp.A);
            assertThat(rollback).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            // Expire the rollback.
            rm.expireRollbackForPackage(TestApp.A);

            // The rollback should no longer be available.
            assertThat(getUniqueRollbackInfoForPackage(
                        rm.getAvailableRollbacks(), TestApp.A)).isNull();
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that app user data is rolled back.
     */
    @Test
    public void testUserDataRollback() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            processUserData(TestApp.A);
            Install.single(TestApp.A2).setEnableRollback().commit();
            processUserData(TestApp.A);

            RollbackInfo rollback = waitForAvailableRollback(TestApp.A);
            RollbackUtils.rollback(rollback.getRollbackId());
            processUserData(TestApp.A);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test rollback of apks involving splits.
     */
    @Test
    public void testRollbackWithSplits() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.ASplit1).commit();
            processUserData(TestApp.A);

            Install.single(TestApp.ASplit2).setEnableRollback().commit();
            processUserData(TestApp.A);

            RollbackInfo rollback = waitForAvailableRollback(TestApp.A);
            RollbackUtils.rollback(rollback.getRollbackId());
            processUserData(TestApp.A);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
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
        assertThat(broadcastReceiver.poll(5, TimeUnit.SECONDS)).isNull();

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
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            // Prep installation of the test apps.
            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            Uninstall.packages(TestApp.B);
            Install.single(TestApp.B1).commit();
            Install.single(TestApp.B2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            // Both test apps should now be available for rollback, and the
            // RollbackInfo returned for the rollbacks should be correct.
            RollbackInfo rollbackA = waitForAvailableRollback(TestApp.A);
            assertThat(rollbackA).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            RollbackInfo rollbackB = waitForAvailableRollback(TestApp.B);
            assertThat(rollbackB).packagesContainsExactly(
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            // Executing rollback should roll back the correct package.
            RollbackUtils.rollback(rollbackA.getRollbackId());
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            RollbackUtils.rollback(rollbackB.getRollbackId());
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(1);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
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
        RollbackManager rm = RollbackUtils.getRollbackManager();

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
            rm.expireRollbackForPackage(TestApp.A);
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
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES);

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

            Install.single(TestApp.A2).setEnableRollback().commit();

            // We expect v2 of the app was installed, but rollback has not
            // been enabled.
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackUtils.getRollbackManager();
            assertThat(
                getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TestApp.A)).isNull();
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test that you cannot enable rollback for a non-module package when
     * holding the MANAGE_ROLLBACKS permission.
     */
    @Test
    public void testNonModuleEnableRollback() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

            Install.single(TestApp.A2).setEnableRollback().commit();

            // We expect v2 of the app was installed, but rollback has not
            // been enabled because the test app is not a module.
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            RollbackManager rm = RollbackUtils.getRollbackManager();
            assertThat(
                getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TestApp.A)).isNull();
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test rollback of multi-package installs is implemented.
     */
    @Test
    public void testMultiPackage() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackUtils.getRollbackManager();

            // Prep installation of the test apps.
            Uninstall.packages(TestApp.A, TestApp.B);
            Install.multi(TestApp.A1, TestApp.B1).commit();
            processUserData(TestApp.A);
            processUserData(TestApp.B);
            Install.multi(TestApp.A2, TestApp.B2).setEnableRollback().commit();
            processUserData(TestApp.A);
            processUserData(TestApp.B);
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            // TestApp.A should now be available for rollback.
            RollbackInfo rollback = waitForAvailableRollback(TestApp.A);
            assertThat(rollback).isNotNull();
            assertThat(rollback).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1),
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            // Rollback the app. It should cause both test apps to be rolled
            // back.
            RollbackUtils.rollback(rollback.getRollbackId());
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(1);

            // We should see recent rollbacks listed for both A and B.
            Thread.sleep(1000);
            RollbackInfo rollbackA = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TestApp.A);

            RollbackInfo rollbackB = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TestApp.B);
            assertThat(rollback).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1),
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            assertThat(rollbackA).hasRollbackId(rollbackB.getRollbackId());

            processUserData(TestApp.A);
            processUserData(TestApp.B);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
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
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackUtils.getRollbackManager();

            Uninstall.packages(TestApp.A, TestApp.B);
            Install.single(TestApp.A1).commit();
            // We should fail to enable rollback here because TestApp B is not
            // already installed.
            Install.multi(TestApp.A2, TestApp.B2).setEnableRollback().commit();

            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            assertThat(getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TestApp.A)).isNull();
            assertThat(getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TestApp.B)).isNull();
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    @Test
    @Ignore("b/120200473")
    /**
     * Test rollback when app is updated to its same version.
     */
    public void testSameVersionUpdate() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);
            RollbackManager rm = RollbackUtils.getRollbackManager();

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.A2).setEnableRollback().commit();
            Install.single(TestApp.ACrashing2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                    rm.getAvailableRollbacks(), TestApp.A);
            assertThat(rollback).isNotNull();
            assertThat(rollback).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A2));

            RollbackUtils.rollback(rollback.getRollbackId());
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            rollback = getUniqueRollbackInfoForPackage(
                    rm.getRecentlyCommittedRollbacks(), TestApp.A);
            assertThat(rollback).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A2));
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test bad update automatic rollback.
     */
    @Test
    public void testBadUpdateRollback() throws Exception {
        Context context = InstrumentationRegistry.getContext();
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.FORCE_STOP_PACKAGES,
                    Manifest.permission.RESTART_PACKAGES);

            // Prep installation of the test apps.
            Uninstall.packages(TestApp.A, TestApp.B);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.ACrashing2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            Install.single(TestApp.B1).commit();
            Install.single(TestApp.B2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            // Both test apps should now be available for rollback, and the
            // targetPackage returned for rollback should be correct.
            RollbackInfo rollbackA = waitForAvailableRollback(TestApp.A);
            assertThat(rollbackA).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            RollbackInfo rollbackB = waitForAvailableRollback(TestApp.B);
            assertThat(rollbackB).packagesContainsExactly(
                    Rollback.from(TestApp.B2).to(TestApp.B1));

            // Register rollback committed receiver
            RollbackBroadcastReceiver rollbackReceiver = new RollbackBroadcastReceiver();

            // Crash TestApp.A PackageWatchdog#TRIGGER_FAILURE_COUNT times to trigger rollback
            RollbackUtils.sendCrashBroadcast(TestApp.A, 5);

            // Verify we received a broadcast for the rollback.
            rollbackReceiver.take();

            // TestApp.A is automatically rolled back by the RollbackPackageHealthObserver
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
            // Instrumented app is still the package installer
            String installer = context.getPackageManager().getInstallerPackageName(TestApp.A);
            assertThat(installer).isEqualTo(INSTRUMENTED_APP);
            // TestApp.B is untouched
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test race between roll back and roll forward.
     */
    @Test
    public void testRollForwardRace() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.MANAGE_ROLLBACKS);

            RollbackManager rm = RollbackUtils.getRollbackManager();

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            Install.single(TestApp.A2).setEnableRollback().commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            RollbackInfo rollback = waitForAvailableRollback(TestApp.A);
            assertThat(rollback).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));

            // Install a new version of package A, then immediately rollback
            // the previous version. We expect the rollback to fail, because
            // it is no longer available.
            // There are a couple different ways this could fail depending on
            // thread interleaving, so don't ignore flaky failures.
            Install.single(TestApp.A3).commit();
            try {
                RollbackUtils.rollback(rollback.getRollbackId());
                // Note: Don't ignore flaky failures here.
                fail("Expected rollback to fail, but it did not.");
            } catch (AssertionError e) {
                Log.i(TAG, "Note expected failure: ", e);
                // Expected
            }

            // Note: Don't ignore flaky failures here.
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(3);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testEnableRollbackTimeoutFailsRollback() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.MANAGE_ROLLBACKS,
                    Manifest.permission.WRITE_DEVICE_CONFIG);

            //setting the timeout to a very short amount that will definitely be triggered
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS,
                    Long.toString(0), false /* makeDefault*/);
            RollbackManager rm = RollbackUtils.getRollbackManager();

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            waitForUnavailableRollback(TestApp.A);

            // Block the RollbackManager to make extra sure it will not be
            // able to enable the rollback in time.
            rm.blockRollbackManager(TimeUnit.SECONDS.toMillis(1));
            Install.single(TestApp.A2).setEnableRollback().commit();

            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            // Give plenty of time for RollbackManager to unblock and attempt
            // to make the rollback available before asserting that the
            // rollback was not made available.
            Thread.sleep(TimeUnit.SECONDS.toMillis(2));
            assertThat(
                getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(), TestApp.A)).isNull();
        } finally {
            //setting the timeout back to default
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS,
                    null, false /* makeDefault*/);
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testEnableRollbackTimeoutFailsRollback_MultiPackage() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS,
                    Manifest.permission.MANAGE_ROLLBACKS,
                    Manifest.permission.WRITE_DEVICE_CONFIG);

            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS,
                    Long.toString(5000), false /* makeDefault*/);
            RollbackManager rm = RollbackUtils.getRollbackManager();

            Uninstall.packages(TestApp.A, TestApp.B);
            Install.multi(TestApp.A1, TestApp.B1).commit();
            waitForUnavailableRollback(TestApp.A);

            // Block the 2nd session for 10s so it will not be able to enable the rollback in time.
            rm.blockRollbackManager(TimeUnit.SECONDS.toMillis(0));
            rm.blockRollbackManager(TimeUnit.SECONDS.toMillis(10));
            Install.multi(TestApp.A2, TestApp.B2).setEnableRollback().commit();

            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
            assertThat(InstallUtils.getInstalledVersion(TestApp.B)).isEqualTo(2);

            // Give plenty of time for RollbackManager to unblock and attempt
            // to make the rollback available before asserting that the
            // rollback was not made available.
            Thread.sleep(TimeUnit.SECONDS.toMillis(2));

            List<RollbackInfo> available = rm.getAvailableRollbacks();
            assertThat(getUniqueRollbackInfoForPackage(available, TestApp.A)).isNull();
            assertThat(getUniqueRollbackInfoForPackage(available, TestApp.B)).isNull();
        } finally {
            //setting the timeout back to default
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS,
                    null, false /* makeDefault*/);
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    /**
     * Test we can't enable rollback for non-whitelisted app without
     * TEST_MANAGE_ROLLBACKS permission
     */
    @Test
    public void testNonRollbackWhitelistedApp() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.MANAGE_ROLLBACKS);

            Uninstall.packages(TestApp.A);
            Install.single(TestApp.A1).commit();
            assertThat(RollbackUtils.getAvailableRollback(TestApp.A)).isNull();

            Install.single(TestApp.A2).setEnableRollback().commit();
            Thread.sleep(TimeUnit.SECONDS.toMillis(2));
            assertThat(RollbackUtils.getAvailableRollback(TestApp.A)).isNull();
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testRollbackDataPolicy() throws Exception {
        try {
            InstallUtils.adoptShellPermissionIdentity(
                    Manifest.permission.INSTALL_PACKAGES,
                    Manifest.permission.DELETE_PACKAGES,
                    Manifest.permission.TEST_MANAGE_ROLLBACKS);

            Uninstall.packages(TestApp.A, TestApp.B);
            Install.multi(TestApp.A1, TestApp.B1).commit();
            // Write user data version = 1
            InstallUtils.processUserData(TestApp.A);
            InstallUtils.processUserData(TestApp.B);

            Install a2 = Install.single(TestApp.A2)
                    .setEnableRollback(PackageManager.RollbackDataPolicy.WIPE);
            Install b2 = Install.single(TestApp.B2)
                    .setEnableRollback(PackageManager.RollbackDataPolicy.RESTORE);
            Install.multi(a2, b2).setEnableRollback().commit();
            // Write user data version = 2
            InstallUtils.processUserData(TestApp.A);
            InstallUtils.processUserData(TestApp.B);

            RollbackInfo info = RollbackUtils.getAvailableRollback(TestApp.A);
            RollbackUtils.rollback(info.getRollbackId());
            // Read user data version from userdata.txt
            // A's user data version is -1 for user data is wiped.
            // B's user data version is 1 as rollback committed.
            assertThat(InstallUtils.getUserDataVersion(TestApp.A)).isEqualTo(-1);
            assertThat(InstallUtils.getUserDataVersion(TestApp.B)).isEqualTo(1);
        } finally {
            InstallUtils.dropShellPermissionIdentity();
        }
    }
}
