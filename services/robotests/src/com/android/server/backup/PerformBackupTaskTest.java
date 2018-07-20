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

package com.android.server.backup;

import static android.app.backup.BackupManager.ERROR_BACKUP_NOT_ALLOWED;
import static android.app.backup.BackupManager.ERROR_PACKAGE_NOT_FOUND;
import static android.app.backup.BackupManager.SUCCESS;
import static android.app.backup.BackupTransport.TRANSPORT_ERROR;
import static android.app.backup.ForwardingBackupAgent.forward;

import static com.android.server.backup.testing.BackupManagerServiceTestUtils.createBackupWakeLock;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils.createInitializedBackupManagerService;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils.setUpBackupManagerServiceBasics;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils.setUpBinderCallerAndApplicationAsSystem;
import static com.android.server.backup.testing.PackageData.PM_PACKAGE;
import static com.android.server.backup.testing.PackageData.fullBackupPackage;
import static com.android.server.backup.testing.PackageData.keyValuePackage;
import static com.android.server.backup.testing.TestUtils.assertEventLogged;
import static com.android.server.backup.testing.TestUtils.uncheck;
import static com.android.server.backup.testing.TransportData.backupTransport;
import static com.android.server.backup.testing.Utils.oneTimeIterable;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

import android.annotation.Nullable;
import android.app.Application;
import android.app.IBackupAgent;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupManager;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;

import com.android.internal.backup.IBackupTransport;
import com.android.server.EventLogTags;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.internal.BackupRequest;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.internal.PerformBackupTask;
import com.android.server.backup.testing.PackageData;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.testing.Utils;
import com.android.server.backup.transport.TransportClient;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderClasses;
import com.android.server.testing.SystemLoaderPackages;
import com.android.server.testing.shadows.ShadowBackupDataInput;
import com.android.server.testing.shadows.ShadowBackupDataOutput;
import com.android.server.testing.shadows.ShadowEventLog;

import com.google.common.truth.IterableSubject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowQueuedWork;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// TODO: When returning to RUNNING_QUEUE vs FINAL, RUNNING_QUEUE sets status = OK. Why? Verify?
// TODO: Verify WakeLock work source as not being app
// TODO: Check agent writes new state file => next agent reads it correctly
// TODO: Check queue of 2, transport rejecting package but other package proceeds
// TODO: Check queue in general, behavior w/ multiple packages
// TODO: Check quota is passed from transport to agent
// TODO: Verify agent with no data doesn't call transport
@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 26,
        shadows = {
            ShadowBackupDataInput.class,
            ShadowBackupDataOutput.class,
            ShadowEventLog.class,
            ShadowQueuedWork.class
        })
@SystemLoaderPackages({"com.android.server.backup", "android.app.backup"})
@SystemLoaderClasses({IBackupTransport.class, IBackupAgent.class, PackageInfo.class})
@Presubmit
public class PerformBackupTaskTest {
    private static final PackageData PACKAGE_1 = keyValuePackage(1);
    private static final PackageData PACKAGE_2 = keyValuePackage(2);

    @Mock private TransportManager mTransportManager;
    @Mock private DataChangedJournal mOldJournal;
    @Mock private IBackupObserver mObserver;
    @Mock private IBackupManagerMonitor mMonitor;
    @Mock private OnTaskFinishedListener mListener;
    private BackupManagerService mBackupManagerService;
    private TransportData mTransport;
    private ShadowLooper mShadowBackupLooper;
    private Handler mBackupHandler;
    private PowerManager.WakeLock mWakeLock;
    private ShadowPackageManager mShadowPackageManager;
    private FakeIBackupManager mBackupManager;
    private File mBaseStateDir;
    private File mDataDir;
    private Application mApplication;
    private ShadowApplication mShadowApplication;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTransport = backupTransport();

        mApplication = RuntimeEnvironment.application;
        mShadowApplication = shadowOf(mApplication);
        mContext = mApplication;

        File cacheDir = mApplication.getCacheDir();
        // Corresponds to /data/backup
        mBaseStateDir = new File(cacheDir, "base_state");
        // Corresponds to /cache/backup_stage
        mDataDir = new File(cacheDir, "data");
        // We create here simulating init.rc
        mDataDir.mkdirs();
        assertThat(mDataDir.isDirectory()).isTrue();

        PackageManager packageManager = mApplication.getPackageManager();
        mShadowPackageManager = shadowOf(packageManager);

        mWakeLock = createBackupWakeLock(mApplication);

