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
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;

import static com.android.server.backup.testing.TransportData.backupTransport;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;
import static org.testng.Assert.expectThrows;

import android.annotation.UserIdInt;
import android.app.Application;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
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

/** Tests for {@link com.android.server.backup.Trampoline}. */
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
public class TrampolineRoboTest {
    private static final String TEST_PACKAGE = "package";
    private static final String TEST_TRANSPORT = "transport";

    private Context mContext;
    private ShadowContextWrapper mShadowContext;
    private ShadowUserManager mShadowUserManager;
    @UserIdInt private int mUserOneId;
    @UserIdInt private int mUserTwoId;
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
        Trampoline backupManagerService = createService();
        backupManagerService.setBackupServiceActive(mUserOneId, true);

        backupManagerService.startServiceForUser(mUserOneId);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(1);
        assertThat(serviceUsers.get(mUserOneId)).isNotNull();
    }

    /** Test that the service registers users. */
    @Test
    public void testStartServiceForUser_withServiceInstance_registersUser() throws Exception {
        Trampoline backupManagerService = createService();
        backupManagerService.setBackupServiceActive(mUserOneId, true);

        backupManagerService.startServiceForUser(mUserOneId, mUserOneService);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(1);
        assertThat(serviceUsers.get(mUserOneId)).isEqualTo(mUserOneService);
    }

    /** Test that the service unregisters users when stopped. */
    @Test
    public void testStopServiceForUser_forRegisteredUser_unregistersCorrectUser() throws Exception {
        Trampoline backupManagerService =
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
        Trampoline backupManagerService =
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
        Trampoline backupManagerService = createService();
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
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.dataChanged(mUserOneId, TEST_PACKAGE);

        verify(mUserOneService).dataChanged(TEST_PACKAGE);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testDataChanged_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.dataChanged(mUserTwoId, TEST_PACKAGE);

        verify(mUserOneService, never()).dataChanged(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAgentConnected_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        IBinder agentBinder = mock(IBinder.class);

        backupManagerService.agentConnected(mUserOneId, TEST_PACKAGE, agentBinder);

        verify(mUserOneService).agentConnected(TEST_PACKAGE, agentBinder);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testAgentConnected_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        IBinder agentBinder = mock(IBinder.class);

        backupManagerService.agentConnected(mUserTwoId, TEST_PACKAGE, agentBinder);

        verify(mUserOneService, never()).agentConnected(TEST_PACKAGE, agentBinder);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testOpComplete_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.opComplete(mUserOneId, /* token */ 0, /* result */ 0L);

        verify(mUserOneService).opComplete(/* token */ 0, /* result */ 0L);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testOpComplete_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
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
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        String[] transports = {TEST_TRANSPORT};

        backupManagerService.initializeTransports(mUserOneId, transports, /* observer */ null);

        verify(mUserOneService).initializeTransports(transports, /* observer */ null);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testInitializeTransports_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);
        String[] transports = {TEST_TRANSPORT};

        backupManagerService.initializeTransports(mUserTwoId, transports, /* observer */ null);

        verify(mUserOneService, never()).initializeTransports(transports, /* observer */ null);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testClearBackupData_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.clearBackupData(mUserOneId, TEST_TRANSPORT, TEST_PACKAGE);

        verify(mUserOneService).clearBackupData(TEST_TRANSPORT, TEST_PACKAGE);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testClearBackupData_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.clearBackupData(mUserTwoId, TEST_TRANSPORT, TEST_PACKAGE);

        verify(mUserOneService, never()).clearBackupData(TEST_TRANSPORT, TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetCurrentTransport_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getCurrentTransport(mUserOneId);

        verify(mUserOneService).getCurrentTransport();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetCurrentTransport_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getCurrentTransport(mUserTwoId);

        verify(mUserOneService, never()).getCurrentTransport();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetCurrentTransportComponent_onRegisteredUser_callsMethodForUser()
            throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getCurrentTransportComponent(mUserOneId);

        verify(mUserOneService).getCurrentTransportComponent();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetCurrentTransportComponent_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getCurrentTransportComponent(mUserTwoId);

        verify(mUserOneService, never()).getCurrentTransportComponent();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testListAllTransports_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.listAllTransports(mUserOneId);

        verify(mUserOneService).listAllTransports();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testListAllTransports_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.listAllTransports(mUserTwoId);

        verify(mUserOneService, never()).listAllTransports();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testListAllTransportComponents_onRegisteredUser_callsMethodForUser()
            throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.listAllTransportComponents(mUserOneId);

        verify(mUserOneService).listAllTransportComponents();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testListAllTransportComponents_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.listAllTransportComponents(mUserTwoId);

        verify(mUserOneService, never()).listAllTransportComponents();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSelectBackupTransport_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.selectBackupTransport(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).selectBackupTransport(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSelectBackupTransport_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.selectBackupTransport(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).selectBackupTransport(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSelectTransportAsync_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
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
        Trampoline backupManagerService = createService();
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
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getConfigurationIntent(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).getConfigurationIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetConfigurationIntent_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getConfigurationIntent(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).getConfigurationIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDestinationString_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getDestinationString(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).getDestinationString(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetDestinationString_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getDestinationString(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).getDestinationString(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDataManagementIntent_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getDataManagementIntent(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).getDataManagementIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetDataManagementIntent_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getDataManagementIntent(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).getDataManagementIntent(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetDataManagementLabel_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getDataManagementLabel(mUserOneId, TEST_TRANSPORT);

        verify(mUserOneService).getDataManagementLabel(TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetDataManagementLabel_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.getDataManagementLabel(mUserTwoId, TEST_TRANSPORT);

        verify(mUserOneService, never()).getDataManagementLabel(TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testUpdateTransportAttributes_onRegisteredUser_callsMethodForUser()
            throws Exception {
        Trampoline backupManagerService = createService();
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
        Trampoline backupManagerService = createService();
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
        Trampoline backupManagerService = createService();
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
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        registerUser(backupManagerService, mUserTwoId, mUserTwoService);

        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.setBackupEnabled(mUserTwoId, true);

        verify(mUserTwoService).setBackupEnabled(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetBackupEnabled_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.setBackupEnabled(mUserOneId, true);

        verify(mUserOneService).setBackupEnabled(true);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSetBackupEnabled_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.setBackupEnabled(mUserTwoId, true);

        verify(mUserOneService, never()).setBackupEnabled(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetAutoRestore_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.setAutoRestore(mUserOneId, true);

        verify(mUserOneService).setAutoRestore(true);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSetAutoRestore_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.setAutoRestore(mUserTwoId, true);

        verify(mUserOneService, never()).setAutoRestore(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testIsBackupEnabled_onRegisteredUser_callsMethodForUser() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.isBackupEnabled(mUserOneId);

        verify(mUserOneService).isBackupEnabled();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testIsBackupEnabled_onUnknownUser_doesNotPropagateCall() throws Exception {
        Trampoline backupManagerService = createService();
        registerUser(backupManagerService, mUserOneId, mUserOneService);
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.isBackupEnabled(mUserTwoId);

        verify(mUserOneService, never()).isBackupEnabled();
    }

    private Trampoline createService() {
        return new Trampoline(mContext);
    }

    private void registerUser(
            Trampoline trampoline, int userId, UserBackupManagerService userBackupManagerService) {
        trampoline.setBackupServiceActive(userId, true);
        trampoline.startServiceForUser(userId, userBackupManagerService);
    }

    private Trampoline createServiceAndRegisterUser(
            int userId, UserBackupManagerService userBackupManagerService) {
        Trampoline backupManagerService = createService();
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
