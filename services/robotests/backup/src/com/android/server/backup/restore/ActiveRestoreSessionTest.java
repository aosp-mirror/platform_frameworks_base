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
 * limitations under the License
 */

package com.android.server.backup.restore;

import static com.android.server.backup.testing.BackupManagerServiceTestUtils.createBackupWakeLock;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils.setUpBackupManagerServiceBasics;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils.startBackupThread;
import static com.android.server.backup.testing.TestUtils.assertEventLogged;
import static com.android.server.backup.testing.TestUtils.assertEventNotLogged;
import static com.android.server.backup.testing.TransportData.backupTransport;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.testng.Assert.expectThrows;

import android.app.Application;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.RestoreSet;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import com.android.server.EventLogTags;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.testing.shadows.ShadowApplicationPackageManager;
import com.android.server.testing.shadows.ShadowEventLog;
import com.android.server.testing.shadows.ShadowPerformUnifiedRestoreTask;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayDeque;

@RunWith(RobolectricTestRunner.class)
@Config(
        shadows = {
            ShadowApplicationPackageManager.class,
            ShadowBinder.class,
            ShadowEventLog.class,
            ShadowPerformUnifiedRestoreTask.class
        })
@Presubmit
public class ActiveRestoreSessionTest {
    private static final String PACKAGE_1 = "com.example.package1";
    private static final String PACKAGE_2 = "com.example.package2";
    private static final long TOKEN_1 = 1L;
    private static final long TOKEN_2 = 2L;

    @Mock private UserBackupManagerService mBackupManagerService;
    @Mock private TransportManager mTransportManager;
    @Mock private IRestoreObserver mObserver;
    @Mock private IBackupManagerMonitor mMonitor;
    private ShadowLooper mShadowBackupLooper;
    private ShadowApplication mShadowApplication;
    private UserBackupManagerService.BackupWakeLock mWakeLock;
    private TransportData mTransport;
    private RestoreSet mRestoreSet1;
    private RestoreSet mRestoreSet2;
    private ShadowPackageManager mShadowPackageManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTransport = backupTransport();

        mRestoreSet1 = new RestoreSet("name1", "device1", TOKEN_1);
        mRestoreSet2 = new RestoreSet("name2", "device2", TOKEN_2);

        Application application = RuntimeEnvironment.application;
        mShadowApplication = shadowOf(application);

        mShadowPackageManager = shadowOf(application.getPackageManager());

        HandlerThread handlerThread = startBackupThread(null);
        Looper backupLooper = handlerThread.getLooper();
        mShadowBackupLooper = shadowOf(backupLooper);

        Handler mainHandler = new Handler(Looper.getMainLooper());
        BackupAgentTimeoutParameters agentTimeoutParameters =
                new BackupAgentTimeoutParameters(mainHandler, application.getContentResolver());
        agentTimeoutParameters.start();

        // We need to mock BMS timeout parameters before initializing the BackupHandler since
        // the constructor of BackupHandler relies on it.
        when(mBackupManagerService.getAgentTimeoutParameters()).thenReturn(agentTimeoutParameters);
        BackupHandler backupHandler = new BackupHandler(mBackupManagerService, handlerThread);

        mWakeLock = createBackupWakeLock(application);

