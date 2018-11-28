/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.server.backup.testing.TransportData.backupTransport;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.Application;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.testing.BackupManagerServiceTestUtils;
import com.android.server.backup.testing.TransportData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Tests for the user-aware backup/restore system service {@link GlobalBackupManagerService}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class GlobalBackupManagerServiceTest {
    private static final String TEST_PACKAGE = "package";
    private static final String TEST_TRANSPORT = "transport";

    @Mock private UserBackupManagerService mUserBackupManagerService;
    @Mock private TransportManager mTransportManager;
    private GlobalBackupManagerService mGlobalBackupManagerService;
    private Context mContext;

    /** Initialize {@link GlobalBackupManagerService}. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Application application = RuntimeEnvironment.application;
        mContext = application;
        mGlobalBackupManagerService =
                new GlobalBackupManagerService(
                        application,
                        new Trampoline(application),
                        BackupManagerServiceTestUtils.startBackupThread(null),
                        new File(application.getCacheDir(), "base_state"),
                        new File(application.getCacheDir(), "data"),
                        mTransportManager);
        mGlobalBackupManagerService.setUserBackupManagerService(mUserBackupManagerService);
    }

    /**
     * Test verifying that {@link GlobalBackupManagerService#MORE_DEBUG} is set to {@code false}.
     * This is specifically to prevent overloading the logs in production.
     */
    @Test
    public void testMoreDebug_isFalse() throws Exception {
        boolean moreDebug = GlobalBackupManagerService.MORE_DEBUG;

        assertThat(moreDebug).isFalse();
    }

    // TODO(b/118520567): Change the following tests to use the per-user instance of
    // UserBackupManagerService once it's implemented. Currently these tests only test the straight
    // forward redirection.

    // ---------------------------------------------
    // Backup agent tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testDataChanged_callsDataChangedForUser() throws Exception {
        mGlobalBackupManagerService.dataChanged(TEST_PACKAGE);

        verify(mUserBackupManagerService).dataChanged(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAgentConnected_callsAgentConnectedForUser() throws Exception {
        IBinder agentBinder = mock(IBinder.class);

        mGlobalBackupManagerService.agentConnected(TEST_PACKAGE, agentBinder);

        verify(mUserBackupManagerService).agentConnected(TEST_PACKAGE, agentBinder);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAgentDisconnected_callsAgentDisconnectedForUser() throws Exception {
        mGlobalBackupManagerService.agentDisconnected(TEST_PACKAGE);

        verify(mUserBackupManagerService).agentDisconnected(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testOpComplete_callsOpCompleteForUser() throws Exception {
        mGlobalBackupManagerService.opComplete(/* token */ 0, /* result */ 0L);

        verify(mUserBackupManagerService).opComplete(/* token */ 0, /* result */ 0L);
    }

    // ---------------------------------------------
    // Transport tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testInitializeTransports_callsInitializeTransportsForUser() throws Exception {
        String[] transports = {TEST_TRANSPORT};

        mGlobalBackupManagerService.initializeTransports(transports, /* observer */ null);

        verify(mUserBackupManagerService).initializeTransports(transports, /* observer */ null);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testClearBackupData_callsClearBackupDataForUser() throws Exception {
        mGlobalBackupManagerService.clearBackupData(TEST_TRANSPORT, TEST_PACKAGE);

        verify(mUserBackupManagerService).clearBackupData(TEST_TRANSPORT, TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetCurrentTransport_callsGetCurrentTransportForUser() throws Exception {
        mGlobalBackupManagerService.getCurrentTransport();

        verify(mUserBackupManagerService).getCurrentTransport();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetCurrentTransportComponent_callsGetCurrentTransportComponentForUser()
            throws Exception {
        mGlobalBackupManagerService.getCurrentTransportComponent();

        verify(mUserBackupManagerService).getCurrentTransportComponent();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testListAllTransports_callsListAllTransportsForUser() throws Exception {
        mGlobalBackupManagerService.listAllTransports();

        verify(mUserBackupManagerService).listAllTransports();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testListAllTransportComponents_callsListAllTransportComponentsForUser()
            throws Exception {
        mGlobalBackupManagerService.listAllTransportComponents();

        verify(mUserBackupManagerService).listAllTransportComponents();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetTransportWhitelist_callsGetTransportWhitelistForUser() throws Exception {
        mGlobalBackupManagerService.getTransportWhitelist();

        verify(mUserBackupManagerService).getTransportWhitelist();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testUpdateTransportAttributes_callsUpdateTransportAttributesForUser()
            throws Exception {
        TransportData transport = backupTransport();
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();

        mGlobalBackupManagerService.updateTransportAttributes(
                transport.getTransportComponent(),
                transport.transportName,
                configurationIntent,
                "currentDestinationString",
                dataManagementIntent,
                "dataManagementLabel");

        verify(mUserBackupManagerService)
                .updateTransportAttributes(
                        transport.getTransportComponent(),
                        transport.transportName,
                        configurationIntent,
                        "currentDestinationString",
                        dataManagementIntent,
                        "dataManagementLabel");
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSelectBackupTransport_callsSelectBackupTransportForUser() throws Exception {
        mGlobalBackupManagerService.selectBackupTransport(TEST_TRANSPORT);

        verify(mUserBackupManagerService).selectBackupTransport(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSelectTransportAsync_callsSelectTransportAsyncForUser() throws Exception {
        TransportData transport = backupTransport();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        mGlobalBackupManagerService.selectBackupTransportAsync(
                transport.getTransportComponent(), callback);

        verify(mUserBackupManagerService)
                .selectBackupTransportAsync(transport.getTransportComponent(), callback);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetConfigurationIntent_callsGetConfigurationIntentForUser() throws Exception {
        mGlobalBackupManagerService.getConfigurationIntent(TEST_TRANSPORT);

        verify(mUserBackupManagerService).getConfigurationIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDestinationString_callsGetDestinationStringForUser() throws Exception {
        mGlobalBackupManagerService.getDestinationString(TEST_TRANSPORT);

        verify(mUserBackupManagerService).getDestinationString(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDataManagementIntent_callsGetDataManagementIntentForUser() throws Exception {
        mGlobalBackupManagerService.getDataManagementIntent(TEST_TRANSPORT);

        verify(mUserBackupManagerService).getDataManagementIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDataManagementLabel_callsGetDataManagementLabelForUser() throws Exception {
        mGlobalBackupManagerService.getDataManagementLabel(TEST_TRANSPORT);

        verify(mUserBackupManagerService).getDataManagementLabel(TEST_TRANSPORT);
    }

    // ---------------------------------------------
    // Settings tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void setBackupEnabled_callsSetBackupEnabledForUser() throws Exception {
        mGlobalBackupManagerService.setBackupEnabled(true);

        verify(mUserBackupManagerService).setBackupEnabled(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void setAutoRestore_callsSetAutoRestoreForUser() throws Exception {
        mGlobalBackupManagerService.setAutoRestore(true);

        verify(mUserBackupManagerService).setAutoRestore(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetBackupProvisioned_callsSetBackupProvisionedForUser() throws Exception {
        mGlobalBackupManagerService.setBackupProvisioned(true);

        verify(mUserBackupManagerService).setBackupProvisioned(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testIsBackupEnabled_callsIsBackupEnabledForUser() throws Exception {
        mGlobalBackupManagerService.isBackupEnabled();

        verify(mUserBackupManagerService).isBackupEnabled();
    }

    // ---------------------------------------------
    // Backup tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testIsAppEligibleForBackup_callsIsAppEligibleForBackupForUser() throws Exception {
        mGlobalBackupManagerService.isAppEligibleForBackup(TEST_PACKAGE);

        verify(mUserBackupManagerService).isAppEligibleForBackup(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testFilterAppsEligibleForBackup_callsFilterAppsEligibleForBackupForUser()
            throws Exception {
        String[] packages = {TEST_PACKAGE};

        mGlobalBackupManagerService.filterAppsEligibleForBackup(packages);

        verify(mUserBackupManagerService).filterAppsEligibleForBackup(packages);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBackupNow_callsBackupNowForUser() throws Exception {
        mGlobalBackupManagerService.backupNow();

        verify(mUserBackupManagerService).backupNow();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testRequestBackup_callsRequestBackupForUser() throws Exception {
        String[] packages = {TEST_PACKAGE};
        IBackupObserver observer = mock(IBackupObserver.class);
        IBackupManagerMonitor monitor = mock(IBackupManagerMonitor.class);

        mGlobalBackupManagerService.requestBackup(packages, observer, monitor, /* flags */ 0);

        verify(mUserBackupManagerService).requestBackup(packages, observer, monitor, /* flags */ 0);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testCancelBackups_callsCancelBackupsForUser() throws Exception {
        mGlobalBackupManagerService.cancelBackups();

        verify(mUserBackupManagerService).cancelBackups();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBeginFullBackup_callsBeginFullBackupForUser() throws Exception {
        FullBackupJob job = new FullBackupJob();

        mGlobalBackupManagerService.beginFullBackup(job);

        verify(mUserBackupManagerService).beginFullBackup(job);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testEndFullBackup_callsEndFullBackupForUser() throws Exception {
        mGlobalBackupManagerService.endFullBackup();

        verify(mUserBackupManagerService).endFullBackup();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testFullTransportBackup_callsFullTransportBackupForUser() throws Exception {
        String[] packages = {TEST_PACKAGE};

        mGlobalBackupManagerService.fullTransportBackup(packages);

        verify(mUserBackupManagerService).fullTransportBackup(packages);
    }

    // ---------------------------------------------
    // Restore tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testRestoreAtInstall_callsRestoreAtInstallForUser() throws Exception {
        mGlobalBackupManagerService.restoreAtInstall(TEST_PACKAGE, /* token */ 0);

        verify(mUserBackupManagerService).restoreAtInstall(TEST_PACKAGE, /* token */ 0);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBeginRestoreSession_callsBeginRestoreSessionForUser() throws Exception {
        mGlobalBackupManagerService.beginRestoreSession(TEST_PACKAGE, TEST_TRANSPORT);

        verify(mUserBackupManagerService).beginRestoreSession(TEST_PACKAGE, TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetAvailableRestoreToken_callsGetAvailableRestoreTokenForUser()
            throws Exception {
        mGlobalBackupManagerService.getAvailableRestoreToken(TEST_PACKAGE);

        verify(mUserBackupManagerService).getAvailableRestoreToken(TEST_PACKAGE);
    }

    // ---------------------------------------------
    // Adb backup/restore tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetBackupPassword_callsSetBackupPasswordForUser() throws Exception {
        mGlobalBackupManagerService.setBackupPassword("currentPassword", "newPassword");

        verify(mUserBackupManagerService).setBackupPassword("currentPassword", "newPassword");
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testHasBackupPassword_callsHasBackupPasswordForUser() throws Exception {
        mGlobalBackupManagerService.hasBackupPassword();

        verify(mUserBackupManagerService).hasBackupPassword();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAdbBackup_callsAdbBackupForUser() throws Exception {
        File testFile = new File(mContext.getFilesDir(), "test");
        testFile.createNewFile();
        ParcelFileDescriptor parcelFileDescriptor =
                ParcelFileDescriptor.open(testFile, ParcelFileDescriptor.MODE_READ_WRITE);
        String[] packages = {TEST_PACKAGE};

        mGlobalBackupManagerService.adbBackup(
                parcelFileDescriptor,
                /* includeApks */ true,
                /* includeObbs */ true,
                /* includeShared */ true,
                /* doWidgets */ true,
                /* doAllApps */ true,
                /* includeSystem */ true,
                /* doCompress */ true,
                /* doKeyValue */ true,
                packages);

        verify(mUserBackupManagerService)
                .adbBackup(
                        parcelFileDescriptor,
                        /* includeApks */ true,
                        /* includeObbs */ true,
                        /* includeShared */ true,
                        /* doWidgets */ true,
                        /* doAllApps */ true,
                        /* includeSystem */ true,
                        /* doCompress */ true,
                        /* doKeyValue */ true,
                        packages);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAdbRestore_callsAdbRestoreForUser() throws Exception {
        File testFile = new File(mContext.getFilesDir(), "test");
        testFile.createNewFile();
        ParcelFileDescriptor parcelFileDescriptor =
                ParcelFileDescriptor.open(testFile, ParcelFileDescriptor.MODE_READ_WRITE);

        mGlobalBackupManagerService.adbRestore(parcelFileDescriptor);

        verify(mUserBackupManagerService).adbRestore(parcelFileDescriptor);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAcknowledgeAdbBackupOrRestore_callsAcknowledgeAdbBackupOrRestoreForUser()
            throws Exception {
        IFullBackupRestoreObserver observer = mock(IFullBackupRestoreObserver.class);

        mGlobalBackupManagerService.acknowledgeAdbBackupOrRestore(
                /* token */ 0, /* allow */ true, "currentPassword", "encryptionPassword", observer);

        verify(mUserBackupManagerService)
                .acknowledgeAdbBackupOrRestore(
                        /* token */ 0,
                        /* allow */ true,
                        "currentPassword",
                        "encryptionPassword",
                        observer);
    }

    // ---------------------------------------------
    //  Service tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testDump_callsDumpForUser() throws Exception {
        File testFile = new File(mContext.getFilesDir(), "test");
        testFile.createNewFile();
        FileDescriptor fileDescriptor = new FileDescriptor();
        PrintWriter printWriter = new PrintWriter(testFile);
        String[] args = {"1", "2"};

        mGlobalBackupManagerService.dump(fileDescriptor, printWriter, args);

        verify(mUserBackupManagerService).dump(fileDescriptor, printWriter, args);
    }
}
