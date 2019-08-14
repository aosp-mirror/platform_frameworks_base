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

import static android.Manifest.permission.BACKUP;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;

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
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import com.android.server.testing.shadows.ShadowApplicationPackageManager;
import com.android.server.testing.shadows.ShadowBinder;
import com.android.server.testing.shadows.ShadowEnvironment;
import com.android.server.testing.shadows.ShadowSystemServiceRegistry;
import com.android.server.testing.shadows.ShadowUserManager;

import org.junit.After;
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

/** Tests for the user-aware backup/restore system service {@link BackupManagerService}. */
@RunWith(RobolectricTestRunner.class)
@Config(
        shadows = {
            ShadowApplicationPackageManager.class,
            ShadowBinder.class,
            ShadowEnvironment.class,
            ShadowSystemServiceRegistry.class,
            ShadowUserManager.class,
        })
@Presubmit
public class BackupManagerServiceTest {
    private static final String TEST_PACKAGE = "package";
    private static final String TEST_TRANSPORT = "transport";
    private static final String[] ADB_TEST_PACKAGES = {TEST_PACKAGE};

    private ShadowContextWrapper mShadowContext;
    private ShadowUserManager mShadowUserManager;
    private Context mContext;
    private Trampoline mTrampoline;
    @UserIdInt private int mUserOneId;
    @UserIdInt private int mUserTwoId;
    @Mock private UserBackupManagerService mUserOneService;
    @Mock private UserBackupManagerService mUserTwoService;

    /** Initialize {@link BackupManagerService}. */
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