        // TODO: Migrate to use spy(createInitializedBackupManagerService())
        setUpBackupManagerServiceBasics(
                mBackupManagerService,
                application,
                mTransportManager,
                application.getPackageManager(),
                backupHandler,
                mWakeLock,
                agentTimeoutParameters);
        when(mBackupManagerService.getPendingRestores()).thenReturn(new ArrayDeque<>());
    }

    @After
    public void tearDown() throws Exception {
        ShadowApplicationPackageManager.reset();
        ShadowPerformUnifiedRestoreTask.reset();
    }

    @Test
    public void testGetAvailableRestoreSets_withoutPermission() throws Exception {
        mShadowApplication.denyPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        expectThrows(
                SecurityException.class,
                () -> restoreSession.getAvailableRestoreSets(mObserver, mMonitor));
    }

    @Test
    public void testGetAvailableRestoreSets_forNullObserver() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        expectThrows(
                RuntimeException.class,
                () -> restoreSession.getAvailableRestoreSets(null, mMonitor));
    }

    @Test
    public void testGetAvailableRestoreSets_whenTransportNotRegistered() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport.unregistered());
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        int result = restoreSession.getAvailableRestoreSets(mObserver, mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testGetAvailableRestoreSets() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpTransport(mTransport);
        when(transportMock.transport.getAvailableRestoreSets())
                .thenReturn(new RestoreSet[] {mRestoreSet1, mRestoreSet2});
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        int result = restoreSession.getAvailableRestoreSets(mObserver, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
        verify(mObserver)
                .restoreSetsAvailable(aryEq(new RestoreSet[] {mRestoreSet1, mRestoreSet2}));
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
        assertThat(mWakeLock.isHeld()).isFalse();
    }

    @Test
    public void testGetAvailableRestoreSets_forEmptyRestoreSets() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpTransport(mTransport);
        when(transportMock.transport.getAvailableRestoreSets()).thenReturn(new RestoreSet[0]);
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        int result = restoreSession.getAvailableRestoreSets(mObserver, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
        verify(mObserver).restoreSetsAvailable(aryEq(new RestoreSet[0]));
        assertEventNotLogged(EventLogTags.RESTORE_TRANSPORT_FAILURE);
    }

    @Test
    public void testGetAvailableRestoreSets_forNullRestoreSets() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpTransport(mTransport);
        when(transportMock.transport.getAvailableRestoreSets()).thenReturn(null);
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        int result = restoreSession.getAvailableRestoreSets(mObserver, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
        verify(mObserver).restoreSetsAvailable(isNull());
        assertEventLogged(EventLogTags.RESTORE_TRANSPORT_FAILURE);
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
        assertThat(mWakeLock.isHeld()).isFalse();
    }

    @Test
    public void testRestoreAll() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        doCallRealMethod().when(mBackupManagerService).setRestoreInProgress(anyBoolean());
        when(mBackupManagerService.isRestoreInProgress()).thenCallRealMethod();
        TransportMock transportMock = setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);

        int result = restoreSession.restoreAll(TOKEN_1, mObserver, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
        assertThat(mWakeLock.isHeld()).isFalse();
        assertThat(mBackupManagerService.isRestoreInProgress()).isFalse();
        // Verify it created the task properly
        ShadowPerformUnifiedRestoreTask shadowTask =
                ShadowPerformUnifiedRestoreTask.getLastCreated();
        assertThat(shadowTask.isFullSystemRestore()).isTrue();
        assertThat(shadowTask.getFilterSet()).isNull();
        assertThat(shadowTask.getPackage()).isNull();
    }

    @Test
    public void testRestoreAll_whenNoRestoreSets() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(null, mTransport);

        int result = restoreSession.restoreAll(TOKEN_1, mObserver, mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testRestoreAll_whenSinglePackageSession() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(PACKAGE_1, mTransport, mRestoreSet1);

        int result = restoreSession.restoreAll(TOKEN_1, mObserver, mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testRestoreAll_whenSessionEnded() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);
        restoreSession.endRestoreSession();
        mShadowBackupLooper.runToEndOfTasks();

        expectThrows(
                IllegalStateException.class,
                () -> restoreSession.restoreAll(TOKEN_1, mObserver, mMonitor));
    }

    @Test
    public void testRestoreAll_whenTransportNotRegistered() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport.unregistered());
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);

        int result = restoreSession.restoreAll(TOKEN_1, mObserver, mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testRestoreAll_whenRestoreInProgress_addsToPendingRestores() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        when(mBackupManagerService.isRestoreInProgress()).thenReturn(true);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);

        int result = restoreSession.restoreAll(TOKEN_1, mObserver, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
        assertThat(mBackupManagerService.getPendingRestores()).hasSize(1);
    }

    @Test
    public void testRestorePackages_for2Packages() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);

        int result = restoreSession.restorePackages(TOKEN_1, mObserver,
                new String[] {PACKAGE_1, PACKAGE_2}, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
        assertThat(mWakeLock.isHeld()).isFalse();
        assertThat(mBackupManagerService.isRestoreInProgress()).isFalse();
        ShadowPerformUnifiedRestoreTask shadowTask =
                ShadowPerformUnifiedRestoreTask.getLastCreated();
        assertThat(shadowTask.getFilterSet()).asList().containsExactly(PACKAGE_1, PACKAGE_2);
        assertThat(shadowTask.getPackage()).isNull();
    }

    @Test
    public void testRestorePackages_for2Packages_createsSystemRestoreTask() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);

        restoreSession.restorePackages(TOKEN_1, mObserver, new String[] {PACKAGE_1, PACKAGE_2},
                mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(ShadowPerformUnifiedRestoreTask.getLastCreated().isFullSystemRestore()).isTrue();
    }

    @Test
    public void testRestorePackages_for1Package() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);

        restoreSession.restorePackages(TOKEN_1, mObserver, new String[] {PACKAGE_1}, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        ShadowPerformUnifiedRestoreTask shadowTask =
                ShadowPerformUnifiedRestoreTask.getLastCreated();
        assertThat(shadowTask.getFilterSet()).asList().containsExactly(PACKAGE_1);
        assertThat(shadowTask.getPackage()).isNull();
    }

    @Test
    public void testRestorePackages_for1Package_createsNonSystemRestoreTask() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);

        restoreSession.restorePackages(TOKEN_1, mObserver, new String[] {PACKAGE_1}, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(ShadowPerformUnifiedRestoreTask.getLastCreated().isFullSystemRestore())
                .isFalse();
    }

    @Test
    public void testRestorePackages_whenNoRestoreSets() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(null, mTransport);

        int result = restoreSession.restorePackages(TOKEN_1, mObserver, new String[] {PACKAGE_1},
                mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testRestorePackages_whenSinglePackageSession() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(PACKAGE_1, mTransport, mRestoreSet1);

        int result = restoreSession.restorePackages(TOKEN_1, mObserver, new String[] {PACKAGE_2},
                mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testRestorePackages_whenSessionEnded() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport);
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);
        restoreSession.endRestoreSession();
        mShadowBackupLooper.runToEndOfTasks();

        expectThrows(
                IllegalStateException.class,
                () ->
                        restoreSession.restorePackages(TOKEN_1, mObserver, new String[] {PACKAGE_1},
                                mMonitor));
    }

    @Test
    public void testRestorePackages_whenTransportNotRegistered() throws Exception {
        mShadowApplication.grantPermissions(android.Manifest.permission.BACKUP);
        setUpTransport(mTransport.unregistered());
        IRestoreSession restoreSession =
                createActiveRestoreSessionWithRestoreSets(null, mTransport, mRestoreSet1);

        int result = restoreSession.restorePackages(TOKEN_1, mObserver, new String[] {PACKAGE_1},
                mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testRestorePackage_whenCallerIsPackage() throws Exception {
        // No need for BACKUP permission in this case
        mShadowApplication.denyPermissions(android.Manifest.permission.BACKUP);
        ShadowBinder.setCallingUid(1);
        setUpPackage(PACKAGE_1, /* uid */ 1);
        when(mBackupManagerService.getAvailableRestoreToken(PACKAGE_1)).thenReturn(TOKEN_1);
        TransportMock transportMock = setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        int result = restoreSession.restorePackage(PACKAGE_1, mObserver, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
        assertThat(mWakeLock.isHeld()).isFalse();
        assertThat(mBackupManagerService.isRestoreInProgress()).isFalse();
        ShadowPerformUnifiedRestoreTask shadowTask =
                ShadowPerformUnifiedRestoreTask.getLastCreated();
        assertThat(shadowTask.isFullSystemRestore()).isFalse();
        assertThat(shadowTask.getFilterSet()).isNull();
        assertThat(shadowTask.getPackage().packageName).isEqualTo(PACKAGE_1);
    }

    @Test
    public void testRestorePackage_whenPackageNullWhenCreated() throws Exception {
        ShadowBinder.setCallingUid(1);
        setUpPackage(PACKAGE_1, /* uid */ 1);
        when(mBackupManagerService.getAvailableRestoreToken(PACKAGE_1)).thenReturn(TOKEN_1);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(null, mTransport);

        int result = restoreSession.restorePackage(PACKAGE_1, mObserver, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
    }

    @Test
    public void testRestorePackage_whenCallerIsNotPackageAndPermissionGranted() throws Exception {
        final int pid = 1211;
        final int uid = 1;
        mShadowApplication.grantPermissions(pid, uid, android.Manifest.permission.BACKUP);
        ShadowBinder.setCallingPid(pid);
        ShadowBinder.setCallingUid(uid);
        setUpPackage(PACKAGE_1, /* uid */ 2);
        when(mBackupManagerService.getAvailableRestoreToken(PACKAGE_1)).thenReturn(TOKEN_1);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        int result = restoreSession.restorePackage(PACKAGE_1, mObserver, mMonitor);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(0);
    }

    @Test
    public void testRestorePackage_whenCallerIsNotPackageAndPermissionDenied() throws Exception {
        final int pid = 1211;
        final int uid = 1;
        mShadowApplication.denyPermissions(pid, uid, android.Manifest.permission.BACKUP);
        ShadowBinder.setCallingPid(pid);
        ShadowBinder.setCallingUid(uid);
        setUpPackage(PACKAGE_1, /* uid */ 2);
        when(mBackupManagerService.getAvailableRestoreToken(PACKAGE_1)).thenReturn(TOKEN_1);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(PACKAGE_1, mTransport);

        expectThrows(
                SecurityException.class,
                () -> restoreSession.restorePackage(PACKAGE_1, mObserver, mMonitor));
    }

    @Test
    public void testRestorePackage_whenPackageNotFound() throws Exception {
        final int pid = 1211;
        final int uid = 1;
        mShadowApplication.grantPermissions(pid, uid, android.Manifest.permission.BACKUP);
        ShadowBinder.setCallingPid(pid);
        ShadowBinder.setCallingUid(uid);
        setUpPackage(PACKAGE_1, /* uid */ 1);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(null, mTransport);

        int result = restoreSession.restorePackage(PACKAGE_2, mObserver, mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    @Test
    public void testRestorePackage_whenSessionEnded() throws Exception {
        ShadowBinder.setCallingUid(1);
        setUpPackage(PACKAGE_1, /* uid */ 1);
        setUpTransport(mTransport);
        IRestoreSession restoreSession = createActiveRestoreSession(null, mTransport);
        restoreSession.endRestoreSession();
        mShadowBackupLooper.runToEndOfTasks();

        expectThrows(
                IllegalStateException.class,
                () -> restoreSession.restorePackage(PACKAGE_1, mObserver, mMonitor));
    }

    @Test
    public void testRestorePackage_whenTransportNotRegistered() throws Exception {
        ShadowBinder.setCallingUid(1);
        setUpPackage(PACKAGE_1, /* uid */ 1);
        setUpTransport(mTransport.unregistered());
        IRestoreSession restoreSession = createActiveRestoreSession(null, mTransport);

        int result = restoreSession.restorePackage(PACKAGE_1, mObserver, mMonitor);

        assertThat(result).isEqualTo(-1);
    }

    // TODO: Create a builder for PackageInfo/ApplicationInfo and unify usage with
    //       TransportManagerTest
    private void setUpPackage(String packageName, int uid) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.uid = uid;
        mShadowPackageManager.installPackage(packageInfo);
        ShadowApplicationPackageManager.addInstalledPackage(packageName, packageInfo);
    }

    private IRestoreSession createActiveRestoreSession(
            String packageName, TransportData transport) {
        return new ActiveRestoreSession(
                mBackupManagerService, packageName, transport.transportName);
    }

    private IRestoreSession createActiveRestoreSessionWithRestoreSets(
            String packageName, TransportData transport, RestoreSet... restoreSets)
            throws RemoteException {
        ActiveRestoreSession restoreSession =
                new ActiveRestoreSession(
                        mBackupManagerService, packageName, transport.transportName);
        restoreSession.setRestoreSets(restoreSets);
        return restoreSession;
    }

    private TransportMock setUpTransport(TransportData transport) throws Exception {
        return TransportTestUtils.setUpTransport(mTransportManager, transport);
    }
}
