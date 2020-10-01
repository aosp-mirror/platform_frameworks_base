/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.Manifest.permission.BACKUP;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;

import static com.android.server.backup.testing.TransportData.backupTransport;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import com.android.server.backup.testing.TransportData;
import com.android.server.testing.shadows.ShadowApplicationPackageManager;
import com.android.server.testing.shadows.ShadowBinder;
import com.android.server.testing.shadows.ShadowEnvironment;
import com.android.server.testing.shadows.ShadowSystemServiceRegistry;
import com.android.server.testing.shadows.ShadowUserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextWrapper;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Tests for {@link BackupManagerService}. */
@RunWith(RobolectricTestRunner.class)
@Config(
        shadows = {
                ShadowApplicationPackageManager.class,
                ShadowBinder.class,
                ShadowUserManager.class,
                ShadowEnvironment.class,
                ShadowSystemServiceRegistry.class
        })
@Presubmit
public class BackupManagerServiceRoboTest {
    private static final String TEST_PACKAGE = "package";
    private static final String TEST_TRANSPORT = "transport";
    private static final String[] ADB_TEST_PACKAGES = {TEST_PACKAGE};

    private Context mContext;
    private ShadowContextWrapper mShadowContext;
    private ShadowUserManager mShadowUserManager;
    @UserIdInt private int mUserOneId;
    @UserIdInt private int mUserTwoId;
    @Mock private UserBackupManagerService mUserSystemService;
    @Mock private UserBackupManagerService mUserOneService;
    @Mock private UserBackupManagerService mUserTwoService;

    /** Setup */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Application application = RuntimeEnvironment.application;
        mContext = application;
        mShadowContext = shadowOf(application);
        mShadowUserManager = Shadow.extract(UserManager.get(application));

        mUserOneId = UserHandle.USER_SYSTEM + 1;
        mUserTwoId = mUserOneId + 1;
        mShadowUserManager.addUser(mUserOneId, "mUserOneId", 0);
        mShadowUserManager.addUser(mUserTwoId, "mUserTwoId", 0);

        mShadowContext.grantPermissions(BACKUP);
        mShadowContext.grantPermissions(INTERACT_ACROSS_USERS_FULL);

