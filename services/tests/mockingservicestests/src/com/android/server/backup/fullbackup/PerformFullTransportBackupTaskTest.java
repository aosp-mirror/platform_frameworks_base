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

package com.android.server.backup.fullbackup;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import android.app.backup.BackupAnnotations;
import android.app.backup.BackupTransport;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.BackupAgentConnectionManager;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.OperationStorage;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.transport.BackupTransportClient;
import com.android.server.backup.transport.TransportConnection;
import com.android.server.backup.utils.BackupEligibilityRules;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PerformFullTransportBackupTaskTest {
    private static final String TEST_PACKAGE_1 = "package1";
    private static final String TEST_PACKAGE_2 = "package2";

    @Mock
    BackupAgentTimeoutParameters mBackupAgentTimeoutParameters;
    @Mock
    BackupEligibilityRules mBackupEligibilityRules;
    @Mock
    UserBackupManagerService mBackupManagerService;
    @Mock
    BackupAgentConnectionManager mBackupAgentConnectionManager;
    @Mock
    BackupTransportClient mBackupTransportClient;
    @Mock
    CountDownLatch mLatch;
    @Mock
    OperationStorage mOperationStorage;
    @Mock
    PackageManager mPackageManager;
    @Mock
    TransportConnection mTransportConnection;
    @Mock
    TransportManager mTransportManager;
    @Mock
    UserBackupManagerService.BackupWakeLock mWakeLock;

    private final List<String> mEligiblePackages = new ArrayList<>();

    private PerformFullTransportBackupTask mTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mBackupManagerService.getPackageManager()).thenReturn(mPackageManager);
        when(mBackupManagerService.getQueueLock()).thenReturn("something!");
        when(mBackupManagerService.isEnabled()).thenReturn(true);
        when(mBackupManagerService.getWakelock()).thenReturn(mWakeLock);
        when(mBackupManagerService.isSetupComplete()).thenReturn(true);
        when(mBackupManagerService.getAgentTimeoutParameters()).thenReturn(
                mBackupAgentTimeoutParameters);
        when(mBackupManagerService.getBackupAgentConnectionManager()).thenReturn(
                mBackupAgentConnectionManager);
        when(mBackupManagerService.getTransportManager()).thenReturn(mTransportManager);
        when(mTransportManager.getCurrentTransportClient(any())).thenReturn(mTransportConnection);
        when(mTransportConnection.connectOrThrow(any())).thenReturn(mBackupTransportClient);
        when(mTransportConnection.connect(any())).thenReturn(mBackupTransportClient);
        when(mBackupTransportClient.performFullBackup(any(), any(), anyInt())).thenReturn(
                BackupTransport.TRANSPORT_ERROR);
        when(mBackupEligibilityRules.appIsEligibleForBackup(
                argThat(app -> mEligiblePackages.contains(app.packageName)))).thenReturn(
                true);
        when(mBackupEligibilityRules.appGetsFullBackup(
                argThat(app -> mEligiblePackages.contains(app.packageName)))).thenReturn(
                true);
    }

    @Test
    public void testNewWithCurrentTransport_noTransportConnection_throws() {
        when(mTransportManager.getCurrentTransportClient(any())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> {
                    PerformFullTransportBackupTask task = PerformFullTransportBackupTask
                            .newWithCurrentTransport(
                                    mBackupManagerService,
                                    /* operationStorage */  null,
                                    /* observer */  null,
                                    /* whichPackages */  null,
                                    /* updateSchedule */  false,
                                    /* runningJob */  null,
                                    /* latch */  null,
                                    /* backupObserver */  null,
                                    /* monitor */  null,
                                    /* userInitiated */  false,
                                    /* caller */  null,
                                    /* backupEligibilityRules */  null);
                });
    }

    @Test
    public void run_setsAndClearsNoRestrictedModePackages() throws Exception {
        mockPackageEligibleForFullBackup(TEST_PACKAGE_1);
        mockPackageEligibleForFullBackup(TEST_PACKAGE_2);
        createTask(new String[] {TEST_PACKAGE_1, TEST_PACKAGE_2});
        when(mBackupTransportClient.getPackagesThatShouldNotUseRestrictedMode(any(),
                anyInt())).thenReturn(Set.of("package1"));

        mTask.run();

        InOrder inOrder = inOrder(mBackupAgentConnectionManager);
        inOrder.verify(mBackupAgentConnectionManager).setNoRestrictedModePackages(
                eq(Set.of("package1")),
                eq(BackupAnnotations.OperationType.BACKUP));
        inOrder.verify(mBackupAgentConnectionManager).clearNoRestrictedModePackages();
    }

    private void createTask(String[] packageNames) {
        mTask = PerformFullTransportBackupTask
                .newWithCurrentTransport(
                        mBackupManagerService,
                        mOperationStorage,
                        /* observer */  null,
                        /* whichPackages */  packageNames,
                        /* updateSchedule */  false,
                        /* runningJob */  null,
                        mLatch,
                        /* backupObserver */  null,
                        /* monitor */  null,
                        /* userInitiated */  false,
                        /* caller */  null,
                        mBackupEligibilityRules);
    }

    private void mockPackageEligibleForFullBackup(String packageName) throws Exception {
        mEligiblePackages.add(packageName);
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = appInfo;
        when(mPackageManager.getPackageInfoAsUser(eq(packageName), anyInt(), anyInt())).thenReturn(
                packageInfo);
    }
}