        mTrampoline = new Trampoline(mContext);
        ShadowBinder.setCallingUid(Process.SYSTEM_UID);
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
     * Test verifying that {@link BackupManagerService#MORE_DEBUG} is set to {@code false}. This is
     * specifically to prevent overloading the logs in production.
     */
    @Test
    public void testMoreDebug_isFalse() throws Exception {
        boolean moreDebug = BackupManagerService.MORE_DEBUG;

        assertThat(moreDebug).isFalse();
    }

    /** Test that the constructor does not create {@link UserBackupManagerService} instances. */
    @Test
    public void testConstructor_doesNotRegisterUsers() throws Exception {
        BackupManagerService backupManagerService = createService();

        assertThat(mTrampoline.getUserServices().size()).isEqualTo(0);
    }

    /** Test that the constructor handles {@code null} parameters. */
    @Test
    public void testConstructor_withNullContext_throws() throws Exception {
        expectThrows(
                NullPointerException.class,
                () ->
                        new BackupManagerService(
                                /* context */ null,
                                new Trampoline(mContext),
                                new SparseArray<>()));
    }

    /** Test that the constructor handles {@code null} parameters. */
    @Test
    public void testConstructor_withNullTrampoline_throws() throws Exception {
        expectThrows(
                NullPointerException.class,
                () ->
                        new BackupManagerService(
                                mContext, /* trampoline */ null, new SparseArray<>()));
    }

    /**
     * Test that the backup services throws a {@link SecurityException} if the caller does not have
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testGetServiceForUser_withoutPermission_throwsSecurityExceptionForNonCallingUser() {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        assertEquals(
                mUserOneService,
                backupManagerService.getServiceForUserIfCallerHasPermission(mUserOneId, "test"));
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        registerUser(mUserTwoId, mUserTwoService);
        BackupManagerService backupManagerService = createService();

        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.setBackupEnabled(mUserTwoId, true);

        verify(mUserTwoService).setBackupEnabled(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetBackupEnabled_onRegisteredUser_callsMethodForUser() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.setBackupEnabled(mUserOneId, true);

        verify(mUserOneService).setBackupEnabled(true);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSetBackupEnabled_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.setBackupEnabled(mUserTwoId, true);

        verify(mUserOneService, never()).setBackupEnabled(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testSetAutoRestore_onRegisteredUser_callsMethodForUser() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.setAutoRestore(mUserOneId, true);

        verify(mUserOneService).setAutoRestore(true);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testSetAutoRestore_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.setAutoRestore(mUserTwoId, true);

        verify(mUserOneService, never()).setAutoRestore(true);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testIsBackupEnabled_onRegisteredUser_callsMethodForUser() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.isBackupEnabled(mUserOneId);

        verify(mUserOneService).isBackupEnabled();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testIsBackupEnabled_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.isAppEligibleForBackup(mUserOneId, TEST_PACKAGE);

        verify(mUserOneService).isAppEligibleForBackup(TEST_PACKAGE);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testIsAppEligibleForBackup_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.isAppEligibleForBackup(mUserTwoId, TEST_PACKAGE);

        verify(mUserOneService, never()).isAppEligibleForBackup(TEST_PACKAGE);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testFilterAppsEligibleForBackup_onRegisteredUser_callsMethodForUser()
            throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        String[] packages = {TEST_PACKAGE};

        backupManagerService.filterAppsEligibleForBackup(mUserOneId, packages);

        verify(mUserOneService).filterAppsEligibleForBackup(packages);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testFilterAppsEligibleForBackup_onUnknownUser_doesNotPropagateCall()
            throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        expectThrows(SecurityException.class, () -> backupManagerService.backupNow(mUserTwoId));
    }

    /**
     * Test that the backup service does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testBackupNow_withPermission_propagatesForNonCallingUser() {
        registerUser(mUserOneId, mUserOneService);
        registerUser(mUserTwoId, mUserTwoService);
        BackupManagerService backupManagerService = createService();

        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.backupNow(mUserTwoId);

        verify(mUserTwoService).backupNow();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBackupNow_onRegisteredUser_callsMethodForUser() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.backupNow(mUserOneId);

        verify(mUserOneService).backupNow();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testBackupNow_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        registerUser(mUserTwoId, mUserTwoService);
        BackupManagerService backupManagerService = createService();

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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        expectThrows(SecurityException.class, () -> backupManagerService.cancelBackups(mUserTwoId));
    }

    /**
     * Test that the backup service does not throw a {@link SecurityException} if the caller has
     * INTERACT_ACROSS_USERS_FULL permission and passes a different user id.
     */
    @Test
    public void testCancelBackups_withPermission_propagatesForNonCallingUser() {
        registerUser(mUserOneId, mUserOneService);
        registerUser(mUserTwoId, mUserTwoService);
        BackupManagerService backupManagerService = createService();

        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.cancelBackups(mUserTwoId);

        verify(mUserTwoService).cancelBackups();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testCancelBackups_onRegisteredUser_callsMethodForUser() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.cancelBackups(mUserOneId);

        verify(mUserOneService).cancelBackups();
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testCancelBackups_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.cancelBackups(mUserTwoId);

        verify(mUserOneService, never()).cancelBackups();
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBeginFullBackup_onRegisteredUser_callsMethodForUser() throws Exception {
        registerUser(UserHandle.USER_SYSTEM, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(UserHandle.USER_SYSTEM, mUserOneService);
        BackupManagerService backupManagerService = createService();

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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);
        String[] packages = {TEST_PACKAGE};

        backupManagerService.fullTransportBackup(mUserOneId, packages);

        verify(mUserOneService).fullTransportBackup(packages);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testFullTransportBackup_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.restoreAtInstall(mUserOneId, TEST_PACKAGE, /* token */ 0);

        verify(mUserOneService).restoreAtInstall(TEST_PACKAGE, /* token */ 0);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testRestoreAtInstall_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.restoreAtInstall(mUserTwoId, TEST_PACKAGE, /* token */ 0);

        verify(mUserOneService, never()).restoreAtInstall(TEST_PACKAGE, /* token */ 0);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testBeginRestoreSession_onRegisteredUser_callsMethodForUser() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.beginRestoreSession(mUserOneId, TEST_PACKAGE, TEST_TRANSPORT);

        verify(mUserOneService).beginRestoreSession(TEST_PACKAGE, TEST_TRANSPORT);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testBeginRestoreSession_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.beginRestoreSession(mUserTwoId, TEST_PACKAGE, TEST_TRANSPORT);

        verify(mUserOneService, never()).beginRestoreSession(TEST_PACKAGE, TEST_TRANSPORT);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testGetAvailableRestoreToken_onRegisteredUser_callsMethodForUser()
            throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.getAvailableRestoreToken(mUserOneId, TEST_PACKAGE);

        verify(mUserOneService).getAvailableRestoreToken(TEST_PACKAGE);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testGetAvailableRestoreToken_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(UserHandle.USER_SYSTEM, mUserOneService);
        BackupManagerService backupManagerService = createService();

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
        registerUser(UserHandle.USER_SYSTEM, mUserOneService);
        BackupManagerService backupManagerService = createService();

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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        registerUser(mUserTwoId, mUserTwoService);
        BackupManagerService backupManagerService = createService();

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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        registerUser(mUserTwoId, mUserTwoService);
        BackupManagerService backupManagerService = createService();
        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ true);

        backupManagerService.adbRestore(mUserTwoId, parcelFileDescriptor);

        verify(mUserTwoService).adbRestore(parcelFileDescriptor);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAdbRestore_onRegisteredUser_callsMethodForUser() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserOneId, /* shouldGrantPermission */ false);

        backupManagerService.adbRestore(mUserOneId, parcelFileDescriptor);

        verify(mUserOneService).adbRestore(parcelFileDescriptor);
    }