        ShadowBinder.setCallingUid(Process.SYSTEM_UID);
    }

    /** Test that the service registers users. */
    @Test
    public void testStartServiceForUser_registersUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        backupManagerService.setBackupServiceActive(mUserOneId, true);

        backupManagerService.startServiceForUser(mUserOneId);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(1);
        assertThat(serviceUsers.get(mUserOneId)).isNotNull();
    }

    /** Test that the service registers users. */
    @Test
    public void testStartServiceForUser_withServiceInstance_registersUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        backupManagerService.setBackupServiceActive(mUserOneId, true);

        backupManagerService.startServiceForUser(mUserOneId, mUserOneService);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(1);
        assertThat(serviceUsers.get(mUserOneId)).isEqualTo(mUserOneService);
    }

    /** Test that the service unregisters users when stopped. */
    @Test
    public void testStopServiceForUser_forRegisteredUser_unregistersCorrectUser() throws Exception {
        BackupManagerService backupManagerService =
                createServiceAndRegisterUser(mUserOneId, mUserOneService);
        backupManagerService.startServiceForUser(mUserTwoId, mUserTwoService);
        ShadowBinder.setCallingUid(Process.SYSTEM_UID);

        backupManagerService.stopServiceForUser(mUserOneId);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(1);
        assertThat(serviceUsers.get(mUserOneId)).isNull();
        assertThat(serviceUsers.get(mUserTwoId)).isEqualTo(mUserTwoService);
    }

    /** Test that the service unregisters users when stopped. */
    @Test
    public void testStopServiceForUser_forRegisteredUser_tearsDownCorrectUser() throws Exception {
        BackupManagerService backupManagerService =
                createServiceAndRegisterUser(mUserOneId, mUserOneService);
        backupManagerService.setBackupServiceActive(mUserTwoId, true);
        backupManagerService.startServiceForUser(mUserTwoId, mUserTwoService);

        backupManagerService.stopServiceForUser(mUserOneId);

        verify(mUserOneService).tearDownService();
        verify(mUserTwoService, never()).tearDownService();
    }

    /** Test that the service unregisters users when stopped. */
    @Test
    public void testStopServiceForUser_forUnknownUser_doesNothing() throws Exception {
        BackupManagerService backupManagerService = createService();
        backupManagerService.setBackupServiceActive(mUserOneId, true);
        ShadowBinder.setCallingUid(Process.SYSTEM_UID);

        backupManagerService.stopServiceForUser(mUserOneId);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(0);
    }

    // ---------------------------------------------
    // Backup agent tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testDataChanged_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.dataChanged(mUserOneId, TEST_PACKAGE);

        verify(mUserOneService).dataChanged(TEST_PACKAGE);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testDataChanged_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.dataChanged(mUserTwoId, TEST_PACKAGE);

        verify(mUserOneService, never()).dataChanged(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAgentConnected_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        IBinder agentBinder = mock(IBinder.class);

        backupManagerService.agentConnected(mUserOneId, TEST_PACKAGE, agentBinder);

        verify(mUserOneService).agentConnected(TEST_PACKAGE, agentBinder);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testAgentConnected_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        IBinder agentBinder = mock(IBinder.class);

        backupManagerService.agentConnected(mUserTwoId, TEST_PACKAGE, agentBinder);

        verify(mUserOneService, never()).agentConnected(TEST_PACKAGE, agentBinder);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testOpComplete_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.opComplete(mUserOneId, /* token */ 0, /* result */ 0L);

        verify(mUserOneService).opComplete(/* token */ 0, /* result */ 0L);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testOpComplete_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.opComplete(mUserTwoId, /* token */ 0, /* result */ 0L);

        verify(mUserOneService, never()).opComplete(/* token */ 0, /* result */ 0L);
    }

    // ---------------------------------------------
    // Transport tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testInitializeTransports_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        String[] transports = {TEST_TRANSPORT};

        backupManagerService.initializeTransports(mUserOneId, transports, /* observer */ null);

        verify(mUserOneService).initializeTransports(transports, /* observer */ null);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testInitializeTransports_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        String[] transports = {TEST_TRANSPORT};

        backupManagerService.initializeTransports(mUserTwoId, transports, /* observer */ null);

        verify(mUserOneService, never()).initializeTransports(transports, /* observer */ null);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testClearBackupData_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.clearBackupData(mUserOneId, TEST_TRANSPORT, TEST_PACKAGE);

        verify(mUserOneService).clearBackupData(TEST_TRANSPORT, TEST_PACKAGE);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testClearBackupData_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.clearBackupData(mUserTwoId, TEST_TRANSPORT, TEST_PACKAGE);

        verify(mUserOneService, never()).clearBackupData(TEST_TRANSPORT, TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetCurrentTransport_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getCurrentTransport(mUserOneId);

        verify(mUserOneService).getCurrentTransport();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetCurrentTransport_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getCurrentTransport(mUserTwoId);

        verify(mUserOneService, never()).getCurrentTransport();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetCurrentTransportComponent_onRegisteredUser_callsMethodForUser()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getCurrentTransportComponent(mUserOneId);

        verify(mUserOneService).getCurrentTransportComponent();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetCurrentTransportComponent_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getCurrentTransportComponent(mUserTwoId);

        verify(mUserOneService, never()).getCurrentTransportComponent();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testListAllTransports_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.listAllTransports(mUserOneId);

        verify(mUserOneService).listAllTransports();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testListAllTransports_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.listAllTransports(mUserTwoId);

        verify(mUserOneService, never()).listAllTransports();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testListAllTransportComponents_onRegisteredUser_callsMethodForUser()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.listAllTransportComponents(mUserOneId);

        verify(mUserOneService).listAllTransportComponents();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testListAllTransportComponents_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.listAllTransportComponents(mUserTwoId);

        verify(mUserOneService, never()).listAllTransportComponents();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSelectBackupTransport_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.selectBackupTransport(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).selectBackupTransport(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSelectBackupTransport_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.selectBackupTransport(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).selectBackupTransport(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSelectTransportAsync_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        TransportData transport = backupTransport();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        backupManagerService.selectBackupTransportAsync(
                mUserOneId, transport.getTransportComponent(), callback);

        verify(mUserOneService)
                .selectBackupTransportAsync(transport.getTransportComponent(), callback);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSelectBackupTransportAsync_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        TransportData transport = backupTransport();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        backupManagerService.selectBackupTransportAsync(
                mUserTwoId, transport.getTransportComponent(), callback);

        verify(mUserOneService, never())
                .selectBackupTransportAsync(transport.getTransportComponent(), callback);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetConfigurationIntent_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getConfigurationIntent(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).getConfigurationIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetConfigurationIntent_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getConfigurationIntent(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).getConfigurationIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDestinationString_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getDestinationString(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).getDestinationString(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetDestinationString_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getDestinationString(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).getDestinationString(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDataManagementIntent_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getDataManagementIntent(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).getDataManagementIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetDataManagementIntent_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getDataManagementIntent(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).getDataManagementIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDataManagementLabel_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getDataManagementLabel(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).getDataManagementLabel(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetDataManagementLabel_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getDataManagementLabel(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).getDataManagementLabel(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testUpdateTransportAttributes_onRegisteredUser_callsMethodForUser()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        TransportData transport = backupTransport();
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();

        backupManagerService.updateTransportAttributes(
                mUserOneId,
                transport.getTransportComponent(),
                transport.transportName,
                configurationIntent,
                "currentDestinationString",
                dataManagementIntent,
                "dataManagementLabel");

        verify(mUserOneService)
                .updateTransportAttributes(
                        transport.getTransportComponent(),
                        transport.transportName,
                        configurationIntent,
                        "currentDestinationString",
                        dataManagementIntent,
                        "dataManagementLabel");
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testUpdateTransportAttributes_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        TransportData transport = backupTransport();
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();

        backupManagerService.updateTransportAttributes(
                mUserTwoId,
                transport.getTransportComponent(),
                transport.transportName,
                configurationIntent,
                "currentDestinationString",
                dataManagementIntent,
                "dataManagementLabel");

        verify(mUserOneService, never())
                .updateTransportAttributes(
                        transport.getTransportComponent(),
                        transport.transportName,
                        configurationIntent,
                        "currentDestinationString",
                        dataManagementIntent,
                        "dataManagementLabel");
    }

    // ---------------------------------------------
    // Settings tests
    // ---------------------------------------------

    /**
     * Test that the backup services throws a {@link SecurityException} if the caller does not have
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testSetBackupEnabled_withoutPermission_throwsSecurityExceptionForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.setBackupEnabled(mUserTwoId, true));
    }

    /**
     * Test that the backup service does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testSetBackupEnabled_withPermission_propagatesForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);

        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.setBackupEnabled(mUserTwoId, true);

        verify(mUserTwoService).setBackupEnabled(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetBackupEnabled_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.setBackupEnabled(mUserOneId, true);

        verify(mUserOneService).setBackupEnabled(true);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSetBackupEnabled_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.setBackupEnabled(mUserTwoId, true);

        verify(mUserOneService, never()).setBackupEnabled(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetAutoRestore_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.setAutoRestore(mUserOneId, true);

        verify(mUserOneService).setAutoRestore(true);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSetAutoRestore_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.setAutoRestore(mUserTwoId, true);

        verify(mUserOneService, never()).setAutoRestore(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testIsBackupEnabled_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.isBackupEnabled(mUserOneId);

        verify(mUserOneService).isBackupEnabled();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testIsBackupEnabled_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.isBackupEnabled(mUserTwoId);

        verify(mUserOneService, never()).isBackupEnabled();
    }

    // ---------------------------------------------
    // Backup tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testIsAppEligibleForBackup_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.isAppEligibleForBackup(mUserOneId, TEST_PACKAGE);

        verify(mUserOneService).isAppEligibleForBackup(TEST_PACKAGE);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testIsAppEligibleForBackup_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.isAppEligibleForBackup(mUserTwoId, TEST_PACKAGE);

        verify(mUserOneService, never()).isAppEligibleForBackup(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testFilterAppsEligibleForBackup_onRegisteredUser_callsMethodForUser()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        String[] packages = {TEST_PACKAGE};

        backupManagerService.filterAppsEligibleForBackup(mUserOneId, packages);

        verify(mUserOneService).filterAppsEligibleForBackup(packages);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testFilterAppsEligibleForBackup_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        String[] packages = {TEST_PACKAGE};

        backupManagerService.filterAppsEligibleForBackup(mUserTwoId, packages);

        verify(mUserOneService, never()).filterAppsEligibleForBackup(packages);
    }

    /**
     * Test verifying that {@link BackupManagerService#backupNow(int)} throws a {@link
     * SecurityException} if the caller does not have INTERACT_ACROSS_USERS_FULL permission.
     */
    @Test
    public void testBackupNow_withoutPermission_throwsSecurityExceptionForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        expectThrows(SecurityException.class, () -> backupManagerService.backupNow(mUserTwoId));
    }

    /**
     * Test that the backup service does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testBackupNow_withPermission_propagatesForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);

        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.backupNow(mUserTwoId);

        verify(mUserTwoService).backupNow();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBackupNow_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.backupNow(mUserOneId);

        verify(mUserOneService).backupNow();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testBackupNow_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.backupNow(mUserTwoId);

        verify(mUserOneService, never()).backupNow();
    }

    /**
     * Test that the backup services throws a {@link SecurityException} if the caller does not have
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testRequestBackup_withoutPermission_throwsSecurityExceptionForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        String[] packages = {TEST_PACKAGE};
        IBackupObserver observer = mock(IBackupObserver.class);
        IBackupManagerMonitor monitor = mock(IBackupManagerMonitor.class);

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.requestBackup(
                                mUserTwoId, packages, observer, monitor, 0));
    }

    /**
     * Test that the backup service does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testRequestBackup_withPermission_propagatesForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);

        String[] packages = {TEST_PACKAGE};
        IBackupObserver observer = mock(IBackupObserver.class);
        IBackupManagerMonitor monitor = mock(IBackupManagerMonitor.class);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.requestBackup(mUserTwoId, packages, observer, monitor, /* flags */ 0);

        verify(mUserTwoService).requestBackup(packages, observer, monitor, /* flags */ 0);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testRequestBackup_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        String[] packages = {TEST_PACKAGE};
        IBackupObserver observer = mock(IBackupObserver.class);
        IBackupManagerMonitor monitor = mock(IBackupManagerMonitor.class);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.requestBackup(mUserOneId, packages, observer, monitor, /* flags */ 0);

        verify(mUserOneService).requestBackup(packages, observer, monitor, /* flags */ 0);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testRequestBackup_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        String[] packages = {TEST_PACKAGE};
        IBackupObserver observer = mock(IBackupObserver.class);
        IBackupManagerMonitor monitor = mock(IBackupManagerMonitor.class);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.requestBackup(mUserTwoId, packages, observer, monitor, /* flags */ 0);

        verify(mUserOneService, never()).requestBackup(packages, observer, monitor, /* flags */ 0);
    }

    /**
     * Test verifying that {@link BackupManagerService#cancelBackups(int)} throws a {@link
     * SecurityException} if the caller does not have INTERACT_ACROSS_USERS_FULL permission.
     */
    @Test
    public void testCancelBackups_withoutPermission_throwsSecurityExceptionForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        expectThrows(SecurityException.class, () -> backupManagerService.cancelBackups(mUserTwoId));
    }

    /**
     * Test that the backup service does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testCancelBackups_withPermission_propagatesForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.cancelBackups(mUserTwoId);

        verify(mUserTwoService).cancelBackups();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testCancelBackups_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.cancelBackups(mUserOneId);

        verify(mUserOneService).cancelBackups();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testCancelBackups_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.cancelBackups(mUserTwoId);

        verify(mUserOneService, never()).cancelBackups();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBeginFullBackup_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, UserHandle.USER_SYSTEM, mUserOneService);
        FullBackupJob job = new FullBackupJob();

        backupManagerService.beginFullBackup(UserHandle.USER_SYSTEM, job);

        verify(mUserOneService).beginFullBackup(job);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testBeginFullBackup_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        FullBackupJob job = new FullBackupJob();

        backupManagerService.beginFullBackup(UserHandle.USER_SYSTEM, job);

        verify(mUserOneService, never()).beginFullBackup(job);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testEndFullBackup_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, UserHandle.USER_SYSTEM, mUserOneService);

        backupManagerService.endFullBackup(UserHandle.USER_SYSTEM);

        verify(mUserOneService).endFullBackup();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testEndFullBackup_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();

        backupManagerService.endFullBackup(UserHandle.USER_SYSTEM);

        verify(mUserOneService, never()).endFullBackup();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testFullTransportBackup_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        String[] packages = {TEST_PACKAGE};

        backupManagerService.fullTransportBackup(mUserOneId, packages);

        verify(mUserOneService).fullTransportBackup(packages);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testFullTransportBackup_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        String[] packages = {TEST_PACKAGE};

        backupManagerService.fullTransportBackup(mUserTwoId, packages);

        verify(mUserOneService, never()).fullTransportBackup(packages);
    }

    // ---------------------------------------------
    // Restore tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testRestoreAtInstall_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.restoreAtInstall(mUserOneId, TEST_PACKAGE, /* token */ 0);

        verify(mUserOneService).restoreAtInstall(TEST_PACKAGE, /* token */ 0);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testRestoreAtInstall_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.restoreAtInstall(mUserTwoId, TEST_PACKAGE, /* token */ 0);

        verify(mUserOneService, never()).restoreAtInstall(TEST_PACKAGE, /* token */ 0);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBeginRestoreSession_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.beginRestoreSession(mUserOneId, TEST_PACKAGE, TEST_TRANSPORT);

        verify(mUserOneService).beginRestoreSession(TEST_PACKAGE, TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testBeginRestoreSession_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.beginRestoreSession(mUserTwoId, TEST_PACKAGE, TEST_TRANSPORT);

        verify(mUserOneService, never()).beginRestoreSession(TEST_PACKAGE, TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetAvailableRestoreToken_onRegisteredUser_callsMethodForUser()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getAvailableRestoreToken(mUserOneId, TEST_PACKAGE);

        verify(mUserOneService).getAvailableRestoreToken(TEST_PACKAGE);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetAvailableRestoreToken_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getAvailableRestoreToken(mUserTwoId, TEST_PACKAGE);

        verify(mUserOneService, never()).getAvailableRestoreToken(TEST_PACKAGE);
    }

    // ---------------------------------------------
    // Adb backup/restore tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetBackupPassword_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, UserHandle.USER_SYSTEM, mUserOneService);
        ShadowBinder.setCallingUserHandle(UserHandle.of(UserHandle.USER_SYSTEM));

        backupManagerService.setBackupPassword("currentPassword", "newPassword");

        verify(mUserOneService).setBackupPassword("currentPassword", "newPassword");
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSetBackupPassword_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();

        backupManagerService.setBackupPassword("currentPassword", "newPassword");

        verify(mUserOneService, never()).setBackupPassword("currentPassword", "newPassword");
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testHasBackupPassword_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, UserHandle.USER_SYSTEM, mUserOneService);
        ShadowBinder.setCallingUserHandle(UserHandle.of(UserHandle.USER_SYSTEM));

        backupManagerService.hasBackupPassword();

        verify(mUserOneService).hasBackupPassword();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testHasBackupPassword_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createService();

        backupManagerService.hasBackupPassword();

        verify(mUserOneService, never()).hasBackupPassword();
    }

    /**
     * Test that the backup services throws a {@link SecurityException} if the caller does not have
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testAdbBackup_withoutPermission_throwsSecurityExceptionForNonCallingUser() {
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.adbBackup(
                                mUserTwoId,
                                /* parcelFileDescriptor*/ null,
                                /* includeApks */ true,
                                /* includeObbs */ true,
                                /* includeShared */ true,
                                /* doWidgets */ true,
                                /* doAllApps */ true,
                                /* includeSystem */ true,
                                /* doCompress */ true,
                                /* doKeyValue */ true,
                                null));
    }

    /**
     * Test that the backup service does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testAdbBackup_withPermission_propagatesForNonCallingUser() throws Exception {
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);

        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.adbBackup(
                mUserTwoId,
                parcelFileDescriptor,
                /* includeApks */ true,
                /* includeObbs */ true,
                /* includeShared */ true,
                /* doWidgets */ true,
                /* doAllApps */ true,
                /* includeSystem */ true,
                /* doCompress */ true,
                /* doKeyValue */ true,
                ADB_TEST_PACKAGES);

        verify(mUserTwoService)
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
                        ADB_TEST_PACKAGES);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAdbBackup_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.adbBackup(
                mUserOneId,
                parcelFileDescriptor,
                /* includeApks */ true,
                /* includeObbs */ true,
                /* includeShared */ true,
                /* doWidgets */ true,
                /* doAllApps */ true,
                /* includeSystem */ true,
                /* doCompress */ true,
                /* doKeyValue */ true,
                ADB_TEST_PACKAGES);

        verify(mUserOneService)
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
                        ADB_TEST_PACKAGES);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testAdbBackup_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.adbBackup(
                mUserTwoId,
                parcelFileDescriptor,
                /* includeApks */ true,
                /* includeObbs */ true,
                /* includeShared */ true,
                /* doWidgets */ true,
                /* doAllApps */ true,
                /* includeSystem */ true,
                /* doCompress */ true,
                /* doKeyValue */ true,
                ADB_TEST_PACKAGES);

        verify(mUserOneService, never())
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
                        ADB_TEST_PACKAGES);
    }

    /**
     * Test that the backup services throws a {@link SecurityException} if the caller does not have
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testAdbRestore_withoutPermission_throwsSecurityExceptionForNonCallingUser() {
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        expectThrows(
                SecurityException.class, () -> backupManagerService.adbRestore(mUserTwoId, null));
    }

    /**
     * Test that the backup service does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testAdbRestore_withPermission_propagatesForNonCallingUser() throws Exception {
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);
        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.adbRestore(mUserTwoId, parcelFileDescriptor);

        verify(mUserTwoService).adbRestore(parcelFileDescriptor);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAdbRestore_onRegisteredUser_callsMethodForUser() throws Exception {
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.adbRestore(mUserOneId, parcelFileDescriptor);

        verify(mUserOneService).adbRestore(parcelFileDescriptor);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testAdbRestore_onUnknownUser_doesNotPropagateCall() throws Exception {
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.adbRestore(mUserTwoId, parcelFileDescriptor);

        verify(mUserOneService, never()).adbRestore(parcelFileDescriptor);
    }

    private ParcelFileDescriptor getFileDescriptorForAdbTest() throws Exception {
        File testFile = new File(mContext.getFilesDir(), "test");
        testFile.createNewFile();
        return ParcelFileDescriptor.open(testFile, ParcelFileDescriptor.MODE_READ_WRITE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAcknowledgeAdbBackupOrRestore_onRegisteredUser_callsMethodForUser()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        IFullBackupRestoreObserver observer = mock(IFullBackupRestoreObserver.class);

        backupManagerService.acknowledgeAdbBackupOrRestore(
                mUserOneId,
                /* token */ 0,
                /* allow */ true,
                "currentPassword",
                "encryptionPassword",
                observer);

        verify(mUserOneService)
                .acknowledgeAdbBackupOrRestore(
                        /* token */ 0,
                        /* allow */ true,
                        "currentPassword",
                        "encryptionPassword",
                        observer);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testAcknowledgeAdbBackupOrRestore_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        IFullBackupRestoreObserver observer = mock(IFullBackupRestoreObserver.class);

        backupManagerService.acknowledgeAdbBackupOrRestore(
                mUserTwoId,
                /* token */ 0,
                /* allow */ true,
                "currentPassword",
                "encryptionPassword",
                observer);

        verify(mUserOneService, never())
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
    public void testDump_onRegisteredUser_callsMethodForUser() throws Exception {
        grantDumpPermissions();
        BackupManagerService backupManagerService = createSystemRegisteredService();
        File testFile = createTestFile();
        FileDescriptor fileDescriptor = new FileDescriptor();
        PrintWriter printWriter = new PrintWriter(testFile);
        String[] args = {"1", "2"};
        ShadowBinder.setCallingUserHandle(UserHandle.of(UserHandle.USER_SYSTEM));

        backupManagerService.dump(fileDescriptor, printWriter, args);

        verify(mUserSystemService).dump(fileDescriptor, printWriter, args);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testDump_onUnknownUser_doesNotPropagateCall() throws Exception {
        grantDumpPermissions();
        BackupManagerService backupManagerService = createService();
        File testFile = createTestFile();
        FileDescriptor fileDescriptor = new FileDescriptor();
        PrintWriter printWriter = new PrintWriter(testFile);
        String[] args = {"1", "2"};

        backupManagerService.dump(fileDescriptor, printWriter, args);

        verify(mUserOneService, never()).dump(fileDescriptor, printWriter, args);
    }

    /** Test that 'dumpsys backup users' dumps the list of users registered in backup service*/
    @Test
    public void testDump_users_dumpsListOfRegisteredUsers() {
        grantDumpPermissions();
        BackupManagerService backupManagerService = createSystemRegisteredService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);
        String[] args = {"users"};

        backupManagerService.dump(null, writer, args);

        writer.flush();
        assertEquals(
                String.format("%s %d %d\n", BackupManagerService.DUMP_RUNNING_USERS_MESSAGE,
                        UserHandle.USER_SYSTEM, mUserOneId),
                out.toString());
    }

    private File createTestFile() throws IOException {
        File testFile = new File(mContext.getFilesDir(), "test");
        testFile.createNewFile();
        return testFile;
    }

    private void grantDumpPermissions() {
        mShadowContext.grantPermissions(DUMP);
        mShadowContext.grantPermissions(PACKAGE_USAGE_STATS);
    }

    /**
     * Test that the backup services throws a {@link SecurityException} if the caller does not have
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testGetServiceForUser_withoutPermission_throwsSecurityExceptionForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.getServiceForUserIfCallerHasPermission(
                                mUserOneId, "test"));
    }

    /**
     * Test that the backup services does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testGetServiceForUserIfCallerHasPermission_withPermission_worksForNonCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ true);

        assertEquals(
                mUserOneService,
                backupManagerService.getServiceForUserIfCallerHasPermission(mUserOneId, "test"));
    }

    /**
     * Test that the backup services does not throw a {@link SecurityException} if the caller does
     * not have INTERACT_ACROSS_USERS_FULL permission and passes in the calling user id.
     */
    @Test
    public void testGetServiceForUserIfCallerHasPermission_withoutPermission_worksForCallingUser() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        assertEquals(
                mUserOneService,
                backupManagerService.getServiceForUserIfCallerHasPermission(mUserOneId, "test"));
    }

    /**
     * Test verifying that {@link BackupManagerService#MORE_DEBUG} is set to {@code false}. This is
     * specifically to prevent overloading the logs in production.
     */
    @Test
    public void testMoreDebug_isFalse() throws Exception {
        boolean moreDebug = BackupManagerService.MORE_DEBUG;

        assertThat(moreDebug).isFalse();
    }

    /** Test that the constructor handles {@code null} parameters. */
    @Test
    public void testConstructor_withNullContext_throws() throws Exception {
        expectThrows(
                NullPointerException.class,
                () ->
                        new BackupManagerService(
                                /* context */ null,
                                new SparseArray<>()));
    }

    /** Test that the constructor does not create {@link UserBackupManagerService} instances. */
    @Test
    public void testConstructor_doesNotRegisterUsers() throws Exception {
        BackupManagerService backupManagerService = createService();

        assertThat(backupManagerService.getUserServices().size()).isEqualTo(0);
    }

    // ---------------------------------------------
    //  Lifecycle tests
    // ---------------------------------------------

    /** testOnStart_publishesService */
    @Test
    public void testOnStart_publishesService() {
        BackupManagerService backupManagerService = mock(BackupManagerService.class);
        BackupManagerService.Lifecycle lifecycle =
                spy(new BackupManagerService.Lifecycle(mContext, backupManagerService));
        doNothing().when(lifecycle).publishService(anyString(), any());

        lifecycle.onStart();

        verify(lifecycle).publishService(Context.BACKUP_SERVICE, backupManagerService);
    }

    /** testOnUnlockUser_forwards */
    @Test
    public void testOnUnlockUser_forwards() {
        BackupManagerService backupManagerService = mock(BackupManagerService.class);
        BackupManagerService.Lifecycle lifecycle =
                new BackupManagerService.Lifecycle(mContext, backupManagerService);

        lifecycle.onUnlockUser(UserHandle.USER_SYSTEM);

        verify(backupManagerService).onUnlockUser(UserHandle.USER_SYSTEM);
    }

    /** testOnStopUser_forwards */
    @Test
    public void testOnStopUser_forwards() {
        BackupManagerService backupManagerService = mock(BackupManagerService.class);
        BackupManagerService.Lifecycle lifecycle =
                new BackupManagerService.Lifecycle(mContext, backupManagerService);

        lifecycle.onStopUser(UserHandle.USER_SYSTEM);

        verify(backupManagerService).onStopUser(UserHandle.USER_SYSTEM);
    }

    private BackupManagerService createService() {
        return new BackupManagerService(mContext);
    }

    private BackupManagerService createSystemRegisteredService() {
        BackupManagerService backupManagerService = createService();
        registerUser(backupManagerService, UserHandle.USER_SYSTEM, mUserSystemService);
        return backupManagerService;
    }

    private void registerUser(
            BackupManagerService backupManagerService,
            int userId,
            UserBackupManagerService userBackupManagerService) {
        backupManagerService.setBackupServiceActive(userId, true);
        backupManagerService.startServiceForUser(userId, userBackupManagerService);
    }

    private BackupManagerService createServiceAndRegisterUser(
            int userId, UserBackupManagerService userBackupManagerService) {
        BackupManagerService backupManagerService = createService();
        backupManagerService.setBackupServiceActive(userBackupManagerService.getUserId(), true);
        backupManagerService.startServiceForUser(userId, userBackupManagerService);
        return backupManagerService;
    }

    /**
     * Sets the calling user to {@code userId} and grants the permission INTERACT_ACROSS_USERS_FULL
     * to the caller if {@code shouldGrantPermission} is {@code true}, else it denies the
     * permission.
     */
    private void setCallerAndGrantInteractUserPermission(
            @UserIdInt int userId, boolean shouldGrantPermission) {
        ShadowBinder.setCallingUserHandle(UserHandle.of(userId));
        if (shouldGrantPermission) {
            mShadowContext.grantPermissions(INTERACT_ACROSS_USERS_FULL);
        } else {
            mShadowContext.denyPermissions(INTERACT_ACROSS_USERS_FULL);
        }
    }
}
