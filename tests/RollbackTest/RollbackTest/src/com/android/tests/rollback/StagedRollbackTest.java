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

import android.Manifest;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.provider.DeviceConfig;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.rollback.lib.Rollback;
import com.android.cts.rollback.lib.RollbackUtils;
import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.concurrent.TimeUnit;

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
    private static final String PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT =
            "watchdog_trigger_failure_count";

    /**
     * Adopts common shell permissions needed for rollback tests.
     */
    @Before
    public void adoptShellPermissions() {
        InstallUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.DELETE_PACKAGES,
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                Manifest.permission.FORCE_STOP_PACKAGES,
                Manifest.permission.WRITE_DEVICE_CONFIG);
    }

    /**
     * Drops shell permissions needed for rollback tests.
     */
    @After
    public void dropShellPermissions() {
        InstallUtils.dropShellPermissionIdentity();
    }

    @Test
    public void testPreviouslyAbandonedRollbacks_Phase1_InstallAndAbandon() throws Exception {
        Install.single(TestApp.A1).commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

        int sessionId = Install.single(TestApp.A2).setStaged().setEnableRollback().commit();
        PackageInstaller pi = InstrumentationRegistry.getInstrumentation().getContext()
                .getPackageManager().getPackageInstaller();
        pi.abandonSession(sessionId);

        Install.single(TestApp.A2).setStaged().setEnableRollback().commit();
    }

    @Test
    public void testPreviouslyAbandonedRollbacks_Phase2_Rollback() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        InstallUtils.processUserData(TestApp.A);

        RollbackManager rm = RollbackUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getAvailableRollbacks(), TestApp.A);
        RollbackUtils.rollback(rollback.getRollbackId());
    }

    @Test
    public void testPreviouslyAbandonedRollbacks_Phase3_VerifyRollback() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        InstallUtils.processUserData(TestApp.A);
    }

    private static String getModuleMetadataPackageName() {
        return InstrumentationRegistry.getInstrumentation().getContext()
                .getResources().getString(R.string.config_defaultModuleMetadataProvider);
    }

    @Test
    public void testRollbackAllowlistedApp_Phase1_Install() throws Exception {
        // Remove available rollbacks
        String pkgName = getModuleMetadataPackageName();
        RollbackUtils.getRollbackManager().expireRollbackForPackage(pkgName);
        assertThat(RollbackUtils.getAvailableRollback(pkgName)).isNull();

        // Overwrite existing permissions. We don't want TEST_MANAGE_ROLLBACKS which allows us
        // to enable rollback for any app
        InstallUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.MANAGE_ROLLBACKS);

        // Re-install a allowlisted app with rollbacks enabled
        String filePath = InstrumentationRegistry.getInstrumentation().getContext()
                .getPackageManager().getPackageInfo(pkgName, 0).applicationInfo.sourceDir;
        TestApp app = new TestApp("ModuleMetadata", pkgName, -1, false, new File(filePath));
        Install.single(app).setStaged().setEnableRollback()
                .addInstallFlags(PackageManager.INSTALL_REPLACE_EXISTING).commit();
    }

    @Test
    public void testRollbackAllowlistedApp_Phase2_VerifyInstall() throws Exception {
        assertThat(RollbackUtils.getAvailableRollback(getModuleMetadataPackageName())).isNotNull();
    }

    @Test
    public void expireRollbacks() throws Exception {
        // testNativeWatchdogTriggersRollback will fail if multiple staged sessions are
        // committed on a device which doesn't support checkpoint. Let's clean up all rollbacks
        // so there is only one rollback to commit when testing native crashes.
        RollbackManager rm = RollbackUtils.getRollbackManager();
        rm.getAvailableRollbacks().stream().flatMap(info -> info.getPackages().stream())
                .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
        rm.getRecentlyCommittedRollbacks().stream().flatMap(info -> info.getPackages().stream())
                .map(info -> info.getPackageName()).forEach(rm::expireRollbackForPackage);
        assertThat(rm.getAvailableRollbacks()).isEmpty();
        assertThat(rm.getRecentlyCommittedRollbacks()).isEmpty();
    }

    private static final String APK_IN_APEX_TESTAPEX_NAME = "com.android.apex.apkrollback.test";
    private static final TestApp TEST_APEX_WITH_APK_V1 = new TestApp("TestApexWithApkV1",
            APK_IN_APEX_TESTAPEX_NAME, 1, /*isApex*/true, APK_IN_APEX_TESTAPEX_NAME + "_v1.apex");
    private static final TestApp TEST_APEX_WITH_APK_V2 = new TestApp("TestApexWithApkV2",
            APK_IN_APEX_TESTAPEX_NAME, 2, /*isApex*/true, APK_IN_APEX_TESTAPEX_NAME + "_v2.apex");
    private static final TestApp TEST_APEX_WITH_APK_V2_CRASHING = new TestApp(
            "TestApexWithApkV2Crashing", APK_IN_APEX_TESTAPEX_NAME, 2, /*isApex*/true,
            APK_IN_APEX_TESTAPEX_NAME + "_v2Crashing.apex");

    /**
     * Installs an apex with an apk that can crash.
     */
    @Test
    public void testRollbackApexWithApkCrashing_Phase1_Install() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        int sessionId = Install.single(TEST_APEX_WITH_APK_V2_CRASHING).setStaged()
                .setEnableRollback().commit();
        InstallUtils.waitForSessionReady(sessionId);
    }

    /**
     * Verifies rollback has been enabled successfully. Then makes TestApp.A crash.
     */
    @Test
    public void testRollbackApexWithApkCrashing_Phase2_Crash() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(APK_IN_APEX_TESTAPEX_NAME)).isEqualTo(2);
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

        RollbackInfo available = RollbackUtils.getAvailableRollback(APK_IN_APEX_TESTAPEX_NAME);
        assertThat(available).isStaged();
        assertThat(available).packagesContainsExactly(
                Rollback.from(TEST_APEX_WITH_APK_V2).to(TEST_APEX_WITH_APK_V1),
                Rollback.from(TestApp.A, 0).to(TestApp.A1));

        // Crash TestApp.A PackageWatchdog#TRIGGER_FAILURE_COUNT-1 times
        RollbackUtils.sendCrashBroadcast(TestApp.A, 4);
        // Sleep for a while to make sure we don't trigger rollback
        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
    }

    @Test
    public void testRollbackApexWithApkCrashing_Phase3_VerifyRollback() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(APK_IN_APEX_TESTAPEX_NAME)).isEqualTo(1);
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
    }

    @Test
    public void testWatchdogMonitorsAcrossReboots_Phase1_Install() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);
        Install.single(TestApp.A1).commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        InstallUtils.processUserData(TestApp.A);

        Install.single(TestApp.ACrashing2).setEnableRollback().setStaged().commit();
    }

    @Test
    public void testWatchdogMonitorsAcrossReboots_Phase2_VerifyInstall() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);

        // Trigger rollback of test app.
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(5), false);

        // The final crash that causes rollback will come from the host side.
        RollbackUtils.sendCrashBroadcast(TestApp.A, 4);
    }

    @Test
    public void testWatchdogMonitorsAcrossReboots_Phase3_VerifyRollback() {
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
    public void hasMainlineModule() throws Exception {
        String pkgName = getModuleMetadataPackageName();
        boolean existed =  InstrumentationRegistry.getInstrumentation().getContext()
                .getPackageManager().getModuleInfo(pkgName, 0) != null;
        assertThat(existed).isTrue();
    }
}
