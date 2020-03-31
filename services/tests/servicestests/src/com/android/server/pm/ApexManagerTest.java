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

package com.android.server.pm;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.apex.ApexInfo;
import android.apex.ApexSessionInfo;
import android.apex.ApexSessionParams;
import android.apex.IApexService;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.FileUtils;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.servicestests.R;
import com.android.server.pm.parsing.PackageParser2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)

public class ApexManagerTest {
    private static final String TEST_APEX_PKG = "com.android.apex.test";
    private static final int TEST_SESSION_ID = 99999999;
    private static final int[] TEST_CHILD_SESSION_ID = {8888, 7777};
    private ApexManager mApexManager;
    private Context mContext;
    private PackageParser2 mPackageParser2;

    private IApexService mApexService = mock(IApexService.class);

    @Before
    public void setUp() throws RemoteException {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        ApexManager.ApexManagerImpl managerImpl = spy(new ApexManager.ApexManagerImpl());
        doReturn(mApexService).when(managerImpl).waitForApexService();
        mApexManager = managerImpl;
        mPackageParser2 = new PackageParser2(null, false, null, null, null);
    }

    @Test
    public void testGetPackageInfo_setFlagsMatchActivePackage() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(true, false));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());
        final PackageInfo activePkgPi = mApexManager.getPackageInfo(TEST_APEX_PKG,
                ApexManager.MATCH_ACTIVE_PACKAGE);

        assertThat(activePkgPi).isNotNull();
        assertThat(activePkgPi.packageName).contains(TEST_APEX_PKG);

        final PackageInfo factoryPkgPi = mApexManager.getPackageInfo(TEST_APEX_PKG,
                ApexManager.MATCH_FACTORY_PACKAGE);

        assertThat(factoryPkgPi).isNull();
    }

    @Test
    public void testGetPackageInfo_setFlagsMatchFactoryPackage() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(false, true));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());
        PackageInfo factoryPkgPi = mApexManager.getPackageInfo(TEST_APEX_PKG,
                ApexManager.MATCH_FACTORY_PACKAGE);

        assertThat(factoryPkgPi).isNotNull();
        assertThat(factoryPkgPi.packageName).contains(TEST_APEX_PKG);

        final PackageInfo activePkgPi = mApexManager.getPackageInfo(TEST_APEX_PKG,
                ApexManager.MATCH_ACTIVE_PACKAGE);

        assertThat(activePkgPi).isNull();
    }

    @Test
    public void testGetPackageInfo_setFlagsNone() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(false, true));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());

        assertThat(mApexManager.getPackageInfo(TEST_APEX_PKG, 0)).isNull();
    }

    @Test
    public void testGetActivePackages() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(true, true));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());

        assertThat(mApexManager.getActivePackages()).isNotEmpty();
    }

    @Test
    public void testGetActivePackages_noneActivePackages() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(false, true));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());

        assertThat(mApexManager.getActivePackages()).isEmpty();
    }

    @Test
    public void testGetFactoryPackages() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(false, true));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());

        assertThat(mApexManager.getFactoryPackages()).isNotEmpty();
    }

    @Test
    public void testGetFactoryPackages_noneFactoryPackages() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(true, false));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());

        assertThat(mApexManager.getFactoryPackages()).isEmpty();
    }

    @Test
    public void testGetInactivePackages() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(false, true));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());

        assertThat(mApexManager.getInactivePackages()).isNotEmpty();
    }

    @Test
    public void testGetInactivePackages_noneInactivePackages() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(true, false));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());

        assertThat(mApexManager.getInactivePackages()).isEmpty();
    }

    @Test
    public void testIsApexPackage() throws RemoteException {
        when(mApexService.getAllPackages()).thenReturn(createApexInfo(false, true));
        mApexManager.scanApexPackagesTraced(mPackageParser2,
                ParallelPackageParser.makeExecutorService());

        assertThat(mApexManager.isApexPackage(TEST_APEX_PKG)).isTrue();
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

    private ApexInfo[] createApexInfo(boolean isActive, boolean isFactory) {
        File apexFile = copyRawResourceToFile(TEST_APEX_PKG, R.raw.apex_test);
        ApexInfo apexInfo = new ApexInfo();
        apexInfo.isActive = isActive;
        apexInfo.isFactory = isFactory;
        apexInfo.moduleName = TEST_APEX_PKG;
        apexInfo.modulePath = apexFile.getPath();
        apexInfo.versionCode = 191000070;

        return new ApexInfo[]{apexInfo};
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

    /**
     * Copies a specified {@code resourceId} to a temp file. Returns a non-null file if the copy
     * succeeded
     */
    File copyRawResourceToFile(String baseName, int resourceId) {
        File outFile;
        try {
            outFile = File.createTempFile(baseName, ".apex");
        } catch (IOException e) {
            throw new AssertionError("CreateTempFile IOException" + e);
        }

        try (InputStream is = mContext.getResources().openRawResource(resourceId);
             FileOutputStream os = new FileOutputStream(outFile)) {
            assertThat(FileUtils.copy(is, os)).isGreaterThan(0L);
        } catch (FileNotFoundException e) {
            throw new AssertionError("File not found exception " + e);
        } catch (IOException e) {
            throw new AssertionError("IOException" + e);
        }

        return outFile;
    }
}
