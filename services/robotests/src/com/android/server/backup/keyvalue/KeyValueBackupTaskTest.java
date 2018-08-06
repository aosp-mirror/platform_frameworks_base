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

package com.android.server.backup.keyvalue;

import static android.app.backup.BackupManager.ERROR_AGENT_FAILURE;
import static android.app.backup.BackupManager.ERROR_BACKUP_NOT_ALLOWED;
import static android.app.backup.BackupManager.ERROR_PACKAGE_NOT_FOUND;
import static android.app.backup.BackupManager.ERROR_TRANSPORT_ABORTED;
import static android.app.backup.BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED;
import static android.app.backup.BackupManager.SUCCESS;
import static android.app.backup.ForwardingBackupAgent.forward;

import static com.android.server.backup.testing.BackupManagerServiceTestUtils.createBackupWakeLock;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils
        .createInitializedBackupManagerService;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils
        .setUpBackupManagerServiceBasics;
import static com.android.server.backup.testing.BackupManagerServiceTestUtils
        .setUpBinderCallerAndApplicationAsSystem;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.shadow.api.Shadow.extract;

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
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;

import com.android.internal.backup.IBackupTransport;
import com.android.server.EventLogTags;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.KeyValueBackupJob;
import com.android.server.backup.PackageManagerBackupAgent;
import com.android.server.backup.TransportManager;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.testing.PackageData;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.testing.Utils;
import com.android.server.backup.transport.TransportClient;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderClasses;
import com.android.server.testing.SystemLoaderPackages;
import com.android.server.testing.shadows.FrameworkShadowLooper;
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
// TODO: Check queue in general, behavior w/ multiple packages
// TODO: Test PM invocation
@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 26,
        shadows = {
            FrameworkShadowLooper.class,
            ShadowBackupDataInput.class,
            ShadowBackupDataOutput.class,
            ShadowEventLog.class,
            ShadowQueuedWork.class,
        })
@SystemLoaderPackages({"com.android.server.backup", "android.app.backup"})
@SystemLoaderClasses({IBackupTransport.class, IBackupAgent.class, PackageInfo.class})
@Presubmit
public class KeyValueBackupTaskTest {
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
    private FrameworkShadowLooper mShadowMainLooper;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTransport = backupTransport();

        mApplication = RuntimeEnvironment.application;
        mShadowApplication = shadowOf(mApplication);
        mContext = mApplication;

