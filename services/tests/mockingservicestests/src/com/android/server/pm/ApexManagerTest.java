/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.apex.ApexSessionInfo;
import android.apex.ApexSessionParams;
import android.apex.IApexService;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)

public class ApexManagerTest {

    @Rule
    public final MockSystemRule mMockSystem = new MockSystemRule();

    private static final String TEST_APEX_PKG = "com.android.apex.test";
    private static final String TEST_APEX_FILE_NAME = "apex.test.apex";
    private static final int TEST_SESSION_ID = 99999999;
    private static final int[] TEST_CHILD_SESSION_ID = {8888, 7777};
    private ApexManager mApexManager;
    private PackageParser2 mPackageParser2;

    private IApexService mApexService = mock(IApexService.class);

    private PackageManagerService mPmService;

    private InstallPackageHelper mInstallPackageHelper;

    @Before
    public void setUp() throws Exception {
        ApexManager.ApexManagerImpl managerImpl = spy(new ApexManager.ApexManagerImpl());
        doReturn(mApexService).when(managerImpl).waitForApexService();
        when(mApexService.getActivePackages()).thenReturn(new ApexInfo[0]);
        mApexManager = managerImpl;
        mPackageParser2 = new PackageParser2(null, null, null, new PackageParser2.Callback() {
            @Override
            public boolean isChangeEnabled(long changeId, @NonNull ApplicationInfo appInfo) {
                return true;
            }

            @Override
            public boolean hasFeature(String feature) {
                return true;
            }
        });

        mMockSystem.system().stageNominalSystemState();
        mPmService = new PackageManagerService(mMockSystem.mocks().getInjector(),
                false /*factoryTest*/,
                MockSystem.Companion.getDEFAULT_VERSION_INFO().fingerprint,
                false /*isEngBuild*/,
                false /*isUserDebugBuild*/,
                Build.VERSION_CODES.CUR_DEVELOPMENT,
                Build.VERSION.INCREMENTAL);
        mMockSystem.system().validateFinalState();
        mInstallPackageHelper = new InstallPackageHelper(mPmService, mock(AppDataHelper.class));
    }

    @NonNull
    private List<ApexManager.ScanResult> scanApexInfos(ApexInfo[] apexInfos) {
        return mInstallPackageHelper.scanApexPackages(apexInfos,
                ParsingPackageUtils.PARSE_IS_SYSTEM_DIR,
                PackageManagerService.SCAN_AS_SYSTEM, mPackageParser2,
                ParallelPackageParser.makeExecutorService());
    }

    @Nullable
    private ApexManager.ScanResult findActive(@NonNull List<ApexManager.ScanResult> results) {
        return results.stream()
                .filter(it -> it.apexInfo.isActive)
                .filter(it -> Objects.equals(it.packageName, TEST_APEX_PKG))
                .findFirst()
                .orElse(null);
    }

    @Nullable
    private ApexManager.ScanResult findFactory(@NonNull List<ApexManager.ScanResult> results,
            @NonNull String packageName) {
        return results.stream()
                .filter(it -> it.apexInfo.isFactory)
                .filter(it -> Objects.equals(it.packageName, packageName))
                .findFirst()
                .orElse(null);
    }

