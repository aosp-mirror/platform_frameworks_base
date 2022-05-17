/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tests.stagedinstallinternal;

import static com.android.cts.install.lib.InstallUtils.getPackageInstaller;
import static com.android.cts.shim.lib.ShimPackage.SHIM_APEX_PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.content.pm.ApexStagedEvent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManagerNative;
import android.content.pm.IStagedApexObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.StagedApexInfo;
import android.os.IBinder;
import android.os.ServiceManager;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;
import com.android.cts.install.lib.Uninstall;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

@RunWith(JUnit4.class)
public class StagedInstallInternalTest {
    private static final String APK_IN_APEX_TESTAPEX_NAME = "com.android.apex.apkrollback.test";
    private static final TestApp TEST_APEX_WITH_APK_V2 = new TestApp("TestApexWithApkV2",
            APK_IN_APEX_TESTAPEX_NAME, 2, /*isApex*/true, APK_IN_APEX_TESTAPEX_NAME + "_v2.apex");
    private static final TestApp APEX_WRONG_SHA_V2 = new TestApp(
            "ApexWrongSha2", SHIM_APEX_PACKAGE_NAME, 2, /* isApex= */ true,
            "com.android.apex.cts.shim.v2_wrong_sha.apex");
    private static final TestApp APEX_V2 = new TestApp(
            "ApexV2", SHIM_APEX_PACKAGE_NAME, 2, /* isApex= */ true,
            "com.android.apex.cts.shim.v2.apex");

    private static final String TEST_APEX_PACKAGE_NAME = "com.android.apex.test_package";
    private static final TestApp TEST_APEX_CLASSPATH = new TestApp("TestApex",
            TEST_APEX_PACKAGE_NAME, 1, /*isApex=*/true,
            "apex.apexd_test_classpath.apex");

    private static final String TEST_APEX_SYSTEM_SERVER_PACKAGE_NAME = "test_com.android.server";

    private File mTestStateFile = new File(
            InstrumentationRegistry.getInstrumentation().getContext().getFilesDir(),
            "stagedinstall_state");

    /**
     * Adopts common shell permissions needed for staged install tests.
     */
    @Before
    public void adoptShellPermissions() {
        InstallUtils.adoptShellPermissionIdentity(
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.DELETE_PACKAGES
        );
    }

    /**
     * Drops shell permissions needed for staged install tests.
     */
    @After
    public void dropShellPermissions() {
        InstallUtils.dropShellPermissionIdentity();
    }

    // This is marked as @Test to take advantage of @Before/@After methods of this class. Actual
    // purpose of this method to be called before and after each test case of
    // com.android.test.stagedinstall.host.StagedInstallTest to reduce tests flakiness.
    @Test
    public void cleanUp() throws Exception {
        Files.deleteIfExists(mTestStateFile.toPath());
        Uninstall.packages(TestApp.A, TestApp.B);
    }

