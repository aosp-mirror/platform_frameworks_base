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
            Install.single(TestApp.A2).setEnableRollback().setRollbackImpactLevel(
                    PackageManager.ROLLBACK_USER_IMPACT_HIGH).commit();
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

            // The app should now be available for rollback.
            RollbackInfo available = waitForAvailableRollback(TestApp.A);
            assertThat(available).isNotStaged();
            assertThat(available).packagesContainsExactly(
                    Rollback.from(TestApp.A2).to(TestApp.A1));
            assertThat(available.getRollbackImpactLevel()).isEqualTo(
                    PackageManager.ROLLBACK_USER_IMPACT_HIGH);

            // We should not have received any rollback requests yet.
            // TODO: Possibly flaky if, by chance, some other app on device
            // happens to be rolled back at the same time?
            assertThat(broadcastReceiver.poll(0, TimeUnit.SECONDS)).isNull();

            // Roll back the app.
            RollbackUtils.rollback(available.getRollbackId());
            assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

            UserManager um = (UserManager) context.getSystemService(context.USER_SERVICE);
            List<Integer> userIds = um.getAliveUsers()
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
            assertThat(rollbackB.getRollbackImpactLevel()).isEqualTo(
                    PackageManager.ROLLBACK_USER_IMPACT_LOW);

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
}