    @NonNull
    private AndroidPackage mockParsePackage(@NonNull PackageParser2 parser,
            @NonNull ApexInfo apexInfo) {
        var flags = PackageManager.GET_META_DATA | PackageManager.GET_SIGNING_CERTIFICATES;
        try {
            var parsedPackage = parser.parsePackage(new File(apexInfo.modulePath), flags,
                    /* useCaches= */ false);
            ScanPackageUtils.applyPolicy(parsedPackage,
                    PackageManagerService.SCAN_AS_APEX | PackageManagerService.SCAN_AS_SYSTEM,
                    mPmService.getPlatformPackage(), /* isUpdatedSystemApp */ false);
            // isUpdatedSystemApp is ignoreable above, only used for shared library adjustment
            return parsedPackage.hideAsFinal();
        } catch (PackageManagerException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testScanActivePackage() {
        var apexInfos = createApexInfoForTestPkg(true, false);
        var results = scanApexInfos(apexInfos);
        var active = findActive(results);
        var factory = findFactory(results, TEST_APEX_PKG);

        assertThat(active).isNotNull();
        assertThat(active.packageName).isEqualTo(TEST_APEX_PKG);

        assertThat(factory).isNull();
    }

    @Test
    public void testScanFactoryPackage() {
        var apexInfos = createApexInfoForTestPkg(false, true);
        var results = scanApexInfos(apexInfos);
        var active = findActive(results);
        var factory = findFactory(results, TEST_APEX_PKG);

        assertThat(factory).isNotNull();
        assertThat(factory.packageName).contains(TEST_APEX_PKG);

        assertThat(active).isNull();
    }

    @Test
    public void testGetApexSystemServices() {
        ApexInfo[] apexInfo = new ApexInfo[]{
                createApexInfoForTestPkg(false, true, 1),
                // only active apex reports apex-system-service
                createApexInfoForTestPkg(true, false, 2),
        };

        List<ApexManager.ScanResult> scanResults = scanApexInfos(apexInfo);
        mApexManager.notifyScanResult(scanResults);

        List<ApexSystemServiceInfo> services = mApexManager.getApexSystemServices();
        assertThat(services).hasSize(1);
        assertThat(services.stream().map(ApexSystemServiceInfo::getName).findFirst().orElse(null))
                .matches("com.android.apex.test.ApexSystemService");
    }

    @Test
    public void testIsApexPackage() {
        var apexInfos = createApexInfoForTestPkg(false, true);
        var results = scanApexInfos(apexInfos);
        var factory = findFactory(results, TEST_APEX_PKG);
        assertThat(factory.pkg.isApex()).isTrue();
    }

    @Test
    public void testIsApexSupported() {
        assertThat(mApexManager.isApexSupported()).isTrue();
    }

    @Test
    public void testGetStagedSessionInfo() throws RemoteException {
        when(mApexService.getStagedSessionInfo(anyInt())).thenReturn(
                getFakeStagedSessionInfo());

        mApexManager.getStagedSessionInfo(TEST_SESSION_ID);
        verify(mApexService, times(1)).getStagedSessionInfo(TEST_SESSION_ID);
    }

    @Test
    public void testGetStagedSessionInfo_unKnownStagedSessionId() throws RemoteException {
        when(mApexService.getStagedSessionInfo(anyInt())).thenReturn(
                getFakeUnknownSessionInfo());

        assertThat(mApexManager.getStagedSessionInfo(TEST_SESSION_ID)).isNull();
    }

    @Test
    public void testSubmitStagedSession_throwPackageManagerException() throws RemoteException {
        doAnswer(invocation -> {
            throw new Exception();
        }).when(mApexService).submitStagedSession(any(), any());

        assertThrows(PackageManagerException.class,
                () -> mApexManager.submitStagedSession(testParamsWithChildren()));
    }

    @Test
    public void testSubmitStagedSession_throwRunTimeException() throws RemoteException {
        doThrow(RemoteException.class).when(mApexService).submitStagedSession(any(), any());

        assertThrows(RuntimeException.class,
                () -> mApexManager.submitStagedSession(testParamsWithChildren()));
    }

    @Test
    public void testGetStagedApexInfos_throwRunTimeException() throws RemoteException {
        doThrow(RemoteException.class).when(mApexService).getStagedApexInfos(any());

        assertThrows(RuntimeException.class,
                () -> mApexManager.getStagedApexInfos(testParamsWithChildren()));
    }

    @Test
    public void testGetStagedApexInfos_returnsEmptyArrayOnError() throws RemoteException {
        doThrow(ServiceSpecificException.class).when(mApexService).getStagedApexInfos(any());

        assertThat(mApexManager.getStagedApexInfos(testParamsWithChildren())).hasLength(0);
    }

    @Test
    public void testMarkStagedSessionReady_throwPackageManagerException() throws RemoteException {
        doAnswer(invocation -> {
            throw new Exception();
        }).when(mApexService).markStagedSessionReady(anyInt());

        assertThrows(PackageManagerException.class,
                () -> mApexManager.markStagedSessionReady(TEST_SESSION_ID));
    }

    @Test
    public void testMarkStagedSessionReady_throwRunTimeException() throws RemoteException {
        doThrow(RemoteException.class).when(mApexService).markStagedSessionReady(anyInt());

        assertThrows(RuntimeException.class,
                () -> mApexManager.markStagedSessionReady(TEST_SESSION_ID));
    }

    @Test
    public void testRevertActiveSessions_remoteException() throws RemoteException {
        doThrow(RemoteException.class).when(mApexService).revertActiveSessions();

        try {
            assertThat(mApexManager.revertActiveSessions()).isFalse();
        } catch (Exception e) {
            throw new AssertionError("ApexManager should not raise Exception");
        }
    }

    @Test
    public void testMarkStagedSessionSuccessful_throwRemoteException() throws RemoteException {
        doThrow(RemoteException.class).when(mApexService).markStagedSessionSuccessful(anyInt());

        assertThrows(RuntimeException.class,
                () -> mApexManager.markStagedSessionSuccessful(TEST_SESSION_ID));
    }

    @Test
    public void testUninstallApex_throwException_returnFalse() throws RemoteException {
        doAnswer(invocation -> {
            throw new Exception();
        }).when(mApexService).unstagePackages(any());

        assertThat(mApexManager.uninstallApex(TEST_APEX_PKG)).isFalse();
    }

    @Test
    public void testReportErrorWithApkInApex() throws RemoteException {
        when(mApexService.getActivePackages()).thenReturn(createApexInfoForTestPkg(true, true));
        final ApexManager.ActiveApexInfo activeApex = mApexManager.getActiveApexInfos().get(0);
        assertThat(activeApex.apexModuleName).isEqualTo(TEST_APEX_PKG);

        ApexInfo[] apexInfo = createApexInfoForTestPkg(true, true);
        List<ApexManager.ScanResult> scanResults = scanApexInfos(apexInfo);
        mApexManager.notifyScanResult(scanResults);

        assertThat(mApexManager.getApkInApexInstallError(activeApex.apexModuleName)).isNull();
        mApexManager.reportErrorWithApkInApex(activeApex.apexDirectory.getAbsolutePath(),
                "Some random error");
        assertThat(mApexManager.getApkInApexInstallError(activeApex.apexModuleName))
                .isEqualTo("Some random error");
    }

    /**
     * registerApkInApex method checks if the prefix of base apk path contains the apex package
     * name. When an apex package name is a prefix of another apex package name, e.g,
     * com.android.media and com.android.mediaprovider, then we need to ensure apk inside apex
     * mediaprovider does not get registered under apex media.
     */
    @Test
    public void testRegisterApkInApexDoesNotRegisterSimilarPrefix() throws RemoteException {
        when(mApexService.getActivePackages()).thenReturn(createApexInfoForTestPkg(true, true));
        final ApexManager.ActiveApexInfo activeApex = mApexManager.getActiveApexInfos().get(0);
        assertThat(activeApex.apexModuleName).isEqualTo(TEST_APEX_PKG);

        AndroidPackage fakeApkInApex = mock(AndroidPackage.class);
        when(fakeApkInApex.getBaseApkPath()).thenReturn("/apex/" + TEST_APEX_PKG + "randomSuffix");
        when(fakeApkInApex.getPackageName()).thenReturn("randomPackageName");

        ApexInfo[] apexInfo = createApexInfoForTestPkg(true, true);
        List<ApexManager.ScanResult> scanResults = scanApexInfos(apexInfo);
        mApexManager.notifyScanResult(scanResults);

        assertThat(mApexManager.getApksInApex(activeApex.apexModuleName)).isEmpty();
        mApexManager.registerApkInApex(fakeApkInApex);
        assertThat(mApexManager.getApksInApex(activeApex.apexModuleName)).isEmpty();
    }

    @Test
    public void testInstallPackage_activeOnSystem() throws Exception {
        ApexInfo activeApexInfo = createApexInfo("test.apex_rebootless", 1, /* isActive= */ true,
                /* isFactory= */ true, extractResource("test.apex_rebootless_v1",
                        "test.rebootless_apex_v1.apex"));
        ApexInfo[] apexInfo = new ApexInfo[]{activeApexInfo};
        var results = scanApexInfos(apexInfo);

        File finalApex = extractResource("test.rebootles_apex_v2", "test.rebootless_apex_v2.apex");
        ApexInfo newApexInfo = createApexInfo("test.apex_rebootless", 2, /* isActive= */ true,
                /* isFactory= */ false, finalApex);
        when(mApexService.installAndActivatePackage(anyString())).thenReturn(newApexInfo);

        File installedApex = extractResource("installed", "test.rebootless_apex_v2.apex");
        newApexInfo = mApexManager.installPackage(installedApex);

        var newPkg = mockParsePackage(mPackageParser2, newApexInfo);
        assertThat(newPkg.getBaseApkPath()).isEqualTo(finalApex.getAbsolutePath());
        assertThat(newPkg.getLongVersionCode()).isEqualTo(2);

        var factoryPkg = mockParsePackage(mPackageParser2,
                findFactory(results, "test.apex.rebootless").apexInfo);
        assertThat(factoryPkg.getBaseApkPath()).isEqualTo(activeApexInfo.modulePath);
        assertThat(factoryPkg.getLongVersionCode()).isEqualTo(1);
        assertThat(AndroidPackageUtils.isSystem(factoryPkg)).isTrue();
    }

    @Test
    public void testInstallPackage_activeOnData() throws Exception {
        ApexInfo factoryApexInfo = createApexInfo("test.apex_rebootless", 1, /* isActive= */ false,
                /* isFactory= */ true, extractResource("test.apex_rebootless_v1",
                        "test.rebootless_apex_v1.apex"));
        ApexInfo activeApexInfo = createApexInfo("test.apex_rebootless", 1, /* isActive= */ true,
                /* isFactory= */ false, extractResource("test.apex.rebootless@1",
                        "test.rebootless_apex_v1.apex"));
        ApexInfo[] apexInfo = new ApexInfo[]{factoryApexInfo, activeApexInfo};
        var results = scanApexInfos(apexInfo);

        File finalApex = extractResource("test.rebootles_apex_v2", "test.rebootless_apex_v2.apex");
        ApexInfo newApexInfo = createApexInfo("test.apex_rebootless", 2, /* isActive= */ true,
                /* isFactory= */ false, finalApex);
        when(mApexService.installAndActivatePackage(anyString())).thenReturn(newApexInfo);

        File installedApex = extractResource("installed", "test.rebootless_apex_v2.apex");
        newApexInfo = mApexManager.installPackage(installedApex);

        var newPkg = mockParsePackage(mPackageParser2, newApexInfo);
        assertThat(newPkg.getBaseApkPath()).isEqualTo(finalApex.getAbsolutePath());
        assertThat(newPkg.getLongVersionCode()).isEqualTo(2);

        var factoryPkg = mockParsePackage(mPackageParser2,
                findFactory(results, "test.apex.rebootless").apexInfo);
        assertThat(factoryPkg.getBaseApkPath()).isEqualTo(factoryApexInfo.modulePath);
        assertThat(factoryPkg.getLongVersionCode()).isEqualTo(1);
        assertThat(AndroidPackageUtils.isSystem(factoryPkg)).isTrue();
    }

    @Test
    public void testInstallPackageBinderCallFails() throws Exception {
        when(mApexService.installAndActivatePackage(anyString())).thenThrow(
                new RuntimeException("install failed :("));

        File installedApex = extractResource("test.apex_rebootless_v1",
                "test.rebootless_apex_v1.apex");
        assertThrows(PackageManagerException.class,
                () -> mApexManager.installPackage(installedApex));
    }

    @Test
    public void testGetActivePackageNameForApexModuleName() {
        final String moduleName = "com.android.module_name";

        ApexInfo[] apexInfo = createApexInfoForTestPkg(true, false);
        apexInfo[0].moduleName = moduleName;
        List<ApexManager.ScanResult> scanResults = scanApexInfos(apexInfo);
        mApexManager.notifyScanResult(scanResults);

        assertThat(mApexManager.getActivePackageNameForApexModuleName(moduleName))
                .isEqualTo(TEST_APEX_PKG);
    }

    @Test
    public void testGetBackingApexFiles() throws Exception {
        final ApexInfo apex = createApexInfoForTestPkg(true, true, 37);
        when(mApexService.getActivePackages()).thenReturn(new ApexInfo[]{apex});

        final File backingApexFile = mApexManager.getBackingApexFile(
                new File(mMockSystem.system().getApexDirectory(),
                        TEST_APEX_PKG + "/apk/App/App.apk"));
        assertThat(backingApexFile.getAbsolutePath()).isEqualTo(apex.modulePath);
    }

    @Test
    public void testGetBackingApexFile_fileNotOnApexMountPoint_returnsNull() {
        File result = mApexManager.getBackingApexFile(
                new File("/data/local/tmp/whatever/does-not-matter"));
        assertThat(result).isNull();
    }

    @Test
    public void testGetBackingApexFiles_unknownApex_returnsNull() throws Exception {
        final ApexInfo apex = createApexInfoForTestPkg(true, true, 37);
        when(mApexService.getActivePackages()).thenReturn(new ApexInfo[]{apex});

        final File backingApexFile = mApexManager.getBackingApexFile(
                new File(mMockSystem.system().getApexDirectory(), "com.wrong.apex/apk/App"));
        assertThat(backingApexFile).isNull();
    }

    @Test
    public void testGetBackingApexFiles_topLevelApexDir_returnsNull() {
        assertThat(mApexManager.getBackingApexFile(Environment.getApexDirectory())).isNull();
        assertThat(mApexManager.getBackingApexFile(new File("/apex/"))).isNull();
        assertThat(mApexManager.getBackingApexFile(new File("/apex//"))).isNull();
    }

    @Test
    public void testGetBackingApexFiles_flattenedApex() {
        ApexManager flattenedApexManager = new ApexManager.ApexManagerFlattenedApex();
        final File backingApexFile = flattenedApexManager.getBackingApexFile(
                new File(mMockSystem.system().getApexDirectory(),
                        "com.android.apex.cts.shim/app/CtsShim/CtsShim.apk"));
        assertThat(backingApexFile).isNull();
    }

    @Test
    public void testActiveApexChanged() throws RemoteException {
        ApexInfo apex1 = createApexInfo(
                "com.apex1", 37, true, true, new File("/data/apex/active/com.apex@37.apex"));
        apex1.activeApexChanged = true;
        apex1.preinstalledModulePath = apex1.modulePath;
        when(mApexService.getActivePackages()).thenReturn(new ApexInfo[]{apex1});
        final ApexManager.ActiveApexInfo activeApex = mApexManager.getActiveApexInfos().get(0);
        assertThat(activeApex.apexModuleName).isEqualTo("com.apex1");
        assertThat(activeApex.activeApexChanged).isTrue();
    }

    private ApexInfo createApexInfoForTestPkg(boolean isActive, boolean isFactory, int version) {
        File apexFile = extractResource(TEST_APEX_PKG, TEST_APEX_FILE_NAME);
        ApexInfo apexInfo = new ApexInfo();
        apexInfo.isActive = isActive;
        apexInfo.isFactory = isFactory;
        apexInfo.moduleName = TEST_APEX_PKG;
        apexInfo.modulePath = apexFile.getPath();
        apexInfo.versionCode = version;
        apexInfo.preinstalledModulePath = apexFile.getPath();
        return apexInfo;
    }

    private ApexInfo[] createApexInfoForTestPkg(boolean isActive, boolean isFactory) {
        return new ApexInfo[]{createApexInfoForTestPkg(isActive, isFactory, 191000070)};
    }

    private ApexInfo createApexInfo(String moduleName, int versionCode, boolean isActive,
            boolean isFactory, File apexFile) {
        ApexInfo apexInfo = new ApexInfo();
        apexInfo.moduleName = moduleName;
        apexInfo.versionCode = versionCode;
        apexInfo.isActive = isActive;
        apexInfo.isFactory = isFactory;
        apexInfo.modulePath = apexFile.getPath();
        apexInfo.preinstalledModulePath = apexFile.getPath();
        return apexInfo;
    }

    private ApexSessionInfo getFakeStagedSessionInfo() {
        ApexSessionInfo stagedSessionInfo = new ApexSessionInfo();
        stagedSessionInfo.sessionId = TEST_SESSION_ID;
        stagedSessionInfo.isStaged = true;

        return stagedSessionInfo;
    }

    private ApexSessionInfo getFakeUnknownSessionInfo() {
        ApexSessionInfo stagedSessionInfo = new ApexSessionInfo();
        stagedSessionInfo.sessionId = TEST_SESSION_ID;
        stagedSessionInfo.isUnknown = true;

        return stagedSessionInfo;
    }

    private static ApexSessionParams testParamsWithChildren() {
        ApexSessionParams params = new ApexSessionParams();
        params.sessionId = TEST_SESSION_ID;
        params.childSessionIds = TEST_CHILD_SESSION_ID;
        return params;
    }

    // Extracts the binary data from a resource and writes it to a temp file
    private static File extractResource(String baseName, String fullResourceName) {
        File file;
        try {
            file = File.createTempFile(baseName, ".apex");
        } catch (IOException e) {
            throw new AssertionError("CreateTempFile IOException" + e);
        }

        try (
                InputStream in = ApexManager.class.getClassLoader()
                        .getResourceAsStream(fullResourceName);
                OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            if (in == null) {
                throw new IllegalArgumentException("Resource not found: " + fullResourceName);
            }
            byte[] buf = new byte[65536];
            int chunkSize;
            while ((chunkSize = in.read(buf)) != -1) {
                out.write(buf, 0, chunkSize);
            }
            return file;
        } catch (IOException e) {
            throw new AssertionError("Exception while converting stream to file" + e);
        }
    }
}