    /** Test that the backup service does not route methods for non-registered users. */
    @Test
    public void testAdbRestore_onUnknownUser_doesNotPropagateCall() throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        ParcelFileDescriptor parcelFileDescriptor = getFileDescriptorForAdbTest();
        setCallerAndGrantInteractUserPermission(mUserTwoId, /* shouldGrantPermission */ false);

        backupManagerService.adbRestore(mUserTwoId, parcelFileDescriptor);

        verify(mUserOneService, never()).adbRestore(parcelFileDescriptor);
    }

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testAcknowledgeAdbBackupOrRestore_onRegisteredUser_callsMethodForUser()
            throws Exception {
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
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
    //  Lifecycle tests
    // ---------------------------------------------

    /** testOnStart_publishesService */
    @Test
    public void testOnStart_publishesService() {
        Trampoline trampoline = mock(Trampoline.class);
        BackupManagerService.Lifecycle lifecycle =
                spy(new BackupManagerService.Lifecycle(mContext, trampoline));
        doNothing().when(lifecycle).publishService(anyString(), any());

        lifecycle.onStart();

        verify(lifecycle).publishService(Context.BACKUP_SERVICE, trampoline);
    }

    /** testOnUnlockUser_forwards */
    @Test
    public void testOnUnlockUser_forwards() {
        Trampoline trampoline = mock(Trampoline.class);
        BackupManagerService.Lifecycle lifecycle =
                new BackupManagerService.Lifecycle(mContext, trampoline);

        lifecycle.onUnlockUser(UserHandle.USER_SYSTEM);

        verify(trampoline).onUnlockUser(UserHandle.USER_SYSTEM);
    }

    /** testOnStopUser_forwards */
    @Test
    public void testOnStopUser_forwards() {
        Trampoline trampoline = mock(Trampoline.class);
        BackupManagerService.Lifecycle lifecycle =
                new BackupManagerService.Lifecycle(mContext, trampoline);

        lifecycle.onStopUser(UserHandle.USER_SYSTEM);

        verify(trampoline).onStopUser(UserHandle.USER_SYSTEM);
    }

    // ---------------------------------------------
    //  Service tests
    // ---------------------------------------------

    /** Test that the backup service routes methods correctly to the user that requests it. */
    @Test
    public void testDump_onRegisteredUser_callsMethodForUser() throws Exception {
        grantDumpPermissions();

        registerUser(UserHandle.USER_SYSTEM, mUserOneService);
        BackupManagerService backupManagerService = createService();
        File testFile = createTestFile();
        FileDescriptor fileDescriptor = new FileDescriptor();
        PrintWriter printWriter = new PrintWriter(testFile);
        String[] args = {"1", "2"};

        backupManagerService.dump(fileDescriptor, printWriter, args);

        verify(mUserOneService).dump(fileDescriptor, printWriter, args);
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

        registerUser(mUserOneId, mUserOneService);
        BackupManagerService backupManagerService = createService();
        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);
        String[] args = {"users"};

        backupManagerService.dump(null, writer, args);

        writer.flush();
        assertEquals(
                String.format("%s %d\n", BackupManagerService.DUMP_RUNNING_USERS_MESSAGE,
                        mUserOneId),
                out.toString());
    }

    private void grantDumpPermissions() {
        mShadowContext.grantPermissions(DUMP);
        mShadowContext.grantPermissions(PACKAGE_USAGE_STATS);
    }

    private File createTestFile() throws IOException {
        File testFile = new File(mContext.getFilesDir(), "test");
        testFile.createNewFile();
        return testFile;
    }

    private BackupManagerService createService() {
        mShadowContext.grantPermissions(BACKUP);
        return new BackupManagerService(mContext, mTrampoline, mTrampoline.getUserServices());
    }

    private void registerUser(int userId, UserBackupManagerService userBackupManagerService) {
        mTrampoline.setBackupServiceActive(userId, true);
        mTrampoline.startServiceForUser(userId, userBackupManagerService);
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

    private ParcelFileDescriptor getFileDescriptorForAdbTest() throws Exception {
        File testFile = new File(mContext.getFilesDir(), "test");
        testFile.createNewFile();
        return ParcelFileDescriptor.open(testFile, ParcelFileDescriptor.MODE_READ_WRITE);
    }
}
