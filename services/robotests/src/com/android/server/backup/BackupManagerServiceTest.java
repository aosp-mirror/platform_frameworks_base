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
import static org.robolectric.Shadows.shadowOf;
import static org.testng.Assert.expectThrows;

import android.annotation.UserIdInt;
import android.app.Application;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.testing.BackupManagerServiceTestUtils;
import com.android.server.backup.testing.TransportData;
import com.android.server.testing.shadows.ShadowBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContextWrapper;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Tests for the user-aware backup/restore system service {@link BackupManagerService}. */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBinder.class})
@Presubmit
public class BackupManagerServiceTest {
    private static final String TEST_PACKAGE = "package";
    private static final String TEST_TRANSPORT = "transport";

    private static final int NON_USER_SYSTEM = UserHandle.USER_SYSTEM + 1;

    private ShadowContextWrapper mShadowContext;
    @Mock private UserBackupManagerService mUserBackupManagerService;
    private BackupManagerService mBackupManagerService;
    private Context mContext;
    @UserIdInt private int mUserId;

    /** Initialize {@link BackupManagerService}. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Application application = RuntimeEnvironment.application;
        mContext = application;
        mShadowContext = shadowOf(application);
        mUserId = NON_USER_SYSTEM;
        mBackupManagerService =
                new BackupManagerService(
                        application,
                        new Trampoline(application),
                        BackupManagerServiceTestUtils.startBackupThread(null));
        mBackupManagerService.setUserBackupManagerService(mUserBackupManagerService);
    }

    /**
     * Clean up and reset state that was created for testing {@link BackupManagerService}
     * operations.
     */
    @After
    public void tearDown() throws Exception {
        ShadowBinder.reset();
    }

