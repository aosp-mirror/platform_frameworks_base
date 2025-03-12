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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ApplicationThreadConstants;
import android.app.IActivityManager;
import android.app.IBackupAgent;
import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.backup.BackupAnnotations.OperationType;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.backup.internal.LifecycleOperationStorage;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

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

import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupAgentConnectionManagerTest {
    private static final String TEST_PACKAGE = "com.test.package";

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    IActivityManager mActivityManager;
    @Mock
    ActivityManagerInternal mActivityManagerInternal;
    @Mock
    LifecycleOperationStorage mOperationStorage;
    @Mock
    UserBackupManagerService mUserBackupManagerService;
    @Mock
    IBackupAgent.Stub mBackupAgentStub;
    @Mock
    PackageManager mPackageManager;

    private BackupAgentConnectionManager mConnectionManager;
    private MockitoSession mSession;
    private ApplicationInfo mTestApplicationInfo;
    private IBackupAgent mBackupAgentResult;
    private Thread mTestThread;

    @Before
    public void setUp() throws Exception {
        mSession = mockitoSession().initMocks(this).mockStatic(ActivityManager.class).mockStatic(
                LocalServices.class).strictness(Strictness.LENIENT).startMocking();
        MockitoAnnotations.initMocks(this);

        doReturn(mActivityManager).when(ActivityManager::getService);
        doReturn(mActivityManagerInternal).when(
                () -> LocalServices.getService(ActivityManagerInternal.class));
        // Real package manager throws if a property is not defined.
        when(mPackageManager.getPropertyAsUser(any(), any(), any(), anyInt())).thenThrow(
                new PackageManager.NameNotFoundException());

        mConnectionManager = spy(
                new BackupAgentConnectionManager(mOperationStorage, mPackageManager,
                        mUserBackupManagerService, UserHandle.USER_SYSTEM));

        mTestApplicationInfo = new ApplicationInfo();
        mTestApplicationInfo.packageName = TEST_PACKAGE;
        mTestApplicationInfo.processName = TEST_PACKAGE;
        mTestApplicationInfo.uid = Process.FIRST_APPLICATION_UID + 1;

        mBackupAgentResult = null;
        mTestThread = null;
    }

    @After
    public void tearDown() {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    @Test
    public void bindToAgentSynchronous_amReturnsFailure_returnsNullAndClearsPendingBackups()
            throws Exception {
        when(mActivityManager.bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                anyBoolean())).thenReturn(false);

        IBackupAgent result = mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        assertThat(result).isNull();
        verify(mActivityManagerInternal).clearPendingBackup(UserHandle.USER_SYSTEM);
    }

    @Test
    public void bindToAgentSynchronous_agentDisconnectedCalled_returnsNullAndClearsPendingBackups()
            throws Exception {
        when(mActivityManager.bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                anyBoolean())).thenReturn(true);
        // This is so that IBackupAgent.Stub.asInterface() works.
        when(mBackupAgentStub.queryLocalInterface(any())).thenReturn(mBackupAgentStub);
        when(mConnectionManager.getCallingUid()).thenReturn(Process.SYSTEM_UID);

        // This is going to block until it receives the callback so we need to run it on a
        // separate thread.
        Thread testThread = new Thread(() -> setBackupAgentResult(
                mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                        ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD)),
                "backup-agent-connection-manager-test");
        testThread.start();
        // Give the testThread a head start, otherwise agentConnected() might run before
        // bindToAgentSynchronous() is called.
        Thread.sleep(500);
        mConnectionManager.agentDisconnected(TEST_PACKAGE);
        testThread.join();

        assertThat(mBackupAgentResult).isNull();
        verify(mActivityManagerInternal).clearPendingBackup(UserHandle.USER_SYSTEM);
    }

    @Test
    public void bindToAgentSynchronous_agentConnectedCalled_returnsBackupAgent() throws Exception {
        bindAndConnectToTestAppAgent(ApplicationThreadConstants.BACKUP_MODE_FULL);

        assertThat(mBackupAgentResult).isEqualTo(mBackupAgentStub);
        verify(mActivityManagerInternal, never()).clearPendingBackup(anyInt());
    }

    @Test
    public void bindToAgentSynchronous_unexpectedAgentConnected_doesNotReturnWrongAgent()
            throws Exception {
        when(mActivityManager.bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                anyBoolean())).thenReturn(true);
        // This is so that IBackupAgent.Stub.asInterface() works.
        when(mBackupAgentStub.queryLocalInterface(any())).thenReturn(mBackupAgentStub);
        when(mConnectionManager.getCallingUid()).thenReturn(Process.SYSTEM_UID);

        // This is going to block until it receives the callback so we need to run it on a
        // separate thread.
        Thread testThread = new Thread(() -> setBackupAgentResult(
                mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                        ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD)),
                "backup-agent-connection-manager-test");
        testThread.start();
        // Give the testThread a head start, otherwise agentConnected() might run before
        // bindToAgentSynchronous() is called.
        Thread.sleep(500);
        mConnectionManager.agentConnected("com.other.package", mBackupAgentStub);
        testThread.join(100); // Avoid waiting the full timeout.

        assertThat(mBackupAgentResult).isNull();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    public void bindToAgentSynchronous_restrictedModeChangesFlagOff_shouldUseRestrictedMode()
            throws Exception {
        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
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
        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
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
        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_RESTORE, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
        // Make sure we never hit the code that checks the property.
        verify(mPackageManager, never()).getPropertyAsUser(any(), any(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    public void bindToAgentSynchronous_packageOptedIn_shouldUseRestrictedMode() throws Exception {
        reset(mPackageManager);
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE), eq(TEST_PACKAGE), any(),
                anyInt())).thenReturn(new PackageManager.Property(
                PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE, /* value= */ true,
                TEST_PACKAGE, /* className= */ null));

        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(true));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    public void bindToAgentSynchronous_packageOptedOut_shouldNotUseRestrictedMode()
            throws Exception {
        reset(mPackageManager);
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE), eq(TEST_PACKAGE), any(),
                anyInt())).thenReturn(new PackageManager.Property(
                PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE, /* value= */ false,
                TEST_PACKAGE, /* className= */ null));

        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    @DisableCompatChanges({BackupAgentConnectionManager.OS_DECIDES_BACKUP_RESTRICTED_MODE})
    public void bindToAgentSynchronous_targetSdkBelowB_shouldUseRestrictedMode() throws Exception {
        reset(mPackageManager);
        // Mock that the app has not explicitly set the property.
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE), eq(TEST_PACKAGE), any(),
                anyInt())).thenThrow(new PackageManager.NameNotFoundException());

        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(true));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    @EnableCompatChanges({BackupAgentConnectionManager.OS_DECIDES_BACKUP_RESTRICTED_MODE})
    public void bindToAgentSynchronous_targetSdkB_notInList_shouldUseRestrictedMode()
            throws Exception {
        reset(mPackageManager);
        // Mock that the app has not explicitly set the property.
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE), eq(TEST_PACKAGE), any(),
                anyInt())).thenThrow(new PackageManager.NameNotFoundException());
        mConnectionManager.clearNoRestrictedModePackages();

        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(true));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    @EnableCompatChanges({BackupAgentConnectionManager.OS_DECIDES_BACKUP_RESTRICTED_MODE})
    public void bindToAgentSynchronous_forRestore_targetSdkB_inList_shouldNotUseRestrictedMode()
            throws Exception {
        reset(mPackageManager);
        // Mock that the app has not explicitly set the property.
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE), eq(TEST_PACKAGE), any(),
                anyInt())).thenThrow(new PackageManager.NameNotFoundException());
        mConnectionManager.setNoRestrictedModePackages(Set.of(TEST_PACKAGE), OperationType.RESTORE);

        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_RESTORE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_RESTRICTED_MODE_CHANGES)
    @EnableCompatChanges({BackupAgentConnectionManager.OS_DECIDES_BACKUP_RESTRICTED_MODE})
    public void bindToAgentSynchronous_forBackup_targetSdkB_inList_shouldNotUseRestrictedMode()
            throws Exception {
        reset(mPackageManager);
        // Mock that the app has not explicitly set the property.
        when(mPackageManager.getPropertyAsUser(
                eq(PackageManager.PROPERTY_USE_RESTRICTED_BACKUP_MODE), eq(TEST_PACKAGE), any(),
                anyInt())).thenThrow(new PackageManager.NameNotFoundException());
        mConnectionManager.setNoRestrictedModePackages(Set.of(TEST_PACKAGE), OperationType.BACKUP);

        mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo,
                ApplicationThreadConstants.BACKUP_MODE_FULL, BackupDestination.CLOUD);

        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));
    }

    @Test
    public void agentDisconnected_cancelsCurrentOperations() throws Exception {
        when(mConnectionManager.getCallingUid()).thenReturn(Process.SYSTEM_UID);
        when(mOperationStorage.operationTokensForPackage(eq(TEST_PACKAGE))).thenReturn(
                ImmutableSet.of(123, 456, 789));
        when(mConnectionManager.getThreadForCancellation(any())).thenAnswer(invocation -> {
            Thread testThread = new Thread((Runnable) invocation.getArgument(0),
                    "agent-disconnected-test");
            setTestThread(testThread);
            return testThread;
        });

        mConnectionManager.agentDisconnected(TEST_PACKAGE);

        mTestThread.join();
        verify(mUserBackupManagerService).handleCancel(eq(123), eq(true));
        verify(mUserBackupManagerService).handleCancel(eq(456), eq(true));
        verify(mUserBackupManagerService).handleCancel(eq(789), eq(true));
    }

    @Test
    public void unbindAgent_callsAmUnbindBackupAgent() throws Exception {
        mConnectionManager.unbindAgent(mTestApplicationInfo, /* allowKill= */ false);

        verify(mActivityManager).unbindBackupAgent(eq(mTestApplicationInfo));
    }

    @Test
    public void unbindAgent_doNotAllowKill_doesNotKillApp() throws Exception {
        mConnectionManager.unbindAgent(mTestApplicationInfo, /* allowKill= */ false);

        verify(mActivityManager, never()).killApplicationProcess(any(), anyInt());
    }

    @Test
    public void unbindAgent_allowKill_isCoreApp_doesNotKillApp() throws Exception {
        mTestApplicationInfo.uid = 1000;

        mConnectionManager.unbindAgent(mTestApplicationInfo, /* allowKill= */ true);

        verify(mActivityManager, never()).killApplicationProcess(any(), anyInt());
    }

    @Test
    public void unbindAgent_allowKill_notCurrentConnection_killsApp() throws Exception {
        mConnectionManager.unbindAgent(mTestApplicationInfo, /* allowKill= */ true);

        verify(mActivityManager).killApplicationProcess(eq(TEST_PACKAGE), anyInt());
    }

    @Test
    public void unbindAgent_allowKill_inRestrictedMode_killsApp() throws Exception {
        bindAndConnectToTestAppAgent(ApplicationThreadConstants.BACKUP_MODE_FULL);

        mConnectionManager.unbindAgent(mTestApplicationInfo, /* allowKill= */ true);

        verify(mActivityManager).killApplicationProcess(eq(TEST_PACKAGE), anyInt());
    }

    @Test
    public void unbindAgent_allowKill_notInRestrictedMode_doesNotKillApp() throws Exception {
        bindAndConnectToTestAppAgent(ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL);

        mConnectionManager.unbindAgent(mTestApplicationInfo, /* allowKill= */ true);

        verify(mActivityManager, never()).killApplicationProcess(any(), anyInt());
    }

    @Test
    public void unbindAgent_allowKill_isRestore_noKillAfterRestore_doesNotKillApp()
            throws Exception {
        bindAndConnectToTestAppAgent(ApplicationThreadConstants.BACKUP_MODE_RESTORE);
        mTestApplicationInfo.flags = 0;
        verify(mActivityManager).bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                /* useRestrictedMode= */ eq(false));

        mConnectionManager.unbindAgent(mTestApplicationInfo, /* allowKill= */ true);

        verify(mActivityManager, never()).killApplicationProcess(any(), anyInt());
    }

    @Test
    public void unbindAgent_allowKill_isRestore_killAfterRestore_killsApp() throws Exception {
        bindAndConnectToTestAppAgent(ApplicationThreadConstants.BACKUP_MODE_RESTORE);
        mTestApplicationInfo.flags |= ApplicationInfo.FLAG_KILL_AFTER_RESTORE;

        mConnectionManager.unbindAgent(mTestApplicationInfo, /* allowKill= */ true);

        verify(mActivityManager).killApplicationProcess(eq(TEST_PACKAGE), anyInt());
    }

    // Needed because variables can't be assigned directly inside lambdas in Java.
    private void setBackupAgentResult(IBackupAgent result) {
        mBackupAgentResult = result;
    }

    // Needed because variables can't be assigned directly inside lambdas in Java.
    private void setTestThread(Thread thread) {
        mTestThread = thread;
    }

    private void bindAndConnectToTestAppAgent(int backupMode) throws Exception {
        when(mActivityManager.bindBackupAgent(eq(TEST_PACKAGE), anyInt(), anyInt(), anyInt(),
                anyBoolean())).thenReturn(true);
        // This is going to block until it receives the callback so we need to run it on a
        // separate thread.
        Thread testThread = new Thread(() -> setBackupAgentResult(
                mConnectionManager.bindToAgentSynchronous(mTestApplicationInfo, backupMode,
                        BackupDestination.CLOUD)), "backup-agent-connection-manager-test");
        testThread.start();
        // Give the testThread a head start, otherwise agentConnected() might run before
        // bindToAgentSynchronous() is called.
        Thread.sleep(500);
        when(mConnectionManager.getCallingUid()).thenReturn(Process.SYSTEM_UID);
        // This is so that IBackupAgent.Stub.asInterface() works.
        when(mBackupAgentStub.queryLocalInterface(any())).thenReturn(mBackupAgentStub);
        mConnectionManager.agentConnected(TEST_PACKAGE, mBackupAgentStub);
        testThread.join();
    }
}
