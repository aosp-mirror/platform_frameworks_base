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
 * limitations under the License
 */

package com.android.server.backup;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.UserIdInt;
import android.app.backup.BackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TrampolineTest {
    private static final String PACKAGE_NAME = "some.package.name";
    private static final String TRANSPORT_NAME = "some.transport.name";
    private static final String CURRENT_PASSWORD = "current_password";
    private static final String NEW_PASSWORD = "new_password";
    private static final String ENCRYPTION_PASSWORD = "encryption_password";
    private static final String DATA_MANAGEMENT_LABEL = "data_management_label";
    private static final String DESTINATION_STRING = "destination_string";
    private static final String[] PACKAGE_NAMES =
            new String[]{"some.package.name._1", "some.package.name._2"};
    private static final String[] TRANSPORTS =
            new String[]{"some.transport.name._1", "some.transport.name._2"};
    private static final ComponentName TRANSPORT_COMPONENT_NAME = new ComponentName("package",
            "class");
    private static final ComponentName[] TRANSPORT_COMPONENTS = new ComponentName[]{
            new ComponentName("package1", "class1"),
            new ComponentName("package2", "class2")
    };
    private static final int NON_USER_SYSTEM = UserHandle.USER_SYSTEM + 1;

    @UserIdInt
    private int mUserId;
    @Mock
    private BackupManagerService mBackupManagerServiceMock;
    @Mock
    private UserBackupManagerService mUserBackupManagerService;
    @Mock
    private Context mContextMock;
    @Mock
    private IBinder mAgentMock;
    @Mock
    private ParcelFileDescriptor mParcelFileDescriptorMock;
    @Mock
    private IFullBackupRestoreObserver mFullBackupRestoreObserverMock;
    @Mock
    private IBackupObserver mBackupObserverMock;
    @Mock
    private IBackupManagerMonitor mBackupManagerMonitorMock;
    @Mock
    private PrintWriter mPrintWriterMock;
    @Mock
    private UserManager mUserManagerMock;
    @Mock
    private UserInfo mUserInfoMock;

    private FileDescriptor mFileDescriptorStub = new FileDescriptor();

    private TrampolineTestable mTrampoline;
    private File mTestDir;
    private File mSuppressFile;
    private File mActivatedFile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUserId = UserHandle.USER_SYSTEM;

        SparseArray<UserBackupManagerService> serviceUsers = new SparseArray<>();
        serviceUsers.append(UserHandle.USER_SYSTEM, mUserBackupManagerService);
        serviceUsers.append(NON_USER_SYSTEM, mUserBackupManagerService);
        when(mBackupManagerServiceMock.getServiceUsers()).thenReturn(serviceUsers);

        when(mUserManagerMock.getUserInfo(UserHandle.USER_SYSTEM)).thenReturn(mUserInfoMock);
        when(mUserManagerMock.getUserInfo(NON_USER_SYSTEM)).thenReturn(mUserInfoMock);

        TrampolineTestable.sBackupManagerServiceMock = mBackupManagerServiceMock;
        TrampolineTestable.sCallingUserId = UserHandle.USER_SYSTEM;
        TrampolineTestable.sCallingUid = Process.SYSTEM_UID;
        TrampolineTestable.sBackupDisabled = false;
        TrampolineTestable.sUserManagerMock = mUserManagerMock;

        mTestDir = InstrumentationRegistry.getContext().getFilesDir();
        mTestDir.mkdirs();

        mSuppressFile = new File(mTestDir, "suppress");
        TrampolineTestable.sSuppressFile = mSuppressFile;

        mActivatedFile = new File(mTestDir, "activate-" + NON_USER_SYSTEM);
        TrampolineTestable.sActivatedFiles.append(NON_USER_SYSTEM, mActivatedFile);

        mTrampoline = new TrampolineTestable(mContextMock);
    }

    @After
    public void tearDown() throws Exception {
        mSuppressFile.delete();
        mActivatedFile.delete();
    }

    @Test
    public void initializeService_successfullyInitializesBackupService() {
        mTrampoline.initializeService();

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void initializeService_globallyDisabled_nonInitialized() {
        TrampolineTestable.sBackupDisabled = true;
        TrampolineTestable trampoline = new TrampolineTestable(mContextMock);

        trampoline.initializeService();

        assertFalse(trampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void initializeService_doesNotStartServiceForUsers() {
        mTrampoline.initializeService();

        verify(mBackupManagerServiceMock, never()).startServiceForUser(anyInt());
    }

    @Test
    public void isBackupServiceActive_calledBeforeInitialize_returnsFalse() {
        assertFalse(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_forSystemUser_returnsTrueWhenActivated() throws Exception {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_forSystemUser_returnsFalseWhenDeactivated() throws Exception {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_forNonSystemUser_returnsFalseWhenSystemUserDeactivated()
            throws Exception {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);
        mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertFalse(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_forNonSystemUser_returnsFalseWhenNonSystemUserDeactivated()
            throws Exception {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        // Don't activate non-system user.

        assertFalse(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void
            isBackupServiceActive_forNonSystemUser_returnsTrueWhenSystemAndNonSystemUserActivated()
                throws Exception {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerSystemUid_serviceCreated() {
        mTrampoline.initializeService();
        TrampolineTestable.sCallingUid = Process.SYSTEM_UID;

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerRootUid_serviceCreated() {
        mTrampoline.initializeService();
        TrampolineTestable.sCallingUid = Process.ROOT_UID;

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forSystemUserAndCallerNonRootNonSystem_throws() {
        mTrampoline.initializeService();
        TrampolineTestable.sCallingUid = Process.FIRST_APPLICATION_UID;

        try {
            mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerSystemUid_serviceCreated() {
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        mTrampoline.initializeService();
        TrampolineTestable.sCallingUid = Process.SYSTEM_UID;

        mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerRootUid_serviceCreated() {
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        mTrampoline.initializeService();
        TrampolineTestable.sCallingUid = Process.ROOT_UID;

        mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forManagedProfileAndCallerNonRootNonSystem_throws() {
        when(mUserInfoMock.isManagedProfile()).thenReturn(true);
        mTrampoline.initializeService();
        TrampolineTestable.sCallingUid = Process.FIRST_APPLICATION_UID;

        try {
            mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_forNonSystemUserAndCallerWithoutBackupPermission_throws() {
        doThrow(new SecurityException())
                .when(mContextMock)
                .enforceCallingOrSelfPermission(eq(Manifest.permission.BACKUP), anyString());
        mTrampoline.initializeService();

        try {
            mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);
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
        mTrampoline.initializeService();

        try {
            mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void setBackupServiceActive_backupDisabled_ignored() {
        TrampolineTestable.sBackupDisabled = true;
        TrampolineTestable trampoline = new TrampolineTestable(mContextMock);
        trampoline.initializeService();

        trampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertFalse(trampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_alreadyActive_ignored() {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
        assertEquals(1, mTrampoline.getCreateServiceCallsCount());

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
        assertEquals(1, mTrampoline.getCreateServiceCallsCount());
    }

    @Test
    public void setBackupServiceActive_makeNonActive_alreadyNonActive_ignored() {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_makeActive_serviceCreatedAndSuppressFileDeleted() {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_makeNonActive_serviceDeletedAndSuppressFileCreated()
            throws IOException {
        mTrampoline.initializeService();
        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupActive_nonSystemUser_disabledForSystemUser_ignored() {
        mTrampoline.initializeService();
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);
        mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertFalse(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_forOneNonSystemUser_doesNotActivateForAllNonSystemUsers() {
        mTrampoline.initializeService();
        int otherUser = NON_USER_SYSTEM + 1;
        File activateFile = new File(mTestDir, "activate-" + otherUser);
        TrampolineTestable.sActivatedFiles.append(otherUser, activateFile);
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
        assertFalse(mTrampoline.isBackupServiceActive(otherUser));
        activateFile.delete();
    }

    @Test
    public void dataChanged_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.dataChanged(PACKAGE_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void dataChangedForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.dataChangedForUser(mUserId, PACKAGE_NAME);

        verify(mBackupManagerServiceMock).dataChanged(mUserId, PACKAGE_NAME);
    }

    @Test
    public void dataChanged_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.dataChanged(PACKAGE_NAME);

        verify(mBackupManagerServiceMock).dataChanged(mUserId, PACKAGE_NAME);
    }

    @Test
    public void clearBackupData_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.clearBackupData(TRANSPORT_NAME, PACKAGE_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void clearBackupDataForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.clearBackupDataForUser(mUserId, TRANSPORT_NAME, PACKAGE_NAME);

        verify(mBackupManagerServiceMock).clearBackupData(mUserId, TRANSPORT_NAME, PACKAGE_NAME);
    }

    @Test
    public void clearBackupData_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.clearBackupData(TRANSPORT_NAME, PACKAGE_NAME);

        verify(mBackupManagerServiceMock).clearBackupData(mUserId, TRANSPORT_NAME, PACKAGE_NAME);
    }

    @Test
    public void agentConnected_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.agentConnected(PACKAGE_NAME, mAgentMock);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void agentConnectedForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.agentConnectedForUser(mUserId, PACKAGE_NAME, mAgentMock);

        verify(mBackupManagerServiceMock).agentConnected(mUserId, PACKAGE_NAME, mAgentMock);
    }

    @Test
    public void agentConnected_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.agentConnected(PACKAGE_NAME, mAgentMock);

        verify(mBackupManagerServiceMock).agentConnected(mUserId, PACKAGE_NAME, mAgentMock);
    }

    @Test
    public void agentDisconnected_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.agentDisconnected(PACKAGE_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void agentDisconnectedForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.agentDisconnectedForUser(mUserId, PACKAGE_NAME);

        verify(mBackupManagerServiceMock).agentDisconnected(mUserId, PACKAGE_NAME);
    }

    @Test
    public void agentDisconnected_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.agentDisconnected(PACKAGE_NAME);

        verify(mBackupManagerServiceMock).agentDisconnected(mUserId, PACKAGE_NAME);
    }

    @Test
    public void restoreAtInstall_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.restoreAtInstall(PACKAGE_NAME, 123);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void restoreAtInstallForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.restoreAtInstallForUser(mUserId, PACKAGE_NAME, 123);

        verify(mBackupManagerServiceMock).restoreAtInstall(mUserId, PACKAGE_NAME, 123);
    }

    @Test
    public void restoreAtInstall_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.restoreAtInstall(PACKAGE_NAME, 123);

        verify(mBackupManagerServiceMock).restoreAtInstall(mUserId, PACKAGE_NAME, 123);
    }

    @Test
    public void setBackupEnabled_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.setBackupEnabled(true);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void setBackupEnabledForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.setBackupEnabledForUser(mUserId, true);

        verify(mBackupManagerServiceMock).setBackupEnabled(mUserId, true);
    }

    @Test
    public void setBackupEnabled_forwardedToCallingUserId() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.setBackupEnabled(true);

        verify(mBackupManagerServiceMock).setBackupEnabled(mUserId, true);
    }

    @Test
    public void setAutoRestore_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.setAutoRestore(true);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void setAutoRestoreForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.setAutoRestoreForUser(mUserId, true);

        verify(mBackupManagerServiceMock).setAutoRestore(mUserId, true);
    }

    @Test
    public void setAutoRestore_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.setAutoRestore(true);

        verify(mBackupManagerServiceMock).setAutoRestore(mUserId, true);
    }

    @Test
    public void isBackupEnabled_calledBeforeInitialize_ignored() throws Exception {
        assertFalse(mTrampoline.isBackupEnabled());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void isBackupEnabledForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.isBackupEnabledForUser(mUserId);

        verify(mBackupManagerServiceMock).isBackupEnabled(mUserId);
    }

    @Test
    public void isBackupEnabled_forwardedToCallingUserId() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.isBackupEnabled();

        verify(mBackupManagerServiceMock).isBackupEnabled(mUserId);
    }

    @Test
    public void setBackupPassword_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.setBackupPassword(CURRENT_PASSWORD, NEW_PASSWORD);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void setBackupPassword_forwarded() throws Exception {
        mTrampoline.initializeService();
        mTrampoline.setBackupPassword(CURRENT_PASSWORD, NEW_PASSWORD);
        verify(mBackupManagerServiceMock).setBackupPassword(CURRENT_PASSWORD, NEW_PASSWORD);
    }

    @Test
    public void hasBackupPassword_calledBeforeInitialize_ignored() throws Exception {
        assertFalse(mTrampoline.hasBackupPassword());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void hasBackupPassword_forwarded() throws Exception {
        mTrampoline.initializeService();
        mTrampoline.hasBackupPassword();
        verify(mBackupManagerServiceMock).hasBackupPassword();
    }

    @Test
    public void backupNow_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.backupNow();
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void backupNowForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.backupNowForUser(mUserId);

        verify(mBackupManagerServiceMock).backupNow(mUserId);
    }

    @Test
    public void backupNow_forwardedToCallingUserId() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.backupNow();

        verify(mBackupManagerServiceMock).backupNow(mUserId);
    }

    @Test
    public void adbBackup_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.adbBackup(mUserId, mParcelFileDescriptorMock, true, true,
                true, true, true, true, true, true,
                PACKAGE_NAMES);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void adbBackup_forwarded() throws Exception {
        mTrampoline.initializeService();
        mTrampoline.adbBackup(mUserId, mParcelFileDescriptorMock, true, true,
                true, true, true, true, true, true,
                PACKAGE_NAMES);
        verify(mBackupManagerServiceMock).adbBackup(mUserId, mParcelFileDescriptorMock, true,
                true, true, true, true, true, true, true, PACKAGE_NAMES);
    }

    @Test
    public void fullTransportBackup_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.fullTransportBackupForUser(mUserId, PACKAGE_NAMES);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void fullTransportBackupForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.fullTransportBackupForUser(mUserId, PACKAGE_NAMES);

        verify(mBackupManagerServiceMock).fullTransportBackup(mUserId, PACKAGE_NAMES);
    }

    @Test
    public void adbRestore_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.adbRestore(mUserId, mParcelFileDescriptorMock);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void adbRestore_forwarded() throws Exception {
        mTrampoline.initializeService();
        mTrampoline.adbRestore(mUserId, mParcelFileDescriptorMock);
        verify(mBackupManagerServiceMock).adbRestore(mUserId, mParcelFileDescriptorMock);
    }

    @Test
    public void acknowledgeFullBackupOrRestore_calledBeforeInitialize_ignored()
            throws Exception {
        mTrampoline.acknowledgeFullBackupOrRestore(123, true, CURRENT_PASSWORD, ENCRYPTION_PASSWORD,
                mFullBackupRestoreObserverMock);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void acknowledgeFullBackupOrRestoreForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.acknowledgeFullBackupOrRestoreForUser(
                mUserId,
                123,
                true,
                CURRENT_PASSWORD,
                ENCRYPTION_PASSWORD,
                mFullBackupRestoreObserverMock);

        verify(mBackupManagerServiceMock)
                .acknowledgeAdbBackupOrRestore(
                        mUserId,
                        123,
                        true,
                        CURRENT_PASSWORD,
                        ENCRYPTION_PASSWORD,
                        mFullBackupRestoreObserverMock);
    }

    @Test
    public void acknowledgeFullBackupOrRestore_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.acknowledgeFullBackupOrRestore(123, true, CURRENT_PASSWORD, ENCRYPTION_PASSWORD,
                mFullBackupRestoreObserverMock);

        verify(mBackupManagerServiceMock)
                .acknowledgeAdbBackupOrRestore(
                        mUserId,
                        123,
                        true,
                        CURRENT_PASSWORD,
                        ENCRYPTION_PASSWORD,
                        mFullBackupRestoreObserverMock);
    }

    @Test
    public void getCurrentTransport_calledBeforeInitialize_ignored() throws Exception {
        assertNull(mTrampoline.getCurrentTransport());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getCurrentTransportForUser_forwarded() throws Exception {
        when(mBackupManagerServiceMock.getCurrentTransport(mUserId)).thenReturn(TRANSPORT_NAME);
        mTrampoline.initializeService();

        assertEquals(TRANSPORT_NAME, mTrampoline.getCurrentTransportForUser(mUserId));
        verify(mBackupManagerServiceMock).getCurrentTransport(mUserId);
    }

    @Test
    public void getCurrentTransport_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        when(mBackupManagerServiceMock.getCurrentTransport(mUserId)).thenReturn(TRANSPORT_NAME);
        mTrampoline.initializeService();

        assertEquals(TRANSPORT_NAME, mTrampoline.getCurrentTransport());
        verify(mBackupManagerServiceMock).getCurrentTransport(mUserId);
    }

    @Test
    public void listAllTransports_calledBeforeInitialize_ignored() throws Exception {
        assertNull(mTrampoline.listAllTransports());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void listAllTransportsForUser_forwarded() throws Exception {
        when(mBackupManagerServiceMock.listAllTransports(mUserId)).thenReturn(TRANSPORTS);
        mTrampoline.initializeService();

        assertEquals(TRANSPORTS, mTrampoline.listAllTransportsForUser(mUserId));
        verify(mBackupManagerServiceMock).listAllTransports(mUserId);
    }


    @Test
    public void listAllTransports_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        when(mBackupManagerServiceMock.listAllTransports(mUserId)).thenReturn(TRANSPORTS);
        mTrampoline.initializeService();

        assertEquals(TRANSPORTS, mTrampoline.listAllTransports());
        verify(mBackupManagerServiceMock).listAllTransports(mUserId);
    }

    @Test
    public void listAllTransportComponentsForUser_calledBeforeInitialize_ignored()
            throws Exception {
        assertNull(mTrampoline.listAllTransportComponentsForUser(mUserId));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void listAllTransportComponentsForUser_forwarded() throws Exception {
        when(mBackupManagerServiceMock.listAllTransportComponents(mUserId)).thenReturn(
                TRANSPORT_COMPONENTS);
        mTrampoline.initializeService();

        assertEquals(TRANSPORT_COMPONENTS, mTrampoline.listAllTransportComponentsForUser(mUserId));
        verify(mBackupManagerServiceMock).listAllTransportComponents(mUserId);
    }

    @Test
    public void getTransportWhitelist_calledBeforeInitialize_ignored() throws Exception {
        assertNull(mTrampoline.getTransportWhitelist());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getTransportWhitelist_forwarded() {
        when(mBackupManagerServiceMock.getTransportWhitelist()).thenReturn(TRANSPORTS);
        mTrampoline.initializeService();

        assertEquals(TRANSPORTS, mTrampoline.getTransportWhitelist());
        verify(mBackupManagerServiceMock).getTransportWhitelist();
    }

    @Test
    public void updateTransportAttributesForUser_calledBeforeInitialize_ignored() {
        mTrampoline.updateTransportAttributesForUser(
                mUserId,
                TRANSPORT_COMPONENT_NAME,
                TRANSPORT_NAME,
                null,
                "Transport Destination",
                null,
                "Data Management");

        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void updateTransportAttributesForUser_forwarded() {
        when(mBackupManagerServiceMock.getTransportWhitelist()).thenReturn(TRANSPORTS);
        mTrampoline.initializeService();

        mTrampoline.updateTransportAttributesForUser(
                mUserId,
                TRANSPORT_COMPONENT_NAME,
                TRANSPORT_NAME,
                null,
                "Transport Destination",
                null,
                "Data Management");

        verify(mBackupManagerServiceMock)
                .updateTransportAttributes(
                        mUserId,
                        TRANSPORT_COMPONENT_NAME,
                        TRANSPORT_NAME,
                        null,
                        "Transport Destination",
                        null,
                        "Data Management");
    }

    @Test
    public void selectBackupTransport_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.selectBackupTransport(TRANSPORT_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void selectBackupTransportForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.selectBackupTransportForUser(mUserId, TRANSPORT_NAME);

        verify(mBackupManagerServiceMock).selectBackupTransport(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void selectBackupTransport_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.selectBackupTransport(TRANSPORT_NAME);

        verify(mBackupManagerServiceMock).selectBackupTransport(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void selectBackupTransportAsyncForUser_calledBeforeInitialize_ignored()
            throws Exception {
        LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue();

        mTrampoline.selectBackupTransportAsyncForUser(
                mUserId,
                TRANSPORT_COMPONENT_NAME,
                new ISelectBackupTransportCallback() {
                    @Override
                    public void onSuccess(String transportName) throws RemoteException {

                    }

                    @Override
                    public void onFailure(int reason) throws RemoteException {
                        q.offer(reason);
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });

        verifyNoMoreInteractions(mBackupManagerServiceMock);
        Integer errorCode = q.poll(5, TimeUnit.SECONDS);
        assertNotNull(errorCode);
        assertEquals(BackupManager.ERROR_BACKUP_NOT_ALLOWED, (int) errorCode);
    }

    @Test
    public void selectBackupTransportAsyncForUser_calledBeforeInitialize_ignored_nullListener()
            throws Exception {
        mTrampoline.selectBackupTransportAsyncForUser(mUserId, TRANSPORT_COMPONENT_NAME, null);

        verifyNoMoreInteractions(mBackupManagerServiceMock);
        // No crash.
    }

    @Test
    public void selectBackupTransportAsyncForUser_calledBeforeInitialize_ignored_listenerThrows()
            throws Exception {
        mTrampoline.selectBackupTransportAsyncForUser(
                mUserId,
                TRANSPORT_COMPONENT_NAME,
                new ISelectBackupTransportCallback() {
                    @Override
                    public void onSuccess(String transportName) throws RemoteException {

                    }

                    @Override
                    public void onFailure(int reason) throws RemoteException {
                        throw new RemoteException("Crash");
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });

        verifyNoMoreInteractions(mBackupManagerServiceMock);
        // No crash.
    }

    @Test
    public void selectBackupTransportAsyncForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.selectBackupTransportAsyncForUser(mUserId, TRANSPORT_COMPONENT_NAME, null);

        verify(mBackupManagerServiceMock)
                .selectBackupTransportAsync(mUserId, TRANSPORT_COMPONENT_NAME, null);
    }

    @Test
    public void getConfigurationIntent_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.getConfigurationIntent(TRANSPORT_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getConfigurationIntentForUser_forwarded() throws Exception {
        Intent configurationIntentStub = new Intent();
        when(mBackupManagerServiceMock.getConfigurationIntent(mUserId, TRANSPORT_NAME)).thenReturn(
                configurationIntentStub);
        mTrampoline.initializeService();

        assertEquals(
                configurationIntentStub,
                mTrampoline.getConfigurationIntentForUser(mUserId, TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getConfigurationIntent(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void getConfigurationIntent_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        Intent configurationIntentStub = new Intent();
        when(mBackupManagerServiceMock.getConfigurationIntent(mUserId, TRANSPORT_NAME)).thenReturn(
                configurationIntentStub);
        mTrampoline.initializeService();

        assertEquals(configurationIntentStub, mTrampoline.getConfigurationIntent(TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getConfigurationIntent(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void getDestinationString_calledBeforeInitialize_ignored() throws Exception {
        assertNull(mTrampoline.getDestinationString(TRANSPORT_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getDestinationStringForUser_forwarded() throws Exception {
        when(mBackupManagerServiceMock.getDestinationString(mUserId, TRANSPORT_NAME)).thenReturn(
                DESTINATION_STRING);
        mTrampoline.initializeService();

        assertEquals(
                DESTINATION_STRING,
                mTrampoline.getDestinationStringForUser(mUserId, TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDestinationString(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void getDestinationString_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        when(mBackupManagerServiceMock.getDestinationString(mUserId, TRANSPORT_NAME)).thenReturn(
                DESTINATION_STRING);

        mTrampoline.initializeService();
        assertEquals(DESTINATION_STRING, mTrampoline.getDestinationString(TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDestinationString(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void getDataManagementIntent_calledBeforeInitialize_ignored() throws Exception {
        assertNull(mTrampoline.getDataManagementIntent(TRANSPORT_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getDataManagementIntentForUser_forwarded() throws Exception {
        Intent dataManagementIntent = new Intent();
        when(mBackupManagerServiceMock.getDataManagementIntent(mUserId, TRANSPORT_NAME)).thenReturn(
                dataManagementIntent);
        mTrampoline.initializeService();

        assertEquals(
                dataManagementIntent,
                mTrampoline.getDataManagementIntentForUser(mUserId, TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDataManagementIntent(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void getDataManagementIntent_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        Intent dataManagementIntent = new Intent();
        when(mBackupManagerServiceMock.getDataManagementIntent(mUserId, TRANSPORT_NAME)).thenReturn(
                dataManagementIntent);
        mTrampoline.initializeService();

        assertEquals(dataManagementIntent, mTrampoline.getDataManagementIntent(TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDataManagementIntent(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void getDataManagementLabel_calledBeforeInitialize_ignored() throws Exception {
        assertNull(mTrampoline.getDataManagementLabel(TRANSPORT_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getDataManagementLabelForUser_forwarded() throws Exception {
        when(mBackupManagerServiceMock.getDataManagementLabel(mUserId, TRANSPORT_NAME)).thenReturn(
                DATA_MANAGEMENT_LABEL);
        mTrampoline.initializeService();

        assertEquals(
                DATA_MANAGEMENT_LABEL,
                mTrampoline.getDataManagementLabelForUser(mUserId, TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDataManagementLabel(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void getDataManagementLabel_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        when(mBackupManagerServiceMock.getDataManagementLabel(mUserId, TRANSPORT_NAME)).thenReturn(
                DATA_MANAGEMENT_LABEL);
        mTrampoline.initializeService();

        assertEquals(DATA_MANAGEMENT_LABEL, mTrampoline.getDataManagementLabel(TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDataManagementLabel(mUserId, TRANSPORT_NAME);
    }

    @Test
    public void beginRestoreSession_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.beginRestoreSessionForUser(mUserId, PACKAGE_NAME, TRANSPORT_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void beginRestoreSessionForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.beginRestoreSessionForUser(mUserId, PACKAGE_NAME, TRANSPORT_NAME);

        verify(mBackupManagerServiceMock)
                .beginRestoreSession(mUserId, PACKAGE_NAME, TRANSPORT_NAME);
    }

    @Test
    public void opComplete_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.opComplete(1, 2);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void opComplete_forwarded() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.opComplete(1, 2);

        verify(mBackupManagerServiceMock).opComplete(mUserId, 1, 2);
    }

    @Test
    public void getAvailableRestoreTokenForUser_calledBeforeInitialize_ignored() {
        assertEquals(0, mTrampoline.getAvailableRestoreTokenForUser(mUserId, PACKAGE_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getAvailableRestoreTokenForUser_forwarded() {
        when(mBackupManagerServiceMock.getAvailableRestoreToken(mUserId, PACKAGE_NAME))
                .thenReturn(123L);
        mTrampoline.initializeService();

        assertEquals(123, mTrampoline.getAvailableRestoreTokenForUser(mUserId, PACKAGE_NAME));
        verify(mBackupManagerServiceMock).getAvailableRestoreToken(mUserId, PACKAGE_NAME);
    }

    @Test
    public void isAppEligibleForBackupForUser_calledBeforeInitialize_ignored() {
        assertFalse(mTrampoline.isAppEligibleForBackupForUser(mUserId, PACKAGE_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void isAppEligibleForBackupForUser_forwarded() {
        when(mBackupManagerServiceMock.isAppEligibleForBackup(mUserId, PACKAGE_NAME))
                .thenReturn(true);
        mTrampoline.initializeService();

        assertTrue(mTrampoline.isAppEligibleForBackupForUser(mUserId, PACKAGE_NAME));
        verify(mBackupManagerServiceMock).isAppEligibleForBackup(mUserId, PACKAGE_NAME);
    }

    @Test
    public void requestBackup_calledBeforeInitialize_ignored() throws RemoteException {
        assertEquals(BackupManager.ERROR_BACKUP_NOT_ALLOWED, mTrampoline.requestBackup(
                PACKAGE_NAMES, mBackupObserverMock, mBackupManagerMonitorMock, 123));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void requestBackupForUser_forwarded() throws Exception {
        when(mBackupManagerServiceMock.requestBackup(mUserId, PACKAGE_NAMES,
                mBackupObserverMock, mBackupManagerMonitorMock, 123)).thenReturn(456);
        mTrampoline.initializeService();

        assertEquals(456, mTrampoline.requestBackupForUser(mUserId, PACKAGE_NAMES,
                mBackupObserverMock, mBackupManagerMonitorMock, 123));
        verify(mBackupManagerServiceMock).requestBackup(mUserId, PACKAGE_NAMES,
                mBackupObserverMock, mBackupManagerMonitorMock, 123);
    }

    @Test
    public void requestBackup_forwardedToCallingUserId() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        when(mBackupManagerServiceMock.requestBackup(mUserId, PACKAGE_NAMES,
                mBackupObserverMock, mBackupManagerMonitorMock, 123)).thenReturn(456);
        mTrampoline.initializeService();

        assertEquals(456, mTrampoline.requestBackup(PACKAGE_NAMES,
                mBackupObserverMock, mBackupManagerMonitorMock, 123));
        verify(mBackupManagerServiceMock).requestBackup(mUserId, PACKAGE_NAMES,
                mBackupObserverMock, mBackupManagerMonitorMock, 123);
    }

    @Test
    public void cancelBackups_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.cancelBackups();
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void cancelBackupsForUser_forwarded() throws Exception {
        mTrampoline.initializeService();

        mTrampoline.cancelBackupsForUser(mUserId);

        verify(mBackupManagerServiceMock).cancelBackups(mUserId);
    }

    @Test
    public void cancelBackups_forwardedToCallingUserId() throws Exception {
        TrampolineTestable.sCallingUserId = mUserId;
        mTrampoline.initializeService();

        mTrampoline.cancelBackups();

        verify(mBackupManagerServiceMock).cancelBackups(mUserId);
    }

    @Test
    public void beginFullBackup_calledBeforeInitialize_ignored() throws Exception {
        mTrampoline.beginFullBackup(mUserId, new FullBackupJob());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void beginFullBackup_forwarded() throws Exception {
        FullBackupJob fullBackupJob = new FullBackupJob();
        when(mBackupManagerServiceMock.beginFullBackup(mUserId, fullBackupJob)).thenReturn(true);

        mTrampoline.initializeService();
        assertTrue(mTrampoline.beginFullBackup(mUserId, fullBackupJob));
        verify(mBackupManagerServiceMock).beginFullBackup(mUserId, fullBackupJob);
    }

    @Test
    public void endFullBackup_calledBeforeInitialize_ignored() {
        mTrampoline.endFullBackup(mUserId);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void endFullBackup_forwarded() {
        mTrampoline.initializeService();
        mTrampoline.endFullBackup(mUserId);
        verify(mBackupManagerServiceMock).endFullBackup(mUserId);
    }

    @Test
    public void dump_callerDoesNotHavePermission_ignored() {
        when(mContextMock.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)).thenReturn(
                PackageManager.PERMISSION_DENIED);
        mTrampoline.initializeService();

        mTrampoline.dump(mFileDescriptorStub, mPrintWriterMock, new String[0]);

        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void dump_calledBeforeInitialize_ignored() {
        when(mContextMock.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        mTrampoline.dump(mFileDescriptorStub, mPrintWriterMock, new String[0]);

        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void dump_callerHasPermission_forwarded() {
        when(mContextMock.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        mTrampoline.initializeService();

        mTrampoline.dump(mFileDescriptorStub, mPrintWriterMock, null);

        verify(mBackupManagerServiceMock).dump(mFileDescriptorStub, mPrintWriterMock, null);
    }

    private static class TrampolineTestable extends Trampoline {
        static boolean sBackupDisabled = false;
        static int sCallingUserId = -1;
        static int sCallingUid = -1;
        static BackupManagerService sBackupManagerServiceMock = null;
        static File sSuppressFile = null;
        static SparseArray<File> sActivatedFiles = new SparseArray<>();
        static UserManager sUserManagerMock = null;
        private int mCreateServiceCallsCount = 0;

        TrampolineTestable(Context context) {
            super(context);
        }

        @Override
        protected UserManager getUserManager() {
            return sUserManagerMock;
        }

        @Override
        public boolean isBackupDisabled() {
            return sBackupDisabled;
        }

        @Override
        protected File getSuppressFileForSystemUser() {
            return sSuppressFile;
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
        protected BackupManagerService createBackupManagerService() {
            mCreateServiceCallsCount++;
            return sBackupManagerServiceMock;
        }

        @Override
        protected void postToHandler(Runnable runnable) {
            runnable.run();
        }

        int getCreateServiceCallsCount() {
            return mCreateServiceCallsCount;
        }
    }
}
