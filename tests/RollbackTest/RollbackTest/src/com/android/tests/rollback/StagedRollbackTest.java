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
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.ParcelFileDescriptor;
import android.provider.DeviceConfig;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.LocalIntentSender;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;
import com.android.cts.rollback.lib.Rollback;
import com.android.cts.rollback.lib.RollbackUtils;
import com.android.internal.R;

import libcore.io.IoUtils;

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

    private static final String NETWORK_STACK_CONNECTOR_CLASS =
            "android.net.INetworkStackConnector";
    private static final String PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT =
            "watchdog_trigger_failure_count";
    private static final String PROPERTY_WATCHDOG_REQUEST_TIMEOUT_MILLIS =
            "watchdog_request_timeout_millis";

    private static final TestApp NETWORK_STACK = new TestApp("NetworkStack",
            getNetworkStackPackageName(), -1, false, findNetworkStackApk());

    private static File findNetworkStackApk() {
        final File apk = new File("/system/priv-app/NetworkStack/NetworkStack.apk");
        if (apk.isFile()) {
            return apk;
        }
        return new File("/system/priv-app/NetworkStackNext/NetworkStackNext.apk");
    }

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

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                Integer.toString(5), false);
        RollbackUtils.sendCrashBroadcast(TestApp.A, 4);
        // Sleep for a while to make sure we don't trigger rollback
        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
    }

    /**
     * Test rollbacks of staged installs involving only apks with bad update.
     * Trigger rollback phase. This is expected to fail due to watchdog
     * rebooting the test out from under it.
     */
    @Test
    public void testBadApkOnly_Phase3() throws Exception {
        // One more crash to trigger rollback
        RollbackUtils.sendCrashBroadcast(TestApp.A, 1);

        // We expect the device to be rebooted automatically. Wait for that to happen.
        // This device method will fail and the host will catch the assertion.
        // If reboot doesn't happen, the host will fail the assertion.
        Thread.sleep(TimeUnit.SECONDS.toMillis(120));
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

    /**
     * Stage install an apk with rollback that will be later triggered by unattributable crash.
     */
    @Test
    public void testNativeWatchdogTriggersRollback_Phase1() throws Exception {
        Uninstall.packages(TestApp.A);
        Install.single(TestApp.A1).commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

        Install.single(TestApp.A2).setEnableRollback().setStaged().commit();
    }

    /**
     * Verify the rollback is available.
     */
    @Test
    public void testNativeWatchdogTriggersRollback_Phase2() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                TestApp.A)).isNotNull();
    }

    /**
     * Verify the rollback is committed after crashing.
     */
    @Test
    public void testNativeWatchdogTriggersRollback_Phase3() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                TestApp.A)).isNotNull();
    }

    @Test
    public void testNetworkFailedRollback_Phase1() throws Exception {
        // Remove available rollbacks and uninstall NetworkStack on /data/
        RollbackManager rm = RollbackUtils.getRollbackManager();
        String networkStack = getNetworkStackPackageName();

        rm.expireRollbackForPackage(networkStack);
        uninstallNetworkStackPackage();

        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        networkStack)).isNull();

        // Reduce health check deadline
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ROLLBACK,
                PROPERTY_WATCHDOG_REQUEST_TIMEOUT_MILLIS,
                Integer.toString(120000), false);
        // Simulate re-installation of new NetworkStack with rollbacks enabled
        installNetworkStackPackage();
    }

    @Test
    public void testNetworkFailedRollback_Phase2() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        getNetworkStackPackageName())).isNotNull();

        // Sleep for < health check deadline
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
        // Verify rollback was not executed before health check deadline
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                        getNetworkStackPackageName())).isNull();
    }

    @Test
    public void testNetworkFailedRollback_Phase3() throws Exception {
        // Sleep for > health check deadline (120s to trigger rollback + 120s to reboot)
        // The device is expected to reboot during sleeping. This device method will fail and
        // the host will catch the assertion. If reboot doesn't happen, the host will fail the
        // assertion.
        Thread.sleep(TimeUnit.SECONDS.toMillis(240));
    }

    @Test
    public void testNetworkFailedRollback_Phase4() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                        getNetworkStackPackageName())).isNotNull();
    }

    private static String getNetworkStackPackageName() {
        Intent intent = new Intent(NETWORK_STACK_CONNECTOR_CLASS);
        ComponentName comp = intent.resolveSystemService(
                InstrumentationRegistry.getInstrumentation().getContext().getPackageManager(), 0);
        return comp.getPackageName();
    }

    private static void installNetworkStackPackage() throws Exception {
        Install.single(NETWORK_STACK).setStaged().setEnableRollback()
                .addInstallFlags(PackageManager.INSTALL_REPLACE_EXISTING).commit();
    }

    private static void uninstallNetworkStackPackage() {
        // Uninstall the package as a privileged user so we won't fail due to permission.
        runShellCommand("pm uninstall " + getNetworkStackPackageName());
    }

    @Test
    public void testPreviouslyAbandonedRollbacks_Phase1() throws Exception {
        Uninstall.packages(TestApp.A);
        Install.single(TestApp.A1).commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);

        int sessionId = Install.single(TestApp.A2).setStaged().setEnableRollback().commit();
        PackageInstaller pi = InstrumentationRegistry.getInstrumentation().getContext()
                .getPackageManager().getPackageInstaller();
        pi.abandonSession(sessionId);

        // Remove the first intent sender result, so that the next staged install session does not
        // erroneously think that it has itself been abandoned.
        // TODO(b/136260017): Restructure LocalIntentSender to negate the need for this step.
        LocalIntentSender.getIntentSenderResult();
        Install.single(TestApp.A2).setStaged().setEnableRollback().commit();
    }

    @Test
    public void testPreviouslyAbandonedRollbacks_Phase2() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        InstallUtils.processUserData(TestApp.A);

        RollbackManager rm = RollbackUtils.getRollbackManager();
        RollbackInfo rollback = getUniqueRollbackInfoForPackage(
                rm.getAvailableRollbacks(), TestApp.A);
        RollbackUtils.rollback(rollback.getRollbackId());
    }

    @Test
    public void testPreviouslyAbandonedRollbacks_Phase3() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        InstallUtils.processUserData(TestApp.A);
        Uninstall.packages(TestApp.A);
    }

    @Test
    public void testNetworkPassedDoesNotRollback_Phase1() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        String networkStack = getNetworkStackPackageName();

        rm.expireRollbackForPackage(networkStack);
        uninstallNetworkStackPackage();

        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        networkStack)).isNull();
    }

    @Test
    public void testNetworkPassedDoesNotRollback_Phase2() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getAvailableRollbacks(),
                        getNetworkStackPackageName())).isNotNull();
    }

    @Test
    public void testNetworkPassedDoesNotRollback_Phase3() throws Exception {
        RollbackManager rm = RollbackUtils.getRollbackManager();
        assertThat(getUniqueRollbackInfoForPackage(rm.getRecentlyCommittedRollbacks(),
                        getNetworkStackPackageName())).isNull();
    }

    private static String getModuleMetadataPackageName() {
        return InstrumentationRegistry.getInstrumentation().getContext()
                .getResources().getString(R.string.config_defaultModuleMetadataProvider);
    }

    @Test
    public void testRollbackWhitelistedApp_Phase1() throws Exception {
        // Remove available rollbacks
        String pkgName = getModuleMetadataPackageName();
        RollbackUtils.getRollbackManager().expireRollbackForPackage(pkgName);
        assertThat(RollbackUtils.getAvailableRollback(pkgName)).isNull();

        // Overwrite existing permissions. We don't want TEST_MANAGE_ROLLBACKS which allows us
        // to enable rollback for any app
        InstallUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.MANAGE_ROLLBACKS);

        // Re-install a whitelisted app with rollbacks enabled
        String filePath = InstrumentationRegistry.getInstrumentation().getContext()
                .getPackageManager().getPackageInfo(pkgName, 0).applicationInfo.sourceDir;
        TestApp app = new TestApp("ModuleMetadata", pkgName, -1, false, new File(filePath));
        Install.single(app).setStaged().setEnableRollback()
                .addInstallFlags(PackageManager.INSTALL_REPLACE_EXISTING).commit();
    }

    @Test
    public void testRollbackWhitelistedApp_Phase2() throws Exception {
        assertThat(RollbackUtils.getAvailableRollback(getModuleMetadataPackageName())).isNotNull();
    }

    private static void runShellCommand(String cmd) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(cmd);
        IoUtils.closeQuietly(pfd);
    }
}
