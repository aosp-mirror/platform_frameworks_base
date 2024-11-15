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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.ApplicationThreadConstants;
import android.app.IActivityManager;
import android.app.backup.BackupAgent;
import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.backup.BackupAnnotations.OperationType;
import android.app.backup.BackupRestoreEventLogger.DataTypeResult;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.testing.TestableContext;
import android.util.FeatureFlagUtils;
import android.util.KeyValueListParser;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.internal.BackupHandler;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class UserBackupManagerServiceTest {
    private static final String TEST_PACKAGE = "package1";
    private static final String[] TEST_PACKAGES = new String[] { TEST_PACKAGE };
    private static final String TEST_TRANSPORT = "transport";
    private static final int WORKER_THREAD_TIMEOUT_MILLISECONDS = 100;
    @UserIdInt private static final int USER_ID = 0;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock IBackupManagerMonitor mBackupManagerMonitor;
    @Mock IBackupObserver mBackupObserver;
    @Mock PackageManager mPackageManager;
    @Mock TransportConnection mTransportConnection;
    @Mock TransportManager mTransportManager;
    @Mock BackupTransportClient mBackupTransport;
    @Mock BackupEligibilityRules mBackupEligibilityRules;
    @Mock LifecycleOperationStorage mOperationStorage;
    @Mock JobScheduler mJobScheduler;
    @Mock BackupHandler mBackupHandler;
    @Mock BackupManagerMonitorEventSender mBackupManagerMonitorEventSender;
    @Mock IActivityManager mActivityManager;
    @Mock
    ActivityManagerInternal mActivityManagerInternal;

    private TestableContext mContext;
    private MockitoSession mSession;
    private TestBackupService mService;
    private ApplicationInfo mTestPackageApplicationInfo;

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

        mContext = new TestableContext(ApplicationProvider.getApplicationContext());
        mContext.addMockSystemService(JobScheduler.class, mJobScheduler);
        mContext.getTestablePermissions().setPermission(android.Manifest.permission.BACKUP,
                PackageManager.PERMISSION_GRANTED);

        mService = new TestBackupService();
        mService.setEnabled(true);
        mService.setSetupComplete(true);
        mService.enqueueFullBackup("com.test.backup.app", /* lastBackedUp= */ 0);

        mTestPackageApplicationInfo = new ApplicationInfo();
        mTestPackageApplicationInfo.packageName = TEST_PACKAGE;
    }

    @After
    public void tearDown() {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    @Test
    public void testSetFrameworkSchedulingEnabled_enablesAndSchedulesBackups() throws Exception {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.BACKUP_SCHEDULING_ENABLED, 0);

        mService.setFrameworkSchedulingEnabled(true);

        assertThat(mService.isFrameworkSchedulingEnabled()).isTrue();
        verify(mJobScheduler).schedule(
                matchesJobWithId(KeyValueBackupJob.getJobIdForUserId(
                        USER_ID)));
        verify(mJobScheduler).schedule(
                matchesJobWithId(FullBackupJob.getJobIdForUserId(
                        USER_ID)));
    }

    private static JobInfo matchesJobWithId(int id) {
        return argThat((jobInfo) -> jobInfo.getId() == id);
    }

    @Test
    public void testSetFrameworkSchedulingEnabled_disablesAndCancelBackups() throws Exception {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.BACKUP_SCHEDULING_ENABLED, 1);

        mService.setFrameworkSchedulingEnabled(false);

        assertThat(mService.isFrameworkSchedulingEnabled()).isFalse();
        verify(mJobScheduler).cancel(FullBackupJob.getJobIdForUserId(USER_ID));
        verify(mJobScheduler).cancel(KeyValueBackupJob.getJobIdForUserId(USER_ID));
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
                eq(packageInfo), eq(results), eq(OperationType.RESTORE));
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    public void bindToAgentSynchronous_restrictedModeChangesFlagOff_shouldUseRestrictedMode()
            throws Exception {
        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(true));
        // Make sure we never hit the code that checks the property.
        verify(mPackageManager, never()).getPropertyAsUser(any(), any(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    public void bindToAgentSynchronous_keyValueBackup_shouldNotUseRestrictedMode()
            throws Exception {
        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
        // Make sure we never hit the code that checks the property.
        verify(mPackageManager, never()).getPropertyAsUser(any(), any(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    public void bindToAgentSynchronous_keyValueRestore_shouldNotUseRestrictedMode()
            throws Exception {
        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_RESTORE, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
        // Make sure we never hit the code that checks the property.
        verify(mPackageManager, never()).getPropertyAsUser(any(), any(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    public void bindToAgentSynchronous_packageOptedIn_shouldUseRestrictedMode()
            throws Exception {
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE),
                eq(TEST_PACKAGE), any(), anyInt())).thenReturn(new PackageManager.Property(
                PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE, /* value= */ true,
                TEST_PACKAGE, /* className= */ null));

        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(true));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    public void bindToAgentSynchronous_packageOptedOut_shouldNotUseRestrictedMode()
            throws Exception {
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE),
                eq(TEST_PACKAGE), any(), anyInt())).thenReturn(new PackageManager.Property(
                PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE, /* value= */ false,
                TEST_PACKAGE, /* className= */ null));

        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    @DisableCompatChanges({UserBackupManagerService.OS_DECIDES_BACKUP_RESTRICTED_MODE})
    public void bindToAgentSynchronous_targetSdkBelowB_shouldUseRestrictedMode()
            throws Exception {
        // Mock that the app has not explicitly set the property.
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE),
                eq(TEST_PACKAGE), any(), anyInt())).thenThrow(
                    new PackageManager.NameNotFoundException()
        );

        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(true));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    @EnableCompatChanges({UserBackupManagerService.OS_DECIDES_BACKUP_RESTRICTED_MODE})
    public void bindToAgentSynchronous_targetSdkB_notInList_shouldUseRestrictedMode()
            throws Exception {
        // Mock that the app has not explicitly set the property.
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE),
                eq(TEST_PACKAGE), any(), anyInt())).thenThrow(
                    new PackageManager.NameNotFoundException()
        );
        mService.clearNoRestrictedModePackages();

        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(true));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    @EnableCompatChanges({UserBackupManagerService.OS_DECIDES_BACKUP_RESTRICTED_MODE})
    public void bindToAgentSynchronous_forRestore_targetSdkB_inList_shouldNotUseRestrictedMode()
            throws Exception {
        // Mock that the app has not explicitly set the property.
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE),
                eq(TEST_PACKAGE), any(), anyInt())).thenThrow(
                    new PackageManager.NameNotFoundException()
        );
        mService.setNoRestrictedModePackages(Set.of(TEST_PACKAGE), OperationType.RESTORE);

        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_RESTORE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    @EnableCompatChanges({UserBackupManagerService.OS_DECIDES_BACKUP_RESTRICTED_MODE})
    public void bindToAgentSynchronous_forBackup_targetSdkB_inList_shouldNotUseRestrictedMode()
            throws Exception {
        // Mock that the app has not explicitly set the property.
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE),
                eq(TEST_PACKAGE), any(), anyInt())).thenThrow(
                    new PackageManager.NameNotFoundException()
        );
        mService.setNoRestrictedModePackages(Set.of(TEST_PACKAGE), OperationType.BACKUP);

        mService.bindToAgentSynchronous(mTestPackageApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
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

        TestBackupService() {
            super(mContext, mPackageManager, mOperationStorage, mTransportManager, mBackupHandler,
                    createConstants(mContext), mActivityManager, mActivityManagerInternal);
        }

        private static BackupManagerConstants createConstants(Context context) {
            BackupManagerConstants constants = new BackupManagerConstants(
                    Handler.getMain(),
                    context.getContentResolver());
            // This will trigger constants default values to be set thus preventing invalid values
            // being used in tests.
            constants.update(new KeyValueListParser(','));
            return constants;
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