    /**
     * Test verifying that {@link BackupManagerService#MORE_DEBUG} is set to {@code false}.
     * This is specifically to prevent overloading the logs in production.
     */
    @Test
    public void testMoreDebug_isFalse() throws Exception {
        boolean moreDebug = BackupManagerService.MORE_DEBUG;

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
        mBackupManagerService.dataChanged(TEST_PACKAGE);

        verify(mUserBackupManagerService).dataChanged(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAgentConnected_callsAgentConnectedForUser() throws Exception {
        IBinder agentBinder = mock(IBinder.class);

        mBackupManagerService.agentConnected(TEST_PACKAGE, agentBinder);

        verify(mUserBackupManagerService).agentConnected(TEST_PACKAGE, agentBinder);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAgentDisconnected_callsAgentDisconnectedForUser() throws Exception {
        mBackupManagerService.agentDisconnected(TEST_PACKAGE);

        verify(mUserBackupManagerService).agentDisconnected(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testOpComplete_callsOpCompleteForUser() throws Exception {
        mBackupManagerService.opComplete(/* token */ 0, /* result */ 0L);

        verify(mUserBackupManagerService).opComplete(/* token */ 0, /* result */ 0L);
    }

    // ---------------------------------------------
    // Transport tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testInitializeTransports_callsInitializeTransportsForUser() throws Exception {
        String[] transports = {TEST_TRANSPORT};

        mBackupManagerService.initializeTransports(transports, /* observer */ null);

        verify(mUserBackupManagerService).initializeTransports(transports, /* observer */ null);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testClearBackupData_callsClearBackupDataForUser() throws Exception {
        mBackupManagerService.clearBackupData(TEST_TRANSPORT, TEST_PACKAGE);

        verify(mUserBackupManagerService).clearBackupData(TEST_TRANSPORT, TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetCurrentTransport_callsGetCurrentTransportForUser() throws Exception {
        mBackupManagerService.getCurrentTransport();

        verify(mUserBackupManagerService).getCurrentTransport();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetCurrentTransportComponent_callsGetCurrentTransportComponentForUser()
            throws Exception {
        mBackupManagerService.getCurrentTransportComponent();

        verify(mUserBackupManagerService).getCurrentTransportComponent();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testListAllTransports_callsListAllTransportsForUser() throws Exception {
        mBackupManagerService.listAllTransports();

        verify(mUserBackupManagerService).listAllTransports();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testListAllTransportComponents_callsListAllTransportComponentsForUser()
            throws Exception {
        mBackupManagerService.listAllTransportComponents();

        verify(mUserBackupManagerService).listAllTransportComponents();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetTransportWhitelist_callsGetTransportWhitelistForUser() throws Exception {
        mBackupManagerService.getTransportWhitelist();

        verify(mUserBackupManagerService).getTransportWhitelist();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testUpdateTransportAttributes_callsUpdateTransportAttributesForUser()
            throws Exception {
        TransportData transport = backupTransport();
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();

        mBackupManagerService.updateTransportAttributes(
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
        mBackupManagerService.selectBackupTransport(TEST_TRANSPORT);

        verify(mUserBackupManagerService).selectBackupTransport(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSelectTransportAsync_callsSelectTransportAsyncForUser() throws Exception {
        TransportData transport = backupTransport();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        mBackupManagerService.selectBackupTransportAsync(
                transport.getTransportComponent(), callback);

        verify(mUserBackupManagerService)
                .selectBackupTransportAsync(transport.getTransportComponent(), callback);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetConfigurationIntent_callsGetConfigurationIntentForUser() throws Exception {
        mBackupManagerService.getConfigurationIntent(TEST_TRANSPORT);

        verify(mUserBackupManagerService).getConfigurationIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDestinationString_callsGetDestinationStringForUser() throws Exception {
        mBackupManagerService.getDestinationString(TEST_TRANSPORT);

        verify(mUserBackupManagerService).getDestinationString(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDataManagementIntent_callsGetDataManagementIntentForUser() throws Exception {
        mBackupManagerService.getDataManagementIntent(TEST_TRANSPORT);

        verify(mUserBackupManagerService).getDataManagementIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDataManagementLabel_callsGetDataManagementLabelForUser() throws Exception {
        mBackupManagerService.getDataManagementLabel(TEST_TRANSPORT);

        verify(mUserBackupManagerService).getDataManagementLabel(TEST_TRANSPORT);
    }

    // ---------------------------------------------
    // Settings tests
    // ---------------------------------------------
    /**
     * Test verifying that {@link BackupManagerService#setBackupEnabled(int, boolean)} throws a
     * {@link SecurityException} if the caller does not have INTERACT_ACROSS_USERS_FULL permission.
     */
    @Test
    public void setBackupEnabled_withoutPermission_throwsSecurityException() {
        mShadowContext.denyPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        expectThrows(
                SecurityException.class,
                () -> mBackupManagerService.setBackupEnabled(mUserId, true));
    }

    /**
     * Test verifying that {@link BackupManagerService#setBackupEnabled(int, boolean)} does not
     * require the caller to have INTERACT_ACROSS_USERS_FULL permission when the calling user id is
     * the same as the target user id.
     */
    @Test
    public void setBackupEnabled_whenCallingUserIsTargetUser_doesntNeedPermission() {
        ShadowBinder.setCallingUserHandle(UserHandle.of(mUserId));
        mShadowContext.denyPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        mBackupManagerService.setBackupEnabled(mUserId, true);

        verify(mUserBackupManagerService).setBackupEnabled(true);
    }


    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void setBackupEnabled_callsSetBackupEnabledForUser() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        mBackupManagerService.setBackupEnabled(mUserId, true);

        verify(mUserBackupManagerService).setBackupEnabled(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void setAutoRestore_callsSetAutoRestoreForUser() throws Exception {
        mBackupManagerService.setAutoRestore(true);

        verify(mUserBackupManagerService).setAutoRestore(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetBackupProvisioned_callsSetBackupProvisionedForUser() throws Exception {
        mBackupManagerService.setBackupProvisioned(true);

        verify(mUserBackupManagerService).setBackupProvisioned(true);
    }

    /**
     * Test verifying that {@link BackupManagerService#isBackupEnabled(int)} throws a
     * {@link SecurityException} if the caller does not have INTERACT_ACROSS_USERS_FULL permission.
     */
    @Test
    public void testIsBackupEnabled_withoutPermission_throwsSecurityException() {
        mShadowContext.denyPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        expectThrows(
                SecurityException.class,
                () -> mBackupManagerService.isBackupEnabled(mUserId));
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testIsBackupEnabled_callsIsBackupEnabledForUser() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        mBackupManagerService.isBackupEnabled(mUserId);

        verify(mUserBackupManagerService).isBackupEnabled();
    }

    // ---------------------------------------------
    // Backup tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testIsAppEligibleForBackup_callsIsAppEligibleForBackupForUser() throws Exception {
        mBackupManagerService.isAppEligibleForBackup(TEST_PACKAGE);

        verify(mUserBackupManagerService).isAppEligibleForBackup(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testFilterAppsEligibleForBackup_callsFilterAppsEligibleForBackupForUser()
            throws Exception {
        String[] packages = {TEST_PACKAGE};

        mBackupManagerService.filterAppsEligibleForBackup(packages);

        verify(mUserBackupManagerService).filterAppsEligibleForBackup(packages);
    }

    /**
     * Test verifying that {@link BackupManagerService#backupNow(int)} throws a
     * {@link SecurityException} if the caller does not have INTERACT_ACROSS_USERS_FULL permission.
     */
    @Test
    public void testBackupNow_withoutPermission_throwsSecurityException() {
        mShadowContext.denyPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        expectThrows(
                SecurityException.class,
                () -> mBackupManagerService.backupNow(mUserId));
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBackupNow_callsBackupNowForUser() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        mBackupManagerService.backupNow(mUserId);

        verify(mUserBackupManagerService).backupNow();
    }

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(int, String[], IBackupObserver,
     * IBackupManagerMonitor, int)} throws a {@link SecurityException} if the caller does not have
     * INTERACT_ACROSS_USERS_FULL permission.
     */
    @Test
    public void testRequestBackup_withoutPermission_throwsSecurityException() {
        mShadowContext.denyPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        String[] packages = {TEST_PACKAGE};
        IBackupObserver observer = mock(IBackupObserver.class);
        IBackupManagerMonitor monitor = mock(IBackupManagerMonitor.class);

        expectThrows(
                SecurityException.class,
                () -> mBackupManagerService.requestBackup(mUserId, packages, observer, monitor, 0));
    }


    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testRequestBackup_callsRequestBackupForUser() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        String[] packages = {TEST_PACKAGE};
        IBackupObserver observer = mock(IBackupObserver.class);
        IBackupManagerMonitor monitor = mock(IBackupManagerMonitor.class);

        mBackupManagerService.requestBackup(mUserId, packages, observer, monitor,
                /* flags */ 0);

        verify(mUserBackupManagerService).requestBackup(packages, observer, monitor, /* flags */ 0);
    }

    /**
     * Test verifying that {@link BackupManagerService#cancelBackups(int)} throws a
     * {@link SecurityException} if the caller does not have INTERACT_ACROSS_USERS_FULL permission.
     */
    @Test
    public void testCancelBackups_withoutPermission_throwsSecurityException() {
        mShadowContext.denyPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        expectThrows(
                SecurityException.class,
                () -> mBackupManagerService.cancelBackups(mUserId));
    }


    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testCancelBackups_callsCancelBackupsForUser() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        mBackupManagerService.cancelBackups(mUserId);

        verify(mUserBackupManagerService).cancelBackups();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBeginFullBackup_callsBeginFullBackupForUser() throws Exception {
        FullBackupJob job = new FullBackupJob();

        mBackupManagerService.beginFullBackup(job);

        verify(mUserBackupManagerService).beginFullBackup(job);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testEndFullBackup_callsEndFullBackupForUser() throws Exception {
        mBackupManagerService.endFullBackup();

        verify(mUserBackupManagerService).endFullBackup();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testFullTransportBackup_callsFullTransportBackupForUser() throws Exception {
        String[] packages = {TEST_PACKAGE};

        mBackupManagerService.fullTransportBackup(packages);

        verify(mUserBackupManagerService).fullTransportBackup(packages);
    }

    // ---------------------------------------------
    // Restore tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testRestoreAtInstall_callsRestoreAtInstallForUser() throws Exception {
        mBackupManagerService.restoreAtInstall(TEST_PACKAGE, /* token */ 0);

        verify(mUserBackupManagerService).restoreAtInstall(TEST_PACKAGE, /* token */ 0);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBeginRestoreSession_callsBeginRestoreSessionForUser() throws Exception {
        mBackupManagerService.beginRestoreSession(TEST_PACKAGE, TEST_TRANSPORT);

        verify(mUserBackupManagerService).beginRestoreSession(TEST_PACKAGE, TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetAvailableRestoreToken_callsGetAvailableRestoreTokenForUser()
            throws Exception {
        mBackupManagerService.getAvailableRestoreToken(TEST_PACKAGE);

        verify(mUserBackupManagerService).getAvailableRestoreToken(TEST_PACKAGE);
    }

    // ---------------------------------------------
    // Adb backup/restore tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetBackupPassword_callsSetBackupPasswordForUser() throws Exception {
        mBackupManagerService.setBackupPassword("currentPassword", "newPassword");

        verify(mUserBackupManagerService).setBackupPassword("currentPassword", "newPassword");
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testHasBackupPassword_callsHasBackupPasswordForUser() throws Exception {
        mBackupManagerService.hasBackupPassword();

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

        mBackupManagerService.adbBackup(
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

        mBackupManagerService.adbRestore(parcelFileDescriptor);

        verify(mUserBackupManagerService).adbRestore(parcelFileDescriptor);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAcknowledgeAdbBackupOrRestore_callsAcknowledgeAdbBackupOrRestoreForUser()
            throws Exception {
        IFullBackupRestoreObserver observer = mock(IFullBackupRestoreObserver.class);

        mBackupManagerService.acknowledgeAdbBackupOrRestore(
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

        mBackupManagerService.dump(fileDescriptor, printWriter, args);

        verify(mUserBackupManagerService).dump(fileDescriptor, printWriter, args);
    }
}
