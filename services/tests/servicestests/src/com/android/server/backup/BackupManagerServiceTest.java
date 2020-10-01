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

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.utils.RandomAccessFileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupManagerServiceTest {
    private static final ComponentName TRANSPORT_COMPONENT_NAME = new ComponentName("package",
            "class");
    private static final int NON_USER_SYSTEM = UserHandle.USER_SYSTEM + 1;
    private static final int UNSTARTED_NON_USER_SYSTEM = UserHandle.USER_SYSTEM + 2;

    @UserIdInt
    private int mUserId;
    @Mock
    private UserBackupManagerService mUserBackupManagerService;
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
    private File mTestDir;
    private File mSuppressFile;
    private SparseArray<UserBackupManagerService> mUserServices;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUserId = UserHandle.USER_SYSTEM;

        mUserServices = new SparseArray<>();
        mUserServices.append(UserHandle.USER_SYSTEM, mUserBackupManagerService);
        mUserServices.append(NON_USER_SYSTEM, mNonSystemUserBackupManagerService);

        when(mUserManagerMock.getUserInfo(UserHandle.USER_SYSTEM)).thenReturn(mUserInfoMock);
        when(mUserManagerMock.getUserInfo(NON_USER_SYSTEM)).thenReturn(mUserInfoMock);
        when(mUserManagerMock.getUserInfo(UNSTARTED_NON_USER_SYSTEM)).thenReturn(mUserInfoMock);

        BackupManagerServiceTestable.sCallingUserId = UserHandle.USER_SYSTEM;
        BackupManagerServiceTestable.sCallingUid = Process.SYSTEM_UID;
        BackupManagerServiceTestable.sBackupDisabled = false;
        BackupManagerServiceTestable.sUserManagerMock = mUserManagerMock;

        mTestDir = InstrumentationRegistry.getContext().getFilesDir();
        mTestDir.mkdirs();

        mSuppressFile = new File(mTestDir, "suppress");
        BackupManagerServiceTestable.sSuppressFile = mSuppressFile;

        setUpStateFilesForNonSystemUser(NON_USER_SYSTEM);
        setUpStateFilesForNonSystemUser(UNSTARTED_NON_USER_SYSTEM);

        when(mContextMock.getSystemService(Context.JOB_SCHEDULER_SERVICE))
                .thenReturn(mock(JobScheduler.class));
        mService = new BackupManagerServiceTestable(mContextMock, mUserServices);
    }

    private void setUpStateFilesForNonSystemUser(int userId) {
        File activatedFile = new File(mTestDir, "activate-" + userId);
        BackupManagerServiceTestable.sActivatedFiles.append(userId, activatedFile);
        File rememberActivatedFile = new File(mTestDir, "rem-activate-" + userId);
        BackupManagerServiceTestable.sRememberActivatedFiles.append(userId, rememberActivatedFile);
    }

    @After
    public void tearDown() throws Exception {
        mSuppressFile.delete();
        deleteFiles(BackupManagerServiceTestable.sActivatedFiles);
        deleteFiles(BackupManagerServiceTestable.sRememberActivatedFiles);
    }

    private void deleteFiles(SparseArray<File> files) {
        int numFiles = files.size();
        for (int i = 0; i < numFiles; i++) {
            files.valueAt(i).delete();
        }
    }

    @Test
    public void testIsBackupServiceActive_whenBackupsNotDisabledAndSuppressFileDoesNotExist() {
        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testOnUnlockUser_forNonSystemUserWhenBackupsDisabled_doesNotStartUser() {
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerServiceTestable service =
                new BackupManagerServiceTestable(mContextMock, new SparseArray<>());
        ConditionVariable unlocked = new ConditionVariable(false);

        service.onUnlockUser(NON_USER_SYSTEM);

        service.getBackupHandler().post(unlocked::open);
        unlocked.block();
        assertNull(service.getUserService(NON_USER_SYSTEM));
    }

    @Test
    public void testOnUnlockUser_forSystemUserWhenBackupsDisabled_doesNotStartUser() {
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerServiceTestable service =
                new BackupManagerServiceTestable(mContextMock, new SparseArray<>());
        ConditionVariable unlocked = new ConditionVariable(false);

        service.onUnlockUser(UserHandle.USER_SYSTEM);

        service.getBackupHandler().post(unlocked::open);
        unlocked.block();
        assertNull(service.getUserService(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testOnUnlockUser_whenBackupNotActivated_doesNotStartUser() {
        BackupManagerServiceTestable.sBackupDisabled = false;
        BackupManagerServiceTestable service =
                new BackupManagerServiceTestable(mContextMock, new SparseArray<>());
        service.setBackupServiceActive(NON_USER_SYSTEM, false);
        ConditionVariable unlocked = new ConditionVariable(false);

        service.onUnlockUser(NON_USER_SYSTEM);

        service.getBackupHandler().post(unlocked::open);
        unlocked.block();
        assertNull(service.getUserService(NON_USER_SYSTEM));
    }

    @Test
    public void testIsBackupServiceActive_forSystemUserWhenBackupDisabled_returnsTrue()
            throws Exception {
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerService backupManagerService =
                new BackupManagerServiceTestable(mContextMock, mUserServices);
        backupManagerService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertFalse(backupManagerService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testIsBackupServiceActive_forNonSystemUserWhenBackupDisabled_returnsTrue()
            throws Exception {
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerService backupManagerService =
                new BackupManagerServiceTestable(mContextMock, mUserServices);
        backupManagerService.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertFalse(backupManagerService.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_forSystemUser_returnsTrueWhenActivated() throws Exception {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_forSystemUser_returnsFalseWhenDeactivated() throws Exception {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_forNonSystemUser_returnsFalseWhenSystemUserDeactivated()
            throws Exception {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);
        mService.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertFalse(mService.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_forNonSystemUser_returnsFalseWhenNonSystemUserDeactivated()
            throws Exception {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        // Don't activate non-system user.

        assertFalse(mService.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void
            isBackupServiceActive_forNonSystemUser_returnsTrueWhenSystemAndNonSystemUserActivated()
            throws Exception {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        mService.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void
            isBackupServiceActive_forUnstartedNonSystemUser_returnsTrueWhenSystemAndUserActivated()
            throws Exception {
        mService.setBackupServiceActive(UNSTARTED_NON_USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(UNSTARTED_NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerSystemUid_serviceCreated() {
        BackupManagerServiceTestable.sCallingUid = Process.SYSTEM_UID;

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerRootUid_serviceCreated() {
        BackupManagerServiceTestable.sCallingUid = Process.ROOT_UID;

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerNonRootNonSystem_throws() {
        BackupManagerServiceTestable.sCallingUid = Process.FIRST_APPLICATION_UID;

        try {
            mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerSystemUid_serviceCreated() {
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        BackupManagerServiceTestable.sCallingUid = Process.SYSTEM_UID;

        mService.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerRootUid_serviceCreated() {
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        BackupManagerServiceTestable.sCallingUid = Process.ROOT_UID;

        mService.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerNonRootNonSystem_throws() {
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        BackupManagerServiceTestable.sCallingUid = Process.FIRST_APPLICATION_UID;

        try {
            mService.setBackupServiceActive(NON_USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_forNonSystemUserAndCallerWithoutBackupPermission_throws() {
        doThrow(new SecurityException())
                .when(mContextMock)
                .enforceCallingOrSelfPermission(eq(Manifest.permission.BACKUP), anyString());

        try {
            mService.setBackupServiceActive(NON_USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_forNonSystemUserAndCallerWithoutUserPermission_throws() {
        doThrow(new SecurityException())
                .when(mContextMock)
                .enforceCallingOrSelfPermission(
                        eq(Manifest.permission.INTERACT_ACROSS_USERS_FULL), anyString());

        try {
            mService.setBackupServiceActive(NON_USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_backupDisabled_ignored() {
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerServiceTestable service =
                new BackupManagerServiceTestable(mContextMock, mUserServices);

        service.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertFalse(service.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_alreadyActive_ignored() {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_makeNonActive_alreadyNonActive_ignored() {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_makeActive_serviceCreatedAndSuppressFileDeleted() {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_makeNonActive_serviceDeletedAndSuppressFileCreated()
            throws IOException {
        assertTrue(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));

        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mService.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupActive_nonSystemUser_disabledForSystemUser_ignored() {
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, false);
        mService.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertFalse(mService.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forOneNonSystemUser_doesNotActivateForAllNonSystemUsers() {
        int otherUser = NON_USER_SYSTEM + 1;
        File activateFile = new File(mTestDir, "activate-" + otherUser);
        BackupManagerServiceTestable.sActivatedFiles.append(otherUser, activateFile);
        mService.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        mService.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(mService.isBackupServiceActive(NON_USER_SYSTEM));
        assertFalse(mService.isBackupServiceActive(otherUser));
        activateFile.delete();
    }

    @Test
    public void setBackupServiceActive_forNonSystemUser_remembersActivated() {

        mService.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(RandomAccessFileUtils.readBoolean(
                BackupManagerServiceTestable.sRememberActivatedFiles.get(NON_USER_SYSTEM), false));
    }

    @Test
    public void setBackupServiceActiveFalse_forNonSystemUser_remembersActivated() {

        mService.setBackupServiceActive(NON_USER_SYSTEM, false);

        assertFalse(RandomAccessFileUtils.readBoolean(
                BackupManagerServiceTestable.sRememberActivatedFiles.get(NON_USER_SYSTEM), true));
    }

    @Test
    public void setBackupServiceActiveTwice_forNonSystemUser_remembersLastActivated() {
        mService.setBackupServiceActive(NON_USER_SYSTEM, true);
        mService.setBackupServiceActive(NON_USER_SYSTEM, false);

        assertFalse(RandomAccessFileUtils.readBoolean(
                BackupManagerServiceTestable.sRememberActivatedFiles.get(NON_USER_SYSTEM), true));
    }

    @Test
    public void selectBackupTransportAsyncForUser_beforeUserUnlocked_notifiesBackupNotAllowed()
            throws Exception {
        mUserServices.clear();
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
        mService.selectBackupTransportAsyncForUser(mUserId, TRANSPORT_COMPONENT_NAME, null);

        // No crash.
    }

    @Test
    public void
            selectBackupTransportAsyncForUser_beforeUserUnlockedListenerThrowing_doesNotThrow()
            throws Exception {
        ISelectBackupTransportCallback.Stub listener =
                new ISelectBackupTransportCallback.Stub() {
                    @Override
                    public void onSuccess(String transportName) {}
                    @Override
                    public void onFailure(int reason) throws RemoteException {
                        throw new RemoteException();
                    }
                };

        mService.selectBackupTransportAsyncForUser(mUserId, TRANSPORT_COMPONENT_NAME, listener);

        // No crash.
    }

    @Test
    public void dump_callerDoesNotHavePermission_ignored() {
        when(mContextMock.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mService.dump(mFileDescriptorStub, mPrintWriterMock, new String[0]);

        verifyNoMoreInteractions(mUserBackupManagerService);
        verifyNoMoreInteractions(mNonSystemUserBackupManagerService);
    }

    /**
     * Test that {@link BackupManagerService#dump()} dumps system user information before non-system
     * user information.
     */
    @Test
    public void testDump_systemUserFirst() {
        String[] args = new String[0];
        mService.dumpWithoutCheckingPermission(mFileDescriptorStub, mPrintWriterMock, args);

        InOrder inOrder =
                inOrder(mUserBackupManagerService, mNonSystemUserBackupManagerService);
        inOrder.verify(mUserBackupManagerService)
                .dump(mFileDescriptorStub, mPrintWriterMock, args);
        inOrder.verify(mNonSystemUserBackupManagerService)
                .dump(mFileDescriptorStub, mPrintWriterMock, args);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testGetUserForAncestralSerialNumber_forSystemUser() {
        BackupManagerServiceTestable.sBackupDisabled = false;
        BackupManagerService backupManagerService =
                new BackupManagerServiceTestable(mContextMock, mUserServices);
        when(mUserManagerMock.getProfileIds(UserHandle.getCallingUserId(), false))
                .thenReturn(new int[] {UserHandle.USER_SYSTEM, NON_USER_SYSTEM});
        when(mUserBackupManagerService.getAncestralSerialNumber()).thenReturn(11L);

        UserHandle user = backupManagerService.getUserForAncestralSerialNumber(11L);

        assertThat(user).isEqualTo(UserHandle.of(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testGetUserForAncestralSerialNumber_forNonSystemUser() {
        BackupManagerServiceTestable.sBackupDisabled = false;
        BackupManagerService backupManagerService =
                new BackupManagerServiceTestable(mContextMock, mUserServices);
        when(mUserManagerMock.getProfileIds(UserHandle.getCallingUserId(), false))
                .thenReturn(new int[] {UserHandle.USER_SYSTEM, NON_USER_SYSTEM});
        when(mNonSystemUserBackupManagerService.getAncestralSerialNumber()).thenReturn(11L);

        UserHandle user = backupManagerService.getUserForAncestralSerialNumber(11L);

        assertThat(user).isEqualTo(UserHandle.of(NON_USER_SYSTEM));
    }

    @Test
    public void testGetUserForAncestralSerialNumber_whenDisabled() {
        BackupManagerServiceTestable.sBackupDisabled = true;
        BackupManagerService backupManagerService =
                new BackupManagerServiceTestable(mContextMock, mUserServices);
        when(mUserBackupManagerService.getAncestralSerialNumber()).thenReturn(11L);

        UserHandle user = backupManagerService.getUserForAncestralSerialNumber(11L);

        assertThat(user).isNull();
    }

    private static class BackupManagerServiceTestable extends BackupManagerService {
        static boolean sBackupDisabled = false;
        static int sCallingUserId = -1;
        static int sCallingUid = -1;
        static File sSuppressFile = null;
        static SparseArray<File> sActivatedFiles = new SparseArray<>();
        static SparseArray<File> sRememberActivatedFiles = new SparseArray<>();
        static UserManager sUserManagerMock = null;

        BackupManagerServiceTestable(
                Context context, SparseArray<UserBackupManagerService> userServices) {
            super(context, userServices);
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
        protected File getSuppressFileForSystemUser() {
            return sSuppressFile;
        }

        @Override
        protected File getRememberActivatedFileForNonSystemUser(int userId) {
            return sRememberActivatedFiles.get(userId);
        }

        @Override
        protected File getActivatedFileForNonSystemUser(int userId) {
            return sActivatedFiles.get(userId);
        }

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