    @Test
    public void testDuplicateApkInApexShouldFail_Commit() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        // Duplicate packages(TestApp.A) in TEST_APEX_WITH_APK_V2(apk-in-apex) and TestApp.A2(apk)
        // should fail to install.
        int sessionId = Install.multi(TEST_APEX_WITH_APK_V2, TestApp.A2).setStaged().commit();
        storeSessionId(sessionId);
    }

    @Test
    public void testDuplicateApkInApexShouldFail_Verify() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(1);
        int sessionId = retrieveLastSessionId();
        PackageInstaller.SessionInfo info =
                InstallUtils.getPackageInstaller().getSessionInfo(sessionId);
        assertThat(info.isStagedSessionFailed()).isTrue();
    }

    @Test
    public void testSystemServerRestartDoesNotAffectStagedSessions_Commit() throws Exception {
        int sessionId = Install.single(TestApp.A1).setStaged().commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testSystemServerRestartDoesNotAffectStagedSessions_Verify() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(-1);
        int sessionId = retrieveLastSessionId();
        assertSessionReady(sessionId);
    }

    @Test
    public void testAbandonStagedSessionShouldCleanUp() throws Exception {
        int id1 = Install.single(TestApp.A1).setStaged().createSession();
        InstallUtils.getPackageInstaller().abandonSession(id1);
        int id2 = Install.multi(TestApp.A1).setStaged().createSession();
        InstallUtils.getPackageInstaller().abandonSession(id2);
        int id3 = Install.single(TestApp.A1).setStaged().commit();
        InstallUtils.getPackageInstaller().abandonSession(id3);
        int id4 = Install.multi(TestApp.A1).setStaged().commit();
        InstallUtils.getPackageInstaller().abandonSession(id4);
    }

    @Test
    public void testStagedSessionShouldCleanUpOnVerificationFailure() throws Exception {
        // APEX verification
        InstallUtils.commitExpectingFailure(AssertionError.class, "apexd verification failed",
                Install.single(APEX_WRONG_SHA_V2).setStaged());
        InstallUtils.commitExpectingFailure(AssertionError.class, "apexd verification failed",
                Install.multi(APEX_WRONG_SHA_V2, TestApp.A1).setStaged());
        // APK verification
        Install.single(TestApp.A2).commit();
        assertThat(InstallUtils.getInstalledVersion(TestApp.A)).isEqualTo(2);
        InstallUtils.commitExpectingFailure(AssertionError.class, "Downgrade detected",
                Install.single(TestApp.A1).setStaged());
        InstallUtils.commitExpectingFailure(AssertionError.class, "Downgrade detected",
                Install.multi(TestApp.A1, TestApp.B1).setStaged());
    }

    @Test
    public void testStagedSessionShouldCleanUpOnOnSuccess_Commit() throws Exception {
        int sessionId = Install.single(TestApp.A1).setStaged().commit();
        storeSessionId(sessionId);
    }

    @Test
    public void testStagedSessionShouldCleanUpOnOnSuccess_Verify() throws Exception {
        int sessionId = retrieveLastSessionId();
        PackageInstaller.SessionInfo info = InstallUtils.getStagedSessionInfo(sessionId);
        assertThat(info).isNotNull();
        assertThat(info.isStagedSessionApplied()).isTrue();
    }

    @Test
    public void testStagedSessionShouldCleanUpOnOnSuccessMultiPackage_Commit() throws Exception {
        int sessionId = Install.multi(TestApp.A1, TestApp.Apex2).setStaged().commit();
        storeSessionId(sessionId);
    }

    @Test
    public void testStagedInstallationShouldCleanUpOnValidationFailure() throws Exception {
        InstallUtils.commitExpectingFailure(AssertionError.class, "INSTALL_FAILED_INVALID_APK",
                Install.single(TestApp.AIncompleteSplit).setStaged());
    }

    @Test
    public void testStagedInstallationShouldCleanUpOnValidationFailureMultiPackage()
            throws Exception {
        InstallUtils.commitExpectingFailure(AssertionError.class, "INSTALL_FAILED_INVALID_APK",
                Install.multi(TestApp.AIncompleteSplit, TestApp.B1, TestApp.Apex1).setStaged());
    }

    @Test
    public void testFailStagedSessionIfStagingDirectoryDeleted_Commit() throws Exception {
        int sessionId = Install.multi(TestApp.A1, TestApp.Apex1).setStaged().commit();
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testFailStagedSessionIfStagingDirectoryDeleted_Verify() throws Exception {
        int sessionId = retrieveLastSessionId();
        PackageInstaller.SessionInfo info =
                InstallUtils.getPackageInstaller().getSessionInfo(sessionId);
        assertThat(info.isStagedSessionFailed()).isTrue();
    }

    @Test
    public void testApexActivationFailureIsCapturedInSession_Commit() throws Exception {
        int sessionId = Install.single(TestApp.Apex1).setStaged().commit();
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testApexActivationFailureIsCapturedInSession_Verify() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionFailedWithMessage(sessionId, "has unexpected SHA512 hash");
    }

    @Test
    public void testActiveApexIsRevertedOnCheckpointRollback_Prepare() throws Exception {
        int sessionId = Install.single(TestApp.Apex2).setStaged().commit();
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testActiveApexIsRevertedOnCheckpointRollback_Commit() throws Exception {
        // Verify apex installed during preparation was successful
        int sessionId = retrieveLastSessionId();
        assertSessionApplied(sessionId);
        assertThat(InstallUtils.getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(2);
        // Commit a new staged session
        sessionId = Install.single(TestApp.Apex3).setStaged().commit();
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
    }

    @Test
    public void testActiveApexIsRevertedOnCheckpointRollback_VerifyPostReboot() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionFailed(sessionId);
        assertThat(InstallUtils.getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(2);
    }

    @Test
    public void testApexIsNotActivatedIfNotInCheckpointMode_Commit() throws Exception {
        int sessionId = Install.single(TestApp.Apex2).setStaged().commit();
        assertSessionReady(sessionId);
        storeSessionId(sessionId);
        assertThat(InstallUtils.getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
    }

    @Test
    public void testApexIsNotActivatedIfNotInCheckpointMode_VerifyPostReboot() throws Exception {
        int sessionId = retrieveLastSessionId();
        assertSessionFailed(sessionId);
        assertThat(InstallUtils.getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
    }

    @Test
    public void testApexInstallerNotInAllowListCanNotInstall_staged() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
        // We don't really care which APEX we are trying to install here, since the session creation
        // should fail immediately.
        InstallUtils.commitExpectingFailure(
                SecurityException.class,
                "Installer not allowed to commit staged install",
                Install.single(APEX_WRONG_SHA_V2).setBypassStangedInstallerCheck(false)
                        .setStaged());
    }

    @Test
    public void testApexInstallerNotInAllowListCanNotInstall_nonStaged() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);
        // We don't really care which APEX we are trying to install here, since the session creation
        // should fail immediately.
        InstallUtils.commitExpectingFailure(
                SecurityException.class,
                "Installer not allowed to commit non-staged APEX install",
                Install.single(APEX_WRONG_SHA_V2).setBypassStangedInstallerCheck(false));
    }

    @Test
    public void testApexNotInAllowListCanNotInstall_staged() throws Exception {
        assertThat(InstallUtils.getInstalledVersion("test.apex.rebootless")).isEqualTo(1);
        TestApp apex = new TestApp("apex", "test.apex.rebootless", 2,
                /* isApex= */ true, "test.rebootless_apex_v2.apex");
        String expectedFailMessage = "Update of APEX package test.apex.rebootless is not allowed "
                + "for com.android.tests.stagedinstallinternal";
        InstallUtils.commitExpectingFailure(
                AssertionError.class, expectedFailMessage,
                Install.single(apex).setBypassAllowedApexUpdateCheck(false).setStaged());
        InstallUtils.commitExpectingFailure(
                AssertionError.class, expectedFailMessage,
                Install.multi(apex).setBypassAllowedApexUpdateCheck(false).setStaged());
    }

    @Test
    public void testApexNotInAllowListCanNotInstall_nonStaged() throws Exception {
        assertThat(InstallUtils.getInstalledVersion("test.apex.rebootless")).isEqualTo(1);
        TestApp apex = new TestApp("apex", "test.apex.rebootless", 2,
                /* isApex= */ true, "test.rebootless_apex_v2.apex");
        String expectedFailMessage = "Update of APEX package test.apex.rebootless is not allowed "
                + "for com.android.tests.stagedinstallinternal";
        InstallUtils.commitExpectingFailure(
                AssertionError.class, expectedFailMessage,
                Install.single(apex).setBypassAllowedApexUpdateCheck(false));
        InstallUtils.commitExpectingFailure(
                AssertionError.class, expectedFailMessage,
                Install.multi(apex).setBypassAllowedApexUpdateCheck(false));
    }

    @Test
    public void testVendorApexWrongInstaller_staged() throws Exception {
        assertThat(InstallUtils.getInstalledVersion("test.apex.rebootless")).isEqualTo(1);
        TestApp apex = new TestApp("apex", "test.apex.rebootless", 2,
                /* isApex= */ true, "test.rebootless_apex_v2.apex");
        String expectedFailMessage = "Update of APEX package test.apex.rebootless is not allowed "
                + "for com.android.tests.stagedinstallinternal";
        InstallUtils.commitExpectingFailure(
                AssertionError.class, expectedFailMessage,
                Install.single(apex).setBypassAllowedApexUpdateCheck(false).setStaged());
        InstallUtils.commitExpectingFailure(
                AssertionError.class, expectedFailMessage,
                Install.multi(apex).setBypassAllowedApexUpdateCheck(false).setStaged());
    }

    @Test
    public void testVendorApexWrongInstaller_nonStaged() throws Exception {
        assertThat(InstallUtils.getInstalledVersion("test.apex.rebootless")).isEqualTo(1);
        TestApp apex = new TestApp("apex", "test.apex.rebootless", 2,
                /* isApex= */ true, "test.rebootless_apex_v2.apex");
        String expectedFailMessage = "Update of APEX package test.apex.rebootless is not allowed "
                + "for com.android.tests.stagedinstallinternal";
        InstallUtils.commitExpectingFailure(
                AssertionError.class, expectedFailMessage,
                Install.single(apex).setBypassAllowedApexUpdateCheck(false));
        InstallUtils.commitExpectingFailure(
                AssertionError.class, expectedFailMessage,
                Install.multi(apex).setBypassAllowedApexUpdateCheck(false));
    }

    @Test
    public void testVendorApexCorrectInstaller_staged() throws Exception {
        assertThat(InstallUtils.getInstalledVersion("test.apex.rebootless")).isEqualTo(1);
        TestApp apex = new TestApp("apex", "test.apex.rebootless", 2,
                /* isApex= */ true, "test.rebootless_apex_v2.apex");
        int sessionId =
                Install.single(apex).setBypassAllowedApexUpdateCheck(false).setStaged().commit();
        InstallUtils.getPackageInstaller().abandonSession(sessionId);
    }

    @Test
    public void testVendorApexCorrectInstaller_nonStaged() throws Exception {
        assertThat(InstallUtils.getInstalledVersion("test.apex.rebootless")).isEqualTo(1);
        TestApp apex = new TestApp("apex", "test.apex.rebootless", 2,
                /* isApex= */ true, "test.rebootless_apex_v2.apex");
        Install.single(apex).setBypassAllowedApexUpdateCheck(false).commit();
        assertThat(InstallUtils.getInstalledVersion("test.apex.rebootless")).isEqualTo(2);
    }

    @Test
    public void testRebootlessUpdates() throws Exception {
        InstallUtils.dropShellPermissionIdentity();
        InstallUtils.adoptShellPermissionIdentity(Manifest.permission.INSTALL_PACKAGE_UPDATES);

        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getContext().getPackageManager();
        {
            PackageInfo apex = pm.getPackageInfo("test.apex.rebootless", PackageManager.MATCH_APEX);
            assertThat(apex.getLongVersionCode()).isEqualTo(1);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                    .isEqualTo(ApplicationInfo.FLAG_SYSTEM);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED)
                    .isEqualTo(ApplicationInfo.FLAG_INSTALLED);
            assertThat(apex.applicationInfo.sourceDir).startsWith("/system/apex");
        }

        TestApp apex1 = new TestApp("TestRebootlessApexV1", "test.apex.rebootless", 1,
                /* isApex= */ true, "test.rebootless_apex_v1.apex");
        Install.single(apex1).commit();

        {
            PackageInfo apex = pm.getPackageInfo("test.apex.rebootless", PackageManager.MATCH_APEX);
            assertThat(apex.getLongVersionCode()).isEqualTo(1);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM).isEqualTo(0);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED)
                    .isEqualTo(ApplicationInfo.FLAG_INSTALLED);
            assertThat(apex.applicationInfo.sourceDir).startsWith("/data/apex/active");
        }
        {
            PackageInfo apex = pm.getPackageInfo("test.apex.rebootless",
                    PackageManager.MATCH_APEX | PackageManager.MATCH_FACTORY_ONLY);
            assertThat(apex.getLongVersionCode()).isEqualTo(1);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                    .isEqualTo(ApplicationInfo.FLAG_SYSTEM);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED).isEqualTo(0);
            assertThat(apex.applicationInfo.sourceDir).startsWith("/system/apex");
        }

        TestApp apex2 = new TestApp("TestRebootlessApexV1", "test.apex.rebootless", 2,
                /* isApex= */ true, "test.rebootless_apex_v2.apex");
        Install.single(apex2).commit();

        {
            PackageInfo apex = pm.getPackageInfo("test.apex.rebootless", PackageManager.MATCH_APEX);
            assertThat(apex.getLongVersionCode()).isEqualTo(2);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM).isEqualTo(0);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED)
                    .isEqualTo(ApplicationInfo.FLAG_INSTALLED);
            assertThat(apex.applicationInfo.sourceDir).startsWith("/data/apex/active");
        }
        {
            PackageInfo apex = pm.getPackageInfo("test.apex.rebootless",
                    PackageManager.MATCH_APEX | PackageManager.MATCH_FACTORY_ONLY);
            assertThat(apex.getLongVersionCode()).isEqualTo(1);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                    .isEqualTo(ApplicationInfo.FLAG_SYSTEM);
            assertThat(apex.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED).isEqualTo(0);
            assertThat(apex.applicationInfo.sourceDir).startsWith("/system/apex");
        }
    }

    @Test
    public void testRebootlessUpdate_hasStagedSessionWithSameApex_fails() throws Exception {
        assertThat(InstallUtils.getInstalledVersion(SHIM_APEX_PACKAGE_NAME)).isEqualTo(1);

        int sessionId = Install.single(APEX_V2).setStaged().commit();
        assertSessionReady(sessionId);
        InstallUtils.commitExpectingFailure(
                AssertionError.class,
                "Staged session " + sessionId + " already contains " + SHIM_APEX_PACKAGE_NAME,
                Install.single(APEX_V2));
    }

    @Test
    public void testGetStagedModuleNames() throws Exception {
        // Before staging a session
        String[] result = getPackageManagerNative().getStagedApexModuleNames();
        assertThat(result).hasLength(0);
        // Stage an apex
        int sessionId = Install.single(APEX_V2).setStaged().commit();
        result = getPackageManagerNative().getStagedApexModuleNames();
        assertThat(result).hasLength(1);
        assertThat(result).isEqualTo(new String[]{SHIM_APEX_PACKAGE_NAME});
        // Abandon the session
        InstallUtils.openPackageInstallerSession(sessionId).abandon();
        result = getPackageManagerNative().getStagedApexModuleNames();
        assertThat(result).hasLength(0);
    }

    @Test
    public void testGetStagedApexInfo() throws Exception {
        // Ask for non-existing module
        StagedApexInfo result = getPackageManagerNative().getStagedApexInfo("not found");
        assertThat(result).isNull();
        // Stage an apex
        int sessionId = Install.single(TEST_APEX_CLASSPATH).setStaged().commit();
        // Query proper module name
        result = getPackageManagerNative().getStagedApexInfo(TEST_APEX_PACKAGE_NAME);
        assertThat(result.moduleName).isEqualTo(TEST_APEX_PACKAGE_NAME);
        assertThat(result.hasClassPathJars).isTrue();
        InstallUtils.openPackageInstallerSession(sessionId).abandon();
    }

    @Test
    public void testGetAppInfo_flagTestOnlyIsSet() throws Exception {
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getContext().getPackageManager();
        final ApplicationInfo info = pm.getApplicationInfo(TEST_APEX_SYSTEM_SERVER_PACKAGE_NAME,
                ApplicationInfoFlags.of(PackageManager.MATCH_APEX));
        assertThat(info).isNotNull();
        assertThat((info.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0).isTrue();
    }

    public static class MockStagedApexObserver extends IStagedApexObserver.Stub {
        @Override
        public void onApexStaged(ApexStagedEvent event) {
            assertThat(event).isNotNull();
        }
    }

    @Test
    public void testStagedApexObserver() throws Exception {
        MockStagedApexObserver realObserver = new MockStagedApexObserver();
        IStagedApexObserver observer = spy(realObserver);
        assertThat(observer).isNotNull();
        getPackageManagerNative().registerStagedApexObserver(observer);

        // Stage an apex and verify observer was called
        int sessionId = Install.single(APEX_V2).setStaged().commit();
        ArgumentCaptor<ApexStagedEvent> captor = ArgumentCaptor.forClass(ApexStagedEvent.class);
        verify(observer, timeout(5000)).onApexStaged(captor.capture());
        assertThat(captor.getValue().stagedApexModuleNames).isEqualTo(
                new String[] {SHIM_APEX_PACKAGE_NAME});

        // Abandon and verify observer is called
        Mockito.clearInvocations(observer);
        InstallUtils.openPackageInstallerSession(sessionId).abandon();
        verify(observer, timeout(5000)).onApexStaged(captor.capture());
        assertThat(captor.getValue().stagedApexModuleNames).hasLength(0);
    }

    @Test
    public void testRebootlessDowngrade() throws Exception {
        final String packageName = "test.apex.rebootless";
        assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(2);
        TestApp apex1 = new TestApp("TestRebootlessApexV1", packageName, 1,
                /* isApex= */ true, "test.rebootless_apex_v1.apex");
        InstallUtils.commitExpectingFailure(AssertionError.class,
                "INSTALL_FAILED_VERSION_DOWNGRADE", Install.single(apex1));
        Install.single(apex1).setRequestDowngrade().commit();
        assertThat(InstallUtils.getInstalledVersion(packageName)).isEqualTo(1);
    }

    private IPackageManagerNative getPackageManagerNative() {
        IBinder binder = ServiceManager.waitForService("package_native");
        assertThat(binder).isNotNull();
        return IPackageManagerNative.Stub.asInterface(binder);
    }
    private static void assertSessionApplied(int sessionId) {
        assertSessionState(sessionId, (session) -> {
            assertThat(session.isStagedSessionApplied()).isTrue();
        });
    }

    private static void assertSessionFailed(int sessionId) {
        assertSessionState(sessionId, (session) -> {
            assertThat(session.isStagedSessionFailed()).isTrue();
        });
    }

    private static void assertSessionFailedWithMessage(int sessionId, String msg) {
        assertSessionState(sessionId, (session) -> {
            assertThat(session.isStagedSessionFailed()).isTrue();
            assertThat(session.getStagedSessionErrorMessage()).contains(msg);
        });
    }

    private static void assertSessionReady(int sessionId) {
        assertSessionState(sessionId,
                (session) -> assertThat(session.isStagedSessionReady()).isTrue());
    }

    private static void assertSessionState(
            int sessionId, Consumer<PackageInstaller.SessionInfo> assertion) {
        PackageInstaller packageInstaller = getPackageInstaller();

        List<PackageInstaller.SessionInfo> sessions = packageInstaller.getStagedSessions();
        boolean found = false;
        for (PackageInstaller.SessionInfo session : sessions) {
            if (session.getSessionId() == sessionId) {
                assertion.accept(session);
                found = true;
            }
        }
        assertWithMessage("Expecting to find session in getStagedSession()")
                .that(found).isTrue();

        // Test also that getSessionInfo correctly returns the session.
        PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
        assertion.accept(sessionInfo);
    }

    private void storeSessionId(int sessionId) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(mTestStateFile))) {
            writer.write("" + sessionId);
        }
    }

    private int retrieveLastSessionId() throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(mTestStateFile))) {
            return Integer.parseInt(reader.readLine());
        }
    }
}