        mShadowMainLooper = extract(Looper.getMainLooper());

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
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        assertThat(mBackupManagerService.getPendingInits()).isEmpty();
        assertThat(mBackupManagerService.isBackupRunning()).isFalse();
        assertThat(mBackupManagerService.getCurrentOperations().size()).isEqualTo(0);
        verify(mOldJournal).delete();
    }

    @Test
    public void testRunTask_whenQueueEmpty_releasesWakeLock() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        assertThat(mWakeLock.isHeld()).isFalse();
    }

    @Test
    public void testRunTask_whenQueueEmpty_doesNotProduceData() throws Exception {
        TransportMock transportMock = setUpTransport(mTransport);
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        assertDirectory(getStateDirectory(mTransport)).isEmpty();
        assertDirectory(mDataDir.toPath()).isEmpty();
    }

    @Test
    public void testRunTask_whenQueueEmpty_doesNotCallTransport() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        verify(transportMock.transport, never()).initializeDevice();
        verify(transportMock.transport, never()).performBackup(any(), any(), anyInt());
        verify(transportMock.transport, never()).finishBackup();
    }

    @Test
    public void testRunTask_whenQueueEmpty_notifiesCorrectly() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(mBackupManagerService.getCurrentToken()).thenReturn(0L);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver, never()).onResult(any(), anyInt());
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenQueueEmpty_doesNotChangeStateFiles() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, true);
        Files.write(getStateFile(mTransport, PM_PACKAGE), "pmState".getBytes());
        Files.write(getStateFile(mTransport, PACKAGE_1), "packageState".getBytes());

        runTask(task);

        assertThat(Files.readAllBytes(getStateFile(mTransport, PM_PACKAGE)))
                .isEqualTo("pmState".getBytes());
        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("packageState".getBytes());
    }

    @Test
    public void testRunTask_whenOnePackageAndTransportUnavailable() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport.unavailable());
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(ERROR_TRANSPORT_ABORTED);
        assertBackupPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenOnePackage_logsBackupStartEvent() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertEventLogged(EventLogTags.BACKUP_START, mTransport.transportName);
    }

    @Test
    public void testRunTask_whenOnePackage_releasesWakeLock() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertThat(mWakeLock.isHeld()).isFalse();
    }

    @Test
    public void testRunTask_whenOnePackage_updatesBookkeeping() throws Exception {
        // Transport has to be initialized to not reset current token
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        mBackupManagerService.setCurrentToken(0L);
        when(transportMock.transport.getCurrentRestoreSet()).thenReturn(1234L);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

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
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        assertThat(agentMock.oldState).isEqualTo("oldState".getBytes());
    }

    @Test
    public void testRunTask_whenPackageWithOldStateAndNonIncremental_passesEmptyOldStateToAgent()
            throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        true,
                        PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        assertThat(agentMock.oldState).isEqualTo(new byte[0]);
    }

    @Test
    public void testRunTask_whenNonPmPackageAndNonIncremental_doesNotBackUpPm() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        PackageManagerBackupAgent pmAgent = spy(createPmAgent());
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(forward(pmAgent));
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        true,
                        PACKAGE_1);

        runTask(task);

        verify(pmAgent, never()).onBackup(any(), any(), any());
    }

    @Test
    public void testRunTask_whenNonPmPackageAndPmAndNonIncremental_backsUpPm() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        PackageManagerBackupAgent pmAgent = spy(createPmAgent());
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(forward(pmAgent));
        KeyValueBackupTask task =
                createKeyValueBackupTask(
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
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        PackageManagerBackupAgent pmAgent = spy(createPmAgent());
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(forward(pmAgent));
        KeyValueBackupTask task =
                createKeyValueBackupTask(
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
        KeyValueBackupTask task =
                createKeyValueBackupTask(
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
        KeyValueBackupTask task =
                createKeyValueBackupTask(
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
        when(transportMock.transport.initializeDevice())
                .thenReturn(BackupTransport.TRANSPORT_ERROR);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
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
        KeyValueBackupTask task =
                createKeyValueBackupTask(
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
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1.backupNotAllowed());
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

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
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        PackageData packageData = fullBackupPackage(1);
        AgentMock agentMock = setUpAgentWithData(packageData);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, packageData);

        runTask(task);

        verify(agentMock.agent, never()).onBackup(any(), any(), any());
        verify(agentMock.agent, never()).onFullBackup(any());
        verify(mObserver).onResult(packageData.packageName, ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(SUCCESS);
        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenPackageIsStopped() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1.stopped());
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(agentMock.agent, never()).onBackup(any(), any(), any());
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(SUCCESS);
        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenPackageUnknown() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        // Not calling setUpAgent()
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(transportMock.transport, never())
                .performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt());
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_PACKAGE_NOT_FOUND);
        verify(mObserver).backupFinished(SUCCESS);
        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenCallingAgent_setsWakeLockWorkSource() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    // In production (for non-system agents) the call is asynchronous, but here is
                    // synchronous, so it's fine to verify here.
                    // Verify has set work source and hasn't unset yet.
                    verify(mBackupManagerService)
                            .setWorkSource(
                                    argThat(workSource -> workSource.get(0) == PACKAGE_1.uid));
                    verify(mBackupManagerService, never()).setWorkSource(null);
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        // More verifications inside agent call above
        verify(mBackupManagerService).setWorkSource(null);
    }

    /**
     * Agent unavailable means {@link BackupManagerService#bindToAgentSynchronous(ApplicationInfo,
     * int)} returns {@code null}.
     *
     * @see #setUpAgent(PackageData)
     */
    @Test
    public void testRunTask_whenAgentUnavailable() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgent(PACKAGE_1.unavailable());
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mBackupManagerService).setWorkSource(null);
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_AGENT_FAILURE);
        verify(mObserver).backupFinished(BackupManager.SUCCESS);
    }

    @Test
    public void testRunTask_whenBindToAgentThrowsSecurityException() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgent(PACKAGE_1);
        doThrow(SecurityException.class)
                .when(mBackupManagerService)
                .bindToAgentSynchronous(argThat(applicationInfo(PACKAGE_1)), anyInt());
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mBackupManagerService).setWorkSource(null);
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_AGENT_FAILURE);
        verify(mObserver).backupFinished(BackupManager.SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportGetBackupQuotaThrows_notifiesCorrectly() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.getBackupQuota(PACKAGE_1.packageName, false))
                .thenThrow(DeadObjectException.class);
        setUpAgent(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver, never()).onResult(eq(PACKAGE_1.packageName), anyInt());
        verify(mObserver).backupFinished(ERROR_TRANSPORT_ABORTED);
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRunTask_whenTransportGetBackupQuotaThrows_cleansUp() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.getBackupQuota(PACKAGE_1.packageName, false))
                .thenThrow(DeadObjectException.class);
        setUpAgent(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mBackupManagerService).setWorkSource(null);
        verify(mBackupManagerService).unbindAgent(argThat(applicationInfo(PACKAGE_1)));
    }

    @Test
    public void testRunTask_whenTransportGetBackupQuotaThrows_doesNotTouchFiles() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.getBackupQuota(PACKAGE_1.packageName, false))
                .thenThrow(DeadObjectException.class);
        setUpAgent(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "packageState".getBytes());

        runTask(task);

        assertThat(Files.exists(getTemporaryStateFile(mTransport, PACKAGE_1))).isFalse();
        assertThat(Files.exists(getStagingFile(PACKAGE_1))).isFalse();
        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("packageState".getBytes());
    }

    @Test
    public void testRunTask_whenTransportGetBackupQuotaThrows_revertsOperation() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.getBackupQuota(PACKAGE_1.packageName, false))
                .thenThrow(DeadObjectException.class);
        setUpAgent(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(transportMock.transport).requestBackupTime();
        assertBackupPendingFor(PACKAGE_1);
        assertThat(KeyValueBackupJob.isScheduled()).isTrue();
    }

    /**
     * For local agents the exception is thrown in our stack, so it hits the catch clause around
     * invocation earlier than the {@link KeyValueBackupTask#operationComplete(long)} code-path,
     * invalidating the latter. Note that this happens because {@link
     * BackupManagerService#opComplete(int, long)} schedules the actual execution to the backup
     * handler.
     */
    @Test
    public void testRunTask_whenLocalAgentOnBackupThrows() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    throw new RuntimeException();
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mBackupManagerService).setWorkSource(null);
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_AGENT_FAILURE);
        verify(mObserver).backupFinished(SUCCESS);
        assertEventLogged(
                EventLogTags.BACKUP_AGENT_FAILURE,
                PACKAGE_1.packageName,
                new RuntimeException().toString());
        assertBackupPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenTransportProvidesFlags_passesThemToTheAgent() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(agentMock.agent)
                .onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_whenTransportDoesNotProvidesFlags() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(agentMock.agent).onBackup(any(), argThat(dataOutputWithTransportFlags(0)), any());
    }

    @Test
    public void testRunTask_whenTransportProvidesFlagsAndMultipleAgents_passesToAll()
            throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);
        List<AgentMock> agentMocks = setUpAgents(PACKAGE_1, PACKAGE_2);
        BackupAgent agent1 = agentMocks.get(0).agent;
        BackupAgent agent2 = agentMocks.get(1).agent;
        KeyValueBackupTask task =
                createKeyValueBackupTask(
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
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        int flags = BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
        when(transportMock.transport.getTransportFlags()).thenReturn(flags);

        runTask(task);

        verify(agentMock.agent)
                .onBackup(any(), argThat(dataOutputWithTransportFlags(flags)), any());
    }

    @Test
    public void testRunTask_whenAgentUsesProhibitedKey_failsAgent() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(agentMock.agentBinder).fail(any());
        verify(mBackupManagerService).unbindAgent(argThat(applicationInfo(PACKAGE_1)));
    }

    @Test
    public void testRunTask_whenAgentUsesProhibitedKey_updatesAndCleansUpFiles() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("oldState".getBytes());
        assertThat(Files.exists(getTemporaryStateFile(mTransport, PACKAGE_1))).isFalse();
        assertThat(Files.exists(getStagingFile(PACKAGE_1))).isFalse();
    }

    @Test
    public void testRunTask_whenAgentUsesProhibitedKey_doesNotCallTransport() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        verify(transportMock.transport, never())
                .performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt());
    }

    @Test
    public void testRunTask_whenAgentUsesProhibitedKey_notifiesCorrectly() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_AGENT_FAILURE);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenAgentUsesProhibitedKey_logsAgentFailureEvent() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        assertEventLogged(EventLogTags.BACKUP_AGENT_FAILURE, PACKAGE_1.packageName, "bad key");
    }

    @Test
    public void testRunTask_whenFirstAgentUsesProhibitedKeyButLastAgentUsesPermittedKey()
            throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        List<AgentMock> agentMocks = setUpAgents(PACKAGE_1, PACKAGE_2);
        AgentMock agentMock1 = agentMocks.get(0);
        AgentMock agentMock2 = agentMocks.get(1);
        agentOnBackupDo(
                agentMock1,
                (oldState, dataOutput, newState) -> {
                    char prohibitedChar = 0xff00;
                    writeData(dataOutput, prohibitedChar + "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        agentOnBackupDo(
                agentMock2,
                (oldState, dataOutput, newState) -> {
                    writeData(dataOutput, "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_AGENT_FAILURE);
        verify(agentMock1.agentBinder).fail(any());
        verify(mObserver).onResult(PACKAGE_2.packageName, SUCCESS);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenAgentDoesNotWriteData_doesNotCallTransport() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    // No-op
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(transportMock.transport, never())
                .performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt());
    }

    @Test
    public void testRunTask_whenAgentDoesNotWriteData_logsEvents() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    // No-op
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertEventLogged(EventLogTags.BACKUP_PACKAGE, PACKAGE_1.packageName, 0L);
        verify(mBackupManagerService).logBackupComplete(PACKAGE_1.packageName);
    }

    @Test
    public void testRunTask_whenAgentDoesNotWriteData_notifiesCorrectly() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    // No-op
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1.packageName, SUCCESS);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenAgentDoesNotWriteData_updatesBookkeeping() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    // No-op
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenAgentDoesNotWriteData_updatesAndCleansUpFiles() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    // No-op
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1))).isEqualTo(new byte[0]);
        assertThat(Files.exists(getTemporaryStateFile(mTransport, PACKAGE_1))).isFalse();
        assertThat(Files.exists(getStagingFile(PACKAGE_1))).isFalse();
    }

    @Test
    public void testRunTask_whenAgentWritesData_callsTransportPerformBackupWithAgentData()
            throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        Path backupDataPath = createTemporaryFile();
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .then(copyBackupDataTo(backupDataPath));
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    writeData(dataOutput, "key1", "data1".getBytes());
                    writeData(dataOutput, "key2", "data2".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(transportMock.transport)
                .performBackup(argThat(packageInfo(PACKAGE_1)), any(), anyInt());
        // Now verify data sent
        try (FileInputStream inputStream = new FileInputStream(backupDataPath.toFile())) {
            BackupDataInput backupData = new BackupDataInput(inputStream.getFD());
            assertDataHasKeyValue(backupData, "key1", "data1".getBytes());
            assertDataHasKeyValue(backupData, "key2", "data2".getBytes());
            assertThat(backupData.readNextHeader()).isFalse();
        }
    }

    @Test
    public void testRunTask_whenPerformBackupSucceeds_callsTransportFinishBackup()
            throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        // First for PM, then for the package
        verify(transportMock.transport, times(2)).finishBackup();
    }

    @Test
    public void testRunTask_whenFinishBackupSucceeds_updatesAndCleansUpFiles() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.finishBackup()).thenReturn(BackupTransport.TRANSPORT_OK);
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    writeData(dataOutput, "key", "data".getBytes());
                    writeState(newState, "newState".getBytes());
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("newState".getBytes());
        assertThat(Files.exists(getTemporaryStateFile(mTransport, PACKAGE_1))).isFalse();
        assertThat(Files.exists(getStagingFile(PACKAGE_1))).isFalse();
    }

    @Test
    public void testRunTask_whenFinishBackupSucceeds_logsBackupPackageEvent() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        Path backupData = createTemporaryFile();
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .then(copyBackupDataTo(backupData));
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertEventLogged(
                EventLogTags.BACKUP_PACKAGE, PACKAGE_1.packageName, Files.size(backupData));
    }

    @Test
    public void testRunTask_whenFinishBackupSucceeds_notifiesCorrectly() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mBackupManagerService).logBackupComplete(PACKAGE_1.packageName);
        verify(mObserver).onResult(PACKAGE_1.packageName, SUCCESS);
        verify(mObserver).backupFinished(SUCCESS);
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRunTask_whenFinishBackupSucceeds_updatesBookkeeping() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenTransportRejectsPackage_doesNotCallFinishBackup() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        // Called only for PM
        verify(transportMock.transport, times(1)).finishBackup();
    }

    @Test
    public void testRunTask_whenTransportRejectsPackage_updatesAndCleansUpFiles() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("oldState".getBytes());
        assertThat(Files.exists(getTemporaryStateFile(mTransport, PACKAGE_1))).isFalse();
        assertThat(Files.exists(getStagingFile(PACKAGE_1))).isFalse();
    }

    @Test
    public void testRunTask_whenTransportRejectsPackage_logsAgentFailureEvent() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertEventLogged(
                EventLogTags.BACKUP_AGENT_FAILURE, PACKAGE_1.packageName, "Transport rejected");
    }

    @Test
    public void testRunTask_whenTransportRejectsPackage_notifiesCorrectly() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).backupFinished(SUCCESS);
        verify(mListener).onFinished(any());
    }

    @Test
    public void testRunTask_whenTransportRejectsPackage_updatesBookkeeping() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertBackupNotPendingFor(PACKAGE_1);
    }

    @Test
    public void testRunTask_whenTransportRejectsFirstPackageButLastSucceeds() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_2)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        setUpAgentsWithData(PACKAGE_1, PACKAGE_2);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).onResult(PACKAGE_2.packageName, SUCCESS);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportRejectsLastPackageButFirstSucceeds() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_OK);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_2)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_PACKAGE_REJECTED);
        setUpAgentsWithData(PACKAGE_1, PACKAGE_2);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        PACKAGE_1,
                        PACKAGE_2);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1.packageName, SUCCESS);
        verify(mObserver).onResult(PACKAGE_2.packageName, ERROR_TRANSPORT_PACKAGE_REJECTED);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportReturnsQuotaExceeded() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.getBackupQuota(PACKAGE_1.packageName, false))
                .thenReturn(1234L);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_QUOTA_EXCEEDED);
        AgentMock agentMock = setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver)
                .onResult(PACKAGE_1.packageName, BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED);
        verify(mObserver).backupFinished(SUCCESS);
        verify(agentMock.agent).onQuotaExceeded(anyLong(), eq(1234L));
        assertEventLogged(EventLogTags.BACKUP_QUOTA_EXCEEDED, PACKAGE_1.packageName);
        assertBackupNotPendingFor(PACKAGE_1);
        // TODO: Assert about state/staging files (possible bug)
    }

    @Test
    public void testRunTask_whenNonIncrementalAndTransportRequestsNonIncremental()
            throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        true,
                        PACKAGE_1);
        // Delete to be non-incremental
        Files.deleteIfExists(getStateFile(mTransport, PACKAGE_1));

        runTask(task);

        // Error because it was non-incremental already, so transport can't request it
        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_TRANSPORT_ABORTED);
        verify(mObserver).backupFinished(ERROR_TRANSPORT_ABORTED);
    }

    @Test
    public void testRunTask_whenIncrementalAndTransportRequestsNonIncremental() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        Path incrementalData = createTemporaryFile();
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)),
                        any(),
                        intThat(flags -> (flags & BackupTransport.FLAG_INCREMENTAL) != 0)))
                .thenAnswer(
                        copyBackupDataAndReturn(
                                incrementalData,
                                BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED));
        Path nonIncrementalData = createTemporaryFile();
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)),
                        any(),
                        intThat(flags -> (flags & BackupTransport.FLAG_NON_INCREMENTAL) != 0)))
                .thenAnswer(
                        copyBackupDataAndReturn(nonIncrementalData, BackupTransport.TRANSPORT_OK));
        AgentMock agentMock = setUpAgent(PACKAGE_1);
        agentOnBackupDo(
                agentMock,
                (oldState, dataOutput, newState) -> {
                    // agentMock.oldState has already been updated by now.
                    if (agentMock.oldState.length > 0) {
                        writeData(dataOutput, "key", "dataForIncremental".getBytes());
                        writeState(newState, "stateForIncremental".getBytes());
                    } else {
                        writeData(dataOutput, "key", "dataForNonIncremental".getBytes());
                        writeState(newState, "stateForNonIncremental".getBytes());
                    }
                });
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient,
                        mTransport.transportDirName,
                        false,
                        PACKAGE_1);
        // Write state to be incremental
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        verify(agentMock.agent, times(2)).onBackup(any(), any(), any());
        byte[] oldStateDuringIncremental = agentMock.oldStateHistory.get(0);
        byte[] oldStateDuringNonIncremental = agentMock.oldStateHistory.get(1);
        assertThat(oldStateDuringIncremental).isEqualTo("oldState".getBytes());
        assertThat(oldStateDuringNonIncremental).isEqualTo(new byte[0]);
        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("stateForNonIncremental".getBytes());
        try (FileInputStream inputStream = new FileInputStream(incrementalData.toFile())) {
            BackupDataInput backupData = new BackupDataInput(inputStream.getFD());
            assertDataHasKeyValue(backupData, "key", "dataForIncremental".getBytes());
            assertThat(backupData.readNextHeader()).isFalse();
        }
        try (FileInputStream inputStream = new FileInputStream(nonIncrementalData.toFile())) {
            BackupDataInput backupData = new BackupDataInput(inputStream.getFD());
            assertDataHasKeyValue(backupData, "key", "dataForNonIncremental".getBytes());
            assertThat(backupData.readNextHeader()).isFalse();
        }
        verify(mObserver).onResult(PACKAGE_1.packageName, SUCCESS);
        verify(mObserver).backupFinished(SUCCESS);
    }

    @Test
    public void testRunTask_whenTransportReturnsError_notifiesCorrectly() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_ERROR);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mObserver).onResult(PACKAGE_1.packageName, ERROR_TRANSPORT_ABORTED);
        verify(mObserver).backupFinished(ERROR_TRANSPORT_ABORTED);
    }

    @Test
    public void testRunTask_whenTransportReturnsError_logsBackupTransportFailureEvent()
            throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_ERROR);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        assertEventLogged(EventLogTags.BACKUP_TRANSPORT_FAILURE, PACKAGE_1.packageName);
    }

    @Test
    public void testRunTask_whenTransportReturnsError_revertsOperation() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_ERROR);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(transportMock.transport).requestBackupTime();
        assertBackupPendingFor(PACKAGE_1);
        assertThat(KeyValueBackupJob.isScheduled()).isTrue();
    }

    @Test
    public void testRunTask_whenTransportReturnsError_updatesAndCleansUpFiles() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.performBackup(
                        argThat(packageInfo(PACKAGE_1)), any(), anyInt()))
                .thenReturn(BackupTransport.TRANSPORT_ERROR);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);
        Files.write(getStateFile(mTransport, PACKAGE_1), "oldState".getBytes());

        runTask(task);

        assertThat(Files.readAllBytes(getStateFile(mTransport, PACKAGE_1)))
                .isEqualTo("oldState".getBytes());
        // TODO: These should be true (Bug)
        // assertThat(Files.exists(getTemporaryStateFile(mTransport, PACKAGE_1))).isFalse();
        // assertThat(Files.exists(getStagingFile(PACKAGE_1))).isFalse();
    }

    @Test
    public void testRunTask_whenTransportGetBackupQuotaThrowsForPm() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(transportMock.transport.getBackupQuota(PM_PACKAGE.packageName, false))
                .thenThrow(DeadObjectException.class);
        setUpAgentWithData(PACKAGE_1);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(ERROR_TRANSPORT_ABORTED);
        assertEventLogged(
                EventLogTags.BACKUP_AGENT_FAILURE,
                PM_PACKAGE.packageName,
                new DeadObjectException().toString());
    }

    @Test
    public void testRunTask_whenPmAgentFails() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        PackageManagerBackupAgent pmAgent = createThrowingPmAgent(new RuntimeException());
        when(mBackupManagerService.makeMetadataAgent()).thenReturn(pmAgent);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName, PACKAGE_1);

        runTask(task);

        verify(mListener).onFinished(any());
        verify(mObserver).backupFinished(eq(ERROR_TRANSPORT_ABORTED));
        assertEventLogged(
                EventLogTags.BACKUP_AGENT_FAILURE,
                PM_PACKAGE.packageName,
                new RuntimeException().toString());
    }

    @Test
    public void testRunTask_whenBackupRunning_doesNotThrow() throws Exception {
        TransportMock transportMock = setUpInitializedTransport(mTransport);
        when(mBackupManagerService.isBackupOperationInProgress()).thenReturn(true);
        KeyValueBackupTask task =
                createKeyValueBackupTask(
                        transportMock.transportClient, mTransport.transportDirName);

        runTask(task);
    }

    private void runTask(KeyValueBackupTask task) {
        // Pretend we are not on the main-thread to prevent RemoteCall from complaining
        mShadowMainLooper.setCurrentThread(false);
        task.run();
        mShadowMainLooper.reset();
        assertTaskPostConditions();
    }

    private TransportMock setUpTransport(TransportData transport) throws Exception {
        TransportMock transportMock =
                TransportTestUtils.setUpTransport(mTransportManager, transport);
        Files.createDirectories(getStateDirectory(transport));
        return transportMock;
    }

    /** Sets up the transport and writes a PM state file in the transport state directory. */
    private TransportMock setUpInitializedTransport(TransportData transport) throws Exception {
        TransportMock transportMock = setUpTransport(transport);
        createPmStateFile(transport);
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
                .resolve(packageData.packageName + KeyValueBackupTask.NEW_STATE_FILE_SUFFIX);
    }

    private Path getStagingDirectory() {
        return mDataDir.toPath();
    }

    private Path getStagingFile(PackageData packageData) {
        return getStagingDirectory()
                .resolve(packageData.packageName + KeyValueBackupTask.STAGING_FILE_SUFFIX);
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
            if (packageData.available) {
                doReturn(backupAgentBinder)
                        .when(mBackupManagerService)
                        .bindToAgentSynchronous(argThat(applicationInfo(packageData)), anyInt());
            } else {
                doReturn(null)
                        .when(mBackupManagerService)
                        .bindToAgentSynchronous(argThat(applicationInfo(packageData)), anyInt());
            }
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

    private KeyValueBackupTask createKeyValueBackupTask(
            TransportClient transportClient, String transportDirName, PackageData... packages) {
        return createKeyValueBackupTask(transportClient, transportDirName, false, packages);
    }

    private KeyValueBackupTask createKeyValueBackupTask(
            TransportClient transportClient,
            String transportDirName,
            boolean nonIncremental,
            PackageData... packages) {
        ArrayList<BackupRequest> keyValueBackupRequests =
                Stream.of(packages)
                        .map(packageData -> packageData.packageName)
                        .map(BackupRequest::new)
                        .collect(toCollection(ArrayList::new));
        mBackupManagerService.getPendingBackups().clear();
        // mOldJournal is a mock, but it would be the value returned by BMS.getJournal() now
        mBackupManagerService.setJournal(null);
        mWakeLock.acquire();
        KeyValueBackupTask task =
                new KeyValueBackupTask(
                        mBackupManagerService,
                        transportClient,
                        transportDirName,
                        keyValueBackupRequests,
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

    /** Matches {@link ApplicationInfo} whose package name is {@code packageData.packageName}. */
    private static ArgumentMatcher<ApplicationInfo> applicationInfo(PackageData packageData) {
        return applicationInfo ->
                applicationInfo != null
                        && packageData.packageName.equals(applicationInfo.packageName);
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
     *         <li>Reset processed packages journal.
     *         <li>Reset current token to 0.
     *         <li>Delete state files.
     *         <li>Mark data changed for every key-value participant.
     *       </ul>
     * </ul>
     */
    private void createPmStateFile() throws IOException {
        createPmStateFile(mTransport);
    }

    /** @see #createPmStateFile() */
    private void createPmStateFile(TransportData transport) throws IOException {
        Files.write(getStateFile(transport, PM_PACKAGE), "pmState".getBytes());
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
                                    agentMock.oldStateHistory.add(agentMock.oldState);
                                    function.onBackup(oldState, dataOutput, newState);
                                })
                .when(agentMock.agent)
                .onBackup(any(), any(), any());
    }

    /**
     * Returns an {@link Answer} that can be used for mocking {@link
     * IBackupTransport#performBackup(PackageInfo, ParcelFileDescriptor, int)} that copies the
     * backup data received to {@code backupDataPath} and returns {@code result}.
     */
    private static Answer<Integer> copyBackupDataAndReturn(Path backupDataPath, int result) {
        return invocation -> {
            ParcelFileDescriptor backupDataParcelFd = invocation.getArgument(1);
            FileDescriptor backupDataFd = backupDataParcelFd.getFileDescriptor();
            Files.copy(new FileInputStream(backupDataFd), backupDataPath, REPLACE_EXISTING);
            backupDataParcelFd.close();
            return result;
        };
    }

    /**
     * Same as {@link #copyBackupDataAndReturn(Path, int)}} with {@code result =
     * BackupTransport.TRANSPORT_OK}.
     */
    private static Answer<Integer> copyBackupDataTo(Path backupDataPath) {
        return copyBackupDataAndReturn(backupDataPath, BackupTransport.TRANSPORT_OK);
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
        String packageName = packageData.packageName;
        // We verify the current journal, NOT the old one passed to KeyValueBackupTask constructor
        assertThat(mBackupManagerService.getJournal().getPackages()).contains(packageName);
        assertThat(mBackupManagerService.getPendingBackups()).containsKey(packageName);
    }

    private void assertBackupNotPendingFor(PackageData packageData) throws IOException {
        String packageName = packageData.packageName;
        // We verify the current journal, NOT the old one passed to KeyValueBackupTask constructor
        assertJournalDoesNotContain(mBackupManagerService.getJournal(), packageName);
        assertThat(mBackupManagerService.getPendingBackups()).doesNotContainKey(packageName);
    }

    private void assertDataHasKeyValue(BackupDataInput backupData, String key, byte[] value)
            throws IOException {
        assertThat(backupData.readNextHeader()).isTrue();
        assertThat(backupData.getKey()).isEqualTo(key);
        int size = backupData.getDataSize();
        byte[] data1 = new byte[size];
        backupData.readEntityData(data1, 0, size);
        assertThat(data1).isEqualTo(value);
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
        private final List<byte[]> oldStateHistory = new ArrayList<>();
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
