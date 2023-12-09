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

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.app.backup.BackupAgent;
import android.app.backup.BackupAnnotations;
import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.backup.BackupRestoreEventLogger.DataTypeResult;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;
import android.util.FeatureFlagUtils;

import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.internal.LifecycleOperationStorage;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.params.BackupParams;
import com.android.server.backup.transport.BackupTransportClient;
import com.android.server.backup.transport.TransportConnection;
import com.android.server.backup.utils.BackupEligibilityRules;
import com.android.server.backup.utils.BackupManagerMonitorEventSender;

import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class UserBackupManagerServiceTest {
    private static final String TEST_PACKAGE = "package1";
    private static final String[] TEST_PACKAGES = new String[] { TEST_PACKAGE };
    private static final String TEST_TRANSPORT = "transport";
    private static final int WORKER_THREAD_TIMEOUT_MILLISECONDS = 100;

    @Mock Context mContext;
    @Mock IBackupManagerMonitor mBackupManagerMonitor;
    @Mock IBackupObserver mBackupObserver;
    @Mock PackageManager mPackageManager;
    @Mock TransportConnection mTransportConnection;
    @Mock TransportManager mTransportManager;
    @Mock BackupTransportClient mBackupTransport;
    @Mock BackupEligibilityRules mBackupEligibilityRules;
    @Mock LifecycleOperationStorage mOperationStorage;
    @Mock BackupManagerMonitorEventSender mBackupManagerMonitorEventSender;

    private MockitoSession mSession;
    private TestBackupService mService;

    @Before
    public void setUp() throws Exception {
        mSession = mockitoSession()
                .initMocks(this)
                .mockStatic(BackupManagerMonitorEventSender.class)
                .mockStatic(FeatureFlagUtils.class)
                // TODO(b/263239775): Remove unnecessary stubbing.
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);

        mService = new TestBackupService(mContext, mPackageManager, mOperationStorage,
                mTransportManager);
        mService.setEnabled(true);
        mService.setSetupComplete(true);
    }

    @After
    public void tearDown() {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    @Test
    public void initializeBackupEnableState_doesntWriteStateToDisk() {
        mService.initializeBackupEnableState();

        assertThat(mService.isEnabledStatePersisted).isFalse();
    }

    @Test
    public void updateBackupEnableState_writesStateToDisk() {
        mService.setBackupEnabled(true);

        assertThat(mService.isEnabledStatePersisted).isTrue();
    }

    @Test
    public void getRequestBackupParams_appIsEligibleForFullBackup() throws Exception {
        when(mPackageManager.getPackageInfoAsUser(anyString(), anyInt(), anyInt())).thenReturn(
                getPackageInfo(TEST_PACKAGE));
        when(mBackupEligibilityRules.appIsEligibleForBackup(any())).thenReturn(true);
        when(mBackupEligibilityRules.appGetsFullBackup(any())).thenReturn(true);

        BackupParams params = mService.getRequestBackupParams(TEST_PACKAGES, mBackupObserver,
                mBackupManagerMonitor, /* flags */ 0, mBackupEligibilityRules,
                mTransportConnection, /* transportDirName */ "", OnTaskFinishedListener.NOP);

        assertThat(params.kvPackages).isEmpty();
        assertThat(params.fullPackages).contains(TEST_PACKAGE);
        assertThat(params.mBackupEligibilityRules).isEqualTo(mBackupEligibilityRules);
    }

    @Test
    public void getRequestBackupParams_appIsEligibleForKeyValueBackup() throws Exception {
        when(mPackageManager.getPackageInfoAsUser(anyString(), anyInt(), anyInt())).thenReturn(
                getPackageInfo(TEST_PACKAGE));
        when(mBackupEligibilityRules.appIsEligibleForBackup(any())).thenReturn(true);
        when(mBackupEligibilityRules.appGetsFullBackup(any())).thenReturn(false);

        BackupParams params = mService.getRequestBackupParams(TEST_PACKAGES, mBackupObserver,
                mBackupManagerMonitor, /* flags */ 0, mBackupEligibilityRules,
                mTransportConnection, /* transportDirName */ "", OnTaskFinishedListener.NOP);

        assertThat(params.kvPackages).contains(TEST_PACKAGE);
        assertThat(params.fullPackages).isEmpty();
        assertThat(params.mBackupEligibilityRules).isEqualTo(mBackupEligibilityRules);
    }

    @Test
    public void getRequestBackupParams_appIsNotEligibleForBackup() throws Exception {
        when(mPackageManager.getPackageInfoAsUser(anyString(), anyInt(), anyInt())).thenReturn(
                getPackageInfo(TEST_PACKAGE));
        when(mBackupEligibilityRules.appIsEligibleForBackup(any())).thenReturn(false);
        when(mBackupEligibilityRules.appGetsFullBackup(any())).thenReturn(false);

        BackupParams params = mService.getRequestBackupParams(TEST_PACKAGES, mBackupObserver,
                mBackupManagerMonitor, /* flags */ 0, mBackupEligibilityRules,
                mTransportConnection, /* transportDirName */ "", OnTaskFinishedListener.NOP);

        assertThat(params.kvPackages).isEmpty();
        assertThat(params.fullPackages).isEmpty();
        assertThat(params.mBackupEligibilityRules).isEqualTo(mBackupEligibilityRules);
    }

    @Test
    public void testGetBackupDestinationFromTransport_returnsCloudByDefault()
            throws Exception {
        when(mTransportConnection.connectOrThrow(any())).thenReturn(mBackupTransport);
        when(mBackupTransport.getTransportFlags()).thenReturn(0);

        int backupDestination = mService.getBackupDestinationFromTransport(mTransportConnection);

        assertThat(backupDestination).isEqualTo(BackupDestination.CLOUD);
    }

    @Test
    public void testGetBackupDestinationFromTransport_returnsDeviceTransferForD2dTransport()
            throws Exception {
        // This is a temporary flag to control the new behaviour until it's ready to be fully
        // rolled out.
        mService.shouldUseNewBackupEligibilityRules = true;

        when(mTransportConnection.connectOrThrow(any())).thenReturn(mBackupTransport);
        when(mBackupTransport.getTransportFlags()).thenReturn(
                BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER);

        int backupDestination = mService.getBackupDestinationFromTransport(mTransportConnection);

        assertThat(backupDestination).isEqualTo(BackupDestination.DEVICE_TRANSFER);
    }

    @Test
    @FlakyTest
    public void testAgentDisconnected_cancelsCurrentOperations() throws Exception {
        when(mOperationStorage.operationTokensForPackage(eq("com.android.foo"))).thenReturn(
                ImmutableSet.of(123, 456, 789)
        );

        mService.agentDisconnected("com.android.foo");

        mService.waitForAsyncOperation();
        verify(mOperationStorage).cancelOperation(eq(123), eq(true), any(IntConsumer.class));
        verify(mOperationStorage).cancelOperation(eq(456), eq(true), any());
        verify(mOperationStorage).cancelOperation(eq(789), eq(true), any());
    }

    @Test
    public void testAgentDisconnected_unknownPackageName_cancelsNothing() throws Exception {
        when(mOperationStorage.operationTokensForPackage(eq("com.android.foo"))).thenReturn(
                ImmutableSet.of()
        );

        mService.agentDisconnected("com.android.foo");

        verify(mOperationStorage, never())
                .cancelOperation(anyInt(), anyBoolean(), any(IntConsumer.class));
    }

    @Test
    public void testReportDelayedRestoreResult_sendsLogsToMonitor() throws Exception {
        PackageInfo packageInfo = getPackageInfo(TEST_PACKAGE);
        when(mPackageManager.getPackageInfoAsUser(anyString(),
                any(PackageManager.PackageInfoFlags.class), anyInt())).thenReturn(packageInfo);
        when(mTransportManager.getCurrentTransportName()).thenReturn(TEST_TRANSPORT);
        when(mTransportManager.getTransportClientOrThrow(eq(TEST_TRANSPORT), anyString()))
                .thenReturn(mTransportConnection);
        when(mTransportConnection.connectOrThrow(any())).thenReturn(mBackupTransport);
        when(mBackupTransport.getBackupManagerMonitor()).thenReturn(mBackupManagerMonitor);


        List<DataTypeResult> results = Arrays.asList(new DataTypeResult(/* dataType */ "type_1"),
                new DataTypeResult(/* dataType */ "type_2"));
        mService.reportDelayedRestoreResult(TEST_PACKAGE, results);


        verify(mBackupManagerMonitorEventSender).sendAgentLoggingResults(
                eq(packageInfo), eq(results), eq(BackupAnnotations.OperationType.RESTORE));
    }

    private static PackageInfo getPackageInfo(String packageName) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.packageName = packageName;
        return packageInfo;
    }

    private class TestBackupService extends UserBackupManagerService {
        boolean isEnabledStatePersisted = false;
        boolean shouldUseNewBackupEligibilityRules = false;

        private volatile Thread mWorkerThread = null;

        TestBackupService(Context context, PackageManager packageManager,
                LifecycleOperationStorage operationStorage, TransportManager transportManager) {
            super(context, packageManager, operationStorage, transportManager);
        }

        @Override
        void writeEnabledState(boolean enable) {
            isEnabledStatePersisted = true;
        }

        @Override
        boolean readEnabledState() {
            return false;
        }

        @Override
        void updateStateOnBackupEnabled(boolean wasEnabled, boolean enable) {}

        @Override
        boolean shouldUseNewBackupEligibilityRules() {
            return shouldUseNewBackupEligibilityRules;
        }

        @Override
        Thread getThreadForAsyncOperation(String operationName, Runnable operation) {
            mWorkerThread = super.getThreadForAsyncOperation(operationName, operation);
            return mWorkerThread;
        }

        @Override
        BackupManagerMonitorEventSender getBMMEventSender(IBackupManagerMonitor monitor) {
            return mBackupManagerMonitorEventSender;
        }

        private void waitForAsyncOperation() {
            if (mWorkerThread == null) {
                return;
            }

            try {
                mWorkerThread.join(/* millis */ WORKER_THREAD_TIMEOUT_MILLISECONDS);
            } catch (InterruptedException e) {
                fail("Failed waiting for worker thread to complete: " + e.getMessage());
            }
        }
    }
}