        mBackupManager = spy(FakeIBackupManager.class);

        // Needed to be able to use a real BMS instead of a mock
        setUpBinderCallerAndApplicationAsSystem(mApplication);
        mBackupManagerService =
                spy(
                        createInitializedBackupManagerService(
                                mContext, mBaseStateDir, mDataDir, mTransportManager));
        setUpBackupManagerServiceBasics(
                mBackupManagerService,
                mApplication,
                mTransportManager,
                packageManager,
                mBackupManagerService.getBackupHandler(),
                mWakeLock,
                mBackupManagerService.getAgentTimeoutParameters());
        when(mBackupManagerService.getBaseStateDir()).thenReturn(mBaseStateDir);
        when(mBackupManagerService.getDataDir()).thenReturn(mDataDir);
        when(mBackupManagerService.getBackupManagerBinder()).thenReturn(mBackupManager);

        mBackupHandler = mBackupManagerService.getBackupHandler();
        mShadowBackupLooper = shadowOf(mBackupHandler.getLooper());
        ShadowEventLog.setUp();
    }

    @Test
    public void testRunTask_whenQueueEmpty_updatesBookkeeping() throws Exception {
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        TransportMock transportMock = setUpTransport(mTransport);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        assertThat(mBackupManagerService.getPendingInits()).isEmpty();
        assertThat(mBackupManagerService.isBackupRunning()).isFalse();
        assertThat(mBackupManagerService.getCurrentOperations().size()).isEqualTo(0);
        verify(mOldJournal).delete();
    }

    @Test
    public void testRunTask_whenQueueEmpty_releasesWakeLock() throws Exception {
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        TransportMock transportMock = setUpTransport(mTransport);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        assertThat(mWakeLock.isHeld()).isFalse();
    }

    @Test
    public void testRunTask_whenQueueEmpty_doesNotProduceData() throws Exception {
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        TransportMock transportMock = setUpTransport(mTransport);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        assertDirectory(getStateDirectory(mTransport)).isEmpty();
        assertDirectory(mDataDir.toPath()).isEmpty();
    }

    @Test
    public void testRunTask_whenQueueEmpty_doesNotCallTransport() throws Exception {
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        TransportMock transportMock = setUpTransport(mTransport);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        verify(transportMock.transport, never()).initializeDevice();
        verify(transportMock.transport, never()).performBackup(any(), any(), anyInt());
        verify(transportMock.transport, never()).finishBackup();
    }

    @Test
    public void testRunTask_whenQueueEmpty_notifiesCorrectly() throws Exception {
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        TransportMock transportMock = setUpTransport(mTransport);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver, never()).onResult(any(), anyInt());
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenQueueEmpty_doesNotChangeStateFiles() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);
        createPmStateFile();
        Files.write(getStateFile(mTransport, PACKAGE_1), "packageState".getBytes());

        runTask(task);

        assertThat(Files.readAllBytes(getStateFile(mTransport, PM_PACKAGE)))
                .isEqualTo("pmState".getBytes());
        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("packageState".getBytes());
    }

    @Test
    public void testRunTask_whenOnePackage_logEvents() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        Path backupData = createTemporaryFile();
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .then(copyBackupDataTo(backupData));
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        createPmStateFile();

        runTask(task);

        assertEventLogged(EventLogTags.BACKUP_START, mTransport.transportDirName);
        assertEventLogged(
                EventLogTags.BACKUP_PACKAGE, PACKAGE_1.packageName, Files.size(backupData));
    }

    @Test
    public void testRunTask_whenOnePackage_notifiesCorrectly() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mBackupManagerService).logBackupComplete(PACKAGE_1.packageName);
        verify(mObserver).onResult(PACKAGE_1.packageName, SUCCESS);
        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenOnePackage_releasesWakeLock() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertThat(mWakeLock.isHeld()).isFalse();
    }

    @Test
    public void testRunTask_whenOnePackage_updatesBookkeeping() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        mBackupManagerService.setCurrentToken(0L);
        when(transportMock.transport.getCurrentRestoreSet()).thenReturn(1234L);
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        // Write PM state to not reset current token
        createPmStateFile();

        runTask(task);

        assertThat(mBackupManagerService.getPendingInits()).isEmpty();
        assertThat(mBackupManagerService.isBackupRunning()).isFalse();
        assertThat(mBackupManagerService.getCurrentOperations().size()).isEqualTo(0);
        assertThat(mBackupManagerService.getCurrentToken()).isEqualTo(1234L);
        verify(mBackupManagerService).writeRestoreTokens();
        verify(mOldJournal).delete();
    }

    @Test
    public void testRunTask_whenPackageWithOldStateAndIncremental_passesOldStateToAgent()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);
        createPmStateFile();
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        assertThat(agentMock.oldState).isEqualTo("oldState".getBytes());
    }

    @Test
    public void testRunTask_whenPackageWithOldStateAndNonIncremental_passesEmptyOldStateToAgent()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        true,
                        PACKAGE_1);
        createPmStateFile();
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        assertThat(agentMock.oldState).isEqualTo(new byte[0]);
    }

    @Test
    public void testRunTask_whenNonPmPackageAndNonIncremental_doesNotBackUpPm() throws Exception {
        PackageManagerBackupAgent pmAgent = spy(createPmAgent());
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(forward(pmAgent));
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        true,
                        PACKAGE_1);

        runTask(task);

        verify(pmAgent, never()).onBackup(any(), any(), any());
    }

    @Test
    public void testRunTask_whenNonPmPackageAndPmAndNonIncremental_backsUpPm() throws Exception {
        PackageManagerBackupAgent pmAgent = spy(createPmAgent());
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(forward(pmAgent));
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        true,
                        PACKAGE_1,
                        PM_PACKAGE);

        runTask(task);

        verify(pmAgent).onBackup(any(), any(), any());
    }

    @Test
    public void testRunTask_whenNonPmPackageAndIncremental_backsUpPm() throws Exception {
        PackageManagerBackupAgent pmAgent = spy(createPmAgent());
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(forward(pmAgent));
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);

        runTask(task);

        verify(pmAgent).onBackup(any(), any(), any());
    }

    @Test
    public void testRunTask_whenOnePackageAndNoPmState_initializesTransportAndResetsState()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        // Need 2 packages to be able to verify state of package not involved in the task
        setUpAgentsWithData(PACKAGE_1, PACKAGE_2);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        deletePmStateFile();
        Files.write(getStateFile(mTransport, PACKAGE_2), "package2State".getBytes());

        runTask(task);

        verify(transportMock.transport).initializeDevice();
        verify(mBackupManagerService).resetBackupState(getStateDirectory(mTransport).toFile());
        // Verifying that it deleted all the states (can't verify package 1 because it generated a
        // new state in this task execution)
        assertThat(Files.exists(getStateFile(mTransport, PACKAGE_2))).isFalse();
        assertEventLogged(EventLogTags.BACKUP_INITIALIZE);
    }

    @Test
    public void testRunTask_whenOnePackageAndWithPmState_doesNotInitializeTransportOrResetState()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentsWithData(PACKAGE_1, PACKAGE_2);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        createPmStateFile();
        Files.write(getStateFile(mTransport, PACKAGE_2), "package2State".getBytes());

        runTask(task);

        verify(transportMock.transport, never()).initializeDevice();
        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_2)))
                .isEqualTo("package2State".getBytes());
    }

    @Test
    public void testRunTask_whenTransportReturnsErrorForInitialization() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        when(transportMock.transport.initializeDevice()).thenReturn(TRANSPORT_ERROR);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        deletePmStateFile();

        runTask(task);

        // First for initialization and second because of the transport failure
        verify(mBackupManagerService, times(2))
                .resetBackupState(getStateDirectory(mTransport).toFile());
        verify(agentMock.agent, never()).onBackup(any(), any(), any());
        verify(transportMock.transport, never()).performBackup(any(), any(), anyInt());
        assertBackupPendingFor(PACKAGE_1);
        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
    }

    @Test
    public void testRunTask_whenTransportThrowsDuringInitialization() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        when(transportMock.transport.initializeDevice()).thenThrow(RemoteException.class);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        deletePmStateFile();

        runTask(task);

        // First for initialization and second because of the transport failure
        verify(mBackupManagerService, times(2))
                .resetBackupState(getStateDirectory(mTransport).toFile());
        verify(agentMock.agent, never()).onBackup(any(), any(), any());
        verify(transportMock.transport, never()).performBackup(any(), any(), anyInt());
        assertBackupPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenPackageNotEligibleForBackup() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1.backupNotAllowed());
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        createPmStateFile();

        runTask(task);

        verify(agentMock.agent, never()).onBackup(any(), any(), any());
        verify(transportMock.transport, never())
                .performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt());
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(SUCCESS);
        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenPackageDoesFullBackup() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        PackageData packageData = fullBackupPackage(1);
        AgentMock agentMock = setUpAgentWithData(packageData);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, packageData);
        createPmStateFile();

        runTask(task);

        verify(agentMock.agent, never()).onBackup(any(), any(), any());
        verify(agentMock.agent, never()).onFullBackup(any());
        verify(mObserver).onResult(packageData.packageName, ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(SUCCESS);
        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenPackageIsStopped() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1.stopped());
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        createPmStateFile();

        runTask(task);

        verify(agentMock.agent, never()).onBackup(any(), any(), any());
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(SUCCESS);
        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenPackageUnknown() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        // Not calling setUpAgent()
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        createPmStateFile();

        runTask(task);

        verify(transportMock.transport, never())
                .performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt());
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_PACKAGE_NOT_FOUND);
        verify(mObserver).backupFinished(SUCCESS);
        assertBackupNotPendingFor(PACKAGE_1);
    }

    // TODO(brufino): Test agent invocation (try block w/ BMS.bindToAgent.. inside invokeNextAgent)

    @Test
    public void testRunTask_whenOnePackage_aboutAgentAndFiles() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    writeData(dataOutput, "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        createPmStateFile();

        runTask(task);

        verify(agentMock.agent).onBackup(any(), any(), any());
        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("newState".getBytes());
        assertThat(Files.exists(getTemporaryStateFile(mTransport, PACKAGE_1))).isFalse();
        assertThat(Files.exists(getStagingFile(PACKAGE_1))).isFalse();
    }

    // TODO: Test PM agent invocation

    @Test
    public void testRunTask_whenTransportProvidesFlags_passesThemToTheAgent() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(agentMock.agent)
                .onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_whenTransportDoesNotProvidesFlags() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(agentMock.agent).onBackup(any(), argThat(dataOutputWithTransportFlags(0)), any());
    }

    @Test
    public void testRunTask_whenTransportProvidesFlagsAndMultipleAgents_passesToAll()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        List<AgentMock> agentMocks = setUpAgents(PACKAGE_1, PACKAGE_2);
        BackupAgent agent1 = agentMocks.get(0).agent;
        BackupAgent agent2 = agentMocks.get(1).agent;
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(agent1).onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
        verify(agent2).onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_whenTransportChangeFlagsAfterTaskCreation() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);

        runTask(task);

        verify(agentMock.agent)
                .onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_callsTransportPerformBackupWithAgentData() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    writeData(dataOutput, "key1", "foo".getBytes());
                    writeData(dataOutput, "key2", "bar".getBytes());
                });
        Path backupDataPath = createTemporaryFile();
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .then(copyBackupDataTo(backupDataPath));
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(transportBinder).performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt());

        // Now verify data sent
        FileInputStream inputStream = new FileInputStream(backupDataPath.toFile());
        BackupDataInput backupData = new BackupDataInput(inputStream.getFD());

        // "key1" => "foo"
        assertThat(backupData.readNextHeader()).isTrue();
        assertThat(backupData.getKey()).isEqualTo("key1");
        int size1 = backupData.getDataSize();
        byte[] data1 = new byte[size1];
        backupData.readEntityData(data1, 0, size1);
        assertThat(data1).isEqualTo("foo".getBytes());

        // "key2" => "bar"
        assertThat(backupData.readNextHeader()).isTrue();
        assertThat(backupData.getKey()).isEqualTo("key2");
        int size2 = backupData.getDataSize();
        byte[] data2 = new byte[size2];
        backupData.readEntityData(data2, 0, size2);
        assertThat(data2).isEqualTo("bar".getBytes());

        // No more
        assertThat(backupData.readNextHeader()).isFalse();
        inputStream.close();
    }

    @Test
    public void testRunTask_whenPerformBackupSucceeds_callsTransportFinishBackup()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);

        runTask(task);

        // First for PM, then for the package
        verify(transportBinder, times(2)).finishBackup();
    }

    @Test
    public void testRunTask_whenProhibitedKey_failsAgent() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "foo".getBytes());
                });
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).onResult(PACKAGE_1.packageName, BackupManager.ERROR_AGENT_FAILURE);
        verify(agentMock.agentBinder).fail(any());
        verify(mObserver).backupFinished(SUCCESS);
        assertEventLogged(EventLogTags.BACKUP_AGENT_FAILURE, PACKAGE_1.packageName, "bad key");
    }

    @Test
    public void testRunTask_whenFirstAgentKeyProhibitedButLastPermitted() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        List<AgentMock> agentMocks = setUpAgents(PACKAGE_1, PACKAGE_2);
        AgentMock agentMock1 = agentMocks.get(0);
        AgentMock agentMock2 = agentMocks.get(1);
        agentOnBackupDo(
                agentMock1,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "foo".getBytes());
                });
        agentOnBackupDo(
                agentMock2,
                (oldState, dataOutput, newState) -> {
                    writeData(dataOutput, "key", "bar".getBytes());
                });
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).onResult(PACKAGE_1.packageName, BackupManager.ERROR_AGENT_FAILURE);
        verify(agentMock1.agentBinder).fail(any());
        verify(mObserver).onResult(PACKAGE_2.packageName, SUCCESS);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportUnavailable() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport.unavailable());
        setUpAgentWithData(PACKAGE_1);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    @Test
    public void testRunTask_whenTransportRejectsPackage() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver)
                .onResult(PACKAGE_1.packageName, BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).backupFinished(SUCCESS);
        assertEventLogged(
                EventLogTags.BACKUP_AGENT_FAILURE, PACKAGE_1.packageName, "Transport rejected");
    }

    @Test
    public void testRunTask_whenTransportRejectsFirstPackageButLastSucceeds() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        setUpAgentsWithData(PACKAGE_1, PACKAGE_2);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_2)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(mObserver)
                .onResult(PACKAGE_1.packageName, BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).onResult(PACKAGE_2.packageName, SUCCESS);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportRejectsLastPackageButFirstSucceeds() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        setUpAgentsWithData(PACKAGE_1, PACKAGE_2);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        when(transportBinder.performBackup(argThat(packageInfo(PACKAGE_2)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1.packageName, SUCCESS);
        verify(mObserver)
                .onResult(PACKAGE_2.packageName, BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportReturnsQuotaExceeded() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_QUOTA_EXCEEDED);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver)
                .onResult(PACKAGE_1.packageName, BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED);
        verify(mObserver).backupFinished(SUCCESS);
        verify(agentMock.agent).onQuotaExceeded(anyLong(), anyLong());
        assertEventLogged(EventLogTags.BACKUP_QUOTA_EXCEEDED, PACKAGE_1.packageName);
    }

    @Test
    public void testRunTask_whenNonIncrementalAndTransportRequestsNonIncremental()
            throws Exception {
        // It's going to be non-incremental because we haven't created any previous state
        TransportMock transportMock = setUpTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        true,
                        PACKAGE_1);

        runTask(task);

        // Error because it was non-incremental already, so transport can't request it
        verify(mObserver).onResult(PACKAGE_1.packageName, BackupManager.ERROR_TRANSPORT_ABORTED);
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    @Test
    public void testRunTask_whenIncrementalAndTransportRequestsNonIncremental() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        IBackupTransport transport = transportMock.transport;
        when(transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)),
                        any(),
                        intThat(flags -> (flags & BackupTransport.FLAG_INCREMENTAL) != 0)))
                .thenReturn(BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED);
        when(transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)),
                        any(),
                        intThat(flags -> (flags & BackupTransport.FLAG_NON_INCREMENTAL) != 0)))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);
        createPmStateFile();
        // Write state to be incremental
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        verify(agentMock.agent, times(2)).onBackup(any(), any(), any());
        verify(mObserver).onResult(PACKAGE_1.packageName, SUCCESS);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenIncrementalAndTransportUnavailableDuringPmBackup()
            throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        IBackupTransport transportBinder = transportMock.transport;
        setUpAgent(PACKAGE_1);
        Exception exception = new DeadObjectException();
        when(transportBinder.getBackupQuota(PM_PACKAGE.packageName, false)).thenThrow(exception);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
        assertEventLogged(
                EventLogTags.BACKUP_AGENT_FAILURE, PM_PACKAGE.packageName, exception.toString());
    }

    @Test
    public void testRunTask_whenIncrementalAndPmAgentFails() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        RuntimeException exception = new RuntimeException();
        PackageManagerBackupAgent pmAgent = createThrowingPmAgent(exception);
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(pmAgent);
        PerformBackupTask task =
                createPerformBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(eq(BackupManager.ERROR_TRANSPORT_ABORTED));
        assertEventLogged(
                EventLogTags.BACKUP_AGENT_FAILURE, PM_PACKAGE.packageName, exception.toString());
    }

    private void runTask(PerformBackupTask task) {
        Message message = mBackupHandler.obtainMessage(BackupHandler.MSG_BACKUP_RESTORE_STEP, task);
        mBackupHandler.sendMessage(message);
        while (mShadowBackupLooper.getScheduler().areAnyRunnable()) {
            mShadowBackupLooper.runToEndOfTasks();
        }
        assertTaskPostConditions();
    }

    private TransportMock setUpTransport(TransportData transport) throws Exception {
        TransportMock transportMock =
                TransportTestUtils.setUpTransport(mTransportManager, transport);
        Files.createDirectories(getStateDirectory(transport));
        return transportMock;
    }

    private Path getStateDirectory(TransportData transport) {
        return mBaseStateDir.toPath().resolve(transport.transportDirName);
    }

    private Path getStateFile(TransportData transport, PackageData packageData) {
        return getStateDirectory(transport).resolve(packageData.packageName);
    }

    private Path getTemporaryStateFile(TransportData transport, PackageData packageData) {
        return getStateDirectory(transport)
                .resolve(packageData.packageName + PerformBackupTask.NEW_STATE_FILE_SUFFIX);
    }

    private Path getStagingDirectory() {
        return mDataDir.toPath();
    }

    private Path getStagingFile(PackageData packageData) {
        return getStagingDirectory()
                .resolve(packageData.packageName + PerformBackupTask.STAGING_FILE_SUFFIX);
    }

    private List<AgentMock> setUpAgents(PackageData... packageNames) {
        return Stream.of(packageNames).map(this::setUpAgent).collect(toList());
    }

    private AgentMock setUpAgent(PackageData packageData) {
        try {
            mShadowPackageManager.setApplicationEnabledSetting(
                    packageData.packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
            PackageInfo packageInfo = getPackageInfo(packageData);
            mShadowPackageManager.addPackage(packageInfo);
            mShadowApplication.sendBroadcast(getPackageAddedIntent(packageData));
            // Run the backup looper because on the receiver we post MSG_SCHEDULE_BACKUP_PACKAGE
            mShadowBackupLooper.runToEndOfTasks();
            BackupAgent backupAgent = spy(BackupAgent.class);
            IBackupAgent backupAgentBinder =
                    spy(IBackupAgent.Stub.asInterface(backupAgent.onBind()));
            // Don't crash our only process (in production code this would crash the app, not us)
            doNothing().when(backupAgentBinder).fail(any());
            doReturn(backupAgentBinder)
                    .when(mBackupManagerService)
                    .bindToAgentSynchronous(eq(packageInfo.applicationInfo), anyInt());
            return new AgentMock(backupAgentBinder, backupAgent);
        } catch (RemoteException e) {
            // Never happens, compiler happy
            throw new AssertionError(e);
        }
    }

    private PackageInfo getPackageInfo(PackageData packageData) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageData.packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.uid = packageData.uid;
        packageInfo.applicationInfo.flags = packageData.flags();
        packageInfo.applicationInfo.backupAgentName = packageData.agentName;
        packageInfo.applicationInfo.packageName = packageData.packageName;
        return packageInfo;
    }

    private Intent getPackageAddedIntent(PackageData packageData) {
        Intent intent =
                new Intent(
                        Intent.ACTION_PACKAGE_ADDED,
                        Uri.parse("package:" + packageData.packageName));
        intent.putExtra(Intent.EXTRA_UID, packageData.uid);
        intent.putExtra(Intent.EXTRA_REPLACING, false);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 0);
        return intent;
    }

    private List<AgentMock> setUpAgentsWithData(PackageData... packages) {
        return Stream.of(packages).map(this::setUpAgentWithData).collect(toList());
    }

    private AgentMock setUpAgentWithData(PackageData packageData) {
        AgentMock agentMock = setUpAgent(packageData);
        String packageName = packageData.packageName;
        uncheck(
                () ->
                        agentOnBackupDo(
                                agentMock,
                                (oldState, dataOutput, newState) -> {
                                    writeData(dataOutput, "key", ("data" + packageName).getBytes());
                                    writeState(newState, ("state" + packageName).getBytes());
                                }));
        return agentMock;
    }

    private PerformBackupTask createPerformBackupTask(
            TransportClient transportClient, String transportDirName, PackageData... packages) {
        return createPerformBackupTask(transportClient, transportDirName, false, packages);
    }

    private PerformBackupTask createPerformBackupTask(
            TransportClient transportClient,
            String transportDirName,
            boolean nonIncremental,
            PackageData... packages) {
        ArrayList<BackupRequest> backupRequests =
                Stream.of(packages)
                        .map(packageData -> packageData.packageName)
                        .map(BackupRequest::new)
                        .collect(toCollection(ArrayList::new));
        mBackupManagerService.getPendingBackups().clear();
        // mOldJournal is a mock, but it would be the value returned by BMS.getJournal() now
        mBackupManagerService.setJournal(null);
        mWakeLock.acquire();
        PerformBackupTask task =
                new PerformBackupTask(
                        mBackupManagerService,
                        transportClient,
                        transportDirName,
                        backupRequests,
                        mOldJournal,
                        mObserver,
                        mMonitor,
                        mListener,
                        emptyList(),
                        /* userInitiated */ false,
                        nonIncremental);
        mBackupManager.setUp(mBackupHandler, task);
        return task;
    }

    private PackageManagerBackupAgent createPmAgent() {
        PackageManagerBackupAgent pmAgent =
                new PackageManagerBackupAgent(mApplication.getPackageManager());
        pmAgent.attach(mApplication);
        pmAgent.onCreate();
        return pmAgent;
    }

    /**
     * Returns an implementation of PackageManagerBackupAgent that throws RuntimeException in {@link
     * BackupAgent#onBackup(ParcelFileDescriptor, BackupDataOutput, ParcelFileDescriptor)}
     */
    private PackageManagerBackupAgent createThrowingPmAgent(RuntimeException exception) {
        PackageManagerBackupAgent pmAgent =
                new ThrowingPackageManagerBackupAgent(mApplication.getPackageManager(), exception);
        pmAgent.attach(mApplication);
        pmAgent.onCreate();
        return pmAgent;
    }

    /** Matches {@link PackageInfo} whose package name is {@code packageData.packageName}. */
    private static ArgumentMatcher<PackageInfo> packageInfo(PackageData packageData) {
        // We have to test for packageInfo nulity because of Mockito's own stubbing with argThat().
        // E.g. if you do:
        //
        //   1. when(object.method(argThat(str -> str.equals("foo")))).thenReturn(0)
        //   2. when(object.method(argThat(str -> str.equals("bar")))).thenReturn(2)
        //
        // The second line will throw NPE because it will call lambda 1 with null, since argThat()
        // returns null. So we guard against that by checking for null.
        return packageInfo ->
                packageInfo != null && packageData.packageName.equals(packageInfo.packageName);
    }

    private static ArgumentMatcher<BackupDataOutput> dataOutputWithTransportFlags(int flags) {
        return dataOutput -> dataOutput.getTransportFlags() == flags;
    }

    private static void writeData(BackupDataOutput dataOutput, String key, byte[] data)
            throws IOException {
        dataOutput.writeEntityHeader(key, data.length);
        dataOutput.writeEntityData(data, data.length);
    }

    private static void writeState(ParcelFileDescriptor newState, byte[] state) throws IOException {
        OutputStream outputStream = new FileOutputStream(newState.getFileDescriptor());
        outputStream.write(state);
        outputStream.flush();
    }

    /**
     * This is to prevent the following:
     *
     * <ul>
     *   <li>The transport being initialized with {@link IBackupTransport#initializeDevice()}
     *   <li>{@link BackupManagerService#resetBackupState(File)} being called, which will:
     *       <ul>
     *         <li>Call {@link ProcessedPackagesJournal#reset()}
     *         <li>Reset current token to 0
     *         <li>Delete state files
     *         <li>Mark data changed for every key-value participant
     *       </ul>
     * </ul>
     */
    private void createPmStateFile() throws IOException {
        Files.write(getStateFile(mTransport, PM_PACKAGE), "pmState".getBytes());
    }

    /**
     * Forces transport initialization and call to {@link
     * BackupManagerService#resetBackupState(File)}
     */
    private void deletePmStateFile() throws IOException {
        Files.deleteIfExists(getStateFile(mTransport, PM_PACKAGE));
    }

    /**
     * Implements {@code function} for {@link BackupAgent#onBackup(ParcelFileDescriptor,
     * BackupDataOutput, ParcelFileDescriptor)} of {@code agentMock} and populates {@link
     * AgentMock#oldState}.
     */
    private static void agentOnBackupDo(AgentMock agentMock, BackupAgentOnBackup function)
            throws Exception {
        doAnswer(
                        (BackupAgentOnBackup)
                                (oldState, dataOutput, newState) -> {
                                    ByteArrayOutputStream outputStream =
                                            new ByteArrayOutputStream();
                                    Utils.transferStreamedData(
                                            new FileInputStream(oldState.getFileDescriptor()),
                                            outputStream);
                                    agentMock.oldState = outputStream.toByteArray();
                                    function.onBackup(oldState, dataOutput, newState);
                                })
                .when(agentMock.agent)
                .onBackup(any(), any(), any());
    }

    /**
     * Returns an {@link Answer} that can be used for mocking {@link
     * IBackupTransport#performBackup(PackageInfo, ParcelFileDescriptor, int)} that copies the
     * backup data received to {@code backupDataPath}.
     */
    private static Answer<Integer> copyBackupDataTo(Path backupDataPath) {
        return invocation -> {
            ParcelFileDescriptor backupDataParcelFd = invocation.getArgument(1);
            FileDescriptor backupDataFd = backupDataParcelFd.getFileDescriptor();
            Files.copy(new FileInputStream(backupDataFd), backupDataPath, REPLACE_EXISTING);
            backupDataParcelFd.close();
            return BackupTransport.TRANSPORT_OK;
        };
    }

    private Path createTemporaryFile() throws IOException {
        return Files.createTempFile(mContext.getCacheDir().toPath(), "backup", ".tmp");
    }

    private static IterableSubject<
                    ? extends IterableSubject<?, Path, Iterable<Path>>, Path, Iterable<Path>>
            assertDirectory(Path directory) throws IOException {
        return assertThat(oneTimeIterable(Files.newDirectoryStream(directory).iterator()))
                .named("directory " + directory);
    }

    private static void assertJournalDoesNotContain(
            @Nullable DataChangedJournal journal, String packageName) throws IOException {
        List<String> packages = (journal == null) ? emptyList() : journal.getPackages();
        assertThat(packages).doesNotContain(packageName);
    }

    private void assertBackupPendingFor(PackageData packageData) throws IOException {
        assertThat(mBackupManagerService.getJournal().getPackages())
                .contains(packageData.packageName);
        assertThat(mBackupManagerService.getPendingBackups()).containsKey(packageData.packageName);
    }

    private void assertBackupNotPendingFor(PackageData packageData) throws IOException {
        assertJournalDoesNotContain(mBackupManagerService.getJournal(), packageData.packageName);
        assertThat(mBackupManagerService.getPendingBackups())
                .doesNotContainKey(packageData.packageName);
    }

    /**
     * Put conditions that should *always* be true after task execution.
     *
     * <p>Note: We should generally NOT do this. For every different set of pre-conditions that
     * result in different code-paths being executed there should be one test method verifying these
     * post-conditions. Since there were a couple of methods here already and these post-conditions
     * are pretty serious to be neglected it was decided to over-verify in this case.
     */
    private void assertTaskPostConditions() {
        assertThat(mWakeLock.isHeld()).isFalse();
    }

    @FunctionalInterface
    private interface BackupAgentOnBackup extends Answer<Void> {
        void onBackup(
                ParcelFileDescriptor oldState,
                BackupDataOutput dataOutput,
                ParcelFileDescriptor newState)
                throws IOException;

        @Override
        default Void answer(InvocationOnMock invocation) throws Throwable {
            onBackup(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2));
            return null;
        }
    }

    private static class AgentMock {
        private final IBackupAgent agentBinder;
        private final BackupAgent agent;
        private byte[] oldState;

        private AgentMock(IBackupAgent agentBinder, BackupAgent agent) {
            this.agentBinder = agentBinder;
            this.agent = agent;
        }
    }

    private abstract static class FakeIBackupManager extends IBackupManager.Stub {
        private Handler mBackupHandler;
        private BackupRestoreTask mTask;

        public FakeIBackupManager() {}

        private void setUp(Handler backupHandler, BackupRestoreTask task) {
            mBackupHandler = backupHandler;
            mTask = task;
        }

        @Override
        public void opComplete(int token, long result) throws RemoteException {
            assertThat(mTask).isNotNull();
            Message message =
                    mBackupHandler.obtainMessage(
                            BackupHandler.MSG_OP_COMPLETE, Pair.create(mTask, result));
            mBackupHandler.sendMessage(message);
        }
    }

    private static class ThrowingPackageManagerBackupAgent extends PackageManagerBackupAgent {
        private final RuntimeException mException;

        ThrowingPackageManagerBackupAgent(
                PackageManager packageManager, RuntimeException exception) {
            super(packageManager);
            mException = exception;
        }

        @Override
        public void onBackup(
                ParcelFileDescriptor oldState,
                BackupDataOutput data,
                ParcelFileDescriptor newState) {
            throw mException;
        }
    }
}
