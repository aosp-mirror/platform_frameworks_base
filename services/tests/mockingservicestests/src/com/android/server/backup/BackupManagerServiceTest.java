/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.UserIdInt;
import android.app.backup.BackupManager;
import android.app.backup.ISelectBackupTransportCallback;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.ConditionVariable;
import android.os.FileUtils;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.SystemService;
import com.android.server.backup.utils.RandomAccessFileUtils;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupManagerServiceTest {
    private static final ComponentName TRANSPORT_COMPONENT_NAME = new ComponentName("package",
            "class");
    private static final int NON_SYSTEM_USER = UserHandle.USER_SYSTEM + 1;

    @UserIdInt
    private int mUserId;
    @Mock
    private UserBackupManagerService mSystemUserBackupManagerService;
    @Mock
    private UserBackupManagerService mNonSystemUserBackupManagerService;
    @Mock
    private Context mContextMock;
    @Mock
    private PrintWriter mPrintWriterMock;
    @Mock
    private UserManager mUserManagerMock;
    @Mock
    private UserInfo mUserInfoMock;

    private FileDescriptor mFileDescriptorStub = new FileDescriptor();

    private BackupManagerServiceTestable mService;
    private BackupManagerService.Lifecycle mServiceLifecycle;
    private static File sTestDir;
    private MockitoSession mSession;

    @Before
    public void setUp() throws Exception {
        mSession =
                ExtendedMockito.mockitoSession().initMocks(
                                this)
                        .strictness(Strictness.LENIENT)
                        .spyStatic(UserBackupManagerService.class)
                        .startMocking();
        doReturn(mSystemUserBackupManagerService).when(
                () -> UserBackupManagerService.createAndInitializeService(
                        eq(UserHandle.USER_SYSTEM), any(), any(), any()));
        doReturn(mNonSystemUserBackupManagerService).when(
                () -> UserBackupManagerService.createAndInitializeService(eq(NON_SYSTEM_USER),
                        any(), any(), any()));

        mUserId = UserHandle.USER_SYSTEM;

        when(mUserManagerMock.getUserInfo(UserHandle.USER_SYSTEM)).thenReturn(mUserInfoMock);
        when(mUserManagerMock.getUserInfo(NON_SYSTEM_USER)).thenReturn(mUserInfoMock);
        // Null main user means there is no main user on the device.
        when(mUserManagerMock.getMainUser()).thenReturn(null);

        BackupManagerServiceTestable.sCallingUserId = UserHandle.USER_SYSTEM;
        BackupManagerServiceTestable.sCallingUid = Process.SYSTEM_UID;
        BackupManagerServiceTestable.sBackupDisabled = false;
        BackupManagerServiceTestable.sUserManagerMock = mUserManagerMock;

        sTestDir = InstrumentationRegistry.getContext().getFilesDir();
        sTestDir.mkdirs();

        when(mContextMock.getSystemService(Context.JOB_SCHEDULER_SERVICE))
                .thenReturn(mock(JobScheduler.class));
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContentsAndDir(sTestDir);
        mSession.finishMocking();
    }

    @Test
    public void onUnlockUser_startsUserService() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(NON_SYSTEM_USER, true);
        ConditionVariable unlocked = new ConditionVariable(false);

        simulateUserUnlocked(NON_SYSTEM_USER);

        assertNotNull(mService.getUserService(NON_SYSTEM_USER));
    }

    @Test
    public void startServiceForUser_backupDisabledGlobally_doesNotStartUserService() {
        createBackupManagerServiceAndUnlockSystemUser();
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerServiceTestable service = new BackupManagerServiceTestable(mContextMock);

        service.startServiceForUser(UserHandle.USER_SYSTEM);

        assertNull(service.getUserService(UserHandle.USER_SYSTEM));
    }

    @Test
    public void startServiceForUser_backupNotActiveForUser_doesNotStartUserService() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        mService.startServiceForUser(UserHandle.USER_SYSTEM);

        assertNull(mService.getUserService(UserHandle.USER_SYSTEM));
    }

    @Test
    public void startServiceForUser_backupEnabledGloballyAndActiveForUser_startsUserService() {
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);

        mService.startServiceForUser(NON_SYSTEM_USER);

        assertNotNull(mService.getUserService(NON_SYSTEM_USER));
    }

    @Test
    public void isBackupServiceActive_backupDisabledGlobally_returnFalse() {
        createBackupManagerServiceAndUnlockSystemUser();
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerService service = new BackupManagerServiceTestable(mContextMock);
        service.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertFalse(service.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_systemUser_isDefault_deactivated_returnsFalse() {
        createBackupManagerServiceAndUnlockSystemUser();
        // If there's no 'main' user on the device, the default user is the system user.
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_systemUser_isNotDefault_returnsFalse() {
        createBackupManagerServiceAndUnlockSystemUser();
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);

        assertFalse(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_systemUser_isDefault_returnsTrue() {
        createBackupManagerServiceAndUnlockSystemUser();

        // If there's no 'main' user on the device, the default user is the system user.
        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_nonSystemUser_isDefault_systemUserDeactivated_returnsFalse() {
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mService.isBackupServiceActive(NON_SYSTEM_USER));
    }

    @Test
    public void isBackupServiceActive_nonSystemUser_isDefault_deactivated_returnsFalse() {
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);
        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        assertFalse(mService.isBackupServiceActive(NON_SYSTEM_USER));
    }

    @Test
    public void isBackupServiceActive_nonSystemUser_isDefault_returnsTrue() {
        createBackupManagerServiceAndUnlockSystemUser();

        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);

        assertTrue(mService.isBackupServiceActive(NON_SYSTEM_USER));
    }

    @Test
    public void isBackupServiceActive_nonSystemUser_isNotDefault_notActivated_returnsFalse() {
        createBackupManagerServiceAndUnlockSystemUser();

        // By default non-system non-default users are not activated.
        assertFalse(mService.isBackupServiceActive(NON_SYSTEM_USER));
    }

    @Test
    public void isBackupServiceActive_nonSystemUser_isNotDefault_activated_returnsTrue() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertTrue(mService.isBackupServiceActive(NON_SYSTEM_USER));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerSystemUid_createsService() {
        createBackupManagerServiceAndUnlockSystemUser();
        BackupManagerServiceTestable.sCallingUid = Process.SYSTEM_UID;

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mService.isUserReadyForBackup(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerRootUid_createsService() {
        createBackupManagerServiceAndUnlockSystemUser();
        BackupManagerServiceTestable.sCallingUid = Process.ROOT_UID;

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mService.isUserReadyForBackup(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerNonRootNonSystem_throws() {
        createBackupManagerServiceAndUnlockSystemUser();
        BackupManagerServiceTestable.sCallingUid = Process.FIRST_APPLICATION_UID;

        try {
            mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerSystemUid_createsService() {
        createBackupManagerServiceAndUnlockSystemUser();
        simulateUserUnlocked(NON_SYSTEM_USER);
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        BackupManagerServiceTestable.sCallingUid = Process.SYSTEM_UID;

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertTrue(mService.isUserReadyForBackup(NON_SYSTEM_USER));
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerRootUid_createsService() {
        createBackupManagerServiceAndUnlockSystemUser();
        simulateUserUnlocked(NON_SYSTEM_USER);
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        BackupManagerServiceTestable.sCallingUid = Process.ROOT_UID;

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertTrue(mService.isUserReadyForBackup(NON_SYSTEM_USER));
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerNonRootNonSystem_throws() {
        createBackupManagerServiceAndUnlockSystemUser();
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        BackupManagerServiceTestable.sCallingUid = Process.FIRST_APPLICATION_UID;

        try {
            mService.setBackupServiceActive(NON_SYSTEM_USER, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_forNonSystemUserAndCallerWithoutBackupPermission_throws() {
        createBackupManagerServiceAndUnlockSystemUser();
        doThrow(new SecurityException())
                .when(mContextMock)
                .enforceCallingOrSelfPermission(eq(Manifest.permission.BACKUP), anyString());

        try {
            mService.setBackupServiceActive(NON_SYSTEM_USER, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_forNonSystemUserAndCallerWithoutUserPermission_throws() {
        createBackupManagerServiceAndUnlockSystemUser();
        doThrow(new SecurityException())
                .when(mContextMock)
                .enforceCallingOrSelfPermission(
                        eq(Manifest.permission.INTERACT_ACROSS_USERS_FULL), anyString());

        try {
            mService.setBackupServiceActive(NON_SYSTEM_USER, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_backupDisabledGlobally_ignored() {
        createBackupManagerServiceAndUnlockSystemUser();
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerServiceTestable service = new BackupManagerServiceTestable(mContextMock);

        service.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertFalse(service.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_alreadyActive_ignored() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_systemUser_makeActive_deletesSuppressFile() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertFalse(getFakeSuppressFileForUser(UserHandle.USER_SYSTEM).exists());
    }

    @Test
    public void setBackupServiceActive_systemUser_makeNonActive_createsSuppressFile() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertTrue(getFakeSuppressFileForUser(UserHandle.USER_SYSTEM).exists());
    }

    @Test
    public void setBackupServiceActive_systemUser_makeNonActive_stopsUserService() {
        createBackupManagerServiceAndUnlockSystemUser();
        assertTrue(mService.isUserReadyForBackup(UserHandle.USER_SYSTEM));

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mService.isUserReadyForBackup(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_nonSystemUser_isDefault_makeActive_createsService() {
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);
        simulateUserUnlocked(NON_SYSTEM_USER);
        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertTrue(mService.isUserReadyForBackup(NON_SYSTEM_USER));
    }

    @Test
    public void setBackupServiceActive_nonSystemUser_isDefault_makeActive_deletesSuppressFile() {
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);
        simulateUserUnlocked(NON_SYSTEM_USER);
        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertFalse(getFakeSuppressFileForUser(NON_SYSTEM_USER).exists());
    }

    @Test
    public void setBackupServiceActive_nonSystemUser_isDefault_makeNonActive_createsSuppressFile() {
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);
        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        assertTrue(getFakeSuppressFileForUser(NON_SYSTEM_USER).exists());
    }

    @Test
    public void setBackupServiceActive_nonSystemUser_isDefault_makeNonActive_stopsUserService() {
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);
        simulateUserUnlocked(NON_SYSTEM_USER);
        assertTrue(mService.isUserReadyForBackup(NON_SYSTEM_USER));

        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        assertFalse(mService.isUserReadyForBackup(NON_SYSTEM_USER));
    }

    @Test
    public void setBackupServiceActive_nonSystemUser_isNotDefault_makeActive_createsService() {
        createBackupManagerServiceAndUnlockSystemUser();
        simulateUserUnlocked(NON_SYSTEM_USER);

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertTrue(mService.isUserReadyForBackup(NON_SYSTEM_USER));
    }

    @Test
    public void setBackupServiceActive_nonSystemUser_isNotDefault_makeActive_createActivatedFile() {
        createBackupManagerServiceAndUnlockSystemUser();

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertTrue(getFakeActivatedFileForUser(NON_SYSTEM_USER).exists());
    }

    @Test
    public void setBackupServiceActive_nonSystemUser_isNotDefault_makeNonActive_stopsUserService() {
        createBackupManagerServiceAndUnlockSystemUser();
        simulateUserUnlocked(NON_SYSTEM_USER);
        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        assertFalse(mService.isUserReadyForBackup(NON_SYSTEM_USER));
    }

    @Test
    public void setBackupServiceActive_nonSystemUser_isNotDefault_makeActive_deleteActivatedFile() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        assertFalse(getFakeActivatedFileForUser(NON_SYSTEM_USER).exists());
    }

    @Test
    public void setBackupServiceActive_forOneNonSystemUser_doesNotActivateForAllNonSystemUsers() {
        createBackupManagerServiceAndUnlockSystemUser();
        int otherUser = NON_SYSTEM_USER + 1;
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertFalse(mService.isBackupServiceActive(otherUser));
    }

    @Test
    public void setBackupServiceActive_forNonSystemUser_remembersActivated() {
        createBackupManagerServiceAndUnlockSystemUser();

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);

        assertTrue(RandomAccessFileUtils.readBoolean(
                getFakeRememberActivatedFileForUser(NON_SYSTEM_USER), false));
    }

    @Test
    public void setBackupServiceActiveFalse_forNonSystemUser_remembersActivated() {
        createBackupManagerServiceAndUnlockSystemUser();

        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        assertFalse(RandomAccessFileUtils.readBoolean(
                getFakeRememberActivatedFileForUser(NON_SYSTEM_USER), true));
    }

    @Test
    public void setBackupServiceActiveTwice_forNonSystemUser_remembersLastActivated() {
        createBackupManagerServiceAndUnlockSystemUser();

        mService.setBackupServiceActive(NON_SYSTEM_USER, true);
        mService.setBackupServiceActive(NON_SYSTEM_USER, false);

        assertFalse(RandomAccessFileUtils.readBoolean(
                getFakeRememberActivatedFileForUser(NON_SYSTEM_USER), true));
    }

    @Test
    public void selectBackupTransportAsyncForUser_beforeUserUnlocked_notifiesBackupNotAllowed()
            throws Exception {
        mService = new BackupManagerServiceTestable(mContextMock);
        CompletableFuture<Integer> future = new CompletableFuture<>();
        ISelectBackupTransportCallback listener =
                new ISelectBackupTransportCallback.Stub() {
                    @Override
                    public void onSuccess(String transportName) {
                        future.completeExceptionally(new AssertionError());
                    }

                    @Override
                    public void onFailure(int reason) {
                        future.complete(reason);
                    }
                };

        mService.selectBackupTransportAsyncForUser(mUserId, TRANSPORT_COMPONENT_NAME, listener);

        assertEquals(BackupManager.ERROR_BACKUP_NOT_ALLOWED, (int) future.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void selectBackupTransportAsyncForUser_beforeUserUnlockedWithNullListener_doesNotThrow()
            throws Exception {
        createBackupManagerServiceAndUnlockSystemUser();

        mService.selectBackupTransportAsyncForUser(mUserId, TRANSPORT_COMPONENT_NAME, null);

        // No crash.
    }

    @Test
    public void selectBackupTransportAsyncForUser_beforeUserUnlockedListenerThrowing_doesNotThrow()
            throws Exception {
        createBackupManagerServiceAndUnlockSystemUser();

        ISelectBackupTransportCallback.Stub listener =
                new ISelectBackupTransportCallback.Stub() {
                    @Override
                    public void onSuccess(String transportName) {
                    }

                    @Override
                    public void onFailure(int reason) throws RemoteException {
                        throw new RemoteException();
                    }
                };

        mService.selectBackupTransportAsyncForUser(mUserId, TRANSPORT_COMPONENT_NAME, listener);

        // No crash.
    }

    @Test
    public void dump_callerDoesNotHaveDumpPermission_ignored() {
        createBackupManagerServiceAndUnlockSystemUser();
        when(mContextMock.checkCallingOrSelfPermission(
                Manifest.permission.DUMP)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mService.dump(mFileDescriptorStub, mPrintWriterMock, new String[0]);

        verify(mSystemUserBackupManagerService, never()).dump(any(), any(), any());
        verify(mNonSystemUserBackupManagerService, never()).dump(any(), any(), any());
    }

    @Test
    public void dump_callerDoesNotHavePackageUsageStatsPermission_ignored() {
        createBackupManagerServiceAndUnlockSystemUser();
        when(mContextMock.checkCallingOrSelfPermission(
                Manifest.permission.PACKAGE_USAGE_STATS)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mService.dump(mFileDescriptorStub, mPrintWriterMock, new String[0]);

        verify(mSystemUserBackupManagerService, never()).dump(any(), any(), any());
        verify(mNonSystemUserBackupManagerService, never()).dump(any(), any(), any());
    }

    @Test
    public void testDumpForOneUser_callerDoesNotHaveInteractAcrossUsersFullPermission_ignored() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(NON_SYSTEM_USER, true);
        simulateUserUnlocked(NON_SYSTEM_USER);

        doThrow(new SecurityException())
                .when(mContextMock)
                .enforceCallingOrSelfPermission(
                        eq(Manifest.permission.INTERACT_ACROSS_USERS_FULL), anyString());

        String[] args = new String[]{"--user", Integer.toString(NON_SYSTEM_USER)};
        Assert.assertThrows(SecurityException.class,
                () -> mService.dumpWithoutCheckingPermission(mFileDescriptorStub, mPrintWriterMock,
                        args));

        verify(mNonSystemUserBackupManagerService, never()).dump(any(), any(), any());
    }

    @Test
    public void
            testDumpForOneUser_callerHasInteractAcrossUsersFullPermission_dumpsOnlySpecifiedUser() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(NON_SYSTEM_USER, true);
        simulateUserUnlocked(NON_SYSTEM_USER);

        String[] args = new String[]{"--user", Integer.toString(UserHandle.USER_SYSTEM)};
        mService.dumpWithoutCheckingPermission(mFileDescriptorStub, mPrintWriterMock, args);

        verify(mSystemUserBackupManagerService).dump(any(), any(), any());
    }

    @Test
    public void testDumpForAllUsers_callerHasInteractAcrossUsersFullPermission_dumpsAllUsers() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(NON_SYSTEM_USER, true);
        simulateUserUnlocked(NON_SYSTEM_USER);

        String[] args = new String[]{"users"};
        mService.dumpWithoutCheckingPermission(mFileDescriptorStub, mPrintWriterMock, args);

        // Check that dump() invocations are not called on user's Backup service,
        // as 'dumpsys backup users' only list users for whom Backup service is running.
        verify(mSystemUserBackupManagerService, never()).dump(any(), any(), any());
        verify(mNonSystemUserBackupManagerService, never()).dump(any(), any(), any());
    }

    /**
     * Test that {@link BackupManagerService#dump(FileDescriptor, PrintWriter, String[])} dumps
     * system user information before non-system user information.
     */
    @Test
    public void testDump_systemUserFirst() {
        createBackupManagerServiceAndUnlockSystemUser();
        mService.setBackupServiceActive(NON_SYSTEM_USER, true);
        simulateUserUnlocked(NON_SYSTEM_USER);
        String[] args = new String[0];
        mService.dumpWithoutCheckingPermission(mFileDescriptorStub, mPrintWriterMock, args);

        InOrder inOrder =
                inOrder(mSystemUserBackupManagerService, mNonSystemUserBackupManagerService);
        inOrder.verify(mSystemUserBackupManagerService)
                .dump(mFileDescriptorStub, mPrintWriterMock, args);
        inOrder.verify(mNonSystemUserBackupManagerService)
                .dump(mFileDescriptorStub, mPrintWriterMock, args);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testGetUserForAncestralSerialNumber_forSystemUser() {
        createBackupManagerServiceAndUnlockSystemUser();
        simulateUserUnlocked(NON_SYSTEM_USER);
        when(mUserManagerMock.getProfileIds(UserHandle.getCallingUserId(), false))
                .thenReturn(new int[]{UserHandle.USER_SYSTEM, NON_SYSTEM_USER});
        when(mSystemUserBackupManagerService.getAncestralSerialNumber()).thenReturn(11L);

        UserHandle user = mService.getUserForAncestralSerialNumber(11L);

        assertThat(user).isEqualTo(UserHandle.of(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testGetUserForAncestralSerialNumber_forNonSystemUser() {
        setMockMainUserAndCreateBackupManagerService(NON_SYSTEM_USER);
        simulateUserUnlocked(NON_SYSTEM_USER);
        when(mUserManagerMock.getProfileIds(UserHandle.getCallingUserId(), false))
                .thenReturn(new int[] {UserHandle.USER_SYSTEM, NON_SYSTEM_USER});
        when(mNonSystemUserBackupManagerService.getAncestralSerialNumber()).thenReturn(11L);

        UserHandle user = mService.getUserForAncestralSerialNumber(11L);

        assertThat(user).isEqualTo(UserHandle.of(NON_SYSTEM_USER));
    }

    @Test
    public void testGetUserForAncestralSerialNumber_whenDisabled() {
        createBackupManagerServiceAndUnlockSystemUser();
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerService backupManagerService = new BackupManagerServiceTestable(mContextMock);
        when(mSystemUserBackupManagerService.getAncestralSerialNumber()).thenReturn(11L);

        UserHandle user = backupManagerService.getUserForAncestralSerialNumber(11L);

        assertThat(user).isNull();
    }

    @Test
    public void onUserUnlocking_mainUserChanged_firstUnlockAfterReboot_updatesDefaultUser() {
        // Create BMS *before* setting a main user to simulate the main user being created after
        // BMS, which can happen for the first ever boot of a new device.
        mService = new BackupManagerServiceTestable(mContextMock);
        mServiceLifecycle = new BackupManagerService.Lifecycle(mContextMock, mService);
        when(mUserManagerMock.getMainUser()).thenReturn(UserHandle.of(NON_SYSTEM_USER));
        assertFalse(mService.isBackupServiceActive(NON_SYSTEM_USER));

        simulateUserUnlocked(UserHandle.USER_SYSTEM);

        assertTrue(mService.isBackupServiceActive(NON_SYSTEM_USER));
    }

    @Test
    public void onUserUnlocking_mainUserChanged_firstUnlockAfterReboot_doesNotStartForSystemUser() {
        // Create BMS *before* setting a main user to simulate the main user being created after
        // BMS, which can happen for the first ever boot of a new device.
        mService = new BackupManagerServiceTestable(mContextMock);
        mServiceLifecycle = new BackupManagerService.Lifecycle(mContextMock, mService);
        when(mUserManagerMock.getMainUser()).thenReturn(UserHandle.of(NON_SYSTEM_USER));
        assertFalse(mService.isBackupServiceActive(NON_SYSTEM_USER));

        simulateUserUnlocked(UserHandle.USER_SYSTEM);

        assertFalse(mService.isUserReadyForBackup(UserHandle.USER_SYSTEM));
    }

    @Test
    public void onUserUnlocking_mainUserChanged_secondUnlockAfterReboot_doesNotUpdateDefaultUser() {
        // Create BMS *before* setting a main user to simulate the main user being created after
        // BMS, which can happen for the first ever boot of a new device.
        createBackupManagerServiceAndUnlockSystemUser();
        when(mUserManagerMock.getMainUser()).thenReturn(UserHandle.of(NON_SYSTEM_USER));
        assertFalse(mService.isBackupServiceActive(NON_SYSTEM_USER));

        simulateUserUnlocked(NON_SYSTEM_USER);

        assertFalse(mService.isBackupServiceActive(NON_SYSTEM_USER));
    }

    private void createBackupManagerServiceAndUnlockSystemUser() {
        mService = new BackupManagerServiceTestable(mContextMock);
        mServiceLifecycle = new BackupManagerService.Lifecycle(mContextMock, mService);
        simulateUserUnlocked(UserHandle.USER_SYSTEM);
    }

    /**
     * The 'default' user is set in the constructor of {@link BackupManagerService} so we need to
     * start a new service after mocking the 'main' user.
     */
    private void setMockMainUserAndCreateBackupManagerService(int userId) {
        when(mUserManagerMock.getMainUser()).thenReturn(UserHandle.of(userId));
        mService = new BackupManagerServiceTestable(mContextMock);
        mServiceLifecycle = new BackupManagerService.Lifecycle(mContextMock, mService);
    }

    private void simulateUserUnlocked(int userId) {
        ConditionVariable unlocked = new ConditionVariable(false);
        mServiceLifecycle.onUserUnlocking(
                new SystemService.TargetUser(
                        new UserInfo(userId, /* name= */ "test", /* flags= */ 0)));
        mService.getBackupHandler().post(unlocked::open);
        unlocked.block();
        when(mUserManagerMock.isUserUnlocked(userId)).thenReturn(true);
    }

    private static File getFakeSuppressFileForUser(int userId) {
        return new File(sTestDir, "suppress-" + userId);
    }

    private static File getFakeActivatedFileForUser(int userId) {
        return new File(sTestDir, "activated-" + userId);
    }

    private static File getFakeRememberActivatedFileForUser(int userId) {
        return new File(sTestDir, "rememberActivated-" + userId);
    }

    private static class BackupManagerServiceTestable extends BackupManagerService {
        static boolean sBackupDisabled = false;
        static int sCallingUserId = -1;
        static int sCallingUid = -1;
        static UserManager sUserManagerMock = null;

        BackupManagerServiceTestable(Context context) {
            super(context);
        }

        @Override
        protected UserManager getUserManager() {
            return sUserManagerMock;
        }

        @Override
        protected boolean isBackupDisabled() {
            return sBackupDisabled;
        }

        @Override
        protected File getSuppressFileForUser(int userId) {
            return getFakeSuppressFileForUser(userId);
        }

        @Override
        protected File getRememberActivatedFileForNonSystemUser(int userId) {
            return getFakeRememberActivatedFileForUser(userId);
        }

        @Override
        protected File getActivatedFileForUser(int userId) {
            return getFakeActivatedFileForUser(userId);
        }

        @Override
        protected int binderGetCallingUserId() {
            return sCallingUserId;
        }

        @Override
        protected int binderGetCallingUid() {
            return sCallingUid;
        }

        @Override
        protected void postToHandler(Runnable runnable) {
            runnable.run();
        }
    }
}
