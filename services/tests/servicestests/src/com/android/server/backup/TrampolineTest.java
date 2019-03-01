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

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.backup.BackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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
    private final int NON_USER_SYSTEM = UserHandle.USER_SYSTEM + 1;

    @Mock private BackupManagerService mBackupManagerServiceMock;
    @Mock private Context mContextMock;
    @Mock private File mSuppressFileMock;
    @Mock private File mSuppressFileParentMock;
    @Mock private IBinder mAgentMock;
    @Mock private ParcelFileDescriptor mParcelFileDescriptorMock;
    @Mock private IFullBackupRestoreObserver mFullBackupRestoreObserverMock;
    @Mock private IBackupObserver mBackupObserverMock;
    @Mock private IBackupManagerMonitor mBackupManagerMonitorMock;
    @Mock private PrintWriter mPrintWriterMock;

    private FileDescriptor mFileDescriptorStub = new FileDescriptor();

    private TrampolineTestable mTrampoline;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        TrampolineTestable.sBackupManagerServiceMock = mBackupManagerServiceMock;
        TrampolineTestable.sSuppressFile = mSuppressFileMock;
        TrampolineTestable.sCallingUid = Process.SYSTEM_UID;
        TrampolineTestable.sBackupDisabled = false;

        when(mSuppressFileMock.getParentFile()).thenReturn(mSuppressFileParentMock);

        mTrampoline = new TrampolineTestable(mContextMock);
    }

    @Test
    public void constructor_createsSuppressFileDirectory() {
        verify(mSuppressFileParentMock).mkdirs();
    }

    @Test
    public void initialize_forUserSystem_successfullyInitialized() {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    // The BackupManagerService can only be initialized by USER_SYSTEM, so we check that if any
    // other user trying to initialize it leaves it non-active.
    @Test
    public void initialize_forNonUserSystem_nonInitialized() {
        mTrampoline.initialize(NON_USER_SYSTEM);

        assertFalse(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void initialize_globallyDisabled_nonInitialized() {
        TrampolineTestable.sBackupDisabled = true;

        TrampolineTestable trampoline = new TrampolineTestable(mContextMock);
        trampoline.initialize(UserHandle.USER_SYSTEM);

        assertFalse(trampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    // Verify that BackupManagerService is not initialized if suppress file exists.
    @Test
    public void initialize_suppressFileExists_nonInitialized() {
        when(mSuppressFileMock.exists()).thenReturn(true);

        TrampolineTestable trampoline = new TrampolineTestable(mContextMock);
        trampoline.initialize(UserHandle.USER_SYSTEM);

        assertFalse(trampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void isBackupServiceActive_calledBeforeInitialize_returnsFalse() {
        assertFalse(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_callerSystemUid_serviceCreated() {
        TrampolineTestable.sCallingUid = Process.SYSTEM_UID;

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_callerRootUid_serviceCreated() {
        TrampolineTestable.sCallingUid = Process.ROOT_UID;

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_callerNonRootNonSystem_securityExceptionThrown() {
        TrampolineTestable.sCallingUid = Process.FIRST_APPLICATION_UID;

        try {
            mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
            fail();
        } catch (SecurityException expected) {
        }

        assertFalse(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_backupDisabled_ignored() {
        TrampolineTestable.sBackupDisabled = true;
        TrampolineTestable trampoline = new TrampolineTestable(mContextMock);

        trampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertFalse(trampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_nonUserSystem_ignored() {
        mTrampoline.setBackupServiceActive(NON_USER_SYSTEM, true);

        assertFalse(mTrampoline.isBackupServiceActive(NON_USER_SYSTEM));
    }

    @Test
    public void setBackupServiceActive_alreadyActive_ignored() {
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
        assertEquals(1, mTrampoline.getCreateServiceCallsCount());

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
        assertEquals(1, mTrampoline.getCreateServiceCallsCount());
    }

    @Test
    public void setBackupServiceActive_makeActive_serviceCreatedAndSuppressFileDeleted() {
        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, true);

        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
        verify(mSuppressFileMock).delete();
    }

    @Test
    public void setBackupServiceActive_makeNonActive_serviceDeletedAndSuppressFileCreated()
            throws IOException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
        verify(mSuppressFileMock).createNewFile();
    }

    @Test
    public void
    setBackupServiceActive_makeNonActive_serviceDeletedAndSuppressFileCreated_ioExceptionHandled()
            throws IOException {
        when(mSuppressFileMock.createNewFile()).thenThrow(new IOException());
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertTrue(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        assertFalse(mTrampoline.isBackupServiceActive(UserHandle.USER_SYSTEM));
        verify(mSuppressFileMock).createNewFile();
    }

    @Test
    public void setBackupServiceActive_makeNonActive_alreadyNonActive_ignored() throws IOException {
        reset(mSuppressFileMock);

        mTrampoline.setBackupServiceActive(UserHandle.USER_SYSTEM, false);

        verifyNoMoreInteractions(mSuppressFileMock);
    }

    @Test
    public void dataChanged_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.dataChanged(PACKAGE_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void dataChanged_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.dataChanged(PACKAGE_NAME);
        verify(mBackupManagerServiceMock).dataChanged(PACKAGE_NAME);
    }

    @Test
    public void clearBackupData_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.clearBackupData(TRANSPORT_NAME, PACKAGE_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void clearBackupData_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.clearBackupData(TRANSPORT_NAME, PACKAGE_NAME);
        verify(mBackupManagerServiceMock).clearBackupData(TRANSPORT_NAME, PACKAGE_NAME);
    }

    @Test
    public void agentConnected_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.agentConnected(PACKAGE_NAME, mAgentMock);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void agentConnected_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.agentConnected(PACKAGE_NAME, mAgentMock);
        verify(mBackupManagerServiceMock).agentConnected(PACKAGE_NAME, mAgentMock);
    }

    @Test
    public void agentDisconnected_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.agentDisconnected(PACKAGE_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void agentDisconnected_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.agentDisconnected(PACKAGE_NAME);
        verify(mBackupManagerServiceMock).agentDisconnected(PACKAGE_NAME);
    }

    @Test
    public void restoreAtInstall_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.restoreAtInstall(PACKAGE_NAME, 123);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void restoreAtInstall_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.restoreAtInstall(PACKAGE_NAME, 123);
        verify(mBackupManagerServiceMock).restoreAtInstall(PACKAGE_NAME, 123);
    }

    @Test
    public void setBackupEnabled_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.setBackupEnabled(true);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void setBackupEnabled_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.setBackupEnabled(true);
        verify(mBackupManagerServiceMock).setBackupEnabled(true);
    }

    @Test
    public void setAutoRestore_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.setAutoRestore(true);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void setAutoRestore_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.setAutoRestore(true);
        verify(mBackupManagerServiceMock).setAutoRestore(true);
    }

    @Test
    public void setBackupProvisioned_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.setBackupProvisioned(true);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void setBackupProvisioned_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.setBackupProvisioned(true);
        verify(mBackupManagerServiceMock).setBackupProvisioned(true);
    }

    @Test
    public void isBackupEnabled_calledBeforeInitialize_ignored() throws RemoteException {
        assertFalse(mTrampoline.isBackupEnabled());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void isBackupEnabled_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.isBackupEnabled();
        verify(mBackupManagerServiceMock).isBackupEnabled();
    }

    @Test
    public void setBackupPassword_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.setBackupPassword(CURRENT_PASSWORD, NEW_PASSWORD);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void setBackupPassword_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.setBackupPassword(CURRENT_PASSWORD, NEW_PASSWORD);
        verify(mBackupManagerServiceMock).setBackupPassword(CURRENT_PASSWORD, NEW_PASSWORD);
    }

    @Test
    public void hasBackupPassword_calledBeforeInitialize_ignored() throws RemoteException {
        assertFalse(mTrampoline.hasBackupPassword());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void hasBackupPassword_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.hasBackupPassword();
        verify(mBackupManagerServiceMock).hasBackupPassword();
    }

    @Test
    public void backupNow_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.backupNow();
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void backupNow_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.backupNow();
        verify(mBackupManagerServiceMock).backupNow();
    }

    @Test
    public void adbBackup_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.adbBackup(mParcelFileDescriptorMock, true, true, true, true, true, true, true,
                true,
                PACKAGE_NAMES);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void adbBackup_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.adbBackup(mParcelFileDescriptorMock, true, true, true, true, true, true, true,
                true,
                PACKAGE_NAMES);
        verify(mBackupManagerServiceMock).adbBackup(mParcelFileDescriptorMock, true, true, true,
                true,
                true, true, true, true, PACKAGE_NAMES);
    }

    @Test
    public void fullTransportBackup_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.fullTransportBackup(PACKAGE_NAMES);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void fullTransportBackup_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.fullTransportBackup(PACKAGE_NAMES);
        verify(mBackupManagerServiceMock).fullTransportBackup(PACKAGE_NAMES);
    }

    @Test
    public void adbRestore_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.adbRestore(mParcelFileDescriptorMock);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void adbRestore_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.adbRestore(mParcelFileDescriptorMock);
        verify(mBackupManagerServiceMock).adbRestore(mParcelFileDescriptorMock);
    }

    @Test
    public void acknowledgeFullBackupOrRestore_calledBeforeInitialize_ignored()
            throws RemoteException {
        mTrampoline.acknowledgeFullBackupOrRestore(123, true, CURRENT_PASSWORD, ENCRYPTION_PASSWORD,
                mFullBackupRestoreObserverMock);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void acknowledgeFullBackupOrRestore_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.acknowledgeFullBackupOrRestore(123, true, CURRENT_PASSWORD, ENCRYPTION_PASSWORD,
                mFullBackupRestoreObserverMock);
        verify(mBackupManagerServiceMock).acknowledgeAdbBackupOrRestore(123, true, CURRENT_PASSWORD,
                ENCRYPTION_PASSWORD, mFullBackupRestoreObserverMock);
    }

    @Test
    public void getCurrentTransport_calledBeforeInitialize_ignored() throws RemoteException {
        assertNull(mTrampoline.getCurrentTransport());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getCurrentTransport_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.getCurrentTransport()).thenReturn(TRANSPORT_NAME);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);

        assertEquals(TRANSPORT_NAME, mTrampoline.getCurrentTransport());
        verify(mBackupManagerServiceMock).getCurrentTransport();
    }

    @Test
    public void listAllTransports_calledBeforeInitialize_ignored() throws RemoteException {
        assertNull(mTrampoline.listAllTransports());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void listAllTransports_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.listAllTransports()).thenReturn(TRANSPORTS);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(TRANSPORTS, mTrampoline.listAllTransports());
        verify(mBackupManagerServiceMock).listAllTransports();
    }

    @Test
    public void listAllTransportComponents_calledBeforeInitialize_ignored() throws RemoteException {
        assertNull(mTrampoline.listAllTransportComponents());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void listAllTransportComponents_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.listAllTransportComponents()).thenReturn(
                TRANSPORT_COMPONENTS);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(TRANSPORT_COMPONENTS, mTrampoline.listAllTransportComponents());
        verify(mBackupManagerServiceMock).listAllTransportComponents();
    }

    @Test
    public void getTransportWhitelist_calledBeforeInitialize_ignored() throws RemoteException {
        assertNull(mTrampoline.getTransportWhitelist());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getTransportWhitelist_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.getTransportWhitelist()).thenReturn(TRANSPORTS);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(TRANSPORTS, mTrampoline.getTransportWhitelist());
        verify(mBackupManagerServiceMock).getTransportWhitelist();
    }

    @Test
    public void describeTransport_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.updateTransportAttributes(TRANSPORT_COMPONENT_NAME, TRANSPORT_NAME, null,
                "Transport Destination", null, "Data Management");
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void describeTransport_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.getTransportWhitelist()).thenReturn(TRANSPORTS);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.updateTransportAttributes(TRANSPORT_COMPONENT_NAME, TRANSPORT_NAME, null,
                "Transport Destination", null, "Data Management");
        verify(mBackupManagerServiceMock).updateTransportAttributes(TRANSPORT_COMPONENT_NAME,
                TRANSPORT_NAME, null, "Transport Destination", null, "Data Management");
    }

    @Test
    public void selectBackupTransport_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.selectBackupTransport(TRANSPORT_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void selectBackupTransport_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.selectBackupTransport(TRANSPORT_NAME);
        verify(mBackupManagerServiceMock).selectBackupTransport(TRANSPORT_NAME);
    }

    @Test
    public void selectBackupTransportAsync_calledBeforeInitialize_ignored() throws Exception {
        LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue();
        mTrampoline.selectBackupTransportAsync(
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
    public void selectBackupTransportAsync_calledBeforeInitialize_ignored_nullListener()
            throws Exception {
        mTrampoline.selectBackupTransportAsync(TRANSPORT_COMPONENT_NAME, null);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
        // No crash.
    }

    @Test
    public void selectBackupTransportAsync_calledBeforeInitialize_ignored_listenerThrowException()
            throws Exception {
        mTrampoline.selectBackupTransportAsync(
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
    public void selectBackupTransportAsync_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.selectBackupTransportAsync(TRANSPORT_COMPONENT_NAME, null);
        verify(mBackupManagerServiceMock).selectBackupTransportAsync(TRANSPORT_COMPONENT_NAME,
                null);
    }

    @Test
    public void getConfigurationIntent_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.getConfigurationIntent(TRANSPORT_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getConfigurationIntent_forwarded() throws RemoteException {
        Intent configurationIntentStub = new Intent();
        when(mBackupManagerServiceMock.getConfigurationIntent(TRANSPORT_NAME)).thenReturn(
                configurationIntentStub);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(configurationIntentStub, mTrampoline.getConfigurationIntent(TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getConfigurationIntent(TRANSPORT_NAME);
    }

    @Test
    public void getDestinationString_calledBeforeInitialize_ignored() throws RemoteException {
        assertNull(mTrampoline.getDestinationString(TRANSPORT_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getDestinationString_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.getDestinationString(TRANSPORT_NAME)).thenReturn(
                DESTINATION_STRING);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(DESTINATION_STRING, mTrampoline.getDestinationString(TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDestinationString(TRANSPORT_NAME);
    }

    @Test
    public void getDataManagementIntent_calledBeforeInitialize_ignored() throws RemoteException {
        assertNull(mTrampoline.getDataManagementIntent(TRANSPORT_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getDataManagementIntent_forwarded() throws RemoteException {
        Intent dataManagementIntent = new Intent();
        when(mBackupManagerServiceMock.getDataManagementIntent(TRANSPORT_NAME)).thenReturn(
                dataManagementIntent);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(dataManagementIntent, mTrampoline.getDataManagementIntent(TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDataManagementIntent(TRANSPORT_NAME);
    }

    @Test
    public void getDataManagementLabel_calledBeforeInitialize_ignored() throws RemoteException {
        assertNull(mTrampoline.getDataManagementLabel(TRANSPORT_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getDataManagementLabel_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.getDataManagementLabel(TRANSPORT_NAME)).thenReturn(
                DATA_MANAGEMENT_LABEL);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(DATA_MANAGEMENT_LABEL, mTrampoline.getDataManagementLabel(TRANSPORT_NAME));
        verify(mBackupManagerServiceMock).getDataManagementLabel(TRANSPORT_NAME);
    }

    @Test
    public void beginRestoreSession_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.beginRestoreSession(PACKAGE_NAME, TRANSPORT_NAME);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void beginRestoreSession_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.beginRestoreSession(PACKAGE_NAME, TRANSPORT_NAME);
        verify(mBackupManagerServiceMock).beginRestoreSession(PACKAGE_NAME, TRANSPORT_NAME);
    }

    @Test
    public void opComplete_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.opComplete(1, 2);
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void opComplete_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.opComplete(1, 2);
        verify(mBackupManagerServiceMock).opComplete(1, 2);
    }

    @Test
    public void getAvailableRestoreToken_calledBeforeInitialize_ignored() throws RemoteException {
        assertEquals(0, mTrampoline.getAvailableRestoreToken(PACKAGE_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void getAvailableRestoreToken_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.getAvailableRestoreToken(PACKAGE_NAME)).thenReturn(123L);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(123, mTrampoline.getAvailableRestoreToken(PACKAGE_NAME));
        verify(mBackupManagerServiceMock).getAvailableRestoreToken(PACKAGE_NAME);
    }

    @Test
    public void isAppEligibleForBackup_calledBeforeInitialize_ignored() throws RemoteException {
        assertFalse(mTrampoline.isAppEligibleForBackup(PACKAGE_NAME));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void isAppEligibleForBackup_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.isAppEligibleForBackup(PACKAGE_NAME)).thenReturn(true);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertTrue(mTrampoline.isAppEligibleForBackup(PACKAGE_NAME));
        verify(mBackupManagerServiceMock).isAppEligibleForBackup(PACKAGE_NAME);
    }

    @Test
    public void requestBackup_calledBeforeInitialize_ignored() throws RemoteException {
        assertEquals(BackupManager.ERROR_BACKUP_NOT_ALLOWED, mTrampoline.requestBackup(
                PACKAGE_NAMES, mBackupObserverMock, mBackupManagerMonitorMock, 123));
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void requestBackup_forwarded() throws RemoteException {
        when(mBackupManagerServiceMock.requestBackup(PACKAGE_NAMES, mBackupObserverMock,
                mBackupManagerMonitorMock, 123)).thenReturn(456);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertEquals(456, mTrampoline.requestBackup(PACKAGE_NAMES, mBackupObserverMock,
                mBackupManagerMonitorMock, 123));
        verify(mBackupManagerServiceMock).requestBackup(PACKAGE_NAMES, mBackupObserverMock,
                mBackupManagerMonitorMock, 123);
    }

    @Test
    public void cancelBackups_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.cancelBackups();
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void cancelBackups_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.cancelBackups();
        verify(mBackupManagerServiceMock).cancelBackups();
    }

    @Test
    public void beginFullBackup_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.beginFullBackup(new FullBackupJob());
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void beginFullBackup_forwarded() throws RemoteException {
        FullBackupJob fullBackupJob = new FullBackupJob();
        when(mBackupManagerServiceMock.beginFullBackup(fullBackupJob)).thenReturn(true);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        assertTrue(mTrampoline.beginFullBackup(fullBackupJob));
        verify(mBackupManagerServiceMock).beginFullBackup(fullBackupJob);
    }

    @Test
    public void endFullBackup_calledBeforeInitialize_ignored() throws RemoteException {
        mTrampoline.endFullBackup();
        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void endFullBackup_forwarded() throws RemoteException {
        mTrampoline.initialize(UserHandle.USER_SYSTEM);
        mTrampoline.endFullBackup();
        verify(mBackupManagerServiceMock).endFullBackup();
    }

    @Test
    public void dump_callerDoesNotHavePermission_ignored() throws RemoteException {
        when(mContextMock.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);

        mTrampoline.dump(mFileDescriptorStub, mPrintWriterMock, new String[0]);

        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void dump_calledBeforeInitialize_ignored() throws RemoteException {
        when(mContextMock.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        mTrampoline.dump(mFileDescriptorStub, mPrintWriterMock, new String[0]);

        verifyNoMoreInteractions(mBackupManagerServiceMock);
    }

    @Test
    public void dump_callerHasPermission_forwarded() throws RemoteException {
        when(mContextMock.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        mTrampoline.initialize(UserHandle.USER_SYSTEM);

        mTrampoline.dump(mFileDescriptorStub, mPrintWriterMock, null);

        verify(mBackupManagerServiceMock).dump(mFileDescriptorStub, mPrintWriterMock, null);
    }

    private static class TrampolineTestable extends Trampoline {
        static boolean sBackupDisabled = false;
        static File sSuppressFile = null;
        static int sCallingUid = -1;
        static BackupManagerService sBackupManagerServiceMock = null;
        private int mCreateServiceCallsCount = 0;

        TrampolineTestable(Context context) {
            super(context);
        }

        @Override
        public boolean isBackupDisabled() {
            return sBackupDisabled;
        }

        @Override
        public File getSuppressFile() {
            return sSuppressFile;
        }

        @Override
        protected int binderGetCallingUid() {
            return sCallingUid;
        }

        @Override
        protected BackupManagerServiceInterface createBackupManagerService() {
            mCreateServiceCallsCount++;
            return sBackupManagerServiceMock;
        }

        int getCreateServiceCallsCount() {
            return mCreateServiceCallsCount;
        }
    }
}
