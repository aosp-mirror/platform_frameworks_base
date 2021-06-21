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

import android.Manifest;
import android.content.pm.PackageInstaller;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.TestApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

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
            "ApexWrongSha2", SHIM_APEX_PACKAGE_NAME, 2, /*isApex*/true,
            "com.android.apex.cts.shim.v2_wrong_sha.apex");

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
        InstallUtils.commitExpectingFailure(AssertionError.class, "apexd verification failed",
                Install.single(APEX_WRONG_SHA_V2).setStaged());
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
