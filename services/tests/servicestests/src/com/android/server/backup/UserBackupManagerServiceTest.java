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

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.app.backup.BackupAgent;
import android.app.backup.BackupManager.OperationType;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.android.internal.backup.IBackupTransport;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.params.BackupParams;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.utils.BackupEligibilityRules;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class UserBackupManagerServiceTest {
    private static final String TEST_PACKAGE = "package1";
    private static final String[] TEST_PACKAGES = new String[] { TEST_PACKAGE };

    @Mock Context mContext;
    @Mock IBackupManagerMonitor mBackupManagerMonitor;
    @Mock IBackupObserver mBackupObserver;
    @Mock PackageManager mPackageManager;
    @Mock TransportClient mTransportClient;
    @Mock IBackupTransport mBackupTransport;
    @Mock BackupEligibilityRules mBackupEligibilityRules;


    private TestBackupService mService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mService = new TestBackupService(mContext, mPackageManager);
        mService.setEnabled(true);
        mService.setSetupComplete(true);
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
                mTransportClient, /* transportDirName */ "", OnTaskFinishedListener.NOP);

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
                mTransportClient, /* transportDirName */ "", OnTaskFinishedListener.NOP);

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
                mTransportClient, /* transportDirName */ "", OnTaskFinishedListener.NOP);

        assertThat(params.kvPackages).isEmpty();
        assertThat(params.fullPackages).isEmpty();
        assertThat(params.mBackupEligibilityRules).isEqualTo(mBackupEligibilityRules);
    }

    @Test
    public void testGetOperationTypeFromTransport_returnsBackupByDefault()
            throws Exception {
        when(mTransportClient.connectOrThrow(any())).thenReturn(mBackupTransport);
        when(mBackupTransport.getTransportFlags()).thenReturn(0);

        int operationType = mService.getOperationTypeFromTransport(mTransportClient);

        assertThat(operationType).isEqualTo(OperationType.BACKUP);
    }

    @Test
    public void testGetOperationTypeFromTransport_returnsMigrationForMigrationTransport()
            throws Exception {
        // This is a temporary flag to control the new behaviour until it's ready to be fully
        // rolled out.
        mService.shouldUseNewBackupEligibilityRules = true;

        when(mTransportClient.connectOrThrow(any())).thenReturn(mBackupTransport);
        when(mBackupTransport.getTransportFlags()).thenReturn(
                BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER);

        int operationType = mService.getOperationTypeFromTransport(mTransportClient);

        assertThat(operationType).isEqualTo(OperationType.MIGRATION);
    }

    private static PackageInfo getPackageInfo(String packageName) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.packageName = packageName;
        return packageInfo;
    }

    private static class TestBackupService extends UserBackupManagerService {
        boolean isEnabledStatePersisted = false;
        boolean shouldUseNewBackupEligibilityRules = false;

        TestBackupService(Context context, PackageManager packageManager) {
            super(context, packageManager);
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
    }
}
