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

import static com.android.cts.rollback.lib.RollbackInfoSubject.assertThat;
import static com.android.cts.rollback.lib.RollbackUtils.getUniqueRollbackInfoForPackage;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.Manifest;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.LocalIntentSender;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;
import com.android.cts.rollback.lib.Rollback;
import com.android.cts.rollback.lib.RollbackUtils;
import com.android.internal.R;

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

    private static final String NETWORK_STACK_CONNECTOR_CLASS =
            "android.net.INetworkStackConnector";

    private static final String MODULE_META_DATA_PACKAGE = getModuleMetadataPackageName();

    /**
     * Adopts common shell permissions needed for rollback tests.
     */
    @Before
    public void adoptShellPermissions() {
        InstallUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.DELETE_PACKAGES,
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                Manifest.permission.FORCE_STOP_PACKAGES);
    }

    /**
     * Drops shell permissions needed for rollback tests.
     */
    @After
    public void dropShellPermissions() {
        InstallUtils.dropShellPermissionIdentity();
    }

    /**
     * Test rollbacks of staged installs involving only apks with bad update.
     * Enable rollback phase.
     */
    @Test
    public void testBadApkOnly_Phase1() throws Exception {
        Uninstall.packages(TestApp.A);
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);

        Install.single(TestApp.A1).commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        InstallUtils.processUserData(TestApp.A);

        Install.single(TestApp.ACrashing2).setEnableRollback().setStaged().commit();
    }

    /**
     * Test rollbacks of staged installs involving only apks with bad update.
     * Confirm that rollback was successfully enabled.
     */
    @Test
    public void testBadApkOnly_Phase2() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        InstallUtils.processUserData(TestApp.A);

        RollbackManager rm = RollbackUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getAvailableRollbacks(), TestApp.A);
        assertThat(rollback).isNotNull();
        assertThat(rollback).packagesContainsExactly(
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(rollback.isStaged()).isTrue();
    }

    /**
     * Test rollbacks of staged installs involving only apks with bad update.
     * Trigger rollback phase. This is expected to fail due to watchdog
     * rebooting the test out from under it.
     */
    @Test
    public void testBadApkOnly_Phase3() throws Exception {
        // Crash TestApp.A PackageWatchdog#TRIGGER_FAILURE_COUNT times to trigger rollback
        RollbackUtils.sendCrashBroadcast(TestApp.A, 5);

        // We expect the device to be rebooted automatically. Wait for that to happen.
        Thread.sleep(30 * 1000);

        // Raise an error anyway if reboot didn't happen.
        fail("watchdog did not trigger reboot");
    }

    /**
     * Test rollbacks of staged installs involving only apks.
     * Confirm rollback phase.
     */
    @Test
    public void testBadApkOnly_Phase4() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        InstallUtils.processUserData(TestApp.A);

        RollbackManager rm = RollbackUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getRecentlyCommittedRollbacks(), TestApp.A);
        assertThat(rollback).isNotNull();
        assertThat(rollback).packagesContainsExactly(
                Rollback.from(TestApp.A2).to(TestApp.A1));
        assertThat(rollback).causePackagesContainsExactly(TestApp.ACrashing2);
        assertThat(rollback).isStaged();
        assertThat(rollback.getCommittedSessionId()).isNotEqualTo(-1);
    }

    @Test
    public void resetNetworkStack() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        String networkStack = getNetworkStackPackageName();

        rm.expireRollbackForPackage(networkStack);
        Uninstall.packages(networkStack);

        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        networkStack)).isNull();
    }

    /**
     * Stage install ModuleMetadata package to simulate a Mainline module update.
     */
    @Test
    public void testNativeWatchdogTriggersRollback_Phase1() throws Exception {
        resetModuleMetadataPackage();
        Context context = InstrumentationRegistry.getContext();
        PackageInfo metadataPackageInfo = context.getPackageManager().getPackageInfo(
                MODULE_META_DATA_PACKAGE, 0);
        String metadataApkPath = metadataPackageInfo.applicationInfo.sourceDir;
        assertThat(metadataApkPath).isNotNull();
        assertThat(metadataApkPath).isNotEqualTo("");

        runShellCommand("pm install "
                + "-r --enable-rollback --staged --wait "
                + metadataApkPath);
    }

    /**
     * Verify the rollback is available.
     */
    @Test
    public void testNativeWatchdogTriggersRollback_Phase2() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        MODULE_META_DATA_PACKAGE)).isNotNull();
    }

    /**
     * Verify the rollback is committed after crashing.
     */
    @Test
    public void testNativeWatchdogTriggersRollback_Phase3() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                        MODULE_META_DATA_PACKAGE)).isNotNull();
    }

    @Test
    public void assertNetworkStackRollbackAvailable() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        getNetworkStackPackageName())).isNotNull();
    }

    @Test
    public void assertNetworkStackRollbackCommitted() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                        getNetworkStackPackageName())).isNotNull();
    }

    @Test
    public void assertNoNetworkStackRollbackCommitted() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                        getNetworkStackPackageName())).isNull();
    }

    private String getNetworkStackPackageName() {
        Intent intent = new Intent(NETWORK_STACK_CONNECTOR_CLASS);
        ComponentName comp = intent.resolveSystemService(
                InstrumentationRegistry.getContext().getPackageManager(), 0);
        return comp.getPackageName();
    }

    @Test
    public void testPreviouslyAbandonedRollbacksEnableRollback() throws Exception {
        Uninstall.packages(TestApp.A);
        Install.single(TestApp.A1).commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

        int sessionId = Install.single(TestApp.A2).setStaged().setEnableRollback().commit();
        PackageInstaller pi = InstrumentationRegistry.getContext().getPackageManager()
                .getPackageInstaller();
        pi.abandonSession(sessionId);

        // Remove the first intent sender result, so that the next staged install session does not
        // erroneously think that it has itself been abandoned.
        // TODO(b/136260017): Restructure LocalIntentSender to negate the need for this step.
        LocalIntentSender.getIntentSenderResult();
        Install.single(TestApp.A2).setStaged().setEnableRollback().commit();
    }

    @Test
    public void testPreviouslyAbandonedRollbacksCommitRollback() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        InstallUtils.processUserData(TestApp.A);

        RollbackManager rm = RollbackUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getAvailableRollbacks(), TestApp.A);
        RollbackUtils.rollback(rollback.getRollbackId());
    }

    @Test
    public void testPreviouslyAbandonedRollbacksCheckUserdataRollback() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        InstallUtils.processUserData(TestApp.A);
        Uninstall.packages(TestApp.A);
    }

    @Nullable
    private static String getModuleMetadataPackageName() {
        String packageName = InstrumentationRegistry.getContext().getResources().getString(
                R.string.config_defaultModuleMetadataProvider);
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        return packageName;
    }

    private void resetModuleMetadataPackage() {
        RollbackManager rm = RollbackUtils.getRollbackManager();

        assertThat(MODULE_META_DATA_PACKAGE).isNotNull();
        rm.expireRollbackForPackage(MODULE_META_DATA_PACKAGE);

        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                MODULE_META_DATA_PACKAGE)).isNull();
    }

    private void runShellCommand(String cmd) {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(cmd);
    }
}
